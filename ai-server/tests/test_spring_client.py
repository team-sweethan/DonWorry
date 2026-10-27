import json

import httpx

from app.client.spring import API_KEY_HEADER, SpringToolClient

FIXTURE = json.loads(open("tests/fixtures/sample_case.json", encoding="utf-8").read())


def _client(seen: list[httpx.Request], api_key: str = "secret") -> SpringToolClient:
    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        if request.url.path.endswith("get_case_payments"):
            return httpx.Response(200, json=FIXTURE["casePayments"])
        return httpx.Response(200, json=FIXTURE["fdsEvidence"])

    return SpringToolClient("http://spring", api_key, transport=httpx.MockTransport(handler))


# 읽기 전용 GET 경로 · Case ID · 서비스 키 헤더를 실어 보낸다
def test_get_case_payments_sends_case_id_and_key() -> None:
    seen: list[httpx.Request] = []

    result = _client(seen).get_case_payments("101")

    assert result.payments[0].paymentId == "9001"
    assert seen[0].method == "GET"
    assert seen[0].url.path == "/internal/ai/tools/get_case_payments"
    assert seen[0].url.params["caseId"] == "101"
    assert seen[0].headers[API_KEY_HEADER] == "secret"


def test_get_fds_evidence_sends_payment_ids() -> None:
    seen: list[httpx.Request] = []

    _client(seen).get_fds_evidence("101", ["9001", "9002"])

    assert seen[0].url.params.get_list("paymentIds") == ["9001", "9002"]


def test_no_key_header_when_key_is_empty() -> None:
    seen: list[httpx.Request] = []

    _client(seen, api_key="").get_case_payments("101")

    assert API_KEY_HEADER not in seen[0].headers
