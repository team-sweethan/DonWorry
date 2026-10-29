from typing import Literal

from pydantic import BaseModel

# Spring AI Tool API 응답 — #254 CasePaymentsToolResponse · FdsEvidenceToolResponse ·
# #296 SimilarCasesToolResponse와 같게 둔다
# 못 찾은 값은 null이고 missingPaymentIds에 들어온다. 추측해서 채우지 않는다


class CasePayment(BaseModel):
    paymentId: str
    amount: int | None
    paymentStatus: str | None
    requestedAt: str | None


class CasePaymentsTool(BaseModel):
    caseId: str
    evidenceVersion: int
    source: str
    retrievedAt: str
    payments: list[CasePayment]
    missingPaymentIds: list[str]


class RuleHit(BaseModel):
    code: str
    name: str
    appliedScore: int | None


class FdsPayment(BaseModel):
    paymentId: str
    riskScore: int | None
    fdsResult: str | None
    ruleHits: list[RuleHit]


class FdsEvidenceTool(BaseModel):
    caseId: str
    evidenceVersion: int
    source: str
    retrievedAt: str
    payments: list[FdsPayment]
    missingPaymentIds: list[str]


class SharedRule(BaseModel):
    code: str
    name: str


# similarity: SAME_CUSTOMER(같은 가맹점 · 고객) / SHARED_RULES(적중 규칙 2개 이상 겹침)
# 결과는 결제별 직원 판정 건수 그대로 — 해석("사기 확정" 등)은 하지 않는다
class SimilarCase(BaseModel):
    caseId: str
    similarity: Literal["SAME_CUSTOMER", "SHARED_RULES"]
    sharedRules: list[SharedRule]
    closedAt: str | None
    acceptCount: int
    rejectCount: int


# 비슷한 종료 사건 최대 5건 — totalMatched는 5건을 넘는 것까지 센 전체 건수
class SimilarCasesTool(BaseModel):
    caseId: str
    evidenceVersion: int
    source: str
    retrievedAt: str
    cases: list[SimilarCase]
    totalMatched: int
