from collections.abc import Callable
from dataclasses import dataclass
from typing import Any, Protocol

from app.client.spring import SpringToolClient
from app.schemas.tools import (
    CasePaymentsTool,
    FdsEvidenceTool,
    LinkedMatch,
    LinkedPaymentsTool,
    PaymentHistoryTool,
    SimilarCasesTool,
    ToolResult,
)


# 추가 조사 도구 — 기본 조회 5개(코드)를 본 AI가 더 볼 때만 고른다 (#578)
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


# 아직 없다 — 첫 도구는 고객 이력 90일 (PLAN_AI_REPORT T3)
EXTRA_TOOLS: tuple[ExtraTool, ...] = ()


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
def llm_content(result: ToolResult) -> str:
    if isinstance(result, FdsEvidenceTool):
        return result.model_dump_json(
            exclude={"payments": {"__all__": {"signals": {"cardDisplayLabel"}}}}
        )
    return result.model_dump_json()


# 한 Case로 묶인 도구 실행기 — 실제는 Spring, 테스트 · 샘플 실행은 고정 응답
class ToolBox(Protocol):
    def get_case_payments(self) -> CasePaymentsTool: ...

    def get_fds_evidence(self, payment_ids: list[str] | None) -> FdsEvidenceTool: ...

    def find_similar_cases(self) -> SimilarCasesTool: ...

    def get_payment_history(self) -> PaymentHistoryTool: ...

    def find_linked_payments(self) -> LinkedPaymentsTool: ...


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

    def get_payment_history(self) -> PaymentHistoryTool:
        return self._client.get_payment_history(self._case_id)

    def find_linked_payments(self) -> LinkedPaymentsTool:
        return self._client.find_linked_payments(self._case_id)


# 미리 받아 둔 응답으로 도구를 흉내 낸다 — payment_ids를 주면 Spring처럼 그 결제만 돌려준다
class StaticToolBox:
    # similar · history · linked를 주지 않으면 비슷한 사건 · 이력 · 연관 결제가 없는 응답을 돌려준다
    def __init__(
        self,
        case_payments: CasePaymentsTool,
        fds_evidence: FdsEvidenceTool,
        similar: SimilarCasesTool | None = None,
        history: PaymentHistoryTool | None = None,
        linked: LinkedPaymentsTool | None = None,
    ) -> None:
        self._case_payments = case_payments
        self._fds_evidence = fds_evidence
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

    # 샘플 JSON 하나로 만든다 — similar · history · linked 키가 없으면 위의 "없음" 응답 (측정 샘플)
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
        )

    def find_linked_payments(self) -> LinkedPaymentsTool:
        return self._linked

    def find_similar_cases(self) -> SimilarCasesTool:
        return self._similar

    def get_payment_history(self) -> PaymentHistoryTool:
        return self._history

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
