from functools import lru_cache
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, status

from app.config import settings
from app.controller.investigation_controller import require_service_key
from app.schemas.ml import FraudScoreRequest, FraudScoreResponse
from app.service.fraud_score_service import (
    FraudScoreService,
    InferenceError,
    ModelUnavailableError,
    build_fraud_score_service,
)

# Spring 전용 ML Shadow 평가 API — 조사 API와 같은 서비스 키를 쓴다
router = APIRouter(prefix="/ml", tags=["ml"])


# 서버 시작 시(main.py lifespan) 한 번 만들어 모델을 한 번만 읽는다
@lru_cache
def get_fraud_score_service() -> FraudScoreService:
    return build_fraud_score_service(settings.ml_model_path)


Service = Annotated[FraudScoreService, Depends(get_fraud_score_service)]


@router.post("/fraud-score", dependencies=[Depends(require_service_key)])
def score(request: FraudScoreRequest, service: Service) -> FraudScoreResponse:
    try:
        return service.score(request)
    except ModelUnavailableError as exc:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "ML_MODEL_UNAVAILABLE") from exc
    except InferenceError as exc:
        raise HTTPException(status.HTTP_500_INTERNAL_SERVER_ERROR, "ML_INFERENCE_FAILED") from exc
