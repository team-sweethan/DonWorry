import json
from typing import Any

import pytest
from fastapi.testclient import TestClient
from langchain_core.messages import ToolMessage

from app.agent import question_prompts as qp
from app.agent.llm import MockLlmClient
from app.agent.question import HISTORY_90, LINKED_WINDOW, answer_question, validate_answer
from app.agent.question_facts import computed_facts, previous_facts, read_report, report_facts
from app.client.spring import API_KEY_HEADER
from app.config import Settings
from app.controller.investigation_controller import get_api_key
from app.controller.question_controller import get_question_service
from app.main import app
from app.schemas.question import PreviousTurn, QuestionRequest
from app.schemas.tools import (
    HistoryPayment,
    LinkedMatch,
    LinkedNearby,
    LinkedPaymentsTool,
    PaymentHistoryTool,
    StatusCount,
)
from app.service.question_service import QuestionService
from tests.fakes import BrokenLlm, ScriptedLlm, call

# 사건 질문 · 답변 (ADR-0086, #750)
# 허용 목록 · 값 · 증거 버전 · 숫자 · 판단 문구 · 고정 거절 문장 · 계약

SETTINGS = Settings(_env_file=None)
KEY = "test-key"
CHECK_DEVICE = "처음 본 기기의 결제를 고객 본인이 했는지 확인"
CHECK_IP = "같은 IP를 쓴 다른 고객과의 관계 확인"


def _payment(
    pid: str, at: str, score: int, rules: list[str], signal: str | None = None
) -> dict[str, Any]:
    basis = None
    if signal:
        basis = {
            "signals": [
                {
                    "key": "NEW_DEVICE",
                    "label": signal,
                    "detail": None,
                    "level": "HIGH_RISK",
                    "normalReason": None,
                    "checkBefore": CHECK_DEVICE,
                }
            ],
            "normals": [],
        }
    return {
        "paymentId": pid,
        "amount": 300000,
        "requestedAt": at,
        "riskScore": score,
        "ruleHits": ["CODE"],
        "unknowns": [],
        "ruleHitDetails": [
            {"code": "R", "name": name, "appliedScore": 20, "evidenceId": "E-31-v1-02"}
            for name in rules
        ],
        "basis": basis,
    }


# 결제 3건(각 300,000원) — 14:01:00 · 14:04:30 · 14:07:00 (한국 시각)
REPORT: dict[str, Any] = {
    "facts": [
        {
            "id": "이력-1",
            "topic": "history",
            "text": "최근 30일 이 고객의 다른 결제 없음",
            "evidenceId": "E-31-v1-04",
            "paymentId": None,
            "notable": False,
        },
    ],
    "paymentAnalyses": [
        _payment("9001", "2026-11-09T05:01:00Z", 35, ["단시간 반복"]),
        _payment("9002", "2026-11-09T05:04:30Z", 55, ["단시간 반복"]),
        _payment("9003", "2026-11-09T05:07:00Z", 75, ["단시간 반복", "신규 기기"], "신규 기기"),
    ],
    "additionalChecks": [CHECK_IP],
}


def question(**overrides: Any) -> QuestionRequest:
    body: dict[str, Any] = {
        "questionId": "41",
        "reportId": "57",
        "caseId": "31",
        "reportEvidenceVersion": 2,
        "currentEvidenceVersion": 2,
        "question": "같은 IP 결제가 하루 안에 몰렸어요?",
        "reportBody": REPORT,
        "previous": [],
    }
    body.update(overrides)
    return QuestionRequest.model_validate(body)


def answer(*sentences: tuple[str, list[str]], kind: str = "ANSWER") -> str:
    return json.dumps(
        {"kind": kind, "sentences": [{"text": t, "facts": f} for t, f in sentences]},
        ensure_ascii=False,
    )


OK = answer(("세 번째 결제는 신규 기기 규칙에 걸렸습니다.", ["결제-3"]))


def _match(count: int) -> LinkedMatch:
    near = LinkedNearby(otherCustomerCount=2, paymentCount=count, resultCounts=[], declinedCount=0)
    return LinkedMatch(
        provided=True,
        otherCustomerCount=2,
        paymentCount=count,
        resultCounts=[],
        declinedCount=0,
        nearby=near,
    )


