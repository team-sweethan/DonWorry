# 0011. Flyway 전환과 마이그레이션 버전 규칙

- **상태:** 제안
- **날짜:** 2026-10-22
- **관련:** Issue #23, Notion fds 정본 03 설계 §3.1(Flyway 기반 스키마 변경)·§3.9(ERD Baseline v1), 1차 CakeShop `docs/flyway_make_sample.md`
- **영향 받는 담당:** P1~P6 전원 (모든 테이블 추가가 이 규칙을 따름)

## 배경

- `build.gradle`에 Flyway 의존성은 있었지만 `flyway.enabled: false`, `ddl-auto: update`로 운영했다. Entity를 바꾸면 Hibernate가 각자 PC의 DB를 조용히 바꿔, 팀원마다 스키마가 달라질 수 있다.
- ERD Baseline v1(8개 테이블)이 나와 각 담당이 업무 테이블을 만들기 시작할 시점이다. 테이블이 생기기 전에 바꾸면 기존 데이터 이관이 필요 없다.
- AGENTS.md는 "병합 직전에 main 최신 번호를 보고 번호를 정한다"는 순번 규칙이었다. 6명이 동시에 테이블을 추가하면 같은 번호가 자주 겹치고, 병합할 때마다 파일명을 바꿔야 한다.
- 이벤트 저장 테이블(`EVENT_PUBLICATION`)은 Modulith가 기동 시 직접 만들고 있었다.

## 결정

Flyway를 켜고 `ddl-auto: validate`로 바꾼다. 마이그레이션 버전은 1차 CakeShop처럼 **생성 시각 타임스탬프** `V<yyyyMMdd_HHmmss>__<설명>.sql`을 `./gradlew newMigration`으로만 만들고, `out-of-order: true`로 늦게 병합된 낮은 버전도 적용한다. `EVENT_PUBLICATION`도 첫 마이그레이션으로 옮긴다.

세부:

- 타임스탬프는 **한국 시간(`Asia/Seoul`)으로 고정**해 찍는다. `newMigration` 코드에 시간대를 직접 적으므로 PC·CI·클라우드의 시간대 설정과 상관없이 모두 같은 기준이 되고, 파일 이름을 만든 시각 그대로 읽을 수 있다 (팀 전원이 한국에 있음). (2026-10-22 UTC에서 변경. CakeShop은 PC 로컬 시각)
- Gradle을 실행할 수 없는 환경(일부 AI 작업 환경)에서만 `TZ=Asia/Seoul date +%Y%m%d_%H%M%S` 등 한국 시간 고정 명령으로 버전을 만든다 (AGENTS.md 4절)
- `validate-on-migrate: true`, `clean-disabled: true`.
- CI(`backend.yml`)가 PR에서 병합된 migration의 수정·삭제와 규칙 밖 파일명을 막는다.
- CI가 `./gradlew mariaDbTest`로 MariaDB 11.8 컨테이너(Testcontainers)에 앱을 기동해 migration 전체 적용·체크섬·JPA `validate`를 검사한다. 1차 CakeShop의 `mariaDbTest` 분리 방식을 따르고, 로컬 `check`는 Docker 없이 돌게 둔다.
- 이번 결정은 **인프라만** 다룬다. 업무 테이블 8개는 소유 담당이 자기 마이그레이션으로 추가한다. ID·금액·시각 물리 타입은 아직 미확정이다 (`docs/payment/README.md` C1).

## 고려한 대안

