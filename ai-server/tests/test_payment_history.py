import json

from langchain_core.messages import HumanMessage

from app.agent.graph import run_investigation
from app.agent.llm import MockLlmClient
from app.agent.narrative import narrative_schema
from app.agent.tools import StaticToolBox
from app.config import Settings
from app.schemas.tools import CasePaymentsTool, FdsEvidenceTool, PaymentHistoryTool
from tests.fakes import ScriptedLlm, call, context, narrative

# 고객 최근 결제 (#338) — 사실 값은 Tool 기록에서 코드가 채운다
# AI가 안 부르면 다시 요청 → 그래도 안 하면 시스템 보충

SETTINGS = Settings(_env_file=None)
FIXTURE = json.loads(open("tests/fixtures/sample_case.json", encoding="utf-8").read())
HISTORY = {
    "caseId": "101",
    "evidenceVersion": 2,
    "source": "P5",
    "retrievedAt": "2026-10-27T12:05:00Z",
    "lookbackDays": 30,
    "payments": [
        {
            "paymentId": "8003",
            "amount": 40000,
            "paymentStatus": "VOIDED",
            "requestedAt": "2026-10-21T03:00:00Z",
        },
        {
            "paymentId": "8002",
            "amount": 580000,
            "paymentStatus": "CAPTURED",
            "requestedAt": "2026-10-14T03:00:00Z",
        },
        {
            "paymentId": "8001",
            "amount": 600000,
            "paymentStatus": "CAPTURED",
            "requestedAt": "2026-10-07T03:00:00Z",
        },
    ],
    "statusCounts": [
        {"paymentStatus": "CAPTURED", "count": 2},
        {"paymentStatus": "VOIDED", "count": 1},
    ],
    "truncated": False,
}


def _toolbox() -> StaticToolBox:
    return StaticToolBox(
        CasePaymentsTool.model_validate(FIXTURE["casePayments"]),
        FdsEvidenceTool.model_validate(FIXTURE["fdsEvidence"]),
        history=PaymentHistoryTool.model_validate(HISTORY),
    )


# 보고서의 고객 최근 결제(건수 · 금액 · 상태)는 Tool 응답 그대로다 — AI가 지어낼 칸이 없다
def test_payment_history_filled_from_tool_record_not_ai() -> None:
    outcome = run_investigation(context(), MockLlmClient(), _toolbox(), SETTINGS)

    assert outcome.status == "SUCCEEDED"
    assert outcome.report.paymentHistory.model_dump() == {
        key: HISTORY[key] for key in ("lookbackDays", "payments", "statusCounts", "truncated")
    }
    # AI가 쓰는 판단 문장 형식에는 이력 칸이 없다
    assert "history" not in json.dumps(narrative_schema(["9001", "9002"])).lower()


# AI가 고객 최근 결제를 안 보면 다시 요청하고, 그래도 안 보면 시스템이 조회한다
def test_payment_history_nudged_then_system_filled() -> None:
    llm = ScriptedLlm(
        turns=[
            [call("get_case_payments")],
            [call("get_fds_evidence")],
            [call("find_similar_cases")],
            None,
        ],
        outputs=[narrative(["9001", "9002"])],
    )

    outcome = run_investigation(context(), llm, _toolbox(), SETTINGS)

    assert outcome.status == "SUCCEEDED"
    nudges = [m for m in llm.choose_messages[-1] if isinstance(m, HumanMessage)]
    assert any("고객 최근 결제" in str(m.content) for m in nudges)
    filled = [r for r in outcome.records if r.filled_by_system]
    assert [r.tool_name for r in filled] == ["get_payment_history"]
    assert outcome.report.paymentHistory.statusCounts[0].count == 2


# 이력에 있는 금액을 쓴 문장은 "근거에 없는 숫자"로 거부되지 않는다 — 한 번에 통과한다
def test_payment_history_numbers_allowed_in_sentences() -> None:
    written = json.loads(narrative(["9001", "9002"]))
    sentence = "이 고객은 최근 58만 원 결제가 매입 완료된 이력이 있습니다."
    written["payments"]["9001"]["normalEvidence"] = [sentence]
    llm = ScriptedLlm(
        turns=[
            [call("get_case_payments")],
            [call("get_fds_evidence")],
            [call("find_similar_cases")],
            [call("get_payment_history")],
            None,
        ],
        outputs=[json.dumps(written, ensure_ascii=False)],
    )

    outcome = run_investigation(context(), llm, _toolbox(), SETTINGS)

    assert outcome.status == "SUCCEEDED"
    assert llm.write_calls == 1
    assert outcome.report.paymentAnalyses[0].normalEvidence == [sentence]
