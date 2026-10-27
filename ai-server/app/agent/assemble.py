import json

from app.agent.narrative import Narrative
from app.agent.tools import ToolRecord
from app.schemas.case_context import CaseContext
from app.schemas.report import (
    EvidenceItem,
    Overview,
    PaymentAnalysis,
    ReportBody,
    ReportRecommendation,
    TimelineItem,
)
from app.schemas.tools import CasePayment, CasePaymentsTool, FdsPayment

# 사실 값은 Tool 기록에서만 채운다 — Qwen이 숫자 · ID를 잘못 옮겨 적어도 보고서 사실은 틀리지 않는다

ALWAYS_LIMITATIONS = (
    "FDS 점수는 부정거래 확률이 아닙니다.",
    "AI 권고는 참고용이며 최종 판정은 보안 직원이 합니다.",
)


# 금액 · 요청 시각이 있어야 결제별 분석 대상이다. 못 찾은 결제는 미확인 사항으로만 남긴다
def analyzable(payment: CasePayment) -> bool:
    return payment.amount is not None and payment.requestedAt is not None


# Qwen에게 보여 줄 근거 요약 — 판단 문장을 쓸 결제와 각 결제의 사실 값
def facts_json(case_payments: CasePaymentsTool, fds: dict[str, FdsPayment]) -> str:
    payments = []
    for payment in case_payments.payments:
        evidence = fds.get(payment.paymentId)
        payments.append(
            {
                "결제 ID": payment.paymentId,
                "금액(원)": payment.amount,
                "결제 상태": payment.paymentStatus,
                "요청 시각": payment.requestedAt,
                "FDS 점수": evidence.riskScore if evidence else None,
                "FDS 판정": evidence.fdsResult if evidence else None,
                "적중 규칙": [
                    {"이름": rule.name, "점수": rule.appliedScore} for rule in evidence.ruleHits
                ]
                if evidence
                else [],
                "확인 못 함": [
                    label
                    for label, missing in (
                        ("결제 정보", not analyzable(payment)),
                        ("FDS 평가", evidence is None or evidence.riskScore is None),
                    )
                    if missing
                ],
            }
        )
    return json.dumps({"결제": payments}, ensure_ascii=False, indent=2)


def _unique(items: list[str]) -> list[str]:
    return list(dict.fromkeys(items))


def assemble_report(
    context: CaseContext,
    case_payments: CasePaymentsTool,
    fds: dict[str, FdsPayment],
    narrative: Narrative,
    records: list[ToolRecord],
    investigated_at: str,
) -> ReportBody:
    analyses: list[PaymentAnalysis] = []
    timeline: list[TimelineItem] = []
    limitations = list(narrative.limitations)
    checks = list(narrative.additionalChecks)
    for payment in case_payments.payments:
        if not analyzable(payment):
            limitations.append(
                f"결제 {payment.paymentId}의 금액 · 요청 시각을 확인하지 못했습니다."
            )
            checks.append(f"결제 {payment.paymentId}의 결제 정보 확인")
            continue
        evidence = fds.get(payment.paymentId)
        written = narrative.payments[payment.paymentId]
        unknowns = list(written.unknowns)
        if evidence is None or evidence.riskScore is None:
            unknowns.append("FDS 평가 결과 미확인")
        analyses.append(
            PaymentAnalysis(
                paymentId=payment.paymentId,
                amount=payment.amount,
                requestedAt=payment.requestedAt,
                riskScore=evidence.riskScore if evidence else None,
                ruleHits=[rule.code for rule in evidence.ruleHits] if evidence else [],
                riskEvidence=written.riskEvidence,
                normalEvidence=written.normalEvidence,
                unknowns=_unique(unknowns),
                recommendation=written.recommendation,
            )
        )
        fds_text = (
            f"FDS {evidence.fdsResult} ({evidence.riskScore}점)"
            if evidence and evidence.riskScore is not None
            else "FDS 평가 미확인"
        )
        timeline.append(
            TimelineItem(
                at=payment.requestedAt, event=f"{payment.paymentId} 결제 요청 · {fds_text}"
            )
        )
    timeline.sort(key=lambda item: item.at)
    return ReportBody(
        overview=Overview(
            caseId=context.case_id,
            merchantId=context.merchant_id,
            customerRef=context.customer_ref,
            paymentCount=len(case_payments.payments),
            totalAmount=sum(p.amount or 0 for p in case_payments.payments),
            caseCreatedAt=context.case_created_at,
            investigatedAt=investigated_at,
            reportVersion=context.report_version,
        ),
        summary=narrative.summary,
        paymentAnalyses=analyses,
        timeline=timeline,
        overallAnalysis=narrative.overallAnalysis,
        additionalChecks=_unique(checks),
        recommendation=ReportRecommendation(
            summary=narrative.recommendationSummary,
            limitations=_unique(limitations + list(ALWAYS_LIMITATIONS)),
        ),
        evidence=[
            EvidenceItem(
                toolName=record.tool_name,
                source=record.result.source + (" (자동 보충)" if record.filled_by_system else ""),
                evidenceId=f"E-{context.case_id}-v{context.report_version}-{seq:02d}",
                retrievedAt=record.result.retrievedAt,
                dataVersion=f"evidence v{record.result.evidenceVersion}",
            )
            for seq, record in enumerate(records, start=1)
        ],
    )
