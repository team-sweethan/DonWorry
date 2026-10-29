from app.agent.graph import run_investigation
from app.agent.llm import MockLlmClient
from app.agent.tools import StaticToolBox
from app.config import Settings
from app.schemas.tools import CasePayment, CasePaymentsTool, FdsEvidenceTool, FdsPayment
from tests.fakes import context, toolbox

# 사건 경위 (#310) — 결제 → FDS → 매입 보류 → 사건 생성 순서를 코드가 사실 값으로 만든다

SETTINGS = Settings(_env_file=None)
CASE_CREATED = "2026-10-27T12:00:30Z"


def _payments(*items: CasePayment) -> CasePaymentsTool:
    return CasePaymentsTool(
        caseId="101",
        evidenceVersion=2,
        source="P3·P5",
        retrievedAt="2026-10-27T12:10:00Z",
        payments=list(items),
        missingPaymentIds=[],
    )


def _fds(*items: FdsPayment, missing: list[str] | None = None) -> FdsEvidenceTool:
    return FdsEvidenceTool(
        caseId="101",
        evidenceVersion=2,
        source="P2",
        retrievedAt="2026-10-27T12:10:00Z",
        payments=list(items),
        missingPaymentIds=missing or [],
    )


# 결제가 사건 생성과 같은 시각이어도 결제 → 사건 생성 순서로 보인다
def test_timeline_payment_hold_then_case_created() -> None:
    payments = _payments(
        CasePayment(paymentId="15", amount=600000, paymentStatus="HELD", requestedAt=CASE_CREATED)
    )
    fds = _fds(FdsPayment(paymentId="15", riskScore=60, fdsResult="REVIEW", ruleHits=[]))

    outcome = run_investigation(context(), MockLlmClient(), StaticToolBox(payments, fds), SETTINGS)

    assert outcome.status == "SUCCEEDED"
    assert [(t.kind, t.event) for t in outcome.report.timeline] == [
        ("PAYMENT", "결제 15 · 600,000원 → FDS 검토 60점 → 매입 보류"),
        ("CASE", "사건 자동 생성 · AI 조사 시작"),
    ]


# 결제 상태가 없거나 모르는 값이면 "매입 보류"로 추측하지 않고 뺀다
# (금액 · FDS 점수가 없으면 보고서 자체가 실패하므로 비어 있을 수 있는 값은 결제 상태뿐)
def test_timeline_unknown_status_not_guessed() -> None:
    payments = _payments(
        CasePayment(
            paymentId="16", amount=30000, paymentStatus=None, requestedAt="2026-10-27T12:00:00Z"
        ),
        CasePayment(
            paymentId="17", amount=40000, paymentStatus="NEW", requestedAt="2026-10-27T12:00:10Z"
        ),
    )
    fds = _fds(
        *(
            FdsPayment(paymentId=p, riskScore=45, fdsResult="REVIEW", ruleHits=[])
            for p in ("16", "17")
        )
    )

    outcome = run_investigation(context(), MockLlmClient(), StaticToolBox(payments, fds), SETTINGS)

    assert outcome.status == "SUCCEEDED"
    paid = [t.event for t in outcome.report.timeline if t.kind == "PAYMENT"]
    assert paid == [
        "결제 16 · 30,000원 → FDS 검토 45점",
        "결제 17 · 40,000원 → FDS 검토 45점",
    ]


# 사건이 생긴 뒤 병합된 결제는 사건 생성 줄 뒤에 남는다 (9001 12:00 → 사건 12:00:30 → 9002 12:07)
def test_timeline_merged_payment_after_case_stays_after() -> None:
    outcome = run_investigation(context(), MockLlmClient(), toolbox("sample_case.json"), SETTINGS)

    assert outcome.status == "SUCCEEDED"
    assert [(t.kind, t.at) for t in outcome.report.timeline] == [
        ("PAYMENT", "2026-10-27T12:00:00Z"),
        ("CASE", CASE_CREATED),
        ("PAYMENT", "2026-10-27T12:07:00Z"),
    ]
