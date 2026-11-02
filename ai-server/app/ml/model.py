from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import joblib

from app.ml.features import FEATURE_COLUMNS, build_features

# 학습 코드(fds-ml/train_random_forest.py)가 저장한 dict에서 추론에 쓰는 키
REQUIRED_KEYS = ("pipeline", "threshold", "feature_columns")


# features.py는 customerRef로만 고객을 묶어 가맹점 번호를 붙여 넘긴다 (가맹점이 다르면 다른 고객)
# 학습 데이터는 고객마다 가맹점이 하나라 이 키로 계산해도 Feature 값이 같다 (golden 테스트로 확인)
def customer_keyed(row: Mapping[str, object]) -> dict[str, object]:
    return {**row, "customerRef": f"{row['merchantId']}:{row['customerRef']}"}


# 모델 파일이 없거나 모양이 다름 — 서버는 뜨고 ML API만 503으로 답한다
class ModelLoadError(Exception):
    pass


@dataclass(frozen=True)
class FraudModel:
    version: str
    threshold: float
    feature_columns: tuple[str, ...]
    pipeline: Any

    # 이력 + 대상 거래로 features.py를 그대로 돌리고 대상 줄만 꺼낸다 (학습 때와 같은 계산)
    def features_for(
        self, target: Mapping[str, object], history: Sequence[Mapping[str, object]]
    ) -> dict[str, int | float | str]:
        rows = build_features([customer_keyed(row) for row in [*history, target]])
        return {name: rows[-1][name] for name in self.feature_columns}

    def probability(self, features: Mapping[str, int | float | str]) -> float:
        return float(self.pipeline.predict_proba([dict(features)])[0][1])


# 읽기 · 검사 · 설정 중 어떤 오류든 ModelLoadError로 바꾼다 — 서버는 뜨고 ML API만 503
def load_model(path: Path, version: str) -> FraudModel:
    try:
        return _load_model(path, version)
    except ModelLoadError:
        raise
    except Exception as exc:
        raise ModelLoadError(f"모델 파일 내용을 쓸 수 없습니다: {path.name}") from exc


# 저장소에 포함한 신뢰하는 로컬 파일만 읽는다 — joblib은 읽을 때 코드를 실행할 수 있다
def _load_model(path: Path, version: str) -> FraudModel:
    try:
        bundle = joblib.load(path)
    except Exception as exc:
        raise ModelLoadError(f"모델 파일을 읽지 못했습니다: {path.name}") from exc

    if not isinstance(bundle, dict) or any(key not in bundle for key in REQUIRED_KEYS):
        raise ModelLoadError(f"모델 파일 형식이 다릅니다: {path.name}")

    threshold = float(bundle["threshold"])
    if not 0 < threshold < 1:
        raise ModelLoadError(f"threshold가 0과 1 사이가 아닙니다: {threshold}")

    columns = tuple(bundle["feature_columns"])
    unknown = set(columns) - set(FEATURE_COLUMNS)
    if unknown:
        raise ModelLoadError(f"features.py에 없는 Feature입니다: {sorted(unknown)}")

    pipeline = bundle["pipeline"]
    if not hasattr(pipeline, "predict_proba"):
        raise ModelLoadError("predict_proba가 없는 모델입니다")

    # 1건 예측은 병렬이 더 느리고(70ms vs 20ms) 실행마다 미세 오차가 생겨 1로 고정한다
    estimator = pipeline[-1]
    if "n_jobs" in estimator.get_params():
        estimator.set_params(n_jobs=1)

    return FraudModel(version, threshold, columns, pipeline)
