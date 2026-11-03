# 05. Flyway 작업 가이드

[← Flyway 문서 목차](README.md)

실제로 테이블을 추가하고, 로컬 DB를 확인·초기화하는 순서입니다. 각 설정이 왜 이런지는 [03 우리 설정](03-how-we-use.md)을 봅니다.

## 목차

1. [테이블 추가 순서](#1-테이블-추가-순서)
2. [내 앱이 어느 DB에 붙었는지 확인](#2-내-앱이-어느-db에-붙었는지-확인)
3. [로컬 DB 초기화](#3-로컬-db-초기화)
4. [적용 결과 확인](#4-적용-결과-확인)
5. [기존 스키마 확인하는 법](#5-기존-스키마-확인하는-법)
6. [작성 기준](#6-작성-기준)

## 1. 테이블 추가 순서

P4 `ai_reports`를 예로 듭니다. (1차 MVP 테이블은 이미 만들어져 있습니다. 새 테이블을 추가할 때 같은 순서를 따릅니다)

**① dev 최신화 → 이슈 브랜치**

```bash
git switch dev && git pull
git switch -c feature/31-ai-report-table
```

시작 전에 두 가지를 확인합니다.

- 그 테이블이 이미 있거나 다른 migration이 바꾼 적이 있는지 → [5절](#5-기존-스키마-확인하는-법)
- [ERD 문서](../erd/README.md)에서 내 소유 테이블·컬럼인지. 문서에 없으면 추측해서 만들지 않고 팀에 확인합니다

**② migration 파일 생성** — 파일 이름은 직접 짓지 않습니다.

```bash
cd backend
./gradlew newMigration -Pdesc=create_ai_reports_table     # Windows: .\gradlew.bat newMigration -Pdesc=...
# created: src/main/resources/db/migration/V20261022_103012__create_ai_reports_table.sql
```

설명(`-Pdesc`)은 소문자 `snake_case`만 됩니다. 버전은 생성 시각(한국 시간)이 자동으로 붙습니다.

**③ SQL 작성** — Notion 팀페이지 / ERD(정본)에 있는 **내 소유 테이블**만 씁니다.

```sql
CREATE TABLE ai_reports
(
    report_id        BIGINT      NOT NULL AUTO_INCREMENT,
    case_id          BIGINT      NOT NULL,        -- P3 security_cases 논리 참조라 FK 없음
    report_version   INT         NOT NULL,
    evidence_version INT         NOT NULL,
    status           VARCHAR(20) NOT NULL,
    report_body      JSON        NULL,
    created_at       DATETIME(6) NOT NULL,        -- UTC 저장
    completed_at     DATETIME(6) NULL,
    PRIMARY KEY (report_id),
    CONSTRAINT uk_ai_reports_case_version UNIQUE (case_id, report_version)
);
```

> 테이블 이름 · PK · 타입 · 제약 이름 규칙은 [ERD 1절 공통 규칙](../erd/README.md#공통-규칙)을 따릅니다.

- 다른 담당 테이블로 FK를 걸지 않습니다. 같은 담당 테이블끼리만 FK를 겁니다
- ERD에 없는 컬럼·테이블은 Notion 담당 페이지 합의 후 추가합니다
- 한 파일에는 하나의 스키마 전환만 넣습니다 (테이블 생성 + 그 테이블의 인덱스 정도)
- 팀 기준 MariaDB는 **11.8**입니다. 더 새 버전에서만 되는 문법을 쓰지 않습니다

**④ Entity 작성** — 테이블·컬럼 이름과 타입을 SQL에 맞춥니다.

**⑤ 로컬 기동**

```bash
./gradlew bootRun
```

로그에 이 줄이 나오면 적용된 것입니다.

```text
Migrating schema `donworry` to version "20261022.103012 - create ai report table"
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
ALTER TABLE ai_reports ADD COLUMN attempt_count INT NOT NULL DEFAULT 0;
```

## 2. 내 앱이 어느 DB에 붙었는지 확인

한 PC에 **Docker MariaDB**와 **직접 설치한 MariaDB**가 둘 다 있으면, 엉뚱한 쪽을 초기화·조회하기 쉽습니다.
`bootRun` 로그의 Flyway 첫 줄에서 실제 DB 버전을 확인합니다.

```text
Database: jdbc:mariadb://localhost:3307/donworry (MariaDB 11.8)   ← Docker compose
Database: jdbc:mariadb://localhost:3307/donworry (MariaDB 12.3)   ← 직접 설치한 MariaDB (버전은 PC마다 다름)
```

| 로그 버전 | 붙은 DB | 초기화 방법 |
| --- | --- | --- |
| 11.8 | Docker compose | [3-1](#3-1-docker-compose를-쓰는-경우) |
| 그 외 | 직접 설치한 MariaDB | [3-2](#3-2-직접-설치한-mariadb를-쓰는-경우-windows) |

`backend/src/main/resources/application-local.yml`(개인 설정, Git 제외)이 있으면 그 접속 정보가 우선합니다.
둘 다 3307을 쓰면 어느 쪽에 붙을지 헷갈리므로, 쓰지 않는 쪽은 꺼 둡니다 (Docker 쪽은 `docker compose down`).

## 3. 로컬 DB 초기화

이럴 때 필요합니다.

- Flyway 전환(#23) **이전에** 앱을 띄운 적이 있다 (`Found non-empty schema` 에러 → [06 A1](06-troubleshooting.md#a1-기존-테이블이-있는데-flyway-이력이-없음))
- 아직 병합 전인 내 migration 파일을 고쳤다 (`checksum mismatch` 에러 → [06 A3](06-troubleshooting.md#a3-적용된-migration-파일이-바뀜))

지금 로컬 DB에는 지워서 아쉬울 데이터가 없습니다. 테이블은 다음 `bootRun` 때 Flyway가 다시 만듭니다.

### 3-1. Docker compose를 쓰는 경우

```bash
docker compose down -v       # 저장소 루트에서. -v = 데이터 볼륨까지 삭제
docker compose up -d
```

### 3-2. 직접 설치한 MariaDB를 쓰는 경우 (Windows)

**① MariaDB 설치 폴더의 `bin`으로 이동해 root로 접속**

```powershell
cd "C:\Program Files\MariaDB 12.3\bin"        # 버전 폴더 이름은 PC마다 다름
.\mariadb.exe -u root -p -P 3307 -h 127.0.0.1
```

- 설치 폴더 찾기: `Get-ChildItem "C:\Program Files" -Directory -Filter "MariaDB*"`
- PowerShell은 현재 폴더의 프로그램도 `.\`를 붙여야 실행됩니다
- 옵션과 값 사이를 띄웁니다 (`-h 127.0.0.1`, `-P 3307`). 붙여 쓰면 [오류](06-troubleshooting.md#c2-unknown-server-host-127)가 납니다
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

## 4. 적용 결과 확인

`bootRun`이 뜬 상태에서 **앱이 붙은 DB**에 접속해 확인합니다 ([2절](#2-내-앱이-어느-db에-붙었는지-확인)).

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

## 5. 기존 스키마 확인하는 법

현재 스키마의 기준은 **migration 파일 전체를 버전 순서대로 적용한 결과**입니다. 처음 `CREATE` 파일만 보면 이후 `ALTER`로 바뀐 내용을 놓칩니다.

### 5-1. 파일에서 찾기

테이블 이름이 나오는 migration 파일을 모두 찾습니다. 파일 이름 순서 = 버전 순서입니다.

```powershell
# Windows PowerShell (저장소 루트에서)
Select-String -Path backend\src\main\resources\db\migration\*.sql -Pattern "ai_reports" -List | Select-Object Path
```

```bash
# bash
grep -l "ai_reports" backend/src/main/resources/db/migration/*.sql
```

찾은 파일을 위에서부터 읽으며 `CREATE TABLE` → `ALTER TABLE`(컬럼 추가·변경·삭제) → 인덱스 순으로 합쳐서 봅니다.

### 5-2. 실제 DB에서 보기

앱이 붙은 DB([2절](#2-내-앱이-어느-db에-붙었는지-확인))에 접속해 최종 모양을 바로 봅니다.

```sql
USE donworry;
SHOW TABLES;
SHOW CREATE TABLE ai_reports;   -- 컬럼·타입·제약·인덱스가 모두 나옴
```

내 로컬 DB에는 **내 브랜치의 병합 전 migration**도 적용돼 있을 수 있습니다. dev 기준 모양은 5-1(파일)로 확인합니다.

### 5-3. 설계에서 보기

어떤 테이블·컬럼이 **있어야 하는지**(설계)는 [ERD 문서](../erd/README.md)에서 봅니다. 실제로 **있는 것**(5-1·5-2)과 다르면 아직 migration이 추가되지 않은 것입니다.

## 6. 작성 기준

1절 ③의 규칙(내 소유 테이블만, 다른 담당 FK 금지, MariaDB 11.8 문법)에 더해 아래를 지킵니다.

### 6-1. 한 파일에 한 변경 — MariaDB DDL은 자동 커밋

MariaDB에서 `CREATE` · `ALTER` · `DROP` 같은 DDL(테이블 구조 변경 SQL)은 실행되는 즉시 **자동 커밋**되어 되돌릴 수 없습니다.

```text
V..__change.sql
  ① ALTER TABLE a ADD COLUMN x ...   → 성공, 즉시 커밋
  ② ALTER TABLE b ADD COLUMN y ...   → 실패
  ③ CREATE INDEX ...                 → 실행 안 됨
결과: ①만 적용된 어중간한 상태. 파일을 고쳐 다시 돌리면 ①이 "이미 있는 컬럼"으로 또 실패
```

- 한 파일에는 서로 떼어 낼 수 없는 변경 하나만 넣습니다 (예: 테이블 생성 + 그 테이블의 인덱스)
- 병합 **전** 내 로컬에서 실패했다면: SQL을 고치고 [로컬 DB를 초기화](#3-로컬-db-초기화)한 뒤 다시 기동합니다
- dev에 **병합된** 파일이 다른 환경에서 실패했다면: 혼자 고치지 말고 팀에 공유합니다 (병합된 파일은 수정 금지)

### 6-2. 데이터가 있는 테이블을 바꿀 때

지금은 업무 데이터가 없지만, 시연 데이터가 쌓인 뒤에는 아래를 주의합니다.

| 변경 | 주의 |
| --- | --- |
| `NOT NULL` 컬럼 추가 | 기존 행에 넣을 값이 없어 실패합니다. `DEFAULT`를 주거나, NULL 허용으로 추가 → 값 채우기 → `NOT NULL`로 변경을 나눠서 합니다 |
| 컬럼 이름 변경·삭제 | 그 컬럼을 쓰는 Entity·다른 담당 코드가 깨집니다. 새 컬럼 추가 → 코드 전환 → 옛 컬럼 삭제 순서로 나눕니다 |
| 타입 축소 (`VARCHAR(100)` → `VARCHAR(50)`) | 기존 값이 잘리거나 실패합니다. 먼저 데이터를 확인합니다 |

### 6-3. 작성 후 확인 목록

- [ ] 파일은 `newMigration`으로 만들었다 (이름을 직접 짓지 않음)
- [ ] Entity의 테이블·컬럼 이름과 타입이 SQL과 같다
- [ ] `./gradlew check mariaDbTest` 통과 (Docker Desktop 필요)
- [ ] [ERD 문서](../erd/README.md)를 같은 PR에서 갱신했다
- [ ] PR 본문 "다른 담당에 영향"에 새 테이블·컬럼을 적었다

에러가 나면: [06 문제 해결](06-troubleshooting.md)
