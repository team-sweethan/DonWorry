# CI 문서

> **한 줄 요약:** PR을 올리면 GitHub이 자동으로 빌드·테스트·규칙 검사를 돌리고, 통과해야 병합할 수 있습니다.

## 상황별 바로가기

| 지금 상황 | 읽을 문서 |
| --- | --- |
| CI가 처음이다 | [01 개념](01-concept.md) → [02 언제 무엇이 도나](02-overview.md) |
| 내 PR에서 어떤 검사가 도는지 궁금하다 | [02 언제 무엇이 도나](02-overview.md) |
| CI가 빨간불이다 | [06 문제 해결](06-troubleshooting.md) — 맨 위 증상 표에서 찾기 |
| PR 올리기 전에 로컬에서 똑같이 돌려 보고 싶다 | 03·04·05의 "로컬에서 똑같이" 열 |

## 문서 목록

| 문서 | 내용 |
| --- | --- |
| [01-concept.md](01-concept.md) | CI란 무엇인가 — 역할, GitHub Actions 용어, 막는 것과 못 막는 것 |
| [02-overview.md](02-overview.md) | 언제 무엇이 도나 — 워크플로 4개, 실행 조건, 변경 없으면 건너뛰기, 필수 검사 |
| [03-backend.md](03-backend.md) | backend 검사 — migration 검사, `check`(모듈·계층 검사), `mariaDbTest` |
| [04-frontend.md](04-frontend.md) | frontend 검사 — `npm ci`, oxlint, Prettier, 타입 검사 + 빌드 |
| [05-ai-server-and-docs.md](05-ai-server-and-docs.md) | ai-server 검사(uv·ruff·pytest)와 문서 링크 검사 |
| [06-troubleshooting.md](06-troubleshooting.md) | 문제 해결 — 증상별 원인과 해결 단계 |

## 관련 파일

- 워크플로 정의: [`.github/workflows/`](../../.github/workflows/) (`backend.yml` · `frontend.yml` · `ai-server.yml` · `docs.yml`)
- 검사 명령 목록: [AGENTS.md 9절](../../AGENTS.md#9-검사-명령)
- Flyway 관련 검사 상세: [Flyway 04 검사](../flyway/04-tests.md)
