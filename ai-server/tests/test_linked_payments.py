import json

import httpx

from app.agent.assemble import LINKED_UNAVAILABLE
from app.agent.graph import run_investigation
from app.agent.llm import MockLlmClient
from app.agent.tools import StaticToolBox
from app.client.spring import SpringToolClient
from app.config import Settings
from app.schemas.tools import CasePaymentsTool, FdsEvidenceTool, LinkedPaymentsTool
from tests.fakes import ScriptedLlm, call, catalog, context, narrative

# 같은 카드 · 기기 · IP를 쓴 다른 고객 결제 (#352)
# 건수 · 고객 수 · 판정은 Tool 기록에서 코드가 채운다

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
    # AI에게 주는 사실은 Tool 응답 숫자 그대로 — 사실 번호 검사로 옮겨 적기를 맞춰 본다 (#548)
    cases = CasePaymentsTool.model_validate(FIXTURE["casePayments"])
    texts = [f.text for f in catalog(cases, tool) if f.topic == "linked"]
    assert texts[0].endswith(
        "같은 카드: 최근 30일 다른 고객 2명 · 결제 3건 "
        "(FDS 판정 검토 2건 · 차단 1건), 카드사 거절 1건"
    )
    assert texts[2].endswith("같은 IP: IP 정보를 가맹점이 보내지 않아 찾지 못함")


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
