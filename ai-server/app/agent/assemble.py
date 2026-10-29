import json
import re
from datetime import datetime

from app.agent.narrative import Narrative
from app.agent.tools import ToolRecord
from app.schemas.case_context import CaseContext
from app.schemas.report import (
    EvidenceItem,
    InvestigationTrace,
    Overview,
    PaymentAnalysis,
    Recommendation,
    ReportBody,
    ReportRecommendation,
    RuleHitDetail,
    SimilarCases,
    TimelineItem,
    Verdict,
)
from app.schemas.tools import (
    CasePayment,
    CasePaymentsTool,
    FdsEvidenceTool,
    FdsPayment,
    SimilarCasesTool,
)

# 사실 값은 Tool 기록에서만 채운다 — Qwen이 숫자 · ID를 잘못 옮겨 적어도 보고서 사실은 틀리지 않는다

ALWAYS_LIMITATIONS = (
    "결제 이력 · 기기 · 로그인 기록 · 주문 정보는 이번 조사에서 확인하지 않았습니다"
    " (조회 도구 미제공).",
    "FDS 점수는 부정거래 확률이 아닙니다.",
    "AI 권고는 참고용이며 최종 판정은 보안 직원이 합니다.",
)

# 구매자 확인 기능이 없어 결제마다 항상 미확인이다
ALWAYS_UNKNOWN = "구매자 본인의 결제 의사"

# "확인 못 한 것이 없다"는 문장 — 위 한계와 모순되므로 걸러 낸다
_NOTHING_UNKNOWN = re.compile(r"(?:미확인|확인되지\s*않은|확인하지\s*못한)[^.]*없")

# Qwen에게는 코드값 대신 한국어 이름으로 보여 준다 — 문장에 코드값이 섞이지 않게
PAYMENT_STATUS_KO = {
    "PENDING": "처리 중",
    "HELD": "매입 보류",
    "CAPTURED": "매입 완료",
    "VOIDED": "승인 취소",
    "DENIED": "자동 차단",
    "DECLINED": "카드사 거절",
    "EXPIRED": "만료",
}
FDS_RESULT_KO = {"ALLOW": "허용", "REVIEW": "검토", "DENY": "차단", "ERROR": "평가 오류"}


# 금액 · 요청 시각이 있어야 결제별 분석 대상이다. 못 찾은 결제는 미확인 사항으로만 남긴다
def analyzable(payment: CasePayment) -> bool:
    return payment.amount is not None and payment.requestedAt is not None


SIMILARITY_KO = {"SAME_CUSTOMER": "같은 고객", "SHARED_RULES": "같은 규칙 조합"}


def similar_outcome(accept: int, reject: int) -> str:
    parts = [f"매입 진행 {accept}건"] if accept else []
    parts += [f"승인 취소 {reject}건"] if reject else []
    return " · ".join(parts) or "판정 없음"


