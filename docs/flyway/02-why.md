# 02. 왜 Flyway를 채택했나

[← Flyway 문서 목차](README.md)

결정 기록 원문(버린 대안 전체)은 [ADR-0011](../decisions/0011-flyway-migration-versioning.md)에 있습니다. 이 문서는 그 내용을 예시로 풀어 씁니다.

## 1. 전에는 어땠나 — `ddl-auto: update`

Flyway 전환(#23) 전에는 Hibernate가 Entity를 보고 **각자 PC의 DB를 직접 고쳤습니다** (`ddl-auto: update`).

```text
월  팀원 A: Entity에 status 필드 추가 → A의 DB에 컬럼 자동 추가
화  팀원 A: 필드 이름 status → state 로 변경
     → update는 컬럼을 지우지 않음 → A의 DB에는 status, state 둘 다 남음
수  팀원 B: 화요일 코드를 pull → B의 DB에는 state만 생김
     → 같은 코드인데 A의 DB와 B의 DB가 다름 (아무도 모름)
```

| 문제 | 설명 |
| --- | --- |
| 조용히 달라짐 | DB 변경이 코드 리뷰에 보이지 않고, PC마다 스키마가 달라진다 |
| 지우지 못함 | `update`는 컬럼·제약을 추가만 한다. 이름 변경·삭제는 흔적이 남는다 |
| 재현 불가 | "내 PC에선 되는데"가 생겨도 어떤 순서로 DB가 바뀌었는지 기록이 없다 |
| 운영 위험 | 운영 DB에서 Hibernate가 테이블을 바꾸는 것은 위험해서 실무에서는 쓰지 않는다 |

## 2. Flyway로 바꾸면

```text
팀원 A: V..__add_case_status.sql 커밋 → PR 리뷰에서 SQL이 보임
팀원 B: pull 후 bootRun → Flyway가 같은 SQL을 같은 순서로 실행
→ 모든 PC·CI·운영 DB가 같은 SQL 목록으로 만들어짐
```

- DB 변경이 **SQL 파일로 Git에 남아** 리뷰·이력 추적이 됩니다
- 누가 언제 받아도 **같은 순서로 같은 스키마**가 만들어집니다
- Hibernate는 검사만 하므로(`validate`) Entity와 SQL이 어긋나면 바로 알 수 있습니다

## 3. 왜 지금 바꿨나

아래는 도입 당시 배경입니다. 이후 #56에서 담당 ERD 기준 업무 테이블 14개를 테이블별 migration으로 일괄 생성했습니다. 현재 목록은 [ERD](../erd/README.md)를 봅니다.

- ERD Baseline v1(8개 테이블)이 나와 각 담당이 업무 테이블을 만들기 **직전**이었습니다
- 테이블이 하나도 없을 때 바꾸면 옮길 데이터가 없어 비용이 0입니다. 테이블이 쌓인 뒤에는 PC마다 다른 스키마를 맞추는 작업이 생깁니다

## 4. 왜 버전을 순번이 아니라 타임스탬프로 하나

순번(`V1`, `V2`, `V3` …)은 혼자 개발할 때는 단순하지만, 6명이 동시에 작업하면 충돌합니다.

```text
dev 최신이 V2인 상태에서
  팀원 A: V3__create_security_case.sql
  팀원 B: V3__create_ai_report.sql       ← 같은 번호
→ 둘 다 병합되면 Flyway가 "같은 버전 두 개"로 기동 실패
→ 늦게 병합하는 사람이 매번 파일 이름을 바꿔야 함 (잊으면 dev가 깨짐)
```

타임스탬프(**만든 시각**)를 버전으로 쓰면 초 단위까지 겹칠 일이 거의 없습니다.

```text
  팀원 A: V20261022_103012__create_security_case.sql
  팀원 B: V20261022_110544__create_ai_report.sql
```

1차 CakeShop도 같은 방식이었습니다. DonWorry는 여기에 두 가지를 더했습니다.

- 시각을 **한국 시간(`Asia/Seoul`)으로 고정**해서 찍습니다. CakeShop은 "실행한 PC의 시간대"라 클라우드·CI(기본 UTC)에서 만든 파일과 섞일 수 있었지만, DonWorry는 코드에 시간대를 적어 두어 누가 어디서 만들어도 같은 기준입니다
- 이름을 손으로 짓지 않고 `./gradlew newMigration`이 만들게 해서 형식 실수를 없앴습니다

## 5. 고려했지만 버린 대안 (요약)

| 대안 | 버린 이유 |
| --- | --- |
| 순번 `V1`, `V2` … | 동시 작업 시 번호 충돌, 병합 직전 파일 이름 수작업 |
| `ddl-auto: update` 유지, 나중에 전환 | 테이블이 쌓인 뒤 전환하면 PC마다 다른 스키마를 맞춰야 함 |
| 8개 테이블을 한 파일로 한 명이 작성 | 다른 담당 테이블의 타입을 한 명이 정하게 됨 (타입 미확정) |
| `baseline-on-migrate: true`로 기존 DB 그대로 수용 | 스키마 차이를 숨긴 채 이력만 찍고 넘어감. 개발 DB는 초기화가 더 안전 |
| CI에서 `services: mariadb`로 DB 띄우기 | 로컬에서 같은 검사를 하려면 DB를 따로 맞춰야 함. Testcontainers는 명령 하나로 로컬·CI가 같음 |

다음: [03 우리 설정](03-how-we-use.md)
