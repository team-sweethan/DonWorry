import hmac
from concurrent.futures import ThreadPoolExecutor
from functools import lru_cache
from typing import Annotated

from fastapi import APIRouter, Depends, Header, HTTPException, status

from app.client.spring import API_KEY_HEADER, SpringToolClient
from app.config import settings
from app.schemas.investigation import InvestigationRequest, InvestigationResult
from app.service.investigation_service import InvestigationService
from app.service.report_generator import build_report_generator

# Spring 전용 조사 API (ADR-0009 결정 1) — controller는 service만 부른다 (AGENTS.md 6절)
router = APIRouter(prefix="/investigations", tags=["investigations"])


@lru_cache
def get_service() -> InvestigationService:
    client = SpringToolClient(
        settings.spring_base_url, settings.internal_api_key, settings.http_timeout_seconds
    )
    return InvestigationService(
        build_report_generator(settings),
        client,
        ThreadPoolExecutor(max_workers=settings.max_concurrent_investigations),
        settings.max_kept_results,
    )


def get_api_key() -> str:
    return settings.internal_api_key


# 서비스 키가 맞을 때만 받는다. 서버 키가 비어 있으면 모두 거절한다 (설정 누락으로 문이 열리지 않게)
def require_service_key(
    expected: Annotated[str, Depends(get_api_key)],
    provided: Annotated[str | None, Header(alias=API_KEY_HEADER)] = None,
) -> None:
    if not expected or provided is None or not hmac.compare_digest(provided, expected):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "서비스 키가 올바르지 않습니다")


Service = Annotated[InvestigationService, Depends(get_service)]


# 접수만 하고 바로 돌려준다 — 결과는 콜백 또는 상태 조회로 받는다
@router.post(
    "",
    status_code=status.HTTP_202_ACCEPTED,
    dependencies=[Depends(require_service_key)],
)
def accept(request: InvestigationRequest, service: Service) -> InvestigationResult:
    return service.accept(request)


# 콜백이 오지 않았을 때 Spring이 결과를 가져가는 곳
@router.get("/{report_id}", dependencies=[Depends(require_service_key)])
def get_status(report_id: str, service: Service) -> InvestigationResult:
    result = service.status(report_id)
    if result is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "조사 기록이 없습니다")
    return result
