import copy
import json
from pathlib import Path
from typing import Any

import pytest
from langchain_core.messages import AIMessage, BaseMessage

from app.agent.graph import InvestigationOutcome, run_investigation
from app.agent.llm import MockLlmClient
from app.agent.tools import StaticToolBox
from app.config import Settings
from app.schemas.report import ReportBody
from app.schemas.tools import DecisionBasisTool, LinkedPaymentsTool
from tests.fakes import FIXTURES, call, context

# AI가 의심 목록을 보고 추가 조회를 고른다 (#817)
# 거절 후 재시도 샘플: 결제 2건, 징후 = 거절 후 재시도 · 신규 카드 · 같은 카드를 다른 고객이 사용
# (단시간 반복은 거절 후 재시도에 이미 센 것이라 목록에서 빠진다)
# 의심 번호는 코드가 처음 나온 순서로 붙인다 — 의심-3이 같은 카드 징후다

SETTINGS = Settings(_env_file=None)
DATA = json.loads((FIXTURES / "measure_reject.json").read_text(encoding="utf-8"))
LINKED_CARD = "의심-3"


def _near(customers: int, payments: int) -> dict[str, Any]:
    return {
        "otherCustomerCount": customers,
        "paymentCount": payments,
        "resultCounts": [],
        "declinedCount": 0,
    }


# 기본 조회는 앞뒤 1시간에 같은 카드 결제 1건, 24시간으로 넓히면 4건
def _sample(with_window: bool = True) -> dict[str, Any]:
    data = copy.deepcopy(DATA)
    linked = data["linked"]
    linked["nearbyWindowMinutes"] = 60
    linked["card"]["nearby"] = _near(1, 1)
    linked["device"]["nearby"] = _near(0, 0)
    linked["ip"]["nearby"] = _near(0, 0)
    if with_window:
        wide = copy.deepcopy(linked)
        wide["nearbyWindowMinutes"] = 24 * 60
        wide["card"]["nearby"] = _near(2, 4)
        data["linkedWindows"] = {"24": wide}
    return data


# 차례마다 도구 호출 하나, 다 쓰면 "조사 완료". 요약은 mock 답(받은 사실 번호 모두 인용)
class _Picks:
    def __init__(self, *calls: dict[str, Any]) -> None:
        self._calls = list(calls)
        self.tools: list[list[dict[str, Any]]] = []
        self.write_prompts: list[str] = []

    def choose_tools(self, messages: list[BaseMessage], tools: list[dict[str, Any]]) -> AIMessage:
        self.tools.append(tools)
        if not self._calls:
            return AIMessage(content="조사 완료")
        return AIMessage(content="", tool_calls=[self._calls.pop(0)])

    def write_json(self, messages: list[BaseMessage], schema: dict[str, Any]) -> str:
        self.write_prompts.append(str(messages[1].content))
        return MockLlmClient().write_json(messages, schema)


# 받은 연결 결제 시간 인자를 기록한다. 징후 조회를 실패시키거나 빈 목록으로 바꿀 수 있다
class _Box(StaticToolBox):
    hours: list[int | None]
    basis_mode: str

    def find_linked_payments(self, nearby_hours: int | None = None) -> LinkedPaymentsTool:
        self.hours.append(nearby_hours)
        return super().find_linked_payments(nearby_hours)

    def get_decision_basis(self) -> DecisionBasisTool:
        if self.basis_mode == "fail":
            raise RuntimeError("Spring 500")
        basis = super().get_decision_basis()
        return basis.model_copy(update={"payments": []}) if self.basis_mode == "empty" else basis


def _box(data: dict[str, Any], basis_mode: str = "ok") -> _Box:
    box = _Box.from_sample(data)
    box.hours = []
    box.basis_mode = basis_mode
    return box


def _run(llm: _Picks, box: _Box) -> InvestigationOutcome:
    return run_investigation(context(DATA["casePayments"]["caseId"], 1), llm, box, SETTINGS)


def _report(outcome: InvestigationOutcome) -> ReportBody:
    assert outcome.status == "SUCCEEDED", outcome.error
    assert outcome.report is not None
    return outcome.report


def _window_call(**args: Any) -> dict[str, Any]:
    return call("find_linked_payments_window", **args)


# 이번 사건 의심 목록에 없는 번호 · 빠진 suspicion이면 조회하지 않는다
# — 없는 의심을 핑계로 조회하고 엉뚱한 의심 카드에 결과가 붙지 않게
@pytest.mark.parametrize("args", [{"suspicion": "의심-9", "hours": 24}, {"hours": 24}])
def test_extra_suspicion_not_in_case_rejected_not_run(args: dict[str, Any]) -> None:
    box = _box(_sample())

    report = _report(_run(_Picks(_window_call(**args)), box))

    assert box.hours == [None]
    assert report.extraInvestigations == []
    steps = [s for s in report.investigationTrace.steps if s.title.endswith("넓혀 보기")]
    assert [s.received for s in steps] == ["의심 목록 밖이라 실행하지 않음"]