class FakeBox:
    # linked_minutes: 응답에 적을 시간 범위. None이면 요청한 시간 그대로 (#751 뒤 Spring)
    def __init__(
        self, linked_minutes: int | None = None, fail: bool = False, version: int = 2
    ) -> None:
        self.calls: list[tuple[str, int | None]] = []
        self._linked_minutes = linked_minutes
        self._fail = fail
        self._version = version

    def get_payment_history(self, lookback_days: int | None = None) -> PaymentHistoryTool:
        self.calls.append(("history", lookback_days))
        if self._fail:
            raise ConnectionError("http://spring.internal:8081 연결 거부")
        return PaymentHistoryTool(
            caseId="31",
            evidenceVersion=self._version,
            source="P5",
            retrievedAt="2026-11-09T05:32:00Z",
            lookbackDays=lookback_days or 30,
            payments=[
                HistoryPayment(
                    paymentId="8001",
                    amount=18000,
                    paymentStatus="CAPTURED",
                    requestedAt="2026-09-01T03:00:00Z",
                )
            ],
            statusCounts=[StatusCount(paymentStatus="CAPTURED", count=1)],
            truncated=False,
        )

    def find_linked_payments(self, nearby_hours: int | None = None) -> LinkedPaymentsTool:
        self.calls.append(("linked", nearby_hours))
        not_sent = LinkedMatch(
            provided=False, otherCustomerCount=0, paymentCount=0, resultCounts=[], declinedCount=0
        )
        minutes = self._linked_minutes or (nearby_hours or 1) * 60
        return LinkedPaymentsTool(
            caseId="31",
            evidenceVersion=self._version,
            source="P2",
            retrievedAt="2026-11-09T05:33:00Z",
            lookbackDays=30,
            nearbyWindowMinutes=minutes,
            card=not_sent,
            device=not_sent,
            ip=_match(3),
        )


def _offered_ids(llm: ScriptedLlm) -> list[str]:
    return llm.write_schemas[0]["properties"]["sentences"]["items"]["properties"]["facts"]["items"][
        "enum"
    ]


# 허용 목록 밖 도구(보고서용 FDS 근거 조회)는 실행하지 않는다
def test_answerQuestion_toolOutsideAllowList_isRejectedAndNotCalled() -> None:
    box = FakeBox()
    llm = ScriptedLlm([[call("get_fds_evidence", payment_ids=["9001"])], None], [OK])

    result = answer_question(question(), llm, box, SETTINGS)

    assert box.calls == []
    assert [(c.toolName, c.status) for c in result.toolCalls] == [("get_fds_evidence", "REJECTED")]
    assert result.status == "ANSWERED"


# 연관 결제 시간은 6 · 24시간만 — 1시간(보고서에 있음) · 72시간 · 글자는 실행하지 않는다
@pytest.mark.parametrize("hours", [1, 72, "24"])
def test_answerQuestion_linkedWindowOutsideAllowedHours_isRejected(hours: Any) -> None:
    box = FakeBox()
    llm = ScriptedLlm([[call(LINKED_WINDOW, hours=hours)], None], [OK])

    result = answer_question(question(), llm, box, SETTINGS)

    assert box.calls == []
    assert result.toolCalls[0].status == "REJECTED"


# #751 전 Spring은 시간 인자를 버리고 1시간 값을 준다 — 24시간 값으로 쓰지 않는다
def test_answerQuestion_linkedResponseWindowDiffers_treatedAsToolFailure() -> None:
    box = FakeBox(linked_minutes=60)
    llm = ScriptedLlm([[call(LINKED_WINDOW, hours=24)], None], [OK])

    result = answer_question(question(), llm, box, SETTINGS)

    assert box.calls == [("linked", 24)]
    assert result.toolCalls[0].status == "FAILED"
    assert not any(fid.startswith("추가") for fid in _offered_ids(llm))
    assert result.status == "ANSWERED"


# 보고서 뒤 사건 증거가 바뀌었으면 넓혀 보기를 하지 않는다 — 지금 값과 보고서 값이 섞이지 않게
def test_answerQuestion_evidenceVersionChanged_doesNotCallTools() -> None:
    box = FakeBox()
    llm = ScriptedLlm([[call(HISTORY_90)]], [OK])

    result = answer_question(question(currentEvidenceVersion=3), llm, box, SETTINGS)

    assert llm.choose_messages == []
    assert box.calls == []
    assert result.toolCalls == []
    assert result.status == "ANSWERED"


