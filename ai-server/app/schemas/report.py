from typing import Literal

from pydantic import BaseModel, Field

# AI 보고서 본문 8개 섹션 + 결론(verdict) + 조사 과정(investigationTrace)
# + 비슷한 과거 사건(similarCases) + 고객 최근 결제(paymentHistory, #338)
# + 같은 카드 · 기기 · IP를 쓴 다른 결제(linkedPayments, #352)
# frontend/src/types/case.ts의 ReportBody와 키 · 타입을 같게 둔다
# (ADR-0009 결정 4)
# 한쪽만 고치지 않는다. 필드 이름이 JSON 키 그대로라 camelCase를 쓴다

Recommendation = Literal["ACCEPT_REVIEW", "REJECT_REVIEW", "NEEDS_CHECK"]


# 적중 규칙 하나 — FDS 조회 기록 값 그대로, evidenceId는 그 값을 받은 증거 추적 줄
class RuleHitDetail(BaseModel):
    code: str
    name: str
    appliedScore: int | None
    evidenceId: str
    # 기준 · 실제 문장 (#326) — Tool 응답 값 그대로, AI 문장이 아니다
    criterion: str | None = None
    observed: str | None = None


class Overview(BaseModel):
    caseId: str
    merchantId: str
    customerRef: str
    paymentCount: int
    totalAmount: int
    caseCreatedAt: str
    investigatedAt: str
    reportVersion: int


# 결제별 카드 · 기기 · 접속 국가 (#346) — Tool 기록 값 그대로 코드가 채운다
class PaymentSignals(BaseModel):
    cardDisplayLabel: str | None
    cardProvided: bool
    deviceProvided: bool
    ipCountryCode: str | None
    ipProvided: bool


class PaymentAnalysis(BaseModel):
    paymentId: str
    amount: int
    requestedAt: str
    riskScore: int | None
    ruleHits: list[str]
    riskEvidence: list[str]
    normalEvidence: list[str]
    unknowns: list[str]
    recommendation: Recommendation
    reason: str
    ruleHitDetails: list[RuleHitDetail]
    # 없으면 None — 예전 Spring · FDS 평가 없음
    signals: PaymentSignals | None = None


# 사건 경위 한 줄 — kind는 화면 점 색 구분용 (결제 · 사건 생성)
class TimelineItem(BaseModel):
    at: str
    event: str
    kind: Literal["PAYMENT", "CASE"]


class ReportRecommendation(BaseModel):
    summary: str
    limitations: list[str]


class EvidenceItem(BaseModel):
    toolName: str
    source: str
    evidenceId: str
    retrievedAt: str
    dataVersion: str


# 보고서 맨 위 결론 — headline은 AI 한 줄, overallRecommendation은 결제별 권고로 코드가 계산
class Verdict(BaseModel):
    headline: str
    overallRecommendation: Recommendation


# 조사 과정 한 단계 — 원본 값 · 프롬프트 없이 코드가 만든 요약만 둔다 (#294)
# kind: AI 판단 · 조회 · 빠진 근거 재요청 · 판단 문장 쓰기 · 보고서 조립
# actor: AI가 했는지 코드가 보충했는지
class TraceStep(BaseModel):
    kind: Literal["DECIDE", "TOOL", "NUDGE", "WRITE", "ASSEMBLE"]
    actor: Literal["AI", "SYSTEM"]
    title: str
    durationMs: int | None
    asked: str | None
    received: str | None
    retryReason: str | None


# 조사 과정 기록 — provider는 실제로 쓴 LLM 제공자, FIXED는 Spring 고정 문장 보고서
class InvestigationTrace(BaseModel):
    provider: Literal["OLLAMA", "MOCK", "FIXED"]
    model: str
    durationMs: int | None
    steps: list[TraceStep]


# 비슷한 과거 사건 (#296) — Tool 기록 값 그대로 코드가 채운다. AI 문장으로 만들지 않는다
class SimilarSharedRule(BaseModel):
    code: str
    name: str


class SimilarCaseItem(BaseModel):
    caseId: str
    similarity: Literal["SAME_CUSTOMER", "SHARED_RULES"]
    sharedRules: list[SimilarSharedRule]
    closedAt: str | None
    acceptCount: int
    rejectCount: int


class SimilarCases(BaseModel):
    cases: list[SimilarCaseItem]
    totalMatched: int


# 고객 최근 결제 (#338) — Tool 기록 값 그대로 코드가 채운다. 건수 · 금액을 AI가 쓰지 않는다
class PaymentHistoryItem(BaseModel):
    paymentId: str
    amount: int | None
    paymentStatus: str | None
    requestedAt: str | None


class PaymentStatusCount(BaseModel):
    paymentStatus: str
    count: int


class PaymentHistory(BaseModel):
    lookbackDays: int
    payments: list[PaymentHistoryItem]
    statusCounts: list[PaymentStatusCount]
    truncated: bool


# 같은 카드 · 기기 · IP를 쓴 다른 고객 결제 (#352) — Tool 기록 값 그대로 코드가 채운다
# 숫자를 AI가 쓰지 않는다
class LinkedResultCount(BaseModel):
    result: str
    count: int


# provided=False면 이 사건 결제가 그 값을 보내지 않음 — 화면은 "정보 없음"을 보여 준다
class LinkedMatch(BaseModel):
    provided: bool
    otherCustomerCount: int
    paymentCount: int
    resultCounts: list[LinkedResultCount]
    declinedCount: int


class LinkedPayments(BaseModel):
    lookbackDays: int
    card: LinkedMatch
    device: LinkedMatch
    ip: LinkedMatch


class ReportBody(BaseModel):
    overview: Overview
    summary: list[str] = Field(min_length=1)
    paymentAnalyses: list[PaymentAnalysis]
    timeline: list[TimelineItem]
    overallAnalysis: list[str]
    additionalChecks: list[str]
    recommendation: ReportRecommendation
    evidence: list[EvidenceItem]
    verdict: Verdict
    investigationTrace: InvestigationTrace
    # 조회하지 못했으면 None — 화면은 "조회하지 못함"을 보여 준다
    similarCases: SimilarCases | None
    # 조회하지 못했으면 None — 화면은 "조회하지 못함"을 보여 준다
    paymentHistory: PaymentHistory | None
    # 조회하지 못했으면 None — 화면은 "조회하지 못함"을 보여 준다
    linkedPayments: LinkedPayments | None
