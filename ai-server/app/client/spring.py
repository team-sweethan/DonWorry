import httpx

from app.schemas.tools import CasePaymentsTool, FdsEvidenceTool

# 서비스 키 헤더 이름 — Spring 쪽 확인(#253)과 같게 둔다
API_KEY_HEADER = "X-Internal-Api-Key"


# Spring 내부 Tool API 읽기 전용 호출 — 결제 · 판정을 바꾸는 호출은 없다 (AGENTS.md 6절)
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

    def close(self) -> None:
        self._http.close()
