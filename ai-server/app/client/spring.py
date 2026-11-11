import httpx

from app.schemas.investigation import InvestigationResult
from app.schemas.tools import (
    CasePaymentsTool,
    DecisionBasisTool,
    FdsEvidenceTool,
    LinkedPaymentsTool,
    OrderContextTool,
    PaymentHistoryTool,
    SimilarCasesTool,
)

# 서비스 키 헤더 이름 — Spring 쪽 확인(#253)과 같게 둔다
API_KEY_HEADER = "X-Internal-Api-Key"


# Spring 내부 API 호출 — Tool 읽기와 조사 결과 전송만 한다
# 결제 · 판정을 바꾸는 호출은 없다 (AGENTS.md 6절)
class SpringToolClient:
    def __init__(
        self,
        base_url: str,
        api_key: str = "",
        timeout_seconds: float = 10.0,
        transport: httpx.BaseTransport | None = None,
    ) -> None:
        headers = {API_KEY_HEADER: api_key} if api_key else {}
        self._http = httpx.Client(
            base_url=base_url, headers=headers, timeout=timeout_seconds, transport=transport
        )

    def get_case_payments(self, case_id: str) -> CasePaymentsTool:
        res = self._http.get("/internal/ai/tools/get_case_payments", params={"caseId": case_id})
        res.raise_for_status()
        return CasePaymentsTool.model_validate(res.json())

    # payment_ids를 비우면 Case에 묶인 결제 전체를 본다
    def get_fds_evidence(
        self, case_id: str, payment_ids: list[str] | None = None
    ) -> FdsEvidenceTool:
        params: list[tuple[str, str]] = [("caseId", case_id)]
        params += [("paymentIds", payment_id) for payment_id in payment_ids or []]
        res = self._http.get("/internal/ai/tools/get_fds_evidence", params=params)
        res.raise_for_status()
        return FdsEvidenceTool.model_validate(res.json())

    # 이 Case와 비슷한 종료 사건 (#296)
    def find_similar_cases(self, case_id: str) -> SimilarCasesTool:
        res = self._http.get("/internal/ai/tools/find_similar_cases", params={"caseId": case_id})
        res.raise_for_status()
        return SimilarCasesTool.model_validate(res.json())

    # 같은 가맹점 · 같은 고객의 최근 결제, 이 사건 결제 제외 (#338)
    # 기간은 기본 조회면 보내지 않고(Spring 기본 30일), AI 추가 조사의 90일만 보낸다 (#581)
    def get_payment_history(
        self, case_id: str, lookback_days: int | None = None
    ) -> PaymentHistoryTool:
        params: dict[str, str | int] = {"caseId": case_id}
        if lookback_days is not None:
            params["lookbackDays"] = lookback_days
        res = self._http.get("/internal/ai/tools/get_payment_history", params=params)
        res.raise_for_status()
        return PaymentHistoryTool.model_validate(res.json())

    # 같은 가맹점 · 최근 30일에 같은 카드 · 기기 · IP를 쓴 다른 고객 결제 집계 (#352)
    # nearby_hours: 사건 결제 앞뒤 몇 시간을 따로 셀지 (#751, 1 · 6 · 24)
    # 보고서 기본 조회는 보내지 않는다 (Spring 기본 1시간)
    # 받은 범위는 응답 nearbyWindowMinutes로 확인한다 — #751 전 Spring은 이 인자를 무시한다
    def find_linked_payments(
        self, case_id: str, nearby_hours: int | None = None
    ) -> LinkedPaymentsTool:
        params: dict[str, str | int] = {"caseId": case_id}
        if nearby_hours is not None:
            params["nearbyHours"] = nearby_hours
        res = self._http.get("/internal/ai/tools/find_linked_payments", params=params)
        res.raise_for_status()
        return LinkedPaymentsTool.model_validate(res.json())

    # 사건 결제마다 가맹점이 결제 준비 때 보낸 주문 정보 (#634) — 주문자 원문 없이 묶음 번호만
    def get_order_context(self, case_id: str) -> OrderContextTool:
        res = self._http.get("/internal/ai/tools/get_order_context", params={"caseId": case_id})
        res.raise_for_status()
        return OrderContextTool.model_validate(res.json())

    # 결제별 징후 · 정상 근거 (#618) — Spring이 계산한 값을 그대로 받는다
    def get_decision_basis(self, case_id: str) -> DecisionBasisTool:
        res = self._http.get("/internal/ai/tools/get_decision_basis", params={"caseId": case_id})
        res.raise_for_status()
        return DecisionBasisTool.model_validate(res.json())

    # 조사가 끝나면 결과를 돌려준다 (ADR-0009 결정 1 결과 수신)
    # 전송이 실패해도 Spring이 상태 조회로 복구한다
    def send_result(self, result: InvestigationResult) -> None:
        res = self._http.post(
            f"/internal/ai/reports/{result.reportId}/result",
            json=result.model_dump(mode="json"),
        )
        res.raise_for_status()

    def close(self) -> None:
        self._http.close()
