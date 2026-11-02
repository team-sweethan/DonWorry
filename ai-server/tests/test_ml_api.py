import json
from collections.abc import Iterator
from pathlib import Path
from typing import Any

import pytest
from fastapi.testclient import TestClient

from app.config import settings
from app.controller.investigation_controller import get_api_key
from app.controller.ml_controller import get_fraud_score_service
from app.main import app
from app.schemas.ml import MAX_HISTORY_ROWS
from app.service.fraud_score_service import build_fraud_score_service

ROOT = Path(__file__).parents[1]
KEY = "test-key"
HEADERS = {"X-Internal-Api-Key": KEY}
SERVICE = build_fraud_score_service(ROOT / "models/rf-v1.joblib")
GOLDEN = json.loads((ROOT / "tests/fixtures/ml_golden.json").read_text(encoding="utf-8"))


@pytest.fixture
def client() -> Iterator[TestClient]:
    app.dependency_overrides[get_fraud_score_service] = lambda: SERVICE
    app.dependency_overrides[get_api_key] = lambda: KEY
    yield TestClient(app)
    app.dependency_overrides.clear()


def tx(payment_id: int, at: str, customer: str = "1:42", card: str | None = "card-a") -> dict:
    return {
        "paymentId": payment_id,
        "merchantId": 1001,
        "customerRef": customer,
        "amount": 30000,
        "paymentRequestedAt": at,
        "deviceId": "device-a",
        "cardToken": card,
    }


def body(target: dict, history: list[dict] | None = None, shared: list[dict] | None = None) -> dict:
    return {
        "evaluationId": 7,
        "target": target,
        "customerHistory": history or [],
        "sharedCardHistory": shared or [],
    }


def features(client: TestClient, request: dict[str, Any]) -> dict[str, Any]:
    res = client.post("/ml/fraud-score", json=request, headers=HEADERS)
    assert res.status_code == 200, res.text
    return res.json()["features"]


# 결제 이후 거래가 섞이면 미래 정보로 판단한다 (데이터 누출)
def test_score_history_after_target_rejected_422(client: TestClient) -> None:
    target = tx(2, "2026-11-02T05:00:00Z")
    later = tx(1, "2026-11-02T05:00:01Z")

    res = client.post("/ml/fraud-score", json=body(target, [later]), headers=HEADERS)

    assert res.status_code == 422


# 첫 결제 고객도 500 없이 학습 때와 같은 기본값으로 계산한다
def test_score_new_customer_no_history_returns_defaults(client: TestClient) -> None:
    result = features(client, body(tx(1, "2026-11-02T05:00:00Z")))

    assert result["customerPastTxCount"] == 0
    assert result["customerAvgAmount"] == 0.0
    assert result["amountRatio"] == 0.0
    assert result["minutesSinceLastTx"] == -1.0
    assert result["txCount5m"] == result["txCount30m"] == result["txCount24h"] == 0
    assert result["deviceSeenBefore"] == result["cardSeenBefore"] == 0
    assert result["cardUsedByOtherCustomer"] == 0


# 키 없이 열리면 결제 정보를 아무나 보낼 수 있다
def test_score_without_service_key_401(client: TestClient) -> None:
    request = body(tx(1, "2026-11-02T05:00:00Z"))

    no_key = client.post("/ml/fraud-score", json=request)
    wrong_key = client.post("/ml/fraud-score", json=request, headers={"X-Internal-Api-Key": "x"})

    assert no_key.status_code == 401
    assert wrong_key.status_code == 401


