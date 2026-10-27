from typing import Literal

from pydantic import BaseModel, Field

# AI 보고서 본문 8개 섹션 — frontend/src/types/case.ts의 ReportBody와 키 · 타입을 같게 둔다
# (ADR-0009 결정 4)
# 한쪽만 고치지 않는다. 필드 이름이 JSON 키 그대로라 camelCase를 쓴다

Recommendation = Literal["ACCEPT_REVIEW", "REJECT_REVIEW", "NEEDS_CHECK"]


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


class ReportBody(BaseModel):
    overview: Overview
    summary: list[str] = Field(min_length=1)
    paymentAnalyses: list[PaymentAnalysis]
    timeline: list[TimelineItem]
    overallAnalysis: list[str]
    additionalChecks: list[str]
    recommendation: ReportRecommendation
    evidence: list[EvidenceItem]
