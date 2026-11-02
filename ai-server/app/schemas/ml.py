from typing import Literal, Self

from pydantic import AwareDatetime, BaseModel, Field, model_validator

# Spring ↔ AI 서버 ML Shadow 평가 계약 — 필드 이름이 JSON 키 그대로라 camelCase를 쓴다
# 결제 판정에는 쓰지 않는다. Rule 결과와 비교 기록용 (Shadow Mode)

# 이력이 이보다 많으면 거절한다 — 1코어 VM에서 요청 하나가 메모리 · 시간을 다 쓰지 않게
MAX_HISTORY_ROWS = 2000


# Feature 계산에 쓰는 거래 한 건 — 카드 · 기기는 같은지만 비교하므로 Spring이 해시로 보내도 된다
class MlTransaction(BaseModel):
    paymentId: int = Field(gt=0)
    merchantId: int = Field(gt=0)
    customerRef: str = Field(min_length=1, max_length=100)
    amount: int = Field(gt=0)
    paymentRequestedAt: AwareDatetime
    deviceId: str | None = Field(default=None, min_length=1, max_length=255)
    cardToken: str | None = Field(default=None, min_length=1, max_length=255)

    # 고객은 가맹점 + customerRef로 구분한다 — 가맹점마다 회원번호가 겹칠 수 있다 (Rule과 같음)
    def same_customer(self, other: "MlTransaction") -> bool:
        return self.merchantId == other.merchantId and self.customerRef == other.customerRef


# customerHistory: 대상 고객(같은 가맹점 · 같은 customerRef)의 이전 거래
# sharedCardHistory: 같은 카드를 쓴 다른 고객(가맹점이나 customerRef가 다름)의 이전 거래
class FraudScoreRequest(BaseModel):
    evaluationId: int = Field(gt=0)
    target: MlTransaction
    customerHistory: list[MlTransaction] = Field(default_factory=list)
    sharedCardHistory: list[MlTransaction] = Field(default_factory=list)

    @model_validator(mode="after")
    def check_history(self) -> Self:
        history = self.customerHistory + self.sharedCardHistory
        if len(history) > MAX_HISTORY_ROWS:
            raise ValueError(f"이력은 {MAX_HISTORY_ROWS}건까지 받습니다")
        # 결제 뒤 거래가 섞이면 미래 정보로 판단하게 된다 (같은 시각은 features.py가 이력에서 뺀다)
        if any(row.paymentRequestedAt > self.target.paymentRequestedAt for row in history):
            raise ValueError("대상 결제보다 뒤 시각의 거래가 이력에 있습니다")
        # 재평가 행이 겹쳐 오면 같은 결제가 두 번 세진다
        payment_ids = [self.target.paymentId, *(row.paymentId for row in history)]
        if len(set(payment_ids)) != len(payment_ids):
            raise ValueError("같은 paymentId가 두 번 이상 있습니다")
        if any(not row.same_customer(self.target) for row in self.customerHistory):
            raise ValueError("customerHistory에 다른 고객 거래가 있습니다")
        if any(
            row.same_customer(self.target) or row.cardToken != self.target.cardToken
            for row in self.sharedCardHistory
        ):
            raise ValueError("sharedCardHistory는 같은 카드를 쓴 다른 고객 거래만 받습니다")
        return self


class FraudScoreResponse(BaseModel):
    evaluationId: int
    modelVersion: str
    fraudProbability: float
    threshold: float
    # 분석용 표시 — 결제 승인 · 보류 · 거절 판정이 아니다
    mlRiskLevel: Literal["HIGH", "LOW"]
    # 모델에 들어간 값 그대로 — 확률 근거 확인 · Spring과 대조용
    features: dict[str, int | float | str]
    latencyMs: int