# 모델 파일 문제로 서버 전체가 죽으면 AI 조사까지 멈춘다 — 서버는 뜨고 ML API만 503
def test_score_model_not_loaded_503_investigations_still_work(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    monkeypatch.setattr(settings, "ml_model_path", tmp_path / "missing.joblib")
    get_fraud_score_service.cache_clear()
    app.dependency_overrides[get_api_key] = lambda: KEY
    try:
        with TestClient(app) as started:  # lifespan(모델 로딩)까지 실행
            health = started.get("/health")
            res = started.post(
                "/ml/fraud-score", json=body(tx(1, "2026-11-02T05:00:00Z")), headers=HEADERS
            )
    finally:
        get_fraud_score_service.cache_clear()
        app.dependency_overrides.clear()

    assert health.status_code == 200
    assert res.status_code == 503
    assert res.json()["detail"] == "ML_MODEL_UNAVAILABLE"


# 응답 필드가 바뀌면 Spring이 결과를 읽지 못한다 (FastAPI 계약)
def test_score_valid_request_returns_contract_fields(client: TestClient) -> None:
    case = next(c for c in GOLDEN["cases"] if c["name"] == "fraud_CARD_TESTING")
    request = body(case["target"], case["customerHistory"], case["sharedCardHistory"])

    res = client.post("/ml/fraud-score", json=request, headers=HEADERS)

    assert res.status_code == 200
    data = res.json()
    assert set(data) == {
        "evaluationId",
        "modelVersion",
        "fraudProbability",
        "threshold",
        "mlRiskLevel",
        "features",
        "latencyMs",
    }
    assert data["evaluationId"] == 7
    assert data["modelVersion"] == "rf-v1"
    assert data["fraudProbability"] == pytest.approx(case["expectedProbability"], abs=1e-9)
    assert data["mlRiskLevel"] == "HIGH"
    assert set(data["features"]) == set(GOLDEN["featureColumns"])
    assert data["latencyMs"] >= 0


# 같은 시각 거래가 이력으로 잡히면 txCount가 부풀어 오른다
def test_score_same_timestamp_history_not_counted(client: TestClient) -> None:
    target = tx(3, "2026-11-02T05:00:00Z")
    earlier = tx(1, "2026-11-02T04:57:00Z")
    same_time = tx(2, "2026-11-02T05:00:00Z")

    result = features(client, body(target, [earlier, same_time]))

    assert result["customerPastTxCount"] == 1
    assert result["txCount5m"] == 1


# 같은 카드를 쓴 다른 고객 이력이 빠지면 카드 도용 신호를 놓친다
def test_score_other_customer_same_card_sets_card_used_by_other_customer(
    client: TestClient,
) -> None:
    target = tx(2, "2026-11-02T05:00:00Z")
    other = tx(1, "2026-11-02T04:00:00Z", customer="1:99")

    shared = features(client, body(target, shared=[other]))
    alone = features(client, body(target))

    assert shared["cardUsedByOtherCustomer"] == 1
    assert alone["cardUsedByOtherCustomer"] == 0


# 이력을 무제한 받으면 1코어 VM에서 메모리 · 지연이 폭증한다
def test_score_history_over_limit_rejected_422(client: TestClient) -> None:
    target = tx(1, "2026-11-02T05:00:00Z")
    history = [tx(i + 2, "2026-11-01T05:00:00Z") for i in range(MAX_HISTORY_ROWS + 1)]

    res = client.post("/ml/fraud-score", json=body(target, history), headers=HEADERS)

    assert res.status_code == 422


# 재평가 행이 겹쳐 오면 같은 결제가 두 번 세져 거래 횟수가 부풀어 오른다
def test_score_duplicate_payment_id_rejected_422(client: TestClient) -> None:
    target = tx(2, "2026-11-02T05:00:00Z")
    first = tx(1, "2026-11-02T04:00:00Z")
    reevaluated = tx(1, "2026-11-02T04:00:00Z")

    res = client.post("/ml/fraud-score", json=body(target, [first, reevaluated]), headers=HEADERS)

    assert res.status_code == 422


# 다른 고객 거래가 내 이력으로 섞이면 정상 고객이 의심받는다
def test_score_customer_history_other_customer_rejected_422(client: TestClient) -> None:
    target = tx(2, "2026-11-02T05:00:00Z")
    other = tx(1, "2026-11-02T04:00:00Z", customer="1:99")

    res = client.post("/ml/fraud-score", json=body(target, [other]), headers=HEADERS)

    assert res.status_code == 422


# 카드가 다른 거래나 내 거래가 "카드 공유"로 잡히면 도용 신호가 잘못 켜진다
def test_score_shared_card_history_mismatch_rejected_422(client: TestClient) -> None:
    target = tx(3, "2026-11-02T05:00:00Z")
    other_card = tx(1, "2026-11-02T04:00:00Z", customer="1:99", card="card-b")
    same_customer = tx(2, "2026-11-02T04:00:00Z")

    wrong_card = client.post(
        "/ml/fraud-score", json=body(target, shared=[other_card]), headers=HEADERS
    )
    mine = client.post(
        "/ml/fraud-score", json=body(target, shared=[same_customer]), headers=HEADERS
    )

    assert wrong_card.status_code == 422
    assert mine.status_code == 422
