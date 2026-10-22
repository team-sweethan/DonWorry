# 06. Flyway 문제 해결

[← Flyway 문서 목차](README.md)

**사용법:** 아래 표에서 로그에 보이는 문구를 찾아 바로가기를 누릅니다. 항목은 모두 **증상 → 원인 → 해결** 순서입니다.

| 언제 | 로그·화면에 보이는 문구 | 원인 한 줄 | 바로가기 |
| --- | --- | --- | --- |
| 앱 기동 | `Found non-empty schema(s) donworry but no schema history table` | Flyway 도입 전에 테이블이 생긴 DB | [A1](#a1-기존-테이블이-있는데-flyway-이력이-없음) |
| 앱 기동 | `docker compose down -v` 했는데도 위와 같은 에러 | 앱이 Docker가 아닌 직접 설치 DB에 붙음 | [A2](#a2-docker-db를-초기화했는데도-같은-에러) |
| 앱 기동 | `Migration checksum mismatch for migration version ...` | 이미 적용된 migration 파일을 고침 | [A3](#a3-적용된-migration-파일이-바뀜) |
| 앱 기동 | `Schema-validation: missing column [xxx] in table [yyy]` | Entity에 있는 컬럼이 SQL에 없음 | [A4](#a4-entity에-있는-컬럼이-테이블에-없음) |
| DB 조회 | `Table 'donworry.flyway_schema_history' doesn't exist` | 앱이 붙지 않은 다른 DB를 조회함 | [B1](#b1-조회할-때-이력표가-없다고-나옴) |
| DB 조회 | 테이블 이름이 소문자 `event_publication`으로 보임 | Windows MariaDB 기본 설정 (정상) | [B2](#b2-테이블-이름이-소문자로-보임) |
| Windows 명령 | `'mariadb' 용어가 cmdlet ... 으로 인식되지 않습니다` | MariaDB 실행 파일이 PATH에 없음 | [C1](#c1-mariadb-명령을-찾을-수-없음) |
| Windows 명령 | `Unknown server host '127'` | PowerShell이 `-h127.0.0.1`을 쪼갬 | [C2](#c2-unknown-server-host-127) |
| 테스트 | `Could not find a valid Docker environment` | Docker Desktop이 꺼져 있음 | [D1](#d1-docker-환경을-찾을-수-없음) |
| PR의 CI | `병합된 migration을 수정/삭제했습니다` | dev에 병합된 migration 파일을 고침 | [D2](#d2-ci-병합된-migration을-수정삭제함) |
| PR의 CI | `migration 파일명이 규칙과 다릅니다` | 파일 이름을 직접 지음 | [D3](#d3-ci-migration-파일명-규칙-위반) |
| 앱 기동 | `WSREP_ON`, `newer than the version Flyway has been verified with` | 무시해도 되는 경고 | [E](#e-무시해도-되는-경고) |

먼저 확인할 것: 많은 문제가 **앱이 붙은 DB와 내가 보고 있는 DB가 다른 것**에서 생깁니다. 막히면 [05 가이드 2절](05-guide.md#2-내-앱이-어느-db에-붙었는지-확인)부터 봅니다.

## A. 앱 기동 실패

### A1. 기존 테이블이 있는데 Flyway 이력이 없음

**증상**

```text
Found non-empty schema(s) donworry but no schema history table
```

**원인** DB에 테이블은 있는데 Flyway 적용 이력표(`flyway_schema_history`)가 없습니다. 대개 Flyway 전환(#23) 전에 Modulith가 `event_publication`을 직접 만든 DB입니다. Flyway는 "모르는 테이블이 있는 DB"에는 손대지 않고 멈춥니다.

**해결**

1. 앱이 어느 DB에 붙었는지 확인합니다 → [05 가이드 2절](05-guide.md#2-내-앱이-어느-db에-붙었는지-확인)
2. 그 DB를 초기화합니다 → [05 가이드 3절](05-guide.md#3-로컬-db-초기화)
3. `bootRun`을 다시 실행하고 [05 가이드 4절](05-guide.md#4-적용-결과-확인)로 확인합니다

> `baseline-on-migrate`로 우회하지 않습니다. 기존 DB에 무엇이 있든 이력만 찍고 넘어가 스키마 차이를 숨기기 때문입니다 ([ADR-0011](../decisions/0011-flyway-migration-versioning.md)).

### A2. Docker DB를 초기화했는데도 같은 에러

**증상** `docker compose down -v` 후 다시 띄웠는데도 [A1](#a1-기존-테이블이-있는데-flyway-이력이-없음) 에러가 그대로 납니다.

**원인** 앱이 Docker가 아니라 **직접 설치한 MariaDB**에 붙어 있습니다. 둘 다 3307 포트를 쓰면 헷갈리기 쉽습니다.

**해결**

1. `bootRun` 로그의 `(MariaDB x.y)` 버전을 확인합니다. 11.8이 아니면 직접 설치 DB입니다 → [05 가이드 2절](05-guide.md#2-내-앱이-어느-db에-붙었는지-확인)
2. 직접 설치 DB를 초기화합니다 → [05 가이드 3-2](05-guide.md#3-2-직접-설치한-mariadb를-쓰는-경우-windows)

### A3. 적용된 migration 파일이 바뀜

**증상**

```text
Migration checksum mismatch for migration version ...
```

**원인** 이미 적용된 migration 파일의 내용이 바뀌어 체크섬(파일 지문)이 달라졌습니다 → [01 개념 4절](01-concept.md#4-체크섬--적용된-파일을-고치면-왜-막히나)

**해결** — 언제 고쳤는지에 따라 다릅니다.

| 경우 | 해결 |
| --- | --- |
| 병합 **전** 내 파일을 고침 | 내 로컬 DB만 옛 버전을 기억하는 것입니다. [05 가이드 3절](05-guide.md#3-로컬-db-초기화)로 초기화합니다 |
| dev에 **병합된** 파일을 고침 | 수정을 되돌리고 새 migration을 만듭니다 (CI에서도 막힘 → [D2](#d2-ci-병합된-migration을-수정삭제함)) |

### A4. Entity에 있는 컬럼이 테이블에 없음

**증상**

```text
Schema-validation: missing column [xxx] in table [yyy]
```

**원인** Entity에는 필드가 있는데 migration SQL에 그 컬럼이 없습니다. Hibernate는 테이블을 만들지 않고 검사만 합니다(`ddl-auto: validate`).

**해결**

| 경우 | 해결 |
| --- | --- |
| 병합 **전** | 내 SQL 파일에 컬럼을 추가하고 [로컬 DB를 초기화](05-guide.md#3-로컬-db-초기화)합니다 |
| 병합 **후** | 새 migration(`ALTER TABLE ... ADD COLUMN ...`)을 추가합니다 → [05 가이드 1절 ⑦](05-guide.md#1-테이블-추가-순서) |

## B. DB 조회

### B1. 조회할 때 이력표가 없다고 나옴

**증상** 앱은 잘 떴는데, DB를 조회하면 이 에러가 납니다.

```text
Table 'donworry.flyway_schema_history' doesn't exist
```

**원인** 앱이 붙지 않은 **다른 DB**를 조회하고 있습니다. 예) 앱은 직접 설치 DB에 붙었는데 `docker compose exec`로 Docker DB를 조회.

**해결** [05 가이드 2절](05-guide.md#2-내-앱이-어느-db에-붙었는지-확인)로 앱이 붙은 DB를 확인하고, 그 DB에 접속해서 조회합니다.

### B2. 테이블 이름이 소문자로 보임

**증상** `SHOW TABLES` 결과가 `EVENT_PUBLICATION`이 아니라 `event_publication`입니다.

**원인** Windows용 MariaDB는 기본으로 테이블 이름을 소문자로 저장합니다 (`lower_case_table_names=1`).

**해결** 고칠 것 없습니다. 대소문자를 구분하지 않으므로 Modulith도 정상 동작합니다.
CI·`mariaDbTest`는 리눅스 컨테이너라 대문자(`EVENT_PUBLICATION`) 그대로입니다.

## C. Windows 명령어

### C1. mariadb 명령을 찾을 수 없음

**증상**

```text
'mariadb' 용어가 cmdlet, 함수, 스크립트 파일 또는 실행할 수 있는 프로그램 이름으로 인식되지 않습니다
```

**원인** MariaDB 실행 파일이 PATH(명령을 찾는 폴더 목록)에 없습니다.

**해결** 설치 폴더의 `bin`으로 이동해 `.\mariadb.exe`로 실행합니다 → [05 가이드 3-2](05-guide.md#3-2-직접-설치한-mariadb를-쓰는-경우-windows)

```powershell
cd "C:\Program Files\MariaDB 12.3\bin"        # 버전 폴더 이름은 PC마다 다름
.\mariadb.exe -u root -p -P 3307 -h 127.0.0.1
```

### C2. Unknown server host '127'

**증상**

```text
Unknown server host '127'
```

**원인** PowerShell이 `-h127.0.0.1`을 `-h127`과 `.0.0.1`로 쪼갰습니다.

**해결** 옵션과 값 사이를 띄웁니다: `-h 127.0.0.1`, `-P 3307`

## D. 테스트·CI

### D1. Docker 환경을 찾을 수 없음

**증상** `./gradlew mariaDbTest` 실행 시

```text
Could not find a valid Docker environment
```

**원인** Docker Desktop이 꺼져 있습니다.

**해결** Docker Desktop을 켜고 다시 실행합니다. `docker compose up`은 필요 없습니다 — 테스트가 일회용 MariaDB 컨테이너를 직접 띄우고 지웁니다 ([04 검사 3절](04-tests.md#3-testcontainers--db를-누가-띄우나)).

### D2. CI: 병합된 migration을 수정·삭제함

**증상** PR의 backend CI에서 `migration 불변·이름 규칙 검사` 단계가 실패합니다.

```text
병합된 migration을 수정/삭제했습니다. 되돌리고 새 migration을 추가하세요 (./gradlew newMigration -Pdesc=...)
```

**원인** dev에 이미 병합된 migration 파일을 내 브랜치에서 수정·삭제·이름 변경했습니다. 그대로 병합되면 그 파일을 이미 적용한 모든 PC의 DB가 [A3](#a3-적용된-migration-파일이-바뀜) 에러로 기동에 실패합니다.

**해결**

1. 에러 메시지에 나온 파일을 dev 상태로 되돌립니다

   ```bash
   git fetch origin
   git restore --source origin/dev -- backend/src/main/resources/db/migration/<파일명>.sql
   ```

2. 바꾸려던 내용은 새 migration으로 만듭니다

   ```bash
   cd backend
   ./gradlew newMigration -Pdesc=alter_ai_report_add_xxx
   ```

3. 내 로컬 DB에 고친 버전이 이미 적용됐다면 [로컬 DB를 초기화](05-guide.md#3-로컬-db-초기화)합니다 (안 하면 [A3](#a3-적용된-migration-파일이-바뀜) 에러)
4. 커밋·push하면 CI가 다시 돕니다

### D3. CI: migration 파일명 규칙 위반

**증상** PR의 backend CI에서 `migration 불변·이름 규칙 검사` 단계가 실패합니다.

```text
migration 파일명이 규칙과 다릅니다: V3__create_ai_report.sql (./gradlew newMigration -Pdesc=... 로 생성)
```

**원인** migration 파일 이름을 직접 지었습니다. 규칙은 `V<yyyyMMdd>_<HHmmss>__<snake_case>.sql`입니다 ([03 우리 설정 2절](03-how-we-use.md#2-파일-이름-규칙)).

**해결**

1. `newMigration`으로 올바른 이름의 파일을 만듭니다

   ```bash
   cd backend
   ./gradlew newMigration -Pdesc=create_ai_report_table
   ```

2. 잘못된 파일의 SQL을 새 파일에 옮기고, 잘못된 파일은 지웁니다 (아직 병합 전 파일이라 지워도 됩니다)
3. 내 로컬 DB에 잘못된 이름의 버전이 이미 적용됐다면 [로컬 DB를 초기화](05-guide.md#3-로컬-db-초기화)합니다
4. 커밋·push하면 CI가 다시 돕니다

## E. 무시해도 되는 경고

| 경고 | 뜻 |
| --- | --- |
| `Unknown system variable 'WSREP_ON'` | Flyway가 클러스터 기능 여부를 확인하다 남기는 경고입니다 |
| `Using MariaDB 12.x which is newer than the version Flyway has been verified with` | 공식 검증 버전보다 새 버전이라는 안내입니다. 다만 CI는 11.8로 검사하므로 새 버전 전용 문법은 쓰지 않습니다 |
