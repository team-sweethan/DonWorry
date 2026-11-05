from datetime import datetime, timedelta

from app.agent.evidence import decision_basis
from app.agent.tools import ToolRecord
from app.agent.verdict_text import headline, payment_reason, recommendation_summary
from app.schemas.case_context import CaseContext
from app.schemas.report import (
    EvidenceItem,
    FactItem,
    FactTopicSection,
    InvestigationTrace,
    LinkedPayments,
    Overview,
    PaymentAnalysis,
    PaymentHistory,
    PaymentSignals,
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
    HistoryPayment,
    LinkedPaymentsTool,
    PaymentHistoryTool,
    SimilarCasesTool,
)

# 사실 값은 Tool 기록에서만 채운다 — AI가 숫자 · ID를 잘못 옮겨 적어도 보고서 사실은 틀리지 않는다

# "AI 권고는 참고용…"은 넣지 않는다 (#355)
# 보고서 전문 화면이 항상 같은 안내를 보여 줘 두 번 나왔다
ALWAYS_LIMITATIONS = (
    "로그인 기록 · 주문 정보는 이번 조사에서 확인하지 않았습니다 (조회 도구 미제공).",
    "FDS 점수는 부정거래 확률이 아닙니다.",
)

# 구매자 확인 기능이 없어 결제마다 항상 미확인이다
ALWAYS_UNKNOWN = "구매자 본인의 결제 의사"

# AI에게는 코드값 대신 한국어 이름으로 보여 준다 — 문장에 코드값이 섞이지 않게
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

# 가맹점이 보내지 않은 카드 · 기기 · IP — 위험 근거가 아니라 한계로 남긴다 (#346)
MISSING_SIGNAL_LIMITATIONS = {
    "card": "카드 정보가 없어 결제 카드를 확인하지 못했습니다 (가맹점이 보내지 않음).",
    "device": "기기 정보가 없어 결제 기기를 확인하지 못했습니다 (가맹점이 보내지 않음).",
    "ip": "접속 IP 정보가 없어 접속 국가를 확인하지 못했습니다 (가맹점이 보내지 않음).",
}


# 결제 중 하나라도 가맹점이 보내지 않은 값이 있으면 종류별로 한 줄씩
def missing_signal_limitations(fds: dict[str, FdsPayment], payment_ids: list[str]) -> list[str]:
    found = [fds[p].signals for p in payment_ids if p in fds and fds[p].signals is not None]
    lines = []
    if any(not s.cardProvided for s in found):
        lines.append(MISSING_SIGNAL_LIMITATIONS["card"])
    if any(not s.deviceProvided for s in found):
        lines.append(MISSING_SIGNAL_LIMITATIONS["device"])
    if any(not s.ipProvided for s in found):
        lines.append(MISSING_SIGNAL_LIMITATIONS["ip"])
    return lines


# AI에게 보여 줄 고객 최근 결제 목록 수 (묶음마다) — 나머지는 상태별 건수로만 본다
HISTORY_SHOWN_TO_AI = 5
HISTORY_UNAVAILABLE = "고객 최근 결제를 조회하지 못했습니다."
# 사건 결제 앞뒤 이 시간 안의 결제는 같은 연속 결제로 본다 (#355)
# 단시간 반복 규칙(10분 안 3회)과 같은 기준
BURST_WINDOW = timedelta(minutes=10)


def _at(value: str | None) -> datetime | None:
    return datetime.fromisoformat(value) if value else None


# 고객 최근 결제를 "같은 연속 결제" · "그 전 결제" · "사건 도중 · 뒤 결제"로 나눈다
# (#355 Case 8 · 9, #532)
# 사건 결제 몇 초 전의 결제가 "평소 결제 · 매입 완료"로 정상 정황에 쓰였다
def split_history(
    case_payments: CasePaymentsTool, history: PaymentHistoryTool
) -> tuple[list[HistoryPayment], list[HistoryPayment], list[HistoryPayment]]:
    case_times = [t for t in (_at(p.requestedAt) for p in case_payments.payments) if t]
    burst: list[HistoryPayment] = []
    earlier: list[HistoryPayment] = []
    later: list[HistoryPayment] = []
    for payment in history.payments:
        at = _at(payment.requestedAt)
        if at is not None and any(abs(at - t) <= BURST_WINDOW for t in case_times):
            burst.append(payment)
        elif at is not None and case_times and at > min(case_times):
            later.append(payment)
        else:
            earlier.append(payment)
    shown = HISTORY_SHOWN_TO_AI
    return burst[:shown], earlier[:shown], later[:shown]


