# Python 3.13 환경
FROM python:3.13-slim

WORKDIR /app

# Python 패키지 관리 도구 설치
RUN pip install --no-cache-dir uv

# 의존성 설치
COPY pyproject.toml uv.lock ./
RUN uv sync --locked --no-dev

# FastAPI 소스 복사
COPY app ./app

# 설치된 가상환경 사용
ENV PATH="/app/.venv/bin:$PATH"

EXPOSE 8000

# FastAPI 서버 실행
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