# 인용한 사실에 없는 숫자(실제 합계는 900,000원)는 한 번 다시 쓰게 하고, 또 걸리면 답 없이 실패
def test_answerQuestion_numberNotInCitedFacts_failsAfterOneRepair() -> None:
    wrong = answer(("세 결제 합계는 1,000,000원입니다.", ["계산-1"]))
    llm = ScriptedLlm([None], [wrong, wrong])

    result = answer_question(question(), llm, FakeBox(), SETTINGS)

    assert llm.write_calls == 2
    assert result.status == "FAILED"
    assert result.error is not None and result.error["code"] == "ANSWER_INVALID"
    assert result.sentences == []


# "가능성이 높다" 같은 추측은 다시 쓰게 하고, 사실만 쓴 답을 내보낸다
def test_answerQuestion_judgementPhrase_isSentBackForRewrite() -> None:
    guess = answer(("세 번째 결제는 처음 쓴 기기라 도용일 가능성이 높습니다.", ["결제-3"]))
    fact = answer(("세 번째 결제는 신규 기기 규칙에 걸렸습니다.", ["결제-3"]))
    llm = ScriptedLlm([None], [guess, fact])

    result = answer_question(
        question(question="세 번째 결제는 왜 위험해요?"), llm, FakeBox(), SETTINGS
    )

    assert llm.write_calls == 2
    assert "가능성이" in str(llm.write_messages[1][-1].content)
    assert [s.text for s in result.sentences] == ["세 번째 결제는 신규 기기 규칙에 걸렸습니다."]
    assert result.status == "ANSWERED"


# 판정 질문은 AI 문장을 버리고 고정 문장 + 보고서의 확인할 것 전체 (체크 상태는 서버가 모름)
def test_answerQuestion_declinedDecision_returnsFixedTextAndReportChecks() -> None:
    reply = answer(("세 번째 결제는 취소하는 편이 낫습니다.", ["결제-3"]), kind="DECLINED_DECISION")
    llm = ScriptedLlm([None], [reply])

    result = answer_question(
        question(question="그래서 승인 취소해도 돼요?"), llm, FakeBox(), SETTINGS
    )

    assert result.status == "DECLINED"
    assert result.kind == "DECLINED_DECISION"
    assert [s.text for s in result.sentences] == [qp.FIXED_TEXT["DECLINED_DECISION"]]
    assert result.checks == [CHECK_DEVICE, CHECK_IP]
    assert result.facts == []


# AI 장애 — 서버 오류나 반쪽 답 없이 실패만 돌려준다
def test_answerQuestion_llmError_returnsFailedWithoutAnswer() -> None:
    result = answer_question(question(), BrokenLlm(), FakeBox(), SETTINGS)

    assert result.status == "FAILED"
    assert result.kind is None
    assert result.sentences == []
    assert result.error is not None and result.error["code"] == "AGENT_ERROR"


def _client(key: str = KEY) -> TestClient:
    service = QuestionService(MockLlmClient(), lambda _: FakeBox(), SETTINGS)
    app.dependency_overrides[get_question_service] = lambda: service
    app.dependency_overrides[get_api_key] = lambda: key
    return TestClient(app)


def teardown_function() -> None:
    app.dependency_overrides.clear()


# Spring(#752)이 보내고 받는 JSON 키 그대로 — 이름이 바뀌면 질문 기능 전체가 멈춘다
def test_questionApi_requestAndResponse_matchSpringContract() -> None:
    body = {
        "questionId": "42",
        "reportId": "57",
        "caseId": "31",
        "reportEvidenceVersion": 2,
        "currentEvidenceVersion": 2,
        "question": "그럼 두 번째 결제는요?",
        "reportBody": REPORT,
        "previous": [
            {
                "question": "신규 기기로 결제한 건 어느 거예요?",
                "kind": "ANSWER",
                "sentences": [{"text": "세 번째 결제입니다.", "facts": ["결제-3"]}],
                "facts": [
                    {
                        "id": "결제-3",
                        "text": "세 번째 결제: ...",
                        "source": "PAYMENT",
                        "paymentId": "9003",
                        "toolName": None,
                        "retrievedAt": None,
                    }
                ],
            }
        ],
    }

    res = _client().post("/questions", json=body, headers={API_KEY_HEADER: KEY})

    assert res.status_code == 200
    data = res.json()
    assert set(data) == {
        "questionId",
        "status",
        "kind",
        "sentences",
        "checks",
        "facts",
        "toolCalls",
        "trace",
        "error",
    }
    assert data["questionId"] == "42"
    assert data["status"] == "ANSWERED"
    assert set(data["sentences"][0]) == {"text", "facts"}
    assert set(data["facts"][0]) == {
        "id",
        "text",
        "source",
        "paymentId",
        "toolName",
        "retrievedAt",
    }