# 코드가 센 상태별 건수를 한국어 이름으로 — "매입 완료 3건 · 승인 취소 1건"
def history_counts(history: PaymentHistoryTool) -> dict[str, int]:
    return {
        PAYMENT_STATUS_KO.get(c.paymentStatus, c.paymentStatus): c.count
        for c in history.statusCounts
    }


LINKED_UNAVAILABLE = "같은 카드 · 기기 · IP를 쓴 다른 결제를 조회하지 못했습니다."
# 다른 고객 결제를 찾았을 때 — AI가 "다른 고객 결제 이력은 알 수 없었다"고 쓰던 자리 (#355, Case 12)
LINKED_PRIVACY = (
    "같은 카드 · 기기 · IP를 쓴 다른 고객 결제는 건수 · 판정만 보고, "
    "개별 결제 내용은 보지 않았습니다 (다른 고객 정보 보호)."
)
MOCK_LIMITATION = "mock 조사입니다(Qwen 미사용). AI 문장은 고정 문장입니다."


def similar_outcome(accept: int, reject: int) -> str:
    parts = [f"매입 진행 {accept}건"] if accept else []
    parts += [f"승인 취소 {reject}건"] if reject else []
    return " · ".join(parts) or "판정 없음"


def _unique(items: list[str]) -> list[str]:
    return list(dict.fromkeys(items))


def overall_recommendation(recommendations: list[Recommendation]) -> Recommendation:
    if "REJECT_REVIEW" in recommendations:
        return "REJECT_REVIEW"
    if recommendations and all(r == "ACCEPT_REVIEW" for r in recommendations):
        return "ACCEPT_REVIEW"
    return "NEEDS_CHECK"


CASE_CREATED_EVENT = "사건 자동 생성 · AI 조사 시작"


# 사건 경위의 결제 한 줄 — "결제 15 · 600,000원 → FDS 검토 60점 → 매입 보류"
# 결제 상태를 모르면 추측하지 않고 뺀다
def payment_timeline_item(payment: CasePayment, evidence: FdsPayment | None) -> TimelineItem:
    parts = [f"결제 {payment.paymentId} · {payment.amount:,}원"]
    if evidence and evidence.riskScore is not None:
        result = FDS_RESULT_KO.get(evidence.fdsResult or "", "판정 미확인")
        parts.append(f"FDS {result} {evidence.riskScore}점")
    else:
        parts.append("FDS 평가 미확인")
    status = PAYMENT_STATUS_KO.get(payment.paymentStatus or "")
    if status:
        parts.append(status)
    return TimelineItem(at=payment.requestedAt or "", event=" → ".join(parts), kind="PAYMENT")


def evidence_id(context: CaseContext, seq: int) -> str:
    return f"E-{context.case_id}-v{context.report_version}-{seq:02d}"


# 결제별로 FDS 근거를 받은 증거 추적 줄 — 같은 결제를 여러 번 봤으면 마지막 조회(보고서에 쓴 값)
def _fds_evidence_ids(context: CaseContext, records: list[ToolRecord]) -> dict[str, str]:
    ids: dict[str, str] = {}
    for seq, record in enumerate(records, start=1):
        if isinstance(record.result, FdsEvidenceTool):
            for payment in record.result.payments:
                ids[payment.paymentId] = evidence_id(context, seq)
    return ids


