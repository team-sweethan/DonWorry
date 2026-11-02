import logging
import time
from pathlib import Path

from app.ml.model import FraudModel, ModelLoadError, load_model
from app.schemas.ml import FraudScoreRequest, FraudScoreResponse

log = logging.getLogger(__name__)


class ModelUnavailableError(Exception):
    pass


class InferenceError(Exception):
    pass


# RF 사기 확률 계산 — 결제 판정은 하지 않고 확률 · 근거만 돌려준다
class FraudScoreService:
    def __init__(self, model: FraudModel | None) -> None:
        self._model = model

    def score(self, request: FraudScoreRequest) -> FraudScoreResponse:
        model = self._model
        if model is None:
            raise ModelUnavailableError
        started = time.perf_counter()
        history = [row.model_dump() for row in request.customerHistory + request.sharedCardHistory]
        try:
            features = model.features_for(request.target.model_dump(), history)
            probability = model.probability(features)
        except Exception as exc:
            # 카드 · 기기 값이 로그에 남지 않게 평가 ID만 적는다
            log.exception("ML 추론 실패 evaluationId=%s", request.evaluationId)
            raise InferenceError from exc
        return FraudScoreResponse(
            evaluationId=request.evaluationId,
            modelVersion=model.version,
            fraudProbability=probability,
            threshold=model.threshold,
            mlRiskLevel="HIGH" if probability >= model.threshold else "LOW",
            features=features,
            latencyMs=round((time.perf_counter() - started) * 1000),
        )


# 모델을 못 읽어도 예외를 던지지 않는다 — 서버(AI 조사)는 계속 뜨고 ML API만 503
def build_fraud_score_service(path: Path) -> FraudScoreService:
    try:
        model = load_model(path, path.stem)
    except ModelLoadError:
        log.exception("RF 모델을 읽지 못해 ML API를 끕니다 path=%s", path.name)
        return FraudScoreService(None)
    log.info("RF 모델 로딩 완료 version=%s threshold=%s", model.version, model.threshold)
    return FraudScoreService(model)
