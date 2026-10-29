from dataclasses import dataclass
from typing import Any, Protocol

from app.client.spring import SpringToolClient
from app.schemas.tools import (
    CasePaymentsTool,
    FdsEvidenceTool,
    PaymentHistoryTool,
    SimilarCasesTool,
)

# Qwen에게 보여 주는 허용 도구 — 읽기 전용만 둔다
# (AGENTS.md 6절, ADR-0009 Tool 6종 중 1차 2종 + #296 비슷한 과거 사건 + #338 고객 최근 결제)
# caseId는 Qwen이 고르지 않고 코드가 묶는다 — 다른 Case를 조회할 수 없게
TOOL_SPECS: list[dict[str, Any]] = [
    {
        "type": "function",
        "function": {
            "name": "get_case_payments",
            "description": (
                "이 사건에 묶인 결제 목록(결제 ID · 금액 · 결제 상태 · 요청 시각)을 본다."
            ),
            "parameters": {"type": "object", "properties": {}, "required": []},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_fds_evidence",
            "description": (
                "결제별 FDS 위험 점수 · 판정 · 적중 규칙을 본다. "
                "payment_ids를 주면 그 결제만, 비우면 사건의 모든 결제를 본다."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "payment_ids": {
                        "type": "array",
                        "items": {"type": "string"},
                        "description": "자세히 볼 결제 ID 목록",
                    }
                },
                "required": [],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "find_similar_cases",
            "description": (
                "이 사건과 비슷한 지난 종료 사건(같은 고객 · 같은 규칙 조합)과 "
                "결제별 직원 판정 결과를 본다. 참고 근거다."
            ),
            "parameters": {"type": "object", "properties": {}, "required": []},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_payment_history",
            "description": (
                "이 사건 고객의 최근 30일 결제 중 이 사건 밖의 결제와 결제 상태별 건수를 본다. "
                "평소 결제와 비교하는 참고 근거다."
            ),
            "parameters": {"type": "object", "properties": {}, "required": []},
        },
    },
]

ALLOWED_TOOL_NAMES = frozenset(spec["function"]["name"] for spec in TOOL_SPECS)

# 도구별로 받는 인자 이름 — 이 밖의 인자는 버린다
TOOL_ARGS: dict[str, frozenset[str]] = {
    spec["function"]["name"]: frozenset(spec["function"]["parameters"]["properties"])
    for spec in TOOL_SPECS
}


# Agent가 실제로 부른 도구 기록 — 보고서의 사실 값과 증거 추적은 이 기록에서만 만든다
@dataclass(frozen=True)
class ToolRecord:
    tool_name: str
    args: dict[str, Any]
    result: CasePaymentsTool | FdsEvidenceTool | SimilarCasesTool | PaymentHistoryTool
    filled_by_system: bool = False


# 한 Case로 묶인 도구 실행기 — 실제는 Spring, 테스트 · 샘플 실행은 고정 응답
class ToolBox(Protocol):
    def get_case_payments(self) -> CasePaymentsTool: ...

    def get_fds_evidence(self, payment_ids: list[str] | None) -> FdsEvidenceTool: ...

    def find_similar_cases(self) -> SimilarCasesTool: ...

    def get_payment_history(self) -> PaymentHistoryTool: ...


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


# 미리 받아 둔 응답으로 도구를 흉내 낸다 — payment_ids를 주면 Spring처럼 그 결제만 돌려준다
class StaticToolBox:
    # similar · history를 주지 않으면 비슷한 사건 · 이력이 없는 응답을 돌려준다
    def __init__(
        self,
        case_payments: CasePaymentsTool,
        fds_evidence: FdsEvidenceTool,
        similar: SimilarCasesTool | None = None,
        history: PaymentHistoryTool | None = None,
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
