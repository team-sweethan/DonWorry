from fastapi import FastAPI

from app.controller.investigation_controller import router as investigation_router

app = FastAPI(title="DonWorry AI Server")
app.include_router(investigation_router)


# 살아있는지 확인용 — backend·CI에서 호출
@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}