# 6 · 24가 아닌 시간(문자열 · 참거짓 포함)은 지어낸 범위라 조회하지 않는다
@pytest.mark.parametrize("hours", [48, 1, "24", True, None])
def test_extra_linked_window_hours_outside_6_24_rejected(hours: Any) -> None:
    box = _box(_sample())

    report = _report(_run(_Picks(_window_call(suspicion=LINKED_CARD, hours=hours)), box))

    assert box.hours == [None]
    assert report.extraInvestigations == []


# 24시간으로 넓혀 봐도 기본 1시간 표 · 연결 사실은 그대로 — 덮어쓰면 1시간 값이 사라진다
def test_extra_linked_window_keeps_base_linked_table_and_facts() -> None:
    plain = _report(_run(_Picks(), _box(_sample())))
    wide = _report(_run(_Picks(_window_call(suspicion=LINKED_CARD, hours=24)), _box(_sample())))

    assert wide.linkedPayments == plain.linkedPayments
    assert wide.linkedPayments is not None
    assert wide.linkedPayments.nearbyWindowMinutes == 60
    assert wide.linkedPayments.card.nearby is not None
    assert wide.linkedPayments.card.nearby.paymentCount == 1

    def linked(report: ReportBody) -> list[tuple[str, str, str]]:
        return [(f.id, f.text, f.evidenceId) for f in report.facts if f.topic == "linked"]

    assert linked(wide) == linked(plain)


# 넓혀 본 결과는 "추가" 사실로 따로 남는다 — 조회 기록 번호와 이어지고 요약 AI에게도 간다
def test_extra_linked_window_adds_extra_facts_with_evidence() -> None:
    llm = _Picks(_window_call(suspicion=LINKED_CARD, hours=24))

    report = _report(_run(llm, _box(_sample())))

    extra = [f for f in report.facts if f.topic == "linkedWindow"]
    assert [f.id for f in extra] == ["추가-1"]
    assert "앞뒤 24시간 안" in extra[0].text
    assert "결제 4건" in extra[0].text
    assert extra[0].notable
    evidence = {e.evidenceId: e.toolName for e in report.evidence}
    assert evidence[extra[0].evidenceId] == "find_linked_payments_window"
    assert "추가-1: " in llm.write_prompts[-1]


# 추가 조사 기록은 화면 의심 카드와의 계약 — 이유는 AI가 보낸 글이 아니라 코드 문장
def test_extra_investigations_reason_built_by_code_not_ai() -> None:
    ai_reason = "이 결제는 사기로 보여서 확인합니다"
    llm = _Picks(_window_call(suspicion=LINKED_CARD, hours=24, reason=ai_reason))

    report = _report(_run(llm, _box(_sample())))

    [extra] = report.extraInvestigations
    label = "이 사건 결제 중 같은 카드를 다른 고객이 사용"
    expected_reason = (
        f"'{label}' 의심을 확인하려고 "
        "같은 카드 · 기기 · IP 결제를 사건 결제 앞뒤 24시간으로 넓혀 봤습니다."
    )
    assert extra.model_dump() == {
        "suspicionKey": "LINKED_CARD",
        "suspicionLabel": label,
        "toolName": "find_linked_payments_window",
        "title": "같은 카드 · 기기 · IP 시간대 넓혀 보기",
        "args": {"hours": 24},
        "reason": expected_reason,
        "status": "DONE",
        "evidenceId": extra.evidenceId,
        "retrievedAt": DATA["linked"]["retrievedAt"],
        "factIds": ["추가-1"],
    }
    assert extra.evidenceId in {e.evidenceId for e in report.evidence}
    assert ai_reason not in report.model_dump_json()
    step = next(s for s in report.investigationTrace.steps if s.title.endswith("넓혀 보기"))
    assert step.asked == expected_reason


# AI 추가 조회는 징후 · 정상 근거(결제별 basis)를 바꾸지 않는다 — 기본 조회 30일 기준 그대로
def test_extra_linked_window_does_not_change_decision_basis() -> None:
    plain = _report(_run(_Picks(), _box(_sample())))
    wide = _report(_run(_Picks(_window_call(suspicion=LINKED_CARD, hours=24)), _box(_sample())))

    assert [a.basis for a in wide.paymentAnalyses] == [a.basis for a in plain.paymentAnalyses]
    assert all(a.basis is not None for a in wide.paymentAnalyses)


