from typing import Literal

from pydantic import BaseModel, Field

# AI 보고서 본문 8개 섹션 + 결론(verdict)
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


class Overview(BaseModel):
    caseId: str
    merchantId: str
    customerRef: str
    paymentCount: int
    totalAmount: int
    caseCreatedAt: str
    investigatedAt: str
    reportVersion: int


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


class TimelineItem(BaseModel):
    at: str
    event: str


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
