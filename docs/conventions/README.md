# 규칙 지도

> **한 줄 요약:** 팀 규칙이 **어디에 적혀 있고, 기계가 막아 주는지**만 모은 표입니다. 규칙 내용은 여기에 쓰지 않습니다. 각 줄의 원본 문서를 엽니다.

| 표시 | 뜻 |
| --- | --- |
| 🤖 | CI · 테스트가 실패시켜 병합을 막는다. 몰라도 걸린다 |
| 📄 | 문서에만 적혀 있다. 리뷰로 지킨다 |
| ⏳ | 아직 정하지 않았다. 담당에게 묻는다 |

## 1. Git · PR

| 무엇 | 강제 | 원본 |
| --- | --- | --- |
| 브랜치 이름 · 병합 흐름 (`feature/*` → `dev` → `main`) | 📄 | [AGENTS.md 7절](../../AGENTS.md#7-작업-순서) |
| `main` · `dev` 직접 push 금지 | 🤖 GitHub 저장소 설정 | [AGENTS.md 7절](../../AGENTS.md#7-작업-순서) |
| PR 본문 형식 · "미검증" 칸 | 📄 | [PR 템플릿](../../.github/pull_request_template.md) |
| 필수 검사 (`backend` · `frontend` · `ai-server`) | 🤖 | [CI 02 4절](../ci/02-overview.md#4-필수-검사와-job-이름) |
| 작업 완료 보고 형식 | 📄 | [AGENTS.md 7절](../../AGENTS.md#7-작업-순서) |
| 코드 리뷰 언어 · 범위 | 📄 | [AGENTS.md Code Review Rules](../../AGENTS.md#code-review-rules) |

## 2. Backend

| 무엇 | 강제 | 원본 |
| --- | --- | --- |
| 코드 모양 (들여쓰기 · 줄바꿈 · import) | 🤖 `spotlessCheck` | [ADR-0013](../decisions/0013-code-formatter.md) |
| 모듈 경계 (`api/`만 사용) · 순환 금지 | 🤖 `ModularityTests` | [Modulith 05](../modulith/05-verification.md) |
| 계층 (Controller → Service → Repository) | 🤖 `ArchitectureTests` | [CI 03 3절](../ci/03-backend.md#3-gradlew-check) |
| 모듈 간 의존 방향 | 📄 (자동 강제는 팀 합의 후) | [ADR-0012](../decisions/0012-payment-settlement-dependency.md) |
| 이벤트 클래스 위치 · 멱등 | 📄 | [Modulith 04](../modulith/04-dependency-and-events.md) |
| 모듈 안 패키지 구조 | 📄 | [Modulith 03](../modulith/03-module-structure.md) |
| Lombok · DTO · 에러 응답 · 트랜잭션 · 이름 · 테스트 · 로그 · 주석 | 📄 `합의 전` | [02 코드 작성 규칙](02-code.md) |
| 공통 에러 클래스 위치 | ⏳ | [02 코드 작성 규칙 3절](02-code.md#공통-클래스-위치-보류) |

## 3. DB

| 무엇 | 강제 | 원본 |
| --- | --- | --- |
| 병합된 migration 수정 금지 · 파일 이름 형식 | 🤖 CI | [Flyway 04 4절](../flyway/04-tests.md#4-ci-migration-검사) |
| Entity ↔ 테이블 일치 | 🤖 `mariaDbTest` | [Flyway 04](../flyway/04-tests.md) |
| 테이블 추가 순서 · 작성 기준 | 📄 | [Flyway 05](../flyway/05-guide.md), [AGENTS.md 4절](../../AGENTS.md#4-backend-규칙) |
| 소유 담당이 다르면 FK 금지 · 물리 삭제 금지 | 📄 | [AGENTS.md 4절](../../AGENTS.md#4-backend-규칙) |
| 테이블 소유 · 컬럼 | ⏳ 작성 예정 | [ERD](../erd/README.md) |
| ID · 금액 · 시각 타입 | ⏳ | [P5 결제 문서 C1](../payment/README.md#구현-전에-필요한-최소-계약) |

## 4. Frontend

| 무엇 | 강제 | 원본 |
| --- | --- | --- |
| 코드 모양 | 🤖 Prettier | [CI 04 4절](../ci/04-frontend.md#4-prettier) |
| `any` 금지 · Hooks 규칙 | 🤖 oxlint | [CI 04 3절](../ci/04-frontend.md#3-oxlint-규칙) |
| 타입 검사 | 🤖 `tsc` | [CI 04 5절](../ci/04-frontend.md#5-빌드) |
| HTTP 호출 위치 · 판정은 백엔드 몫 | 📄 | [AGENTS.md 5절](../../AGENTS.md#5-frontend-규칙) |
| 페이지 배치 · 라우터 연결 | 📄 | [공통 레이아웃 적용 가이드](../../frontend/공통레이아웃_적용가이드.md) |

## 5. AI Server

| 무엇 | 강제 | 원본 |
| --- | --- | --- |
| 코드 모양 | 🤖 `ruff format` | [CI 05 1절](../ci/05-ai-server-and-docs.md#1-ai-server) |
| 코드 규칙 (import · 문법 · 버그 패턴) | 🤖 `ruff check` | [CI 05 ruff 규칙](../ci/05-ai-server-and-docs.md#ruff-규칙) |
| 의존성 잠금 파일 일치 | 🤖 `uv sync --locked` | [CI 05 1절](../ci/05-ai-server-and-docs.md#1-ai-server) |
| 역할 분리 · 읽기 전용 Tool · `LlmClient` 경계 · 자동 판정 금지 | 📄 | [AGENTS.md 6절](../../AGENTS.md#6-ai-server-규칙), [ADR-0009](../decisions/0009-ai-investigation-call-structure.md) |

## 6. 문서 · 설계

| 무엇 | 강제 | 원본 |
| --- | --- | --- |
| Markdown 상대 링크 | 🤖 docs 검사 (필수 아님, `#앵커`는 안 봄) | [CI 05 2절](../ci/05-ai-server-and-docs.md#2-docs) |
| 설계를 바꾸면 ADR, 버린 대안 필수 | 📄 | [AGENTS.md 8절](../../AGENTS.md#8-설계를-바꿀-때), [ADR 안내](../decisions/README.md) |
| 결정됨 ≠ 구현됨 ≠ 검증됨 | 📄 | [AGENTS.md 2절](../../AGENTS.md#2-결정됨--구현됨--검증됨) |

## 이 표를 고칠 때

- 규칙 **내용**을 바꿀 때는 원본 문서만 고친다. 이 표는 규칙이 다른 문서로 옮겨지거나, 강제 수준(📄 → 🤖)이 바뀔 때만 고친다
- 같은 규칙을 리뷰에서 반복해서 지적하게 되면 📄를 🤖(테스트 · CI)로 바꾸는 것을 먼저 검토한다
