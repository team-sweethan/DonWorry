from collections.abc import Callable
from dataclasses import dataclass
from typing import Any, Protocol

from app.client.spring import SpringToolClient
from app.schemas.tools import (
    CasePaymentsTool,
    DecisionBasisTool,
    FdsEvidenceTool,
    LinkedMatch,
    LinkedPaymentsTool,
    OrderContextTool,
    OrderPayment,
    PaymentHistoryTool,
    SimilarCasesTool,
    ToolResult,
)


# 추가 조사 도구 — 기본 조회 6개(코드)를 본 AI가 더 볼 때만 고른다 (#578)
# 읽기 전용만 둔다 (AGENTS.md 6절). caseId 같은 권한 인자는 AI가 아니라 ToolBox가 묶는다
@dataclass(frozen=True)
class ExtraTool:
    spec: dict[str, Any]
    title: str
    run: Callable[["ToolBox", dict[str, Any]], ToolResult]

    @property
    def name(self) -> str:
        return self.spec["function"]["name"]

    # 도구 설명에 없는 인자(예: AI가 지어낸 case_id)는 쓰지도 기록하지도 않는다
    @property
    def arg_names(self) -> frozenset[str]:
        return frozenset(self.spec["function"]["parameters"]["properties"])


# 고객 이력 90일 (#581) — 기본 조회의 30일로 평소 결제를 알 수 없을 때만 넓혀 본다
# 기간은 AI가 정하지 않는다: 도구에 인자가 없고 90은 코드가 넣는다 (Spring도 30 · 90만 받음)
HISTORY_90_DAYS = 90


def _history_90(box: "ToolBox", args: dict[str, Any]) -> ToolResult:
    return box.get_payment_history(HISTORY_90_DAYS)


HISTORY_90 = ExtraTool(
    spec={
        "type": "function",
        "function": {
            "name": "get_payment_history_90d",
            "description": (
                "같은 고객의 최근 90일 결제(이 사건 결제 제외, 최근 순 최대 20건)와 "
                "상태별 건수를 본다. "
                "기본 조회의 고객 최근 결제(30일)에 이번 결제보다 앞선 결제가 없거나 적어서 "
                "이 고객의 평소 결제를 알 수 없을 때만 부른다. "
                "30일 결제로 평소 결제를 볼 수 있으면 부르지 않는다. 인자는 없다."
            ),
            "parameters": {"type": "object", "properties": {}},
        },
    },
    title="고객 이력 90일 조회",
    run=_history_90,
)

EXTRA_TOOLS: tuple[ExtraTool, ...] = (HISTORY_90,)


# 실제로 한 조회 기록 — 보고서의 사실 값과 증거 추적은 이 기록에서만 만든다
# filled_by_system: 코드가 한 기본 조회면 True, AI가 고른 추가 조회면 False (#578)
@dataclass(frozen=True)
class ToolRecord:
    tool_name: str
    args: dict[str, Any]
    result: ToolResult
    filled_by_system: bool = False


# AI에게 돌려줄 도구 응답 — 카드 표시값(카드사 · 끝자리 숫자)은 뺀다 (#346 리뷰)
# 보고서를 채우는 Tool 기록(ToolRecord)에는 원래 응답을 그대로 둔다
# 연관 결제(#352) 응답은 처음부터 건수 · 판정만 있어 뺄 것이 없다
# 주문 정보(#634)는 주문번호 · 상품명을 뺀다 — 가맹점이 쓴 글자라 지시문이 섞일 수 있다
# (프롬프트 주입 차단)
# 같은 주문인지는 orderGroup 번호로 안다
def llm_content(result: ToolResult) -> str:
    if isinstance(result, FdsEvidenceTool):
        return result.model_dump_json(
            exclude={"payments": {"__all__": {"signals": {"cardDisplayLabel"}}}}
        )
    if isinstance(result, OrderContextTool):
        return result.model_dump_json(exclude={"payments": {"__all__": {"orderRef", "orderName"}}})
    return result.model_dump_json()


