import json
import re
from datetime import datetime

from app.agent.narrative import Narrative
from app.agent.tools import ToolRecord
from app.schemas.case_context import CaseContext
from app.schemas.report import (
    EvidenceItem,
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
    LinkedMatch,
    LinkedPaymentsTool,
    PaymentHistoryTool,
    Signals,
    SimilarCasesTool,
)

# 사실 값은 Tool 기록에서만 채운다 — Qwen이 숫자 · ID를 잘못 옮겨 적어도 보고서 사실은 틀리지 않는다

ALWAYS_LIMITATIONS = (
    "로그인 기록 · 주문 정보는 이번 조사에서 확인하지 않았습니다 (조회 도구 미제공).",
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

# 가맹점이 보내지 않은 카드 · 기기 · IP — 위험 근거가 아니라 한계로 남긴다 (#346)
MISSING_SIGNAL_LIMITATIONS = {
    "card": "카드 정보가 없어 결제 카드를 확인하지 못했습니다 (가맹점이 보내지 않음).",
    "device": "기기 정보가 없어 결제 기기를 확인하지 못했습니다 (가맹점이 보내지 않음).",
    "ip": "접속 IP 정보가 없어 접속 국가를 확인하지 못했습니다 (가맹점이 보내지 않음).",
}
NOT_SENT = "정보 없음 (가맹점이 보내지 않음)"


# AI에게 보여 줄 카드 · 기기 · 접속 국가 — 있음/없음과 국가만 (#346)
# 카드 표시값(끝자리 숫자)은 넣지 않는다 — 문장에 섞이면 숫자 검사에 걸린다
def signal_facts(signals: Signals | None) -> dict[str, str] | str:
    if signals is None:
        return "정보 없음"
    if signals.ipCountryCode:
        code = signals.ipCountryCode
        country = "한국" if code == "KR" else f"해외({code})"
    else:
        country = "국가 모름" if signals.ipProvided else NOT_SENT
    return {
        "카드 정보": "있음" if signals.cardProvided else NOT_SENT,
        "기기 정보": "있음" if signals.deviceProvided else NOT_SENT,
        "접속 국가": country,
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


# AI에게 보여 줄 고객 최근 결제 목록 수 — 나머지는 상태별 건수로만 본다
HISTORY_SHOWN_TO_AI = 5
HISTORY_UNAVAILABLE = "고객 최근 결제를 조회하지 못했습니다."


# 코드가 센 상태별 건수를 한국어 이름으로 — "매입 완료 3건 · 승인 취소 1건"
def history_counts(history: PaymentHistoryTool) -> dict[str, int]:
    return {
        PAYMENT_STATUS_KO.get(c.paymentStatus, c.paymentStatus): c.count
        for c in history.statusCounts
    }


LINKED_UNAVAILABLE = "같은 카드 · 기기 · IP를 쓴 다른 결제를 조회하지 못했습니다."


# AI에게 보여 줄 연관 결제 — 숫자 대신 있음/없음 · 한 명/여러 명 · 판정 이름만 (#352)
# 건수 · 고객 수는 보고서 카드에 코드가 채운다. AI 문장에 숫자가 섞이지 않게 넣지 않는다
def linked_facts(match: LinkedMatch) -> dict[str, object] | str:
    if not match.provided:
        return NOT_SENT
    if match.paymentCount == 0:
        return "다른 고객 결제 없음"
    return {
        "다른 고객 결제": "있음",
        "다른 고객": "한 명" if match.otherCustomerCount == 1 else "여러 명",
        "그 결제들의 FDS 판정": [FDS_RESULT_KO.get(c.result, c.result) for c in match.resultCounts],
        "카드사 거절": "있음" if match.declinedCount else "없음",
    }


def similar_outcome(accept: int, reject: int) -> str:
    parts = [f"매입 진행 {accept}건"] if accept else []
    parts += [f"승인 취소 {reject}건"] if reject else []
    return " · ".join(parts) or "판정 없음"


# Qwen에게 보여 줄 근거 요약 — 판단 문장을 쓸 결제와 각 결제의 사실 값
# 비슷한 과거 사건은 이유 · 결과만 보여 준다 (참고 근거, 사건 번호는 문장에 쓰지 않으므로 넣지 않음)
# 고객 최근 결제는 건수 · 상태별 건수 · 최근 몇 건의 금액 · 상태만 보여 준다 (#338)
def facts_json(
    case_payments: CasePaymentsTool,
    fds: dict[str, FdsPayment],
    similar: SimilarCasesTool | None = None,
    history: PaymentHistoryTool | None = None,
    linked: LinkedPaymentsTool | None = None,
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
                "카드 · 기기 · 접속": signal_facts(evidence.signals if evidence else None),
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
    key = "고객 최근 결제(이 사건 밖)"
    if history is None:
        facts[key] = "조회하지 못함"
    else:
        facts[key] = {
            "기간(일)": history.lookbackDays,
            "건수": len(history.payments),
            "더 있음": history.truncated,
            "상태별 건수": history_counts(history),
            "최근 결제": [
                {
                    "금액(원)": p.amount,
                    "결제 상태": PAYMENT_STATUS_KO.get(p.paymentStatus or "", None),
                    "요청 시각": p.requestedAt,
                }
                for p in history.payments[:HISTORY_SHOWN_TO_AI]
            ],
        }
    key = "같은 카드 · 기기 · IP를 쓴 다른 고객 결제(최근 30일)"
    if linked is None:
        facts[key] = "조회하지 못함"
    else:
        facts[key] = {
            "같은 카드": linked_facts(linked.card),
            "같은 기기": linked_facts(linked.device),
            "같은 IP": linked_facts(linked.ip),
        }
    return json.dumps(facts, ensure_ascii=False, indent=2)


# 판단 문장에 나와도 되는 숫자
# 근거에 있는 금액 · 점수 · 결제 ID · 결제 수 · 시각 · 규칙 이름 속 숫자
def fact_numbers(
    case_payments: CasePaymentsTool,
    fds: dict[str, FdsPayment],
    similar: SimilarCasesTool | None = None,
    history: PaymentHistoryTool | None = None,
) -> set[int]:
    numbers = {len(case_payments.payments), sum(p.amount or 0 for p in case_payments.payments)}
    if history is not None:
        numbers |= {history.lookbackDays, len(history.payments)}
        numbers |= {c.count for c in history.statusCounts}
        for p in history.payments[:HISTORY_SHOWN_TO_AI]:
            numbers |= {int(d) for d in re.findall(r"\d+", p.requestedAt or "")}
            if p.amount is not None:
                numbers |= {p.amount, p.amount // 10000}
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
    history: PaymentHistoryTool | None = None,
    linked: LinkedPaymentsTool | None = None,
) -> ReportBody:
    analyses: list[PaymentAnalysis] = []
    timeline: list[TimelineItem] = []
    limitations = _drop_nothing_unknown(list(narrative.limitations))
    if history is None:
        limitations.append(HISTORY_UNAVAILABLE)
    if linked is None:
        limitations.append(LINKED_UNAVAILABLE)
    limitations += missing_signal_limitations(
        fds, [p.paymentId for p in case_payments.payments if analyzable(p)]
    )
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