# 추가 조사 칸이 없는 예전 보고서 · 고정 보고서도 그대로 읽힌다
def test_report_body_without_extra_investigations_valid() -> None:
    old = json.loads((Path(FIXTURES) / "sample_report.json").read_text(encoding="utf-8"))
    assert "extraInvestigations" not in old

    body = ReportBody.model_validate(old)

    assert body.extraInvestigations == []


# 요약 AI가 사건이 왜 열렸는지 알도록 징후를 사실로 맨 앞에 준다 (#817 선택 D)
# 다른 징후에 이미 센 것(단시간 반복)은 빼고, 같은 징후는 결제 2건을 한 줄로 묶는다
# 눈여겨볼 사실처럼 강제하지는 않는다 (11/14 결정 — 다시 쓰기 비용)
def test_writer_facts_include_basis_signals_with_evidence() -> None:
    llm = _Picks()

    report = _report(_run(llm, _box(_sample())))

    basis = [f for f in report.facts if f.topic == "basis"]
    assert [f.id for f in basis] == ["징후-1", "징후-2", "징후-3"]
    assert basis[0].text.startswith("징후 '거절 후 재시도': 첫 번째 결제")
    assert "두 번째 결제" in basis[0].text
    assert not any(f.notable for f in basis)
    assert not any("단시간 반복" in f.text for f in basis)
    evidence = {e.evidenceId: e.toolName for e in report.evidence}
    assert {evidence[f.evidenceId] for f in basis} == {"get_decision_basis"}
    # [조사 사실] · 사건 결제 N건 다음 줄부터 징후
    lines = llm.write_prompts[-1].splitlines()
    assert lines[2 : 2 + len(basis)] == [f"{f.id}: {f.text}" for f in basis]


# 징후 사실에 등급 · 점수를 넣지 않는다 — 요약 AI가 따라 쓰면 판단 문장이 된다
def test_basis_facts_exclude_grade_and_score() -> None:
    report = _report(_run(_Picks(), _box(_sample())))

    texts = [f.text for f in report.facts if f.topic == "basis"]
    assert texts
    scores = [str(p["riskScore"]) for p in DATA["fdsEvidence"]["payments"]]
    for text in texts:
        for word in ("고위험 징후", "주의 징후", "HIGH_RISK", "CAUTION"):
            assert word not in text
        assert not any(score in text for score in scores)


# 징후를 받지 못했으면(조회 실패 · 빈 목록) 징후 사실이 없다 — "징후 없음"으로 읽히지 않게
@pytest.mark.parametrize("basis_mode", ["fail", "empty"])
def test_basis_lookup_failed_no_basis_facts(basis_mode: str) -> None:
    llm = _Picks()

    report = _report(_run(llm, _box(_sample(), basis_mode)))

    assert [f for f in report.facts if f.topic == "basis"] == []
    assert "징후" not in llm.write_prompts[-1]


# Spring이 모르는 시간을 버리고 1시간 값을 주면 실패로 남긴다 — 1시간 값이 24시간으로 보이지 않게
def test_extra_linked_window_response_mismatch_is_failure() -> None:
    box = _box(_sample(with_window=False))

    report = _report(_run(_Picks(_window_call(suspicion=LINKED_CARD, hours=24)), box))

    assert box.hours == [None, 24]
    assert [f for f in report.facts if f.topic == "linkedWindow"] == []
    [extra] = report.extraInvestigations
    assert (extra.status, extra.evidenceId, extra.retrievedAt, extra.factIds) == (
        "FAILED",
        None,
        None,
        [],
    )
    assert extra.reason.endswith("보려 했지만 조회하지 못했습니다.")


# 징후를 못 받은 사건은 사건 전체(CASE) 조회만 고를 수 있다 — 90일 이력은 그대로 볼 수 있게
def test_extra_no_suspicions_allows_case_wide_only() -> None:
    llm = _Picks(
        call("get_payment_history_90d", suspicion="의심-1"),
        call("get_payment_history_90d", suspicion="CASE"),
    )

    report = _report(_run(llm, _box(_sample(), basis_mode="empty")))

    for tools in llm.tools:
        enums = [t["function"]["parameters"]["properties"]["suspicion"]["enum"] for t in tools]
        assert enums == [["CASE"], ["CASE"]]
    [extra] = report.extraInvestigations
    assert (extra.suspicionKey, extra.suspicionLabel, extra.status) == ("CASE", "사건 전체", "DONE")
    assert extra.reason == "사건 전체를 보려고 고객 결제 이력을 90일로 넓혀 봤습니다."