# 서비스 키가 없거나 틀리면 사건 질문을 받지 않는다
def test_questionApi_missingServiceKey_returns401() -> None:
    client = _client()
    body = question().model_dump()

    assert client.post("/questions", json=body).status_code == 401
    assert client.post("/questions", json=body, headers={API_KEY_HEADER: "x"}).status_code == 401


# 계산값은 코드가 결제 표로 만든다 — 합계 · 건수 · 첫~마지막 간격 · 결제 사이 간격
def test_computedFacts_totalCountAndSpan_matchPaymentTable() -> None:
    texts = [f.text for f in computed_facts(read_report(REPORT))]

    assert texts[0] == "사건 결제 3건 합계 900,000원"
    assert texts[1].endswith("까지 6분")
    assert texts[2] == (
        "결제 사이 간격(시각 순): 첫 번째 결제 → 두 번째 결제 3분 30초 · "
        "두 번째 결제 → 세 번째 결제 2분 30초"
    )
    # 코드 계산값을 인용하면 합계 숫자가 검사를 통과한다
    ok = answer(("세 결제 합계는 900,000원입니다.", ["계산-1"]))
    parsed, errors = validate_answer(ok, report_facts(read_report(REPORT)), 3)
    assert parsed is not None and errors == []


# 앞 답 문장의 숫자는 근거가 아니다 — 앞 답에만 있는 "4건"을 보고서 사실로 인용하면 실패
def test_answerQuestion_previousAnswerSentence_cannotBeCited() -> None:
    previous = [
        {
            "question": "평소 결제는?",
            "kind": "ANSWER",
            "sentences": [{"text": "90일 결제 4건이 모두 매입됐습니다.", "facts": ["추가-1"]}],
            "facts": [],
        }
    ]
    wrong = answer(("앞서 본 4건은 모두 매입됐습니다.", ["이력-1"]))
    llm = ScriptedLlm([None], [wrong, wrong])

    result = answer_question(question(previous=previous), llm, FakeBox(), SETTINGS)

    assert "평소 결제는?" in str(llm.write_messages[0][-1].content)
    assert "추가-1" not in _offered_ids(llm)
    assert result.status == "FAILED"


# 사실 목록이 없는 옛 보고서(#548 전)는 AI를 부르지 않고 지원 안 함으로 끝낸다
def test_answerQuestion_reportWithoutFacts_returnsUnsupported() -> None:
    old = {k: v for k, v in REPORT.items() if k != "facts"}
    llm = ScriptedLlm([None], [OK])

    result = answer_question(question(reportBody=old), llm, FakeBox(), SETTINGS)

    assert result.status == "FAILED"
    assert result.error is not None and result.error["code"] == "REPORT_UNSUPPORTED"
    assert llm.write_calls == 0


# 넓혀 보기 하나가 실패해도 보고서 사실로 답한다 — 오류 원문(내부 주소)은 AI에 보내지 않는다
def test_answerQuestion_toolFailure_answersWithoutAddedFacts() -> None:
    llm = ScriptedLlm([[call(HISTORY_90)], None], [OK])

    result = answer_question(question(), llm, FakeBox(fail=True), SETTINGS)

    assert result.toolCalls[0].status == "FAILED"
    tool_reply = [m for m in llm.choose_messages[1] if isinstance(m, ToolMessage)][0]
    assert tool_reply.content == qp.CHOOSE_TOOL_FAILED
    assert not any(fid.startswith("추가") for fid in _offered_ids(llm))
    assert result.status == "ANSWERED"