# 한 Case로 묶인 도구 실행기 — 실제는 Spring, 테스트 · 샘플 실행은 고정 응답
class ToolBox(Protocol):
    def get_case_payments(self) -> CasePaymentsTool: ...

    def get_fds_evidence(self, payment_ids: list[str] | None) -> FdsEvidenceTool: ...

    def find_similar_cases(self) -> SimilarCasesTool: ...

    # lookback_days가 None이면 기본 조회(30일), 90이면 AI 추가 조사 (#581)
    def get_payment_history(self, lookback_days: int | None = None) -> PaymentHistoryTool: ...

    # nearby_hours가 None이면 Spring 기본(앞뒤 1시간, #637)
    # 6 · 24는 질문 · 답변의 넓혀 보기 (ADR-0086)
    def find_linked_payments(self, nearby_hours: int | None = None) -> LinkedPaymentsTool: ...

    # 사건 결제의 주문 정보 (#634) — 기본 조회
    def get_order_context(self) -> OrderContextTool: ...

    # 결제별 징후 · 정상 근거 (#618) — Spring이 계산한다. AI가 고르는 도구가 아니다
    def get_decision_basis(self) -> DecisionBasisTool: ...


class SpringToolBox:
    def __init__(self, client: SpringToolClient, case_id: str) -> None:
        self._client = client
        self._case_id = case_id

    def get_case_payments(self) -> CasePaymentsTool:
        return self._client.get_case_payments(self._case_id)

    def get_fds_evidence(self, payment_ids: list[str] | None) -> FdsEvidenceTool:
        return self._client.get_fds_evidence(self._case_id, payment_ids)

    def find_similar_cases(self) -> SimilarCasesTool:
        return self._client.find_similar_cases(self._case_id)

    def get_payment_history(self, lookback_days: int | None = None) -> PaymentHistoryTool:
        return self._client.get_payment_history(self._case_id, lookback_days)

    def find_linked_payments(self, nearby_hours: int | None = None) -> LinkedPaymentsTool:
        return self._client.find_linked_payments(self._case_id, nearby_hours)

    def get_order_context(self) -> OrderContextTool:
        return self._client.get_order_context(self._case_id)

    def get_decision_basis(self) -> DecisionBasisTool:
        return self._client.get_decision_basis(self._case_id)


