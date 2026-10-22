# Flyway 문서

> **한 줄 요약:** DB 테이블은 Entity가 아니라 **SQL 파일(migration)** 로 만들고 바꿉니다. Entity만 만들어서는 테이블이 생기지 않습니다.

## 상황별 바로가기

| 지금 상황 | 읽을 문서 |
| --- | --- |
| Flyway가 처음이다 | [01 개념](01-concept.md) → [02 채택 이유](02-why.md) → [03 우리 설정](03-how-we-use.md) |
| 내 테이블을 추가하려고 한다 | [05 작업 가이드](05-guide.md) |
| 에러가 났다 | [06 문제 해결](06-troubleshooting.md) — 맨 위 증상 표에서 찾기 |
| 테스트·CI가 무엇을 검사하는지 궁금하다 | [04 검사](04-tests.md) |

## 문서 목록

| 문서 | 내용 |
| --- | --- |
| [01-concept.md](01-concept.md) | Flyway란 무엇인가 — migration 파일, 적용 이력표, 체크섬, 기동 순서 |
| [02-why.md](02-why.md) | 왜 채택했나 — `ddl-auto: update`의 문제, 타임스탬프 버전을 고른 이유 |
| [03-how-we-use.md](03-how-we-use.md) | 우리 프로젝트 설정 — `application.yml` 옵션, 파일 이름 규칙, `newMigration` |
| [04-tests.md](04-tests.md) | 무엇이 어떻게 검사하나 — `FlywayMigrationTests`, Testcontainers, CI migration 검사 |
| [05-guide.md](05-guide.md) | 실제 작업 순서 — 테이블 추가, 붙은 DB 확인, 로컬 DB 초기화, 결과 확인 |
| [06-troubleshooting.md](06-troubleshooting.md) | 문제 해결 — 증상별 원인과 해결 단계 |

## 관련 문서

- 규칙 정본(지켜야 하는 것): [AGENTS.md 4절](../../AGENTS.md#4-backend-규칙)
- 결정 기록(왜 이렇게 정했나, 버린 대안): [ADR-0011](../decisions/0011-flyway-migration-versioning.md)
- 테이블 설계: [ERD](../erd/README.md)
