from app.agent.graph import run_investigation
from app.agent.llm import MockLlmClient
from app.agent.tools import StaticToolBox
from app.config import Settings
from app.schemas.tools import CasePayment, CasePaymentsTool, FdsEvidenceTool
from tests.fakes import context

# 규칙 근거의 기준 · 실제 문장 (#326) — Spring Tool 응답 값을 코드가 보고서에 그대로 옮긴다

SETTINGS = Settings(_env_file=None)


def _payments() -> CasePaymentsTool:
    return CasePaymentsTool(
        caseId="101",
        evidenceVersion=2,
        source="P3·P5",
        retrievedAt="2026-10-27T12:10:00Z",
        payments=[
            CasePayment(
                paymentId="15",
                amount=600000,
                paymentStatus="HELD",
                requestedAt="2026-10-27T12:00:00Z",
            )
        ],
        missingPaymentIds=[],
    )


def _fds(rule: dict[str, object]) -> FdsEvidenceTool:
    # Spring이 보낸 JSON 모양 그대로 읽는다
    return FdsEvidenceTool.model_validate(
        {
            "caseId": "101",
            "evidenceVersion": 2,
            "source": "P2",
            "retrievedAt": "2026-10-27T12:10:00Z",
            "payments": [
                {"paymentId": "15", "riskScore": 60, "fdsResult": "REVIEW", "ruleHits": [rule]}
            ],
            "missingPaymentIds": [],
        }
    )


# Tool 응답의 기준 · 실제 문장이 보고서 ruleHitDetails에 그대로 실린다
def test_assemble_copies_criterion_and_observed_to_rule_hit_details() -> None:
    fds = _fds(
        {
            "code": "R01",
            "name": "고액 결제",
            "appliedScore": 30,
            "criterion": "50만 원 이상",
            "observed": "60만 원",
        }
    )

    outcome = run_investigation(
        context(), MockLlmClient(), StaticToolBox(_payments(), fds), SETTINGS
    )

    assert outcome.status == "SUCCEEDED"
    [detail] = outcome.report.paymentAnalyses[0].ruleHitDetails
    assert (detail.criterion, detail.observed) == ("50만 원 이상", "60만 원")


# 기준 · 실제가 없는 예전 Spring 응답도 읽히고 보고서가 만들어진다
def test_fds_tool_without_criterion_still_parses() -> None:
    fds = _fds({"code": "R01", "name": "고액 결제", "appliedScore": 30})

    outcome = run_investigation(
        context(), MockLlmClient(), StaticToolBox(_payments(), fds), SETTINGS
    )

    assert outcome.status == "SUCCEEDED"
    [detail] = outcome.report.paymentAnalyses[0].ruleHitDetails
    assert (detail.criterion, detail.observed) == (None, None)
