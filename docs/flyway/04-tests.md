# 04. Flyway 검사 — 무엇이 어떻게 막아 주나

[← Flyway 문서 목차](README.md)

## 1. 3중 방어 한눈에

```text
 ① 내 PC  bootRun            ② 내 PC · CI  mariaDbTest          ③ PR의 CI  migration 검사
 ─────────────────          ───────────────────────────        ──────────────────────────
 내 DB에 새 파일 적용          빈 MariaDB 11.8에 전체 파일 적용      병합된 파일 수정·삭제 차단
 + Entity validate            + 체크섬 + Entity validate          + 파일 이름 규칙 검사
 SQL 오류·불일치를 바로 앎     "처음 받는 팀원" 상황을 재현          다른 PC의 DB가 깨지는 것을 방지
```

| 검사 | 언제 | 무엇을 잡나 |
| --- | --- | --- |
| `bootRun` | 내 PC | SQL 오류, Entity ↔ 테이블 불일치 |
| `./gradlew mariaDbTest` | 내 PC (Docker 필요) · CI | 빈 MariaDB 11.8에 전체 migration 적용 + validate |
| CI `migration 불변·이름 규칙 검사` | PR | 병합된 migration 수정·삭제, 규칙 밖 파일명 |

①만으로 부족한 이유: 내 DB에는 예전 파일이 이미 적용돼 있어서, **처음부터 전부 적용했을 때**의 문제(버전 순서, 다른 팀원 파일과의 충돌)는 안 보입니다. ②가 매번 빈 DB로 이것을 확인합니다.

## 2. `FlywayMigrationTests` — 테스트 3개

`backend/src/test/java/com/donworry/FlywayMigrationTests.java`

테스트가 시작되면 **앱 전체가 빈 MariaDB에 붙어 기동**합니다. 기동 과정에서 Flyway 적용과 Hibernate validate가 일어나므로, **앱이 뜨는 것 자체가 첫 번째 검사**입니다. SQL 오류·버전 충돌·Entity 불일치가 있으면 테스트 3개가 실행되기 전에 실패합니다.

| 테스트 | 확인하는 것 | 이걸로 잡는 사고 |
| --- | --- | --- |
| `모든_migration이_성공으로_적용되고_대기중인_것이_없다` | 모든 파일이 성공(또는 `out-of-order` 성공) 상태, 실행 대기 중인 파일 0개 | 일부 파일이 실패·누락된 채 넘어감 |
| `적용된_migration과_파일의_체크섬이_일치한다` | 이력표의 체크섬 = 현재 파일의 체크섬 | 적용된 파일을 수정함 (③ CI 검사와 이중 방어) |
| `Modulith_이벤트_테이블은_Flyway가_대문자_이름으로_만든다` | `EVENT_PUBLICATION`이 대문자 이름 그대로 존재 | 이름이 소문자로 바뀌어 리눅스 운영 DB에서 Modulith 이벤트 저장 실패 |

## 3. Testcontainers — DB를 누가 띄우나

Testcontainers는 **테스트 코드가 Docker 컨테이너를 직접 띄우고 끝나면 지우는** 라이브러리입니다.

```text
./gradlew mariaDbTest
  → MariaDbTestContainerConfig 가 Docker에 mariadb:11.8 컨테이너 실행 (빈 DB)
  → 앱의 DB 접속 정보를 이 컨테이너로 바꿔 끼움 (application.yml의 localhost:3307 대신)
  → 앱 기동 → Flyway 전체 적용 → 테스트 3개
  → 테스트 종료 후 컨테이너 자동 삭제
```

- `docker compose up`이 **필요 없습니다**. Docker Desktop만 켜져 있으면 됩니다
- 내 개발 DB(`localhost:3307`)와 **완전히 별개**입니다. 내 DB에 테이블이 있든 없든 테스트 결과에 영향이 없습니다
- 컨테이너는 **JVM당 한 번만** 띄웁니다 (`static` 필드). 나중에 MariaDB 테스트 클래스가 늘어나도 컨테이너를 반복해서 띄우지 않습니다
- 이미지 버전 `mariadb:11.8`과 문자셋(`utf8mb4_unicode_ci`)은 `docker-compose.yml`과 맞췄습니다. 개발 DB 버전을 올리면 여기도 같이 올립니다

## 4. CI migration 검사

`.github/workflows/backend.yml`의 `migration 불변·이름 규칙 검사` 단계입니다. **PR일 때만** 돕니다.
`dev`(PR 대상 브랜치)와 내 브랜치를 비교해서 `db/migration/*.sql`만 봅니다.

| 검사 | 통과 | 실패 |
| --- | --- | --- |
| 불변 | 새 파일 **추가**만 있음 | 이미 병합된 파일을 수정·삭제·이름 변경함 |
| 이름 규칙 | 새 파일 이름이 `V<8자리>_<6자리>__<snake_case>.sql` | `V3__bad_name.sql`처럼 직접 지은 이름 |

실패 메시지와 해결 방법은 [06 D2·D3](06-troubleshooting.md#d-테스트ci)에 있습니다.

## 5. `check`와 `mariaDbTest`를 나눈 이유

```text
./gradlew check        DB 없이 돎 (컴파일 + 모듈 경계 검사 + 계층 검사)   ← 모든 팀원이 언제든 실행
./gradlew mariaDbTest  Docker 필요 (@Tag("mariadb") 테스트만)              ← Docker가 있을 때 / CI
```

- `build.gradle`에서 기본 `test`는 `mariadb` 태그를 **제외**하고, `mariaDbTest`는 그 태그**만** 실행합니다
- Docker가 없는 팀원도 `check`는 막힘없이 돌릴 수 있게 하려는 분리입니다 (1차 CakeShop과 같은 방식)
- CI는 두 가지를 모두 실행하므로, 로컬에서 `mariaDbTest`를 깜빡해도 PR에서 잡힙니다
- 실패하면 CI 실행 화면의 Artifacts에서 `backend-test-reports`(테스트 리포트)를 내려받아 원인을 봅니다

다음: [05 작업 가이드](05-guide.md)
