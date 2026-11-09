from functools import lru_cache
from typing import Annotated

from fastapi import APIRouter, Depends

from app.config import settings
from app.controller.investigation_controller import require_service_key
from app.schemas.question import QuestionRequest, QuestionResult
from app.service.question_service import QuestionService, build_question_service

# Spring 전용 사건 질문 API (ADR-0086) — 조사 API와 같은 서비스 키, 바로 답한다(동기)
# 답을 못 만들어도 200 + status FAILED로 돌려준다 — Spring이 실패로 저장한다
router = APIRouter(prefix="/questions", tags=["questions"])


@lru_cache
def get_question_service() -> QuestionService:
    return build_question_service(settings)


Service = Annotated[QuestionService, Depends(get_question_service)]


@router.post("", dependencies=[Depends(require_service_key)])
def answer(request: QuestionRequest, service: Service) -> QuestionResult:
    return service.answer(request)