# 미리 받아 둔 응답으로 도구를 흉내 낸다 — payment_ids를 주면 Spring처럼 그 결제만 돌려준다
class StaticToolBox:
    # similar · history · linked · order를 주지 않으면
    # 비슷한 사건 · 이력 · 연관 결제 · 주문 정보가 없는 응답을 돌려준다
    # history90을 주지 않으면 30일 이력과 같은 결제를 90일로 돌려준다 (#581)
    def __init__(
        self,
        case_payments: CasePaymentsTool,
        fds_evidence: FdsEvidenceTool,
        similar: SimilarCasesTool | None = None,
        history: PaymentHistoryTool | None = None,
        linked: LinkedPaymentsTool | None = None,
        history90: PaymentHistoryTool | None = None,
        basis: DecisionBasisTool | None = None,
        order: OrderContextTool | None = None,
    ) -> None:
        self._case_payments = case_payments
        self._fds_evidence = fds_evidence
        self._basis = basis
        self._similar = similar or SimilarCasesTool(
            caseId=case_payments.caseId,
            evidenceVersion=case_payments.evidenceVersion,
            source="P3·P2",
            retrievedAt=case_payments.retrievedAt,
            cases=[],
            totalMatched=0,
        )
        self._history = history or PaymentHistoryTool(
            caseId=case_payments.caseId,
            evidenceVersion=case_payments.evidenceVersion,
            source="P5",
            retrievedAt=case_payments.retrievedAt,
            lookbackDays=30,
            payments=[],
            statusCounts=[],
            truncated=False,
        )
        # 90일 기본값 — 샘플에 30일보다 오래된 결제가 없으면 30일 결과와 같은 결제다 (#581)
        self._history90 = history90 or self._history.model_copy(
            update={"lookbackDays": HISTORY_90_DAYS}
        )
        # 연관 결제 기본값 — 카드 · 기기 · IP 모두 "정보 없음"
        not_provided = LinkedMatch(
            provided=False, otherCustomerCount=0, paymentCount=0, resultCounts=[], declinedCount=0
        )
        self._linked = linked or LinkedPaymentsTool(
            caseId=case_payments.caseId,
            evidenceVersion=case_payments.evidenceVersion,
            source="P2",
            retrievedAt=case_payments.retrievedAt,
            lookbackDays=30,
            card=not_provided,
            device=not_provided,
            ip=not_provided,
        )
        # 주문 정보 기본값 — 결제마다 "주문 정보 없음" (결제 준비 세션이 없는 결제와 같다, #634)
        self._order = order or OrderContextTool(
            caseId=case_payments.caseId,
            evidenceVersion=case_payments.evidenceVersion,
            source="P5",
            retrievedAt=case_payments.retrievedAt,
            payments=[
                OrderPayment(paymentId=p.paymentId, provided=False) for p in case_payments.payments
            ],
        )

    # 징후 · 정상 근거 기본값 — 결제별 근거가 없는 응답 (보고서는 한계 줄로 알림, #618)
    # Spring이 계산하므로 여기서 계산하지 않는다. 샘플은 decisionBasis 키로 준다
    def _default_basis(self) -> DecisionBasisTool:
        return DecisionBasisTool(
            caseId=self._case_payments.caseId,
            evidenceVersion=self._case_payments.evidenceVersion,
            source="P4",
            retrievedAt=self._case_payments.retrievedAt,
            payments=[],
        )

    # 샘플 JSON 하나로 만든다 — similar · history · linked 키가 없으면 위의 "없음" 응답 (측정 샘플)
    # history90은 AI가 90일 도구를 골랐을 때 돌려줄 응답 (#581)
    @classmethod
    def from_sample(cls, data: dict[str, Any]) -> "StaticToolBox":
        return cls(
            CasePaymentsTool.model_validate(data["casePayments"]),
            FdsEvidenceTool.model_validate(data["fdsEvidence"]),
            similar=SimilarCasesTool.model_validate(data["similar"]) if "similar" in data else None,
            history=PaymentHistoryTool.model_validate(data["history"])
            if "history" in data
            else None,
            linked=LinkedPaymentsTool.model_validate(data["linked"]) if "linked" in data else None,
            history90=PaymentHistoryTool.model_validate(data["history90"])
            if "history90" in data
            else None,
            basis=DecisionBasisTool.model_validate(data["decisionBasis"])
            if "decisionBasis" in data
            else None,
            order=OrderContextTool.model_validate(data["order"]) if "order" in data else None,
        )

    def get_decision_basis(self) -> DecisionBasisTool:
        return self._basis or self._default_basis()

    # 시간 인자는 무시한다 — #751 전 Spring처럼 같은 응답을 준다
    def find_linked_payments(self, nearby_hours: int | None = None) -> LinkedPaymentsTool:
        return self._linked

    def get_order_context(self) -> OrderContextTool:
        return self._order

    def find_similar_cases(self) -> SimilarCasesTool:
        return self._similar

    def get_payment_history(self, lookback_days: int | None = None) -> PaymentHistoryTool:
        return self._history90 if lookback_days == HISTORY_90_DAYS else self._history

    def get_case_payments(self) -> CasePaymentsTool:
        return self._case_payments

    def get_fds_evidence(self, payment_ids: list[str] | None) -> FdsEvidenceTool:
        if not payment_ids:
            return self._fds_evidence
        wanted = set(payment_ids)
        linked = {payment.paymentId for payment in self._case_payments.payments}
        if not wanted <= linked:
            # Spring Tool API의 400 INV_006과 같다
            raise ValueError("이 Case에 속한 결제가 아닙니다")
        return self._fds_evidence.model_copy(
            update={
                "payments": [p for p in self._fds_evidence.payments if p.paymentId in wanted],
                "missingPaymentIds": [
                    p for p in self._fds_evidence.missingPaymentIds if p in wanted
                ],
            }
        )
