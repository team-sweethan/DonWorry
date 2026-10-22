# 03. 우리 프로젝트의 Flyway 설정

[← Flyway 문서 목차](README.md)

## 1. `application.yml` 설정

`backend/src/main/resources/application.yml`에서 Flyway와 관련된 부분입니다.

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true
    locations: classpath:db/migration
    validate-on-migrate: true
    clean-disabled: true
    out-of-order: true
  modulith:
    events:
      jdbc:
        schema-initialization:
          enabled: false
```

| 설정 | 뜻 | 없으면 생기는 일 |
| --- | --- | --- |
| `ddl-auto: validate` | Hibernate는 Entity ↔ 테이블 일치를 **검사만** 한다 | `update`면 Hibernate가 DB를 조용히 바꿔 PC마다 스키마가 달라짐 ([02](02-why.md)) |
| `flyway.enabled: true` | 앱이 뜰 때 Flyway를 실행한다 | 테이블이 하나도 안 생김 |
| `locations: classpath:db/migration` | migration 파일을 찾는 폴더 = `src/main/resources/db/migration/` | — (기본값과 같지만 명시) |
| `validate-on-migrate: true` | 실행 전에 이미 적용된 파일의 체크섬을 비교한다 | 적용된 파일을 고쳐도 모르고 넘어감 |
| `clean-disabled: true` | `flyway clean`(모든 테이블 삭제) 명령을 막는다 | 실수 한 번으로 DB가 통째로 지워질 수 있음 |
| `out-of-order: true` | 이미 적용된 버전보다 **낮은 버전**의 파일도 실행한다 | 아래 예시처럼 늦게 병합된 파일이 무시됨 |
| `modulith ... schema-initialization.enabled: false` | Modulith가 이벤트 테이블을 직접 만들지 않는다 | Flyway 이력 밖의 테이블이 생겨 Flyway 첫 적용과 충돌 |

### `out-of-order`가 필요한 이유

타임스탬프는 **파일을 만든 시각**이라, 병합 순서와 다를 수 있습니다.

```text
10:00  팀원 A가 V..1000__a.sql 생성 (PR 리뷰가 길어짐)
11:00  팀원 B가 V..1100__b.sql 생성 → 먼저 dev 병합 → 모두의 DB에 적용
13:00  A의 PR 병합 → V..1000 은 이미 적용된 V..1100 보다 낮은 버전
       out-of-order: false → "적용 안 된 낮은 버전" 오류로 기동 실패
       out-of-order: true  → 그냥 실행
```

단, 실행 **순서**까지 보장하지는 않습니다. 서로 의존하는 migration(테이블 생성 → 그 테이블에 컬럼 추가)은 **같은 PR**에 넣습니다.

## 2. 파일 이름 규칙

```text
V20261022_013012__create_ai_report_table.sql
│└──────┬──────┘ │└─────────┬──────────┘
│     버전       │         설명
접두어 V         밑줄 두 개(__)로 버전과 설명을 구분
```

| 부분 | 규칙 |
| --- | --- |
| 버전 | 파일을 **만든 시각(UTC)** `yyyyMMdd_HHmmss`. Flyway는 `_`를 `.`으로 읽어 `20261022.013012`로 표시 |
| 설명 | 소문자 `snake_case`. 로그에서는 `_`가 공백으로 보임 (`create ai report table`) |

- UTC를 쓰는 이유: PC 시간대 설정이 달라도 같은 순서가 되고, ERD 공통 규칙(시각은 UTC 저장)과도 맞습니다
- 파일 이름은 **직접 짓지 않습니다**. 아래 `newMigration`으로 만듭니다. 규칙 밖의 이름은 CI가 막습니다 ([04](04-tests.md#4-ci-migration-검사))

## 3. `newMigration` 태스크

```bash
cd backend
./gradlew newMigration -Pdesc=create_ai_report_table      # Windows: .\gradlew.bat newMigration -Pdesc=...
# created: src/main/resources/db/migration/V20261022_013012__create_ai_report_table.sql
```

`backend/build.gradle`에 있는 태스크이며, 이렇게 동작합니다.

1. `-Pdesc`가 비었거나 소문자 `snake_case`가 아니면 실패
2. 현재 UTC 시각으로 버전을 만들고, 같은 초의 파일이 이미 있으면 1초씩 미룸
3. 아래 머리말이 들어간 빈 SQL 파일을 만듦

```sql
-- create_ai_report_table
-- 생성: 2026-10-22 01:30:12 UTC
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다
```

## 4. 첫 migration — `EVENT_PUBLICATION`

현재 저장소의 유일한 migration은 `V20261021_215412__create_event_publication_table.sql`입니다.

- Spring Modulith가 모듈 간 이벤트를 "보냈다 / 처리됐다"로 기록하는 테이블입니다 (이벤트 유실 시 재발행용)
- 원래는 Modulith가 기동 때 직접 만들었지만, Flyway 이력 밖의 테이블이 되지 않도록 migration으로 옮겼습니다. Modulith 2.1.1이 제공하는 MariaDB용 정의와 같습니다
- 이름이 **대문자**인 이유: Modulith가 `EVENT_PUBLICATION`으로 조회하고, 리눅스의 MariaDB(CI·운영)는 테이블 이름의 대소문자를 구분합니다
  - Windows MariaDB에서 소문자 `event_publication`으로 보이는 것은 정상입니다 ([06 B2](06-troubleshooting.md#b2-테이블-이름이-소문자로-보임))

다음: [04 검사](04-tests.md)
