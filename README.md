# DonWorry

CakeShop 결제를 받아 **결제(PG) → FDS 위험평가 → 보안 케이스 조사 → AI 보고서 → 직원 판정 → 매입·정산**까지 처리하는 2차 프로젝트.

작업 규칙은 [AGENTS.md](AGENTS.md)를 먼저 읽는다. 설계 결정은 [docs/decisions/](docs/decisions/), CI 설명은 [.github/workflows/README.md](.github/workflows/README.md).

## 구성

| 폴더 | 기술 | 포트 |
| --- | --- | --- |
| `backend/` | Java 21 · Spring Boot 4.1 · Spring Modulith 2.1 · MariaDB · Flyway | 8081 |
| `frontend/` | React 19 · TypeScript 7 · Vite 8 · oxlint · Prettier | 5173 |
| `ai-server/` | Python 3.13 · FastAPI · uv · ruff · pytest | 8000 |
| `docker-compose.yml` | MariaDB 11.8 (개발용) | 3307 |

CakeShop은 별도 저장소 (8080).

## Backend

1) DB 실행 — Docker로 MariaDB 11.8을 3307 포트에 띄운다 (DB·계정 자동 생성)

```bash
docker compose up -d          # 저장소 루트에서
docker compose down -v        # DB 초기화가 필요할 때 (데이터 삭제)
```

Docker 없이 직접 설치한 MariaDB를 쓰면 관리자로 1회 실행:

```sql
CREATE DATABASE donworry CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'donworry'@'%' IDENTIFIED BY 'donworry';
GRANT ALL PRIVILEGES ON donworry.* TO 'donworry'@'%';
```

2) 내 PC 설정이 기본값(3307 / donworry / donworry)과 다를 때만 (예: 직접 설치한 3306)

```bash
cd backend
cp src/main/resources/application-local.yml.example src/main/resources/application-local.yml
```

3) 실행·검사

```bash
cd backend
./gradlew bootRun
./gradlew check        # 컴파일 + 모듈 경계 검사 (DB 불필요)
```

모듈 관계도: `./gradlew test` 후 `build/spring-modulith-docs/`

## Frontend

```bash
cd frontend
npm install
npm run dev            # /api 요청은 8081로 전달됨
npm run lint && npm run format:check && npm run build
```

## AI Server

```bash
cd ai-server
uv sync
cp .env.example .env
uv run uvicorn app.main:app --reload --port 8000
uv run ruff check . && uv run pytest
```

## 아직 없는 것

- 업무 테이블 — ERD 확정 후 Flyway로 추가
- Flyway 비활성화 + `ddl-auto: update` 임시 사용 — ERD 확정 후 `validate` + Flyway로 전환
- 로그인 — 지금은 모든 요청 허용 (`identity/security/SecurityConfig`)
- 화면 — 홈 하나
- AI 보고서 API — 보고서 단위 확정 후
