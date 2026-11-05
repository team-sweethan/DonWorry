import json
from datetime import UTC, datetime

import pytest

from app.agent.assemble import ALWAYS_LIMITATIONS, ALWAYS_UNKNOWN
from app.agent.facts import Fact
from app.agent.graph import run_investigation
from app.agent.llm import MockLlmClient
from app.agent.narrative import validate_narrative
from app.agent.prompts import WRITE_EXAMPLES
from app.agent.tools import StaticToolBox
from app.config import Settings
from app.schemas.tools import CasePayment, CasePaymentsTool, FdsEvidenceTool, FdsPayment
from tests.fakes import ScriptedLlm, call, catalog, context, narrative, toolbox

# AI 실제 실행에서 나온 실수를 코드가 막는지 본다 — 걸리면 다시 쓰게 한다
# (코드값 · 확률 단정 · 한자 · 영어 · 반복 · "미확인 없음")
SETTINGS = Settings(_env_file=None)


def _run(*outputs: str) -> tuple[ScriptedLlm, object]:
    llm = ScriptedLlm(turns=[], outputs=list(outputs))
    return llm, run_investigation(context(), llm, toolbox("sample_case.json"), SETTINGS)


def _summary(*texts: str) -> list[dict[str, object]]:
    return [{"text": text, "facts": ["접속-1"]} for text in texts]


def test_code_value_is_repaired() -> None:
    wrong = narrative(
        summary=_summary("두 결제 모두 REJECT_REVIEW 대상입니다.", "정보가 없습니다.")
    )

    llm, outcome = _run(wrong, narrative())

    assert llm.write_calls == 2
    assert outcome.status == "SUCCEEDED"


def test_status_code_is_repaired() -> None:
    wrong = narrative(summary=_summary("두 결제 모두 HELD 상태입니다.", "정보가 없습니다."))

    llm, _ = _run(wrong, narrative())

    assert llm.write_calls == 2


# 확률처럼 단정하는 표현은 쓰지 못하게 한다 (FDS 점수는 확률이 아니다)
def test_assertive_phrase_is_repaired() -> None:
    wrong = narrative(summary=_summary("두 결제 모두 사기 가능성이 높습니다.", "정보가 없습니다."))

    llm, outcome = _run(wrong, narrative())

    assert llm.write_calls == 2
    assert outcome.status == "SUCCEEDED"


# 고쳐 쓰기 횟수를 넘어도 계속 틀리면 실패로 끝낸다 — 자동 판정 없음
def test_content_keeps_failing_ends_failed() -> None:
    wrong = narrative(summary=_summary("사기 확률이 높습니다.", "정보가 없습니다."))

    llm, outcome = _run(wrong)

    assert outcome.status == "FAILED"
    assert outcome.error["code"] == "NARRATIVE_INVALID"
    assert llm.write_calls == SETTINGS.agent_max_repairs + 1


# 모르는 사실 · 한계 줄은 코드가 항상 채운다 — AI가 "미확인 없음"을 써도 들어가지 않는다
def test_unknowns_and_limitations_are_always_filled() -> None:
    body = json.loads(narrative(limitations=["확인되지 않은 정보가 없습니다."]))

    _, outcome = _run(json.dumps(body, ensure_ascii=False))

    report = outcome.report
    assert all(p.unknowns == [ALWAYS_UNKNOWN] for p in report.paymentAnalyses)
    limitations = report.recommendation.limitations
    assert "확인되지 않은 정보가 없습니다." not in limitations
    assert all(item in limitations for item in ALWAYS_LIMITATIONS)


# AI에게 주는 사실은 코드값 대신 한국어 이름으로 보여 준다
def test_facts_use_korean_labels() -> None:
    from tests.test_narrative_quality import CASES, _history

    history = _history(("8001", 30000, datetime(2026, 10, 1, 3, tzinfo=UTC)))

    texts = " ".join(f.text for f in catalog(CASES, history))

    assert "매입 완료" in texts and "CAPTURED" not in texts


# AI가 지어낸 인자(case_id)는 쓰지도 기록하지도 않는다
def test_invented_tool_args_are_dropped() -> None:
    llm = ScriptedLlm(
        turns=[[call("get_case_payments", case_id=101)], [call("get_fds_evidence")], None],
        outputs=[narrative()],
    )

    outcome = run_investigation(context(), llm, toolbox("sample_case.json"), SETTINGS)

    assert outcome.records[0].args == {}