# Qwen에게 보여 줄 근거 요약 — 판단 문장을 쓸 결제와 각 결제의 사실 값
# 비슷한 과거 사건은 이유 · 결과만 보여 준다 (참고 근거, 사건 번호는 문장에 쓰지 않으므로 넣지 않음)
def facts_json(
    case_payments: CasePaymentsTool,
    fds: dict[str, FdsPayment],
    similar: SimilarCasesTool | None = None,
) -> str:
    payments = []
    for payment in case_payments.payments:
        evidence = fds.get(payment.paymentId)
        payments.append(
            {
                "결제 ID": payment.paymentId,
                "금액(원)": payment.amount,
                "결제 상태": PAYMENT_STATUS_KO.get(payment.paymentStatus or "", None),
                "요청 시각": payment.requestedAt,
                "FDS 점수": evidence.riskScore if evidence else None,
                "FDS 판정": FDS_RESULT_KO.get(evidence.fdsResult or "", None) if evidence else None,
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
    facts: dict[str, object] = {"결제": payments}
    if similar is not None:
        facts["비슷한 과거 사건(참고)"] = [
            {
                "비슷한 이유": SIMILARITY_KO[c.similarity],
                "겹친 규칙": [rule.name for rule in c.sharedRules],
                "결과": similar_outcome(c.acceptCount, c.rejectCount),
            }
            for c in similar.cases
        ]
    return json.dumps(facts, ensure_ascii=False, indent=2)


# 판단 문장에 나와도 되는 숫자
# 근거에 있는 금액 · 점수 · 결제 ID · 결제 수 · 시각 · 규칙 이름 속 숫자
def fact_numbers(
    case_payments: CasePaymentsTool,
    fds: dict[str, FdsPayment],
    similar: SimilarCasesTool | None = None,
) -> set[int]:
    numbers = {len(case_payments.payments), sum(p.amount or 0 for p in case_payments.payments)}
    if similar is not None:
        numbers |= {len(similar.cases), similar.totalMatched}
        for c in similar.cases:
            numbers |= {c.acceptCount, c.rejectCount}
    for payment in case_payments.payments:
        numbers |= {int(d) for d in re.findall(r"\d+", payment.paymentId)}
        numbers |= {int(d) for d in re.findall(r"\d+", payment.requestedAt or "")}
        if payment.amount is not None:
            numbers |= {payment.amount, payment.amount // 10000}
    for evidence in fds.values():
        if evidence.riskScore is not None:
            numbers.add(evidence.riskScore)
        for rule in evidence.ruleHits:
            if rule.appliedScore is not None:
                numbers.add(rule.appliedScore)
            numbers |= {int(d) for d in re.findall(r"\d+", rule.name)}
    return numbers


def _unique(items: list[str]) -> list[str]:
    return list(dict.fromkeys(items))


def _drop_nothing_unknown(items: list[str]) -> list[str]:
    return [item for item in items if not _NOTHING_UNKNOWN.search(item)]


# 종합 권고 — 하나라도 승인 취소 검토면 승인 취소 검토, 모두 매입 진행 검토면 매입 진행 검토
def overall_recommendation(recommendations: list[Recommendation]) -> Recommendation:
    if "REJECT_REVIEW" in recommendations:
        return "REJECT_REVIEW"
    if recommendations and all(r == "ACCEPT_REVIEW" for r in recommendations):
        return "ACCEPT_REVIEW"
    return "NEEDS_CHECK"


def _evidence_id(context: CaseContext, seq: int) -> str:
    return f"E-{context.case_id}-v{context.report_version}-{seq:02d}"


# 결제별로 FDS 근거를 받은 증거 추적 줄 — 같은 결제를 여러 번 봤으면 마지막 조회(보고서에 쓴 값)
def _fds_evidence_ids(context: CaseContext, records: list[ToolRecord]) -> dict[str, str]:
    ids: dict[str, str] = {}
    for seq, record in enumerate(records, start=1):
        if isinstance(record.result, FdsEvidenceTool):
            for payment in record.result.payments:
                ids[payment.paymentId] = _evidence_id(context, seq)
    return ids


def assemble_report(
    context: CaseContext,
    case_payments: CasePaymentsTool,
    fds: dict[str, FdsPayment],
    narrative: Narrative,
    records: list[ToolRecord],
    investigated_at: str,
    trace: InvestigationTrace,
    similar: SimilarCasesTool | None = None,
) -> ReportBody:
    analyses: list[PaymentAnalysis] = []
    timeline: list[TimelineItem] = []
    limitations = _drop_nothing_unknown(list(narrative.limitations))
    checks = list(narrative.additionalChecks)
    fds_evidence_ids = _fds_evidence_ids(context, records)
    for payment in case_payments.payments:
        if not analyzable(payment):
            limitations.append(
                f"결제 {payment.paymentId}의 금액 · 요청 시각을 확인하지 못했습니다."
            )
            checks.append(f"결제 {payment.paymentId}의 결제 정보 확인")
            continue
        evidence = fds.get(payment.paymentId)
        written = narrative.payments[payment.paymentId]
        unknowns = _drop_nothing_unknown(list(written.unknowns)) + [ALWAYS_UNKNOWN]
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
                reason=written.reason,
                ruleHitDetails=[
                    RuleHitDetail(
                        code=rule.code,
                        name=rule.name,
                        appliedScore=rule.appliedScore,
                        evidenceId=fds_evidence_ids[payment.paymentId],
                    )
                    for rule in evidence.ruleHits
                ]
                if evidence
                else [],
            )
        )
        fds_text = (
            f"FDS {FDS_RESULT_KO.get(evidence.fdsResult or '', '판정 미확인')}"
            f" ({evidence.riskScore}점)"
            if evidence and evidence.riskScore is not None
            else "FDS 평가 미확인"
        )
        timeline.append(
            TimelineItem(
                at=payment.requestedAt, event=f"{payment.paymentId} 결제 요청 · {fds_text}"
            )
        )
    # 글자 순서로 정렬하면 "12:00:00.100Z"가 "12:00:00Z"보다 앞에 온다 — 실제 시각으로 비교한다
    timeline.sort(key=lambda item: datetime.fromisoformat(item.at))
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
                evidenceId=_evidence_id(context, seq),
                retrievedAt=record.result.retrievedAt,
                dataVersion=f"evidence v{record.result.evidenceVersion}",
            )
            for seq, record in enumerate(records, start=1)
        ],
        verdict=Verdict(
            headline=narrative.headline.strip(),
            overallRecommendation=overall_recommendation([a.recommendation for a in analyses]),
        ),
        investigationTrace=trace,
        similarCases=SimilarCases.model_validate(
            {"cases": [c.model_dump() for c in similar.cases], "totalMatched": similar.totalMatched}
        )
        if similar is not None
        else None,
    )
