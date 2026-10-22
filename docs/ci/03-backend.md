# 03. backend 검사

[← CI 문서 목차](README.md)

워크플로: [`.github/workflows/backend.yml`](../../.github/workflows/backend.yml) · job 이름 `backend`

## 1. 단계 한눈에

```text
변경 감지 ──(backend/ 변경 없음)──▶ 통과
   │
   ▼ (PR일 때만)
① migration 불변·이름 규칙 검사
   ▼
   Java 21 · Gradle 준비
   ▼
② ./gradlew check        ← DB 없이: 컴파일 + 코드 모양 + 모듈 경계 검사 + 계층 검사
   ▼
③ ./gradlew mariaDbTest  ← Docker로 MariaDB 11.8을 띄워 Flyway 전체 적용 + JPA validate
   ▼ (실패했을 때만)
④ 테스트 리포트 업로드 (backend-test-reports)
```

| 단계 | 하는 일 | 실패하면 의심할 것 | 로컬에서 똑같이 (`backend/`에서) |
| --- | --- | --- | --- |
| ① migration 검사 | 병합된 migration 수정·삭제, 규칙 밖 파일명 차단 | 병합된 SQL을 고침, 파일 이름을 직접 지음 | (git 비교라 로컬 명령 없음) |
| ② `check` | 컴파일, `spotlessCheck`, `ModularityTests`, `ArchitectureTests` | 컴파일 오류, `spotlessApply` 안 돌림, 다른 모듈 내부 접근, 순환, 계층 위반 | `./gradlew spotlessApply check` |
| ③ `mariaDbTest` | 빈 MariaDB에 migration 전체 적용 + Entity 검사 | SQL 오류, Entity ↔ 테이블 불일치 | `./gradlew mariaDbTest` (Docker Desktop 필요) |

Windows에서는 `./gradlew` 대신 `.\gradlew.bat`을 씁니다.

## 2. migration 불변·이름 규칙 검사

PR이 `dev`(PR 대상 브랜치)와 비교해 `backend/src/main/resources/db/migration/*.sql`을 어떻게 바꿨는지 봅니다.

| 검사 | 통과 | 실패 |
| --- | --- | --- |
| 불변 | 새 파일 **추가**만 있음 | 이미 병합된 파일의 수정·삭제·이름 변경 |
| 이름 규칙 | `V<yyyyMMdd>_<HHmmss>__<snake_case>.sql` | `V3__create_table.sql`처럼 직접 지은 이름 |

왜 필요한지와 동작 원리는 [Flyway 04 검사 4절](../flyway/04-tests.md#4-ci-migration-검사)에 있습니다.

## 3. `./gradlew check`

DB 없이 코드 모양과 클래스 구조만 검사합니다. `spotlessCheck`가 코드 모양을 보고, 컴파일 후 `test` 태스크가 아래 두 테스트를 돌립니다 (`@Tag("mariadb")` 테스트는 제외).

새 테스트는 `backend/src/test` 아래에 넣기만 하면 이 단계에 자동으로 포함됩니다. 워크플로를 고칠 필요가 없습니다. DB가 필요한 테스트라면 `@Tag("mariadb")`를 붙여 ③으로 보냅니다.

### `spotlessCheck` — 코드 모양 (palantir-java-format)

| 규칙 | 예 |
| --- | --- |
| 들여쓰기 4칸, 한 줄 120자 | 긴 메서드 호출은 자동으로 줄을 나눈다 |
| import 정리 | 안 쓰는 import 제거, 순서 고정 |

- 사람이 맞추지 않습니다. 커밋 전에 `./gradlew spotlessApply`를 돌리면 파일이 고쳐집니다
- 왜 이 도구를 골랐는지는 [ADR-0013](../decisions/0013-code-formatter.md)에 있습니다

### `ModularityTests` — 모듈 **사이** 규칙 (Spring Modulith)

| 규칙 | 위반 예 |
| --- | --- |
| 다른 모듈은 공개 `api/` 패키지만 쓴다 | `investigation`이 `payment.entity.Payment`를 import |
| 모듈 간 순환 의존 금지 | `payment` → `settlement` 호출 + `settlement` → `payment` 호출 |
| 허용된 방향으로만 의존 (ADR-0012) | `fds`가 `investigation.api`를 import |

- 모듈이 **어느 방향**으로 의존해도 되는지는 각 모듈 `package-info.java`의 `allowedDependencies`(허용 의존 목록)로 검사합니다 ([Modulith 05 3절](../modulith/05-verification.md#3-alloweddependencies--모듈별-허용-의존))
- 같은 테스트가 `build/spring-modulith-docs/`에 모듈 관계도(PlantUML)도 만듭니다

### `ArchitectureTests` — 모듈 **안** 계층 규칙 (ArchUnit)

| 테스트 | 규칙 | 이유 |
| --- | --- | --- |
| `controller는_repository를_직접_호출하지_않는다` | `..controller..` → `..repository..` 금지 | Controller → Service → Repository 순서를 지킨다 |
| `controller는_entity에_의존하지_않는다` | `..controller..` → `..entity..` 금지 | 화면·외부 응답은 DTO로 내보낸다 |
| `entity는_controller와_service에_의존하지_않는다` | `..entity..` → `..controller..`·`..service..` 금지 | 엔티티는 데이터만 담고 위 계층을 모른다 |

아직 클래스가 없는 패키지도 통과하도록 `allowEmptyShould(true)`가 붙어 있습니다.

## 4. `./gradlew mariaDbTest`

- `@Tag("mariadb")`가 붙은 테스트만 돕니다. 지금은 `FlywayMigrationTests` 하나입니다
- GitHub runner에는 Docker가 있어서 별도 설정 없이 Testcontainers(테스트용 일회용 DB 컨테이너)가 MariaDB 11.8을 띄웁니다
- 무엇을 확인하는지는 [Flyway 04 검사 2·3절](../flyway/04-tests.md#2-flywaymigrationtests--테스트-3개)에 있습니다

## 5. 실패 리포트 받기

②나 ③이 실패하면 테스트 결과를 artifact(결과 파일)로 7일간 남깁니다.

1. 실패한 실행 화면(Actions의 run 페이지) 아래쪽 **Artifacts**에서 `backend-test-reports`를 내려받습니다
2. 압축을 풀고 `reports/tests/test/index.html`(`check`) 또는 `reports/tests/mariaDbTest/index.html`을 브라우저로 엽니다
3. 실패한 테스트를 눌러 전체 에러 메시지를 봅니다

## 6. 속도

- 검사 자체는 1분 안팎입니다
- `setup-gradle`이 Gradle·라이브러리를 캐시에 저장해서, 두 번째 실행부터 빨라집니다. 첫 실행이나 `build.gradle` 의존성을 바꾼 직후에는 캐시 업로드 때문에 몇 분 더 걸립니다 ([06](06-troubleshooting.md#a2-첫-실행이-5분-넘게-걸림))

다음: [04 frontend 검사](04-frontend.md)