# 프롬프트 예시 문장도 검사를 통과해야 한다 — 예시가 규칙을 어기면 AI가 따라 한다
# 예시의 [조사 사실] 줄을 사실로 넣고, 예시 베끼기 검사는 빼고 본다
@pytest.mark.parametrize("example", WRITE_EXAMPLES)
def test_prompt_example_passes_content_checks(example: dict) -> None:
    topic = {"이력": "history", "연결": "linked", "접속": "signals", "과거": "similar"}
    facts = []
    for line in example["[조사 사실]"]:
        fid, text = line.split(": ", 1)
        facts.append(Fact(fid, topic[fid.split("-")[0]], text.replace("<결제 ID>", "9201"), "E"))
    written = json.dumps(example["출력"], ensure_ascii=False).replace("<결제 ID>", "9201")

    _, errors = validate_narrative(written, facts, ["9201"])

    assert [e for e in errors if "예시 문장" not in e] == []


# 타임라인은 코드가 만드는 칸이라 코드값 대신 한국어로 쓴다
def test_timeline_uses_korean_fds_result() -> None:
    outcome = run_investigation(
        context("103", 1), MockLlmClient(), toolbox("sample_single.json"), SETTINGS
    )

    assert outcome.status == "SUCCEEDED"
    assert [t.event for t in outcome.report.timeline if t.kind == "PAYMENT"] == [
        "결제 9201 · 48,000원 → FDS 검토 55점 → 매입 보류"
    ]


# 같은 초 안의 결제도 실제 시각 순서로 보인다 (카드 테스팅처럼 결제가 몰릴 때)
def test_timeline_sorted_by_real_time() -> None:
    payments = CasePaymentsTool(
        caseId="104",
        evidenceVersion=1,
        source="P3·P5",
        retrievedAt="2026-10-27T12:00:05Z",
        payments=[
            CasePayment(
                paymentId="9302",
                amount=1000,
                paymentStatus="HELD",
                requestedAt="2026-10-27T12:00:00.100Z",
            ),
            CasePayment(
                paymentId="9301",
                amount=1000,
                paymentStatus="HELD",
                requestedAt="2026-10-27T12:00:00Z",
            ),
        ],
        missingPaymentIds=[],
    )
    fds = FdsEvidenceTool(
        caseId="104",
        evidenceVersion=1,
        source="P2",
        retrievedAt="2026-10-27T12:00:05Z",
        payments=[
            FdsPayment(paymentId=p, riskScore=55, fdsResult="REVIEW", ruleHits=[])
            for p in ("9301", "9302")
        ],
        missingPaymentIds=[],
    )

    outcome = run_investigation(
        context("104", 1), MockLlmClient(), StaticToolBox(payments, fds), SETTINGS
    )

    paid = [t.event.split()[1] for t in outcome.report.timeline if t.kind == "PAYMENT"]
    assert paid == ["9301", "9302"]


# 한자 · 반복 문장 · 영어 단어는 AI에게 돌려보내 다시 쓰게 한다
@pytest.mark.parametrize(
    "overrides",
    [
        {"summary": _summary("新規 카드로 결제됐습니다.", "정보가 없습니다.")},
        {"summary": _summary("정보가 없습니다.", "정보가 없습니다")},
        {"summary": _summary("처음 쓰는 card로 결제됐습니다.", "정보가 없습니다.")},
    ],
    ids=["hanja", "repeat", "english"],
)
def test_narrative_with_hanja_repeat_or_english_is_repaired(
    overrides: dict[str, object],
) -> None:
    llm, outcome = _run(narrative(**overrides), narrative())

    assert llm.write_calls == 2
    assert outcome.status == "SUCCEEDED"


# 화면에서 쓰는 약어(FDS · IP · AI)는 통과한다
def test_allowed_abbreviations_pass() -> None:
    ok = narrative(summary=_summary("FDS 규칙과 접속 IP를 함께 확인했습니다.", "정보가 없습니다."))

    llm, outcome = _run(ok)

    assert llm.write_calls == 1
    assert outcome.status == "SUCCEEDED"