| 대안 | 버린 이유 |
| --- | --- |
| 타임스탬프를 UTC로 (이 ADR의 첫 결정) | 모두 같은 기준이라 순서 문제는 없지만, 파일 이름을 읽을 때마다 9시간을 더해야 한다. 한국 시간도 코드에 고정하면 같은 효과 |
| 타임스탬프를 PC 로컬 시각으로 (CakeShop) | CI·클라우드 AI 환경은 기본이 UTC라 PC에서 만든 파일과 시간대가 섞여 순서가 어긋난다 |
| 순번 `V1`, `V2`… (기존 AGENTS.md 규칙) | 동시 작업 시 번호 충돌이 잦고, 병합 직전 파일명을 바꾸는 수작업이 필요하다. 바꾸는 걸 잊으면 Flyway가 같은 버전 두 개로 기동 실패 |
| `ddl-auto: update` 유지, 나중에 전환 | 테이블이 쌓인 뒤 전환하면 각자 PC의 서로 다른 스키마를 baseline으로 맞추는 작업이 생긴다. 지금은 테이블이 없어 비용이 0 |
| 8개 테이블 전체를 V0 하나로 작성 | 다른 담당 테이블의 물리 타입을 한 명이 정하게 된다. 타입 미확정(C1) 상태라 각 담당이 정하는 편이 맞다 |
| `EVENT_PUBLICATION`은 Modulith 자동 생성 유지 | Flyway 이력 밖의 테이블이 생기고, 빈 DB가 아니라는 이유로 Flyway 첫 적용과 충돌한다. Modulith 버전업 시 스키마 변경도 추적되지 않는다 |
| Flyway 실제 적용 검사를 첫 Entity가 생길 때 추가 | 각 담당이 곧 migration을 올리기 시작한다. SQL 오류가 `dev`에 병합되면 팀원 전원의 기동이 깨지므로, 첫 업무 migration보다 먼저 있어야 한다 |
| CI 서비스 컨테이너(`services: mariadb`)로 DB 띄우기 | 로컬에서 같은 검사를 돌리려면 따로 DB를 맞춰야 한다. Testcontainers는 `./gradlew mariaDbTest` 하나로 로컬·CI가 같다 |
| `baseline-on-migrate: true`로 기존 로컬 DB 그대로 수용 | 기존 DB에 무엇이 있든 이력만 찍고 넘어가 스키마 차이를 숨긴다. 개발 DB라 `docker compose down -v` 한 번이 더 안전하다 |

## 영향

- API: 없음
- 버전 시간대: 첫 migration `V20261021_215412`는 UTC로 만들었다. 이후 한국 시간 파일은 항상 이보다 큰 숫자라 순서 문제가 없고, 파일은 병합됐으므로 고치지 않는다. 한국 시간 전환 전 UTC로 만든 미병합 파일도 `out-of-order: true`로 적용된다
- DB: `EVENT_PUBLICATION` 생성 migration 추가 (Modulith 2.1.1 `schemas/v2/schema-mariadb.sql`과 같은 정의). 기존 로컬 DB는 한 번 초기화 필요 (README "DB 실행")
- 화면: 없음
- 테스트: `./gradlew check`는 그대로 DB 없이. `FlywayMigrationTests`(`@Tag("mariadb")`)를 `mariaDbTest` 태스크로 분리하고 CI에서 실행. 테스트 의존성 `spring-boot-testcontainers`·`testcontainers-mariadb` 추가
- 문서: AGENTS.md 4·9절, README, `.github/workflows/README.md`

## 검증

검증됨 (MariaDB 10.11.14, `utf8mb4_unicode_ci`, TZ=UTC — 팀 기준 11.8과 버전이 다름):

- migration SQL 적용 성공. `SHOW CREATE TABLE` 결과가 Modulith 2.1.1 원본 `schemas/v2/schema-mariadb.sql`로 만든 테이블과 `diff` 동일
- 같은 파일 재적용 시 `ERROR 1050 Table 'EVENT_PUBLICATION' already exists`로 실패 (IF NOT EXISTS 제거 확인)
- 이벤트 1건 INSERT → 완료 UPDATE 왕복 성공. `explicit_defaults_for_timestamp=1`이라 `TIMESTAMP(6)`에 자동 `ON UPDATE`가 붙지 않음
- CI migration 검사 스크립트를 로컬 git으로 4가지 경우 실행: 이 브랜치 vs `dev` 통과 / 병합된 migration 수정 → 실패 / `V3__bad_name.sql` 추가 → 실패 / `.gitkeep` 삭제 → 통과

미검증 (작업 환경에서 Maven Central·Gradle 배포 서버 접근이 막힘):

- `./gradlew check`, `./gradlew mariaDbTest`(`FlywayMigrationTests` 컴파일·실행 포함) — 첫 실행 결과는 PR의 CI로 확인
- 애플리케이션 기동 시 Flyway 자동 적용과 `flyway_schema_history` 기록, Modulith가 이 테이블로 이벤트를 저장하는지
- `./gradlew newMigration -Pdesc=...` 태스크 실행
- MariaDB 11.8에서의 적용
