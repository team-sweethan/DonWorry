# Flyway 사용 가이드

DB 테이블은 **Flyway migration 파일로만** 만들고 바꿉니다. Entity만 만들어서는 테이블이 생기지 않습니다 (`ddl-auto: validate`).
규칙의 정본은 [AGENTS.md 4절](../AGENTS.md), 왜 이렇게 정했는지는 [ADR-0011](decisions/0011-flyway-migration-versioning.md)에 있습니다.

## 목차

1. [기동할 때 일어나는 일](#1-기동할-때-일어나는-일)
2. [테이블 추가 순서](#2-테이블-추가-순서)
3. [내 앱이 어느 DB에 붙었는지 확인](#3-내-앱이-어느-db에-붙었는지-확인)
4. [로컬 DB 초기화](#4-로컬-db-초기화)
5. [적용 결과 확인](#5-적용-결과-확인)
6. [문제 해결](#6-문제-해결)

## 1. 기동할 때 일어나는 일

```text
./gradlew bootRun
  ① Flyway     db/migration/*.sql 중 아직 적용 안 된 파일을 버전 순서대로 실행
               → 실행 기록을 flyway_schema_history 테이블에 남김
  ② Hibernate  Entity와 ①이 만든 테이블이 맞는지 검사만 함 (validate)
               → 다르면 기동 실패
```

| 검사 | 언제 | 무엇을 잡나 |
| --- | --- | --- |
| `bootRun` | 내 PC | SQL 오류, Entity ↔ 테이블 불일치 |
| `./gradlew mariaDbTest` | 내 PC (Docker 필요) · CI | 빈 MariaDB 11.8에 전체 migration 적용 + validate |
| CI `migration 불변·이름 규칙 검사` | PR | 병합된 migration 수정·삭제, 규칙 밖 파일명 |

## 2. 테이블 추가 순서

P4 `ai_report`를 예로 듭니다.

**① dev 최신화 → 이슈 브랜치**

```bash
git switch dev && git pull
git switch -c feature/31-ai-report-table
```

**② migration 파일 생성** — 파일 이름은 직접 짓지 않습니다.

```bash
cd backend
./gradlew newMigration -Pdesc=create_ai_report_table      # Windows: .\gradlew.bat newMigration -Pdesc=...
# created: src/main/resources/db/migration/V20261022_013012__create_ai_report_table.sql
```

설명(`-Pdesc`)은 소문자 `snake_case`만 됩니다. 버전은 생성 시각(UTC)이 자동으로 붙습니다.

**③ SQL 작성** — Notion 설계 3.9(ERD 정본)에 있는 **내 소유 테이블**만 씁니다.

```sql
CREATE TABLE ai_report
(
    report_id        BIGINT      NOT NULL AUTO_INCREMENT,
    case_id          BIGINT      NOT NULL,        -- P3 security_case 논리 참조라 FK 없음
    report_version   INT         NOT NULL,
    evidence_version INT         NOT NULL,
    status           VARCHAR(20) NOT NULL,
    report_body      JSON        NULL,
    created_at       DATETIME(6) NOT NULL,        -- UTC 저장
    completed_at     DATETIME(6) NULL,
    PRIMARY KEY (report_id),
    UNIQUE KEY uk_ai_report_case_version (case_id, report_version)
);
```

> 위 타입은 예시입니다. ID·금액·시각 물리 타입은 아직 미확정입니다 (`docs/payment/README.md` C1).

- 다른 담당 테이블로 FK를 걸지 않습니다. 같은 담당 테이블끼리만 FK를 겁니다
- ERD에 없는 컬럼·테이블은 3.9 합의 후 추가합니다
- 한 파일에는 하나의 스키마 전환만 넣습니다 (테이블 생성 + 그 테이블의 인덱스 정도)
- 팀 기준 MariaDB는 **11.8**입니다. 더 새 버전에서만 되는 문법을 쓰지 않습니다

**④ Entity 작성** — 테이블·컬럼 이름과 타입을 SQL에 맞춥니다.

**⑤ 로컬 기동**

```bash
./gradlew bootRun
```

로그에 이 줄이 나오면 적용된 것입니다.

```text
Migrating schema `donworry` to version "20261022.013012 - create ai report table"
```

**⑥ 검사 → PR**

```bash
./gradlew check mariaDbTest      # mariaDbTest는 Docker Desktop이 켜져 있어야 함
```

PR 본문의 "다른 담당에 영향"에 새 테이블을 적습니다.

**⑦ dev에 병합된 뒤에는 그 파일을 고치지 않습니다** — 바꿀 것이 있으면 새 migration을 만듭니다.

```bash
./gradlew newMigration -Pdesc=add_ai_report_attempt_count
```

```sql
ALTER TABLE ai_report ADD COLUMN attempt_count INT NOT NULL DEFAULT 0;
```

## 3. 내 앱이 어느 DB에 붙었는지 확인

한 PC에 **Docker MariaDB**와 **직접 설치한 MariaDB**가 둘 다 있으면, 엉뚱한 쪽을 초기화·조회하기 쉽습니다.
`bootRun` 로그의 Flyway 첫 줄에서 실제 DB 버전을 확인합니다.

```text
Database: jdbc:mariadb://localhost:3307/donworry (MariaDB 11.8)   ← Docker compose
Database: jdbc:mariadb://localhost:3307/donworry (MariaDB 12.3)   ← 직접 설치한 MariaDB (버전은 PC마다 다름)
```

| 로그 버전 | 붙은 DB | 초기화 방법 |
| --- | --- | --- |
| 11.8 | Docker compose | [4-1](#4-1-docker-compose를-쓰는-경우) |
| 그 외 | 직접 설치한 MariaDB | [4-2](#4-2-직접-설치한-mariadb를-쓰는-경우-windows) |

`backend/src/main/resources/application-local.yml`(개인 설정, Git 제외)이 있으면 그 접속 정보가 우선합니다.
둘 다 3307을 쓰면 어느 쪽에 붙을지 헷갈리므로, 쓰지 않는 쪽은 꺼 둡니다 (Docker 쪽은 `docker compose down`).

## 4. 로컬 DB 초기화

이럴 때 필요합니다.

- Flyway 전환(#23) **이전에** 앱을 띄운 적이 있다 (`Found non-empty schema` 에러)
- 아직 병합 전인 내 migration 파일을 고쳤다 (`checksum mismatch` 에러)

지금 로컬 DB에는 지워서 아쉬울 데이터가 없습니다. 테이블은 다음 `bootRun` 때 Flyway가 다시 만듭니다.

### 4-1. Docker compose를 쓰는 경우

```bash
docker compose down -v       # 저장소 루트에서. -v = 데이터 볼륨까지 삭제
docker compose up -d
```

### 4-2. 직접 설치한 MariaDB를 쓰는 경우 (Windows)

**① MariaDB 설치 폴더의 `bin`으로 이동해 root로 접속**

```powershell
cd "C:\Program Files\MariaDB 12.3\bin"        # 버전 폴더 이름은 PC마다 다름
.\mariadb.exe -u root -p -P 3307 -h 127.0.0.1
```

- 설치 폴더 찾기: `Get-ChildItem "C:\Program Files" -Directory -Filter "MariaDB*"`
- PowerShell은 현재 폴더의 프로그램도 `.\`를 붙여야 실행됩니다
- 옵션과 값 사이를 띄웁니다 (`-h 127.0.0.1`, `-P 3307`). 붙여 쓰면 [오류](#unknown-server-host-127)가 납니다
- `-P 3307`을 빼면 기본 포트 3306으로 붙으려 합니다
- 비밀번호는 MariaDB 설치 때 정한 root 비밀번호입니다
- 명령줄 대신 MariaDB와 함께 설치되는 **HeidiSQL**로 접속해도 됩니다

**② 남은 테이블 확인 후 DB 다시 만들기**

```sql
SHOW TABLES FROM donworry;      -- 지워도 되는 테이블인지 먼저 확인
DROP DATABASE donworry;
CREATE DATABASE donworry CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
exit
```

`donworry` 계정과 권한은 DB를 지워도 남아 있어서 다시 만들 필요가 없습니다.
`SHOW TABLES` 결과에 `event_publication` 말고 다른 테이블이 있으면, 지우기 전에 팀 채널에 공유합니다.

## 5. 적용 결과 확인

`bootRun`이 뜬 상태에서 **앱이 붙은 DB**에 접속해 확인합니다 ([3절](#3-내-앱이-어느-db에-붙었는지-확인)).

```sql
USE donworry;
SELECT version, description, success FROM flyway_schema_history;
SHOW TABLES;
```

```text
version          | description                    | success
20261021.215412  | create event publication table | 1

Tables_in_donworry
event_publication          ← Windows 직접 설치는 소문자로 보이는 게 정상
flyway_schema_history
```

Docker compose DB라면 저장소 루트에서 이렇게 조회합니다.

```bash
docker compose exec mariadb mariadb -udonworry -pdonworry donworry -e "SELECT version, description, success FROM flyway_schema_history; SHOW TABLES;"
```

## 6. 문제 해결

### `Found non-empty schema(s) donworry but no schema history table`

DB에 테이블은 있는데 Flyway 기록 테이블이 없습니다. 대개 Flyway 전환 전에 Modulith가 `event_publication`을 직접 만든 DB입니다.
→ [3절](#3-내-앱이-어느-db에-붙었는지-확인)로 붙은 DB를 확인하고 [4절](#4-로컬-db-초기화)로 초기화합니다.
`baseline-on-migrate`는 켜지 않습니다. 스키마 차이를 숨긴 채 넘어가기 때문입니다 (ADR-0011).

### `docker compose down -v` 했는데도 같은 에러

앱이 Docker가 아니라 **직접 설치한 MariaDB**에 붙어 있습니다. 로그의 `(MariaDB x.y)` 버전을 확인하고 [4-2](#4-2-직접-설치한-mariadb를-쓰는-경우-windows)로 초기화합니다.

### `Table 'donworry.flyway_schema_history' doesn't exist` (조회할 때)

앱이 붙지 않은 다른 DB를 조회하고 있습니다. 예) 앱은 직접 설치 DB에 붙었는데 `docker compose exec`로 Docker DB를 조회. [3절](#3-내-앱이-어느-db에-붙었는지-확인)을 봅니다.

### `Migration checksum mismatch for migration version ...`

이미 적용된 migration 파일 내용이 바뀌었습니다.

- 병합 **전** 내 파일을 고친 경우: 내 로컬 DB만 옛 버전을 기억하는 것이므로 [4절](#4-로컬-db-초기화)로 초기화
- dev에 병합된 파일을 고친 경우: 수정을 되돌리고 새 migration을 만듭니다 (CI에서도 막힘)

### `Schema-validation: missing column [xxx] in table [yyy]`

Entity에는 있는데 migration SQL에 그 컬럼이 없습니다. 병합 전이면 SQL을 고치고 DB 초기화, 병합 후면 새 migration으로 추가합니다.

### `'mariadb' 용어가 cmdlet ... 으로 인식되지 않습니다`

MariaDB 실행 파일이 PATH에 없습니다. 설치 폴더의 `bin`으로 이동해 `.\mariadb.exe`로 실행합니다 ([4-2](#4-2-직접-설치한-mariadb를-쓰는-경우-windows)).

### `Unknown server host '127'`

PowerShell이 `-h127.0.0.1`을 `-h127`과 `.0.0.1`로 쪼갰습니다. `-h 127.0.0.1`처럼 띄어 씁니다.

### 테이블 이름이 소문자로 보임 (`event_publication`)

Windows용 MariaDB는 기본으로 테이블 이름을 소문자로 저장합니다 (`lower_case_table_names=1`). 대소문자를 구분하지 않으므로 Modulith도 정상 동작합니다.
CI·`mariaDbTest`는 리눅스 컨테이너라 대문자(`EVENT_PUBLICATION`) 그대로입니다.

### `mariaDbTest`가 `Could not find a valid Docker environment`

Docker Desktop이 꺼져 있습니다. 켜고 다시 실행합니다. `docker compose up`은 필요 없습니다 — 테스트가 일회용 MariaDB 컨테이너를 직접 띄우고 지웁니다.

### 무시해도 되는 경고

- `Unknown system variable 'WSREP_ON'` — Flyway가 클러스터 기능 여부를 확인하다 남기는 경고입니다
- `Using MariaDB 12.x which is newer than the version Flyway has been verified with` — 공식 검증 버전보다 새 버전이라는 안내입니다. 다만 CI는 11.8로 검사하므로 새 버전 전용 문법은 쓰지 않습니다
