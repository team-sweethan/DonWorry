from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.controller.investigation_controller import get_service
from app.controller.investigation_controller import router as investigation_router
from app.controller.ml_controller import get_fraud_score_service
from app.controller.ml_controller import router as ml_router


# RF 모델은 서버 시작 시 한 번 읽는다 — 실패해도 서버는 뜨고 ML API만 503
# 조사 서비스도 시작 시 만든다 — LLM 설정이 틀리면(openai 키 누락) 서버가 뜨지 않게 (#453)
@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    get_fraud_score_service()
    get_service()
    yield


app = FastAPI(title="DonWorry AI Server", lifespan=lifespan)
app.include_router(investigation_router)
app.include_router(ml_router)


# 살아있는지 확인용 — backend·CI에서 호출
@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}
