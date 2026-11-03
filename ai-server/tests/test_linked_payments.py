import json
import re

import httpx

from app.agent.assemble import LINKED_UNAVAILABLE, facts_json
from app.agent.graph import run_investigation
from app.agent.llm import MockLlmClient
from app.agent.narrative import narrative_schema
from app.agent.tools import StaticToolBox
from app.client.spring import SpringToolClient
from app.config import Settings
from app.schemas.tools import CasePaymentsTool, FdsEvidenceTool, LinkedPaymentsTool
from tests.fakes import ScriptedLlm, call, context, narrative

# 같은 카드 · 기기 · IP를 쓴 다른 고객 결제 (#352)
# 건수 · 고객 수 · 판정은 Tool 기록에서 코드가 채운다
# AI에게는 있음/없음 · 한 명/여러 명 · 판정 이름만 보여 준다

SETTINGS = Settings(_env_file=None)
FIXTURE = json.loads(open("tests/fixtures/sample_case.json", encoding="utf-8").read())
# Spring LinkedPaymentsToolResponse를 Jackson이 내보내는 모양 그대로
LINKED = {
    "caseId": "101",
    "evidenceVersion": 2,
    "source": "P2",
    "retrievedAt": "2026-10-27T12:05:00Z",
    "lookbackDays": 30,
    "card": {
        "provided": True,
        "otherCustomerCount": 2,
        "paymentCount": 3,
        "resultCounts": [{"result": "REVIEW", "count": 2}, {"result": "DENY", "count": 1}],
        "declinedCount": 1,
    },
    "device": {
        "provided": True,
        "otherCustomerCount": 0,
        "paymentCount": 0,
        "resultCounts": [],
        "declinedCount": 0,
    },
    "ip": {
        "provided": False,
        "otherCustomerCount": 0,
        "paymentCount": 0,
        "resultCounts": [],
        "declinedCount": 0,
    },
}


def _toolbox(linked: LinkedPaymentsTool | None = None) -> StaticToolBox:
    return StaticToolBox(
        CasePaymentsTool.model_validate(FIXTURE["casePayments"]),
        FdsEvidenceTool.model_validate(FIXTURE["fdsEvidence"]),
        linked=linked,
    )


class FailingLinkedToolBox(StaticToolBox):
    def find_linked_payments(self) -> LinkedPaymentsTool:
        raise ConnectionError("Spring에 연결할 수 없다")


# 보고서의 고객 수 · 결제 수 · 판정은 Tool 응답 그대로다 — AI가 지어낼 칸도, 볼 숫자도 없다
def test_linked_payments_filled_from_tool_record_not_ai() -> None:
    tool = LinkedPaymentsTool.model_validate(LINKED)

    outcome = run_investigation(context(), MockLlmClient(), _toolbox(tool), SETTINGS)

    assert outcome.status == "SUCCEEDED"
    assert outcome.report.linkedPayments.model_dump() == {
        key: LINKED[key] for key in ("lookbackDays", "card", "device", "ip")
    }
    # AI가 쓰는 판단 문장 형식에는 연관 결제 칸이 없다
    assert "linked" not in json.dumps(narrative_schema(["9001", "9002"])).lower()
    # AI에게 보여 주는 근거에는 숫자 대신 있음/없음 · 여러 명 · 판정 이름만 들어간다
    cases = CasePaymentsTool.model_validate(FIXTURE["casePayments"])
    facts = json.loads(facts_json(cases, {}, None, None, tool))
    linked_facts = facts["같은 카드 · 기기 · IP를 쓴 다른 고객 결제(최근 30일)"]
    assert not re.search(r"\d", json.dumps(linked_facts, ensure_ascii=False))
    assert linked_facts["같은 카드"]["다른 고객"] == "여러 명"
    assert linked_facts["같은 IP"] == "정보 없음 (가맹점이 보내지 않음)"


# Spring 응답 키를 그대로 읽는다 — 어긋나면 카드가 늘 "조회하지 못함"이 된다 (FastAPI ↔ Spring 계약)
def test_find_linked_payments_parses_spring_response() -> None:
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(200, json=LINKED)

    client = SpringToolClient("http://spring", "secret", transport=httpx.MockTransport(handler))

    result = client.find_linked_payments("101")

    assert seen[0].method == "GET"
    assert seen[0].url.path == "/internal/ai/tools/find_linked_payments"
    assert seen[0].url.params["caseId"] == "101"
    assert result.card.otherCustomerCount == 2
    assert [(c.result, c.count) for c in result.card.resultCounts] == [("REVIEW", 2), ("DENY", 1)]
    assert result.ip.provided is False


# 조회가 실패해도 보고서는 만들고, 칸은 비우고(null) 한계 줄에 남긴다
# "같은 카드 없음"으로 보이지 않게
def test_linked_lookup_failure_report_succeeds_with_limitation() -> None:
    failing = FailingLinkedToolBox(
        CasePaymentsTool.model_validate(FIXTURE["casePayments"]),
        FdsEvidenceTool.model_validate(FIXTURE["fdsEvidence"]),
    )
    llm = ScriptedLlm(
        turns=[
            [call("get_case_payments")],
            [call("get_fds_evidence")],
            [call("find_similar_cases")],
            [call("get_payment_history")],
            [call("find_linked_payments")],
            None,
        ],
        outputs=[narrative(["9001", "9002"])],
    )

    outcome = run_investigation(context(), llm, failing, SETTINGS)

    assert outcome.status == "SUCCEEDED"
    assert outcome.report.linkedPayments is None
    assert LINKED_UNAVAILABLE in outcome.report.recommendation.limitations