# 넓혀 보기가 성공하면 새 사실(추가-N)이 조회 도구 · 시각과 함께 답의 근거로 남는다
def test_answerQuestion_history90Chosen_citesAddedFactsWithRetrievedAt() -> None:
    box = FakeBox()
    reply = answer(("최근 90일에 이 고객의 다른 결제가 1건 있습니다.", ["추가-1"]))
    llm = ScriptedLlm([[call(HISTORY_90)], None], [reply])

    result = answer_question(question(question="평소에도 이 정도 써요?"), llm, box, SETTINGS)

    assert box.calls == [("history", 90)]
    assert [(c.toolName, c.status) for c in result.toolCalls] == [(HISTORY_90, "OK")]
    assert result.status == "ANSWERED"
    cited = {f.id: f for f in result.facts}
    assert cited["추가-1"].source == "ADDED"
    assert cited["추가-1"].toolName == "get_payment_history"
    assert cited["추가-1"].retrievedAt == "2026-11-09T05:32:00Z"


# Codex 리뷰(#756): 결제 순번(1~3)은 "3번 결제"처럼 결제를 가리킬 때만 허용한다
# 이력-1은 "다른 결제 없음"인데 "2건"이 결제 수 이하라는 이유로 통과하면 틀린 건수가 나간다
def test_answerQuestion_countWithinPaymentCountButNotInFact_isRejected() -> None:
    wrong = answer(("최근 30일 이 고객의 다른 결제는 2건입니다.", ["이력-1"]))
    llm = ScriptedLlm([None], [wrong, wrong])

    result = answer_question(question(), llm, FakeBox(), SETTINGS)

    assert result.status == "FAILED"
    ok = answer(("3번 결제는 신규 기기 규칙에 걸렸습니다.", ["결제-3"]))
    parsed, errors = validate_answer(ok, report_facts(read_report(REPORT)), 3)
    assert parsed is not None and errors == []


# Codex 리뷰(#756): 앞 질문의 조회값을 다시 넘길 때 원문은 그대로, 조회 시각은 한 번만
# 같은 사실이 여러 번 이어 넘겨져도 하나로 본다
def test_previousFacts_reusedAcrossTurns_keepsTextAndShowsRetrievedAtOnce() -> None:
    text = "최근 90일 이 고객의 다른 결제 1건: 매입 완료 1건"
    first = {
        "id": "추가-1",
        "text": text,
        "source": "ADDED",
        "toolName": "get_payment_history",
        "retrievedAt": "2026-11-09T05:32:00Z",
    }
    turn1 = {"question": "평소 결제는?", "kind": "ANSWER", "sentences": [], "facts": [first]}
    # 두 번째 답이 앞-1을 인용했다면 Spring은 답이 돌려준 사실 그대로 다음 질문에 싣는다
    reused = previous_facts([PreviousTurn.model_validate(turn1)])[0].model_dump()
    turn2 = {"question": "그럼 금액은?", "kind": "ANSWER", "sentences": [], "facts": [reused]}
    previous = [turn1, turn2]
    llm = ScriptedLlm([None], [OK])

    answer_question(question(previous=previous), llm, FakeBox(), SETTINGS)

    offered = [fid for fid in _offered_ids(llm) if fid.startswith("앞")]
    assert offered == ["앞-1"]
    prompt = str(llm.write_messages[0][-1].content)
    assert prompt.count(text) == 1
    assert prompt.count("11/09 14:32 조회") == 1


# Codex 리뷰(#756): 보고서에 저장된 규칙 기준 · 이번 값 · 가산 점수도 번호 있는 사실로 준다
def test_reportFacts_ruleHitDetails_includeCriterionObservedAndScore() -> None:
    body = json.loads(json.dumps(REPORT))
    body["paymentAnalyses"][2]["ruleHitDetails"].append(
        {
            "code": "HIGH_AMOUNT",
            "name": "고액 결제",
            "appliedScore": 35,
            "evidenceId": "E-31-v1-02",
            "criterion": "50만 원 이상",
            "observed": "60만 원",
        }
    )
    facts = report_facts(read_report(body))

    rule = [f for f in facts if "고액 결제" in f.text and "기준" in f.text]
    assert len(rule) == 1 and rule[0].paymentId == "9003"
    reply = answer(("고액 결제 기준은 50만 원 이상이고 이번 값은 60만 원입니다.", [rule[0].id]))
    parsed, errors = validate_answer(reply, facts, 3)
    assert parsed is not None and errors == []
