from dataclasses import dataclass
from typing import Any, Protocol

from app.client.spring import SpringToolClient
from app.schemas.tools import CasePaymentsTool, FdsEvidenceTool

# Qwen에게 보여 주는 허용 도구 — 읽기 전용만 둔다
# (AGENTS.md 6절, ADR-0009 Tool 6종 중 1차 2종)
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
]

ALLOWED_TOOL_NAMES = frozenset(spec["function"]["name"] for spec in TOOL_SPECS)


# Agent가 실제로 부른 도구 기록 — 보고서의 사실 값과 증거 추적은 이 기록에서만 만든다
@dataclass(frozen=True)
class ToolRecord:
    tool_name: str
    args: dict[str, Any]
    result: CasePaymentsTool | FdsEvidenceTool
    filled_by_system: bool = False


# 한 Case로 묶인 도구 실행기 — 실제는 Spring, 테스트 · 샘플 실행은 고정 응답
class ToolBox(Protocol):
    def get_case_payments(self) -> CasePaymentsTool: ...

    def get_fds_evidence(self, payment_ids: list[str] | None) -> FdsEvidenceTool: ...


class SpringToolBox:
    def __init__(self, client: SpringToolClient, case_id: str) -> None:
        self._client = client
        self._case_id = case_id

    def get_case_payments(self) -> CasePaymentsTool:
        return self._client.get_case_payments(self._case_id)

    def get_fds_evidence(self, payment_ids: list[str] | None) -> FdsEvidenceTool:
        return self._client.get_fds_evidence(self._case_id, payment_ids)


# 미리 받아 둔 응답으로 도구를 흉내 낸다 — payment_ids를 주면 Spring처럼 그 결제만 돌려준다
class StaticToolBox:
    def __init__(self, case_payments: CasePaymentsTool, fds_evidence: FdsEvidenceTool) -> None:
        self._case_payments = case_payments
        self._fds_evidence = fds_evidence

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
