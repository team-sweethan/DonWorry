from pydantic import BaseModel

# Spring AI Tool API 응답 — #254 CasePaymentsToolResponse · FdsEvidenceToolResponse와 같게 둔다
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
