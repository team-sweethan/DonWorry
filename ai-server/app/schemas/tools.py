from typing import Literal

from pydantic import BaseModel

# Spring AI Tool API 응답 — #254 CasePaymentsToolResponse · FdsEvidenceToolResponse ·
# #296 SimilarCasesToolResponse · #338 PaymentHistoryToolResponse ·
# #352 LinkedPaymentsToolResponse와 같게 둔다
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
    # Spring이 FDS 기준값 · 실제값으로 만든 문장 (#326). 정리할 수 없는 규칙 · 예전 Spring이면 None
    criterion: str | None = None
    observed: str | None = None


# 카드 · 기기 · 접속 국가 요약 (#346) — 카드는 마스킹 표시값, IP는 국가 코드만
# 원문(카드 토큰 · IP 주소 · 기기 ID · 이메일)은 Spring이 보내지 않는다
# *Provided는 가맹점이 그 값을 보냈는지 여부
class Signals(BaseModel):
    cardDisplayLabel: str | None
    cardProvided: bool
    deviceProvided: bool
    ipCountryCode: str | None
    ipProvided: bool


class FdsPayment(BaseModel):
    paymentId: str
    riskScore: int | None
    fdsResult: str | None
    ruleHits: list[RuleHit]
    # FDS 평가가 없거나 예전 Spring이면 None
    signals: Signals | None = None


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


# 고객 최근 결제 (#338) — 같은 가맹점 · 같은 고객의 최근 lookbackDays일 결제
# 이 사건 결제는 빠져 있다
class HistoryPayment(BaseModel):
    paymentId: str
    amount: int | None
    paymentStatus: str | None
    requestedAt: str | None


class StatusCount(BaseModel):
    paymentStatus: str
    count: int


# statusCounts는 payments(최대 20건) 안에서 Spring이 센 값이다. 기간 안에 더 있으면 truncated
class PaymentHistoryTool(BaseModel):
    caseId: str
    evidenceVersion: int
    source: str
    retrievedAt: str
    lookbackDays: int
    payments: list[HistoryPayment]
    statusCounts: list[StatusCount]
    truncated: bool


# 같은 카드 · 기기 · IP를 쓴 다른 고객 결제 (#352) — 같은 가맹점 · 최근 lookbackDays일
# 건수 · 고객 수 · 판정만 온다
# 카드 토큰 · IP 주소 · 기기 ID · 다른 고객 번호 · 결제 ID는 오지 않는다
class ResultCount(BaseModel):
    result: str
    count: int


# provided=False면 이 사건 결제가 그 값을 보내지 않아 비교하지 않았다는 뜻 ("정보 없음")
class LinkedMatch(BaseModel):
    provided: bool
    otherCustomerCount: int
    paymentCount: int
    resultCounts: list[ResultCount]
    declinedCount: int


class LinkedPaymentsTool(BaseModel):
    caseId: str
    evidenceVersion: int
    source: str
    retrievedAt: str
    lookbackDays: int
    card: LinkedMatch
    device: LinkedMatch
    ip: LinkedMatch


# 도구 응답 종류 전체 — Tool 기록 · 조사 과정 요약이 같이 쓴다
ToolResult = (
    CasePaymentsTool | FdsEvidenceTool | SimilarCasesTool | PaymentHistoryTool | LinkedPaymentsTool
)
