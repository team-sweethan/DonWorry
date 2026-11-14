import copy
import json
from typing import Any

from langchain_core.messages import AIMessage, BaseMessage

from app.agent.assemble import LINKED_CASES_UNAVAILABLE
from app.agent.facts import fact_catalog
from app.agent.graph import InvestigationOutcome, run_investigation
from app.agent.llm import MockLlmClient
from app.agent.narrative import validate_narrative
from app.agent.tools import StaticToolBox, ToolRecord
from app.config import Settings
from app.schemas.report import ReportBody
from app.schemas.tools import LinkedCasesTool
from tests.fakes import FIXTURES, call, context, narrative

# 같은 카드 · 기기 · IP로 열린 지난 사건 (#821, ADR-0009 결정 15)
# 카드 · 기기는 코드가 기본 조회, IP는 AI가 고르는 추가 조회. AI에는 사건 번호를 주지 않는다

SETTINGS = Settings(_env_file=None)
DATA = json.loads((FIXTURES / "measure_reject.json").read_text(encoding="utf-8"))
PAST_CASE_ID = "777123"


def _linked_cases(by: str, case_id: str = PAST_CASE_ID) -> dict[str, Any]:
    payments = DATA["casePayments"]
    return {
        "caseId": payments["caseId"],
        "evidenceVersion": payments["evidenceVersion"],
        "source": "P3·P2",
        "retrievedAt": payments["retrievedAt"],
        "by": by,
        "lookbackDays": 90,
        "kinds": ["IP"] if by == "IP" else ["CARD", "DEVICE"],
        "notProvided": [],
        "cases": [
            {
                "caseId": case_id,
                "matchedBy": ["IP"] if by == "IP" else ["DEVICE"],
                "closedAt": "2026-11-10T03:00:00Z",
                "paymentCount": 1,
                "acceptCount": 0,
                "rejectCount": 1,
            }
        ],
        "totalMatched": 1,
    }


def _sample() -> dict[str, Any]:
    data = copy.deepcopy(DATA)
    data["linkedCases"] = {
        "CARD_DEVICE": _linked_cases("CARD_DEVICE"),
        "IP": _linked_cases("IP", "888456"),
    }
    return data


# 받은 메시지를 모두 남긴다 — AI에게 간 글에 사건 번호가 없는지 본다
class _Picks:
    def __init__(self, *calls: dict[str, Any]) -> None:
        self._calls = list(calls)
        self.seen: list[str] = []

    def choose_tools(self, messages: list[BaseMessage], tools: list[dict[str, Any]]) -> AIMessage:
        self.seen += [str(m.content) for m in messages]
        if not self._calls:
            return AIMessage(content="조사 완료")
        return AIMessage(content="", tool_calls=[self._calls.pop(0)])

    def write_json(self, messages: list[BaseMessage], schema: dict[str, Any]) -> str:
        self.seen += [str(m.content) for m in messages]
        return MockLlmClient().write_json(messages, schema)


# 받은 조회 범위를 남긴다. 기본 조회를 실패시키거나 IP 요청에 다른 범위를 돌려줄 수 있다
class _Box(StaticToolBox):
    asked: list[str]
    mode: str

    def find_linked_cases(self, by: str) -> LinkedCasesTool:
        self.asked.append(by)
        if self.mode == "fail" and by == "CARD_DEVICE":
            raise RuntimeError("Spring 500")
        if self.mode == "mismatch" and by == "IP":
            return super().find_linked_cases("CARD_DEVICE")
        return super().find_linked_cases(by)


def _box(mode: str = "ok") -> _Box:
    box = _Box.from_sample(_sample())
    box.asked = []
    box.mode = mode
    return box


def _run(llm: _Picks, box: _Box) -> InvestigationOutcome:
    return run_investigation(context(DATA["casePayments"]["caseId"], 1), llm, box, SETTINGS)


