from app.agent.facts import fact_catalog
from app.agent.tools import ToolRecord
from app.schemas.case_context import CaseContext
from app.schemas.tools import CasePaymentsTool, PaymentHistoryTool

# 사건은 30분 안의 보류 결제를 계속 합친다 — 10:00 보류 → 10:14 정상 결제 2건 → 10:28 보류 (#532)
# 기준표 계산은 Spring으로 옮겼고(#618, DecisionBasisCalculatorTests), AI 사실 쪽 기준은 여기서 본다
# (tests/test_decision_basis.py에서 옮김)

AT = "2026-05-21T11:16:43Z"
HEAD = {"caseId": "1011", "evidenceVersion": 1, "source": "P2", "retrievedAt": AT}


def _stretched_case() -> CasePaymentsTool:
    return CasePaymentsTool.model_validate(
        {
            **HEAD,
            "payments": [
                {"paymentId": pid, "amount": 220000, "paymentStatus": "HELD", "requestedAt": at}
                for pid, at in [("1", "2026-05-21T10:00:00Z"), ("2", "2026-05-21T10:28:00Z")]
            ],
            "missingPaymentIds": [],
        }
    )


def _during() -> PaymentHistoryTool:
    return PaymentHistoryTool.model_validate(
        {
            **HEAD,
            "lookbackDays": 30,
            "payments": [
                {"paymentId": f"h{i}", "amount": a, "paymentStatus": "CAPTURED", "requestedAt": t}
                for i, (a, t) in enumerate(
                    [(220000, "2026-05-21T10:14:00Z"), (230000, "2026-05-21T10:14:30Z")]
                )
            ],
            "statusCounts": [],
            "truncated": False,
        }
    )


# AI에게도 사건 도중 결제를 "사건 전 결제"(평소 결제)로 보여 주지 않는다 — 판정과 같은 기준
def test_facts_do_not_show_payments_during_case_as_earlier() -> None:
    context = CaseContext(
        case_id="1011",
        evidence_version=1,
        report_version=1,
        merchant_id="m1",
        customer_ref="c1",
        case_created_at=AT,
    )
    records = [
        ToolRecord("get_case_payments", {}, _stretched_case()),
        ToolRecord("get_payment_history", {}, _during()),
    ]

    texts = [fact.text for fact in fact_catalog(context, records) if fact.topic == "history"]

    assert not any(text.startswith("사건 전 결제") for text in texts)
    assert any(text.startswith("사건 도중 · 뒤 결제 2건") for text in texts)
