import json
from pathlib import Path

import joblib
import pytest

from app.ml.model import FraudModel, ModelLoadError, load_model

ROOT = Path(__file__).parents[1]
GOLDEN = json.loads((ROOT / "tests/fixtures/ml_golden.json").read_text(encoding="utf-8"))
CASES = GOLDEN["cases"]


@pytest.fixture(scope="module")
def model() -> FraudModel:
    return load_model(ROOT / "models/rf-v1.joblib", "rf-v1")


# 학습 · 운영 Feature가 달라지면 모델이 엉뚱한 확률을 낸다
# fds-ml이 2만 건 전체로 계산한 값 = ai-server가 대상 + 이전 이력만으로 계산한 값
@pytest.mark.parametrize("case", CASES, ids=lambda c: c["name"])
def test_features_golden_rows_match_fds_ml(model: FraudModel, case: dict) -> None:
    history = case["customerHistory"] + case["sharedCardHistory"]

    features = model.features_for(case["target"], history)

    assert features == pytest.approx(case["expectedFeatures"], rel=1e-12)


# 같은 거래인데 확률이 학습 환경과 다르면 Shadow 기록을 믿을 수 없다 (Phase 3 완료 조건)
@pytest.mark.parametrize("case", CASES, ids=lambda c: c["name"])
def test_score_golden_rows_match_fds_ml_probability(model: FraudModel, case: dict) -> None:
    history = case["customerHistory"] + case["sharedCardHistory"]

    probability = model.probability(model.features_for(case["target"], history))

    assert probability == pytest.approx(case["expectedProbability"], abs=1e-9)
    assert model.threshold == pytest.approx(GOLDEN["threshold"])


# predict_proba는 있지만 pipeline[-1]로 마지막 단계를 꺼낼 수 없는 객체
class NotIndexablePipeline:
    def predict_proba(self, rows: list) -> list:
        return [[1.0, 0.0] for _ in rows]


# 교체한 모델 내용이 잘못되면 서버 시작이 멈춰 AI 조사까지 못 쓴다 — ModelLoadError로 바꿔 503
@pytest.mark.parametrize(
    "override",
    [
        {"threshold": None},
        {"threshold": "abc"},
        {"feature_columns": None},
        {"pipeline": NotIndexablePipeline()},
    ],
    ids=["threshold_none", "threshold_text", "columns_none", "pipeline_not_indexable"],
)
def test_load_model_malformed_bundle_raises_model_load_error(
    tmp_path: Path, override: dict
) -> None:
    bundle = {"pipeline": NotIndexablePipeline(), "threshold": 0.3, "feature_columns": ["amount"]}
    path = tmp_path / "rf-broken.joblib"
    joblib.dump({**bundle, **override}, path)

    with pytest.raises(ModelLoadError):
        load_model(path, "rf-broken")
