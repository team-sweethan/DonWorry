import json

import httpx

from app.agent.tools import HISTORY_90, SpringToolBox
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


# 기본 조회는 기간을 보내지 않고(Spring 기본 30일), 90일 도구만 lookbackDays=90을 보낸다 (#581)
# AI가 지어낸 인자(days=365)는 Spring까지 가지 않는다
def test_get_payment_history_sends_lookback_days_only_for_90_tool() -> None:
    seen: list[httpx.Request] = []
    history = json.loads(open("tests/fixtures/measure_case5_new.json", encoding="utf-8").read())[
        "history"
    ]

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(200, json=history)

    client = SpringToolClient("http://spring", "secret", transport=httpx.MockTransport(handler))
    box = SpringToolBox(client, "205")

    box.get_payment_history()
    HISTORY_90.run(box, {"days": 365})

    assert [r.url.path for r in seen] == ["/internal/ai/tools/get_payment_history"] * 2
    assert dict(seen[0].url.params) == {"caseId": "205"}
    assert dict(seen[1].url.params) == {"caseId": "205", "lookbackDays": "90"}


# 징후 · 정상 근거 (#618) — Spring DecisionBasisToolResponse 모양(null 칸 포함)을 그대로 읽는다
# 기준표 칸 · 권고가 없는 새 응답(#644)을 읽어야 한다 — 못 읽으면 결제마다 징후 칸이 빈다
def test_springClient_decisionBasis_parsesWithoutRuleAndRecommendation() -> None:
    seen: list[httpx.Request] = []
    spring = {
        "caseId": "101",
        "evidenceVersion": 2,
        "source": "P4",
        "retrievedAt": "2026-11-07T12:00:00.123456Z",
        "payments": [
            {
                "paymentId": "9001",
                "basis": {
                    "signals": [
                        {
                            "key": "R01",
                            "label": "고액 결제",
                            "detail": None,
                            "level": "CAUTION",
                            "normalReason": None,
                            "countedIn": None,
                            "checkBefore": "고객의 평소 결제 금액과 비교",
                        }
                    ],
                    "normals": [],
                },
            }
        ],
    }

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(200, json=spring)

    client = SpringToolClient("http://spring", "secret", transport=httpx.MockTransport(handler))

    result = client.get_decision_basis("101")

    assert seen[0].method == "GET"
    assert seen[0].url.path == "/internal/ai/tools/get_decision_basis"
    assert seen[0].url.params["caseId"] == "101"
    basis = result.for_payment("9001")
    assert basis is not None
    assert list(basis.model_dump()) == ["signals", "normals"]
    assert basis.signals[0].checkBefore == "고객의 평소 결제 금액과 비교"
    assert result.for_payment("9002") is None