def _report(outcome: InvestigationOutcome) -> ReportBody:
    assert outcome.status == "SUCCEEDED", outcome.error
    assert outcome.report is not None
    return outcome.report


# 지난 사건 조회가 실패해도 보고서는 만들고, "없음"이 아니라 "조회하지 못함"으로 남긴다
def test_base_linked_cases_failure_still_writes_report() -> None:
    report = _report(_run(_Picks(), _box("fail")))

    assert report.linkedCases is None
    assert LINKED_CASES_UNAVAILABLE in report.recommendation.limitations
    assert not [f for f in report.facts if f.topic == "linkedCases"]


# IP 조회는 AI가 지어낸 범위 인자를 버리고 늘 IP로만 묻는다
# 응답 범위가 IP가 아니면 실패로 남기고 카드 · 기기 결과를 IP 칸에 싣지 않는다
def test_extra_ip_linked_cases_ignores_ai_scope_arg() -> None:
    picked = call("find_linked_cases_ip", suspicion="CASE", by="CARD_DEVICE", caseId="1")

    box = _box()
    report = _report(_run(_Picks(picked), box))

    assert box.asked == ["CARD_DEVICE", "IP"]
    assert report.linkedCasesByIp is not None
    assert [c.caseId for c in report.linkedCasesByIp.cases] == ["888456"]
    [extra] = report.extraInvestigations
    assert (extra.args, extra.status) == ({}, "DONE")
    assert extra.factIds

    mismatched = _report(_run(_Picks(picked), _box("mismatch")))

    assert mismatched.linkedCasesByIp is None
    assert [e.status for e in mismatched.extraInvestigations] == ["FAILED"]


# AI에는 지난 사건 번호를 주지 않는다 — 건수 · 판정 종류 · 시점만 (11/14 결정)
# 화면 링크용 번호는 보고서 칸에 남는다
def test_linked_case_facts_hide_case_ids_and_reasons() -> None:
    llm = _Picks(call("find_linked_cases_ip", suspicion="CASE"))

    report = _report(_run(llm, _box()))

    texts = [f.text for f in report.facts if f.topic == "linkedCases"]
    assert any("승인 취소 1건" in t and "11/10 종료" in t for t in texts)
    for text in [*texts, *llm.seen]:
        assert PAST_CASE_ID not in text
        assert "888456" not in text
    assert report.linkedCases is not None
    assert [c.caseId for c in report.linkedCases.cases] == [PAST_CASE_ID]


# 지난 사건이 여러 건이면 합계 숫자가 사실에 있어야 한다
# 없으면 AI가 쓴 "2건"이 문장 검사에 걸려 보고서가 실패한다
# (로컬 사건 30, 11/15: 지난 사건 2건 · 모두 매입 진행 → NARRATIVE_INVALID 3번)
def test_linked_case_facts_twoCases_allowTotalInSummary() -> None:
    data = _linked_cases("CARD_DEVICE")
    second = {**data["cases"][0], "caseId": "29", "closedAt": "2026-11-14T18:10:30Z"}
    first = {**data["cases"][0], "caseId": "28", "closedAt": "2026-11-14T18:10:01Z"}
    for c in (first, second):
        c.update({"acceptCount": 1, "rejectCount": 0})
    data.update({"cases": [second, first], "totalMatched": 2})
    record = ToolRecord("find_linked_cases", {}, LinkedCasesTool.model_validate(data), True)
    facts = fact_catalog(context(DATA["casePayments"]["caseId"], 1), [record])
    sentence = (
        "같은 기기를 사용한 다른 고객의 지난 사건 2건이 최근 90일 내 종료되었고, "
        "모두 매입 진행 판정을 받았습니다."
    )
    summary = [{"text": sentence, "facts": ["지난사건-1", "지난사건-2"]}]

    _, errors = validate_narrative(narrative(summary=summary), facts, ["1609"])

    assert not [e for e in errors if "없는 숫자" in e], errors
