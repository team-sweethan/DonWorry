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


# 판정 근거 (#501) — 코드가 판정 기준표로 계산한다. AI는 이 값을 바꾸지 않는다
# level: HIGH_RISK(고위험 징후, 정상 고객에게 거의 없음) / CAUTION(주의 징후, 정상 고객에게도 있음)
# normalReason: 이 징후를 설명하는 정상 거래 근거 이름. None이면 "정상 사유 없음"
# countedIn: 같은 행동을 이미 센 징후 이름 (예: 짧은 시간 반복 → 거절 후 재시도)
# 판정에 따로 세지 않는다
class BasisSignal(BaseModel):
    key: str
    label: str
    detail: str | None
    level: Literal["HIGH_RISK", "CAUTION"]
    normalReason: str | None
    countedIn: str | None = None


# 정상 거래 근거 — canExplain은 설명할 수 있는 징후, explains는 이 결제에서 실제로 설명한 징후
class NormalBasis(BaseModel):
    key: str
    label: str
    canExplain: list[str]
    explains: list[str]


# rule: 판정 기준표에서 해당한 칸
# HIGH_RISK_WITH_OTHERS(고위험 + 정상 사유 없는 다른 징후 → 승인 취소 검토)
# HIGH_RISK_ONLY(고위험 하나만 → 추가 확인) · CAUTION_ONLY(주의만 → 추가 확인)
# ALL_EXPLAINED(모든 징후에 정상 사유 → 매입 진행 검토)
BasisRule = Literal["HIGH_RISK_WITH_OTHERS", "HIGH_RISK_ONLY", "CAUTION_ONLY", "ALL_EXPLAINED"]


class DecisionBasis(BaseModel):
    signals: list[BasisSignal]
    normals: list[NormalBasis]
    rule: BasisRule
    recommendation: Recommendation


class PaymentAnalysis(BaseModel):
    paymentId: str
    amount: int
    requestedAt: str
    riskScore: int | None
    ruleHits: list[str]
    # 위험 근거 · 정상 근거 칸은 없앴다 — 어느 쪽에 넣는 것 자체가 AI 판단이었다 (#548)
    # 모르는 사실은 코드가 채운다
    unknowns: list[str]
    # 기준표 판정 (#501) — basis.recommendation과 같다. 예전 보고서는 AI가 고른 값
    recommendation: Recommendation
    reason: str
    ruleHitDetails: list[RuleHitDetail]
    # 없으면 None — 예전 Spring · FDS 평가 없음
    signals: PaymentSignals | None = None
    # 없으면 None — #501 이전 보고서
    basis: DecisionBasis | None = None


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
# kind: AI 판단 · 조회 · 빠진 근거 재요청 · 사실 문장 쓰기 · 보고서 조립
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
    provider: Literal["OLLAMA", "OPENAI", "MOCK", "FIXED"]
    model: str
    durationMs: int | None
    steps: list[TraceStep]
    # 프롬프트 지문 (#581) — AI 보고서만 채운다. 고정 보고서 · 예전 보고서에는 없다
    promptFingerprint: str | None = None


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


# AI가 전달한 조사 사실 (#548) — 사실 번호(id)는 코드가 Tool 기록에서 만든다
class FactItem(BaseModel):
    id: str
    topic: str
    text: str
    # 이 사실을 가져온 조회 기록 — evidence의 evidenceId
    evidenceId: str
    # 결제 하나의 사실(접속 정보)이면 그 결제 ID — 화면 링크가 결제 카드로 간다 (#559)
    paymentId: str | None = None


class FactSentenceItem(BaseModel):
    text: str
    factIds: list[str] = Field(min_length=1)


class ReportBody(BaseModel):
    overview: Overview
    summary: list[str] = Field(min_length=1)
    # 요약 문장마다 근거 사실 번호 (#559) — summary와 같은 문장 · 같은 순서
    # 화면이 번호의 주제로 표 카드 링크를 붙인다
    summaryFacts: list[FactSentenceItem]
    paymentAnalyses: list[PaymentAnalysis]
    timeline: list[TimelineItem]
    # 종합 분석 칸은 없앴다 — 판단 문장이었다 (#548). 주제별 문장 칸도 없앴다 (#559)
    facts: list[FactItem]
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