def assemble_report(
    context: CaseContext,
    case_payments: CasePaymentsTool,
    fds: dict[str, FdsPayment],
    summary: list[str],
    additional_checks: list[str],
    fact_topics: list[FactTopicSection],
    facts: list[FactItem],
    records: list[ToolRecord],
    investigated_at: str,
    trace: InvestigationTrace,
    similar: SimilarCasesTool | None = None,
    history: PaymentHistoryTool | None = None,
    linked: LinkedPaymentsTool | None = None,
) -> ReportBody:
    analyses: list[PaymentAnalysis] = []
    timeline: list[TimelineItem] = []
    # 한계 줄은 조회 기록으로 코드만 쓴다 — 무엇을 봤는지는 AI보다 코드가 정확히 안다 (#355)
    limitations: list[str] = []
    if trace.provider == "MOCK":
        limitations.append(MOCK_LIMITATION)
    if history is None:
        limitations.append(HISTORY_UNAVAILABLE)
    if linked is None:
        limitations.append(LINKED_UNAVAILABLE)
    elif any(m.provided and m.paymentCount > 0 for m in (linked.card, linked.device, linked.ip)):
        limitations.append(LINKED_PRIVACY)
    limitations += missing_signal_limitations(
        fds, [p.paymentId for p in case_payments.payments if analyzable(p)]
    )
    checks = list(additional_checks)
    fds_evidence_ids = _fds_evidence_ids(context, records)
    for payment in case_payments.payments:
        if not analyzable(payment):
            limitations.append(
                f"결제 {payment.paymentId}의 금액 · 요청 시각을 확인하지 못했습니다."
            )
            checks.append(f"결제 {payment.paymentId}의 결제 정보 확인")
            continue
        evidence = fds.get(payment.paymentId)
        # 권고는 AI가 아니라 판정 기준표가 정한다 (#501)
        basis = (
            decision_basis(payment, evidence, case_payments, history, linked)
            if evidence is not None
            else None
        )
        # 모르는 사실은 코드가 쓴다 (#548) — AI는 조사한 사실만 전달한다
        unknowns = [ALWAYS_UNKNOWN]
        if evidence is None or evidence.riskScore is None:
            unknowns.append("FDS 평가 결과 미확인")
        analyses.append(
            PaymentAnalysis(
                paymentId=payment.paymentId,
                amount=payment.amount,
                requestedAt=payment.requestedAt,
                riskScore=evidence.riskScore if evidence else None,
                ruleHits=[rule.code for rule in evidence.ruleHits] if evidence else [],
                unknowns=_unique(unknowns),
                recommendation=basis.recommendation if basis else "NEEDS_CHECK",
                reason=payment_reason(basis),
                ruleHitDetails=[
                    RuleHitDetail(
                        code=rule.code,
                        name=rule.name,
                        appliedScore=rule.appliedScore,
                        evidenceId=fds_evidence_ids[payment.paymentId],
                        criterion=rule.criterion,
                        observed=rule.observed,
                    )
                    for rule in evidence.ruleHits
                ]
                if evidence
                else [],
                signals=PaymentSignals.model_validate(evidence.signals.model_dump())
                if evidence and evidence.signals
                else None,
                basis=basis,
            )
        )
        timeline.append(payment_timeline_item(payment, evidence))
    timeline.append(TimelineItem(at=context.case_created_at, event=CASE_CREATED_EVENT, kind="CASE"))
    # 글자 순서로 정렬하면 "12:00:00.100Z"가 "12:00:00Z"보다 앞에 온다 — 실제 시각으로 비교한다
    # 같은 시각이면 넣은 순서(결제 → 사건 생성)를 유지한다
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
        summary=summary,
        paymentAnalyses=analyses,
        timeline=timeline,
        factTopics=fact_topics,
        facts=facts,
        additionalChecks=_unique(checks),
        recommendation=ReportRecommendation(
            summary=recommendation_summary(analyses),
            limitations=_unique(limitations + list(ALWAYS_LIMITATIONS)),
        ),
        evidence=[
            EvidenceItem(
                toolName=record.tool_name,
                source=record.result.source + (" (자동 보충)" if record.filled_by_system else ""),
                evidenceId=evidence_id(context, seq),
                retrievedAt=record.result.retrievedAt,
                dataVersion=f"evidence v{record.result.evidenceVersion}",
            )
            for seq, record in enumerate(records, start=1)
        ],
        verdict=Verdict(
            headline=headline(analyses),
            overallRecommendation=overall_recommendation([a.recommendation for a in analyses]),
        ),
        investigationTrace=trace,
        similarCases=SimilarCases.model_validate(
            {"cases": [c.model_dump() for c in similar.cases], "totalMatched": similar.totalMatched}
        )
        if similar is not None
        else None,
        paymentHistory=PaymentHistory.model_validate(
            history.model_dump(include={"lookbackDays", "payments", "statusCounts", "truncated"})
        )
        if history is not None
        else None,
        linkedPayments=LinkedPayments.model_validate(
            linked.model_dump(include={"lookbackDays", "card", "device", "ip"})
        )
        if linked is not None
        else None,
    )
