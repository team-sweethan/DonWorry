# 01. Flyway 개념

[← Flyway 문서 목차](README.md)

## 1. 한 문장으로

**Flyway는 DB 스키마(테이블 구조)용 git입니다.**

| git | Flyway |
| --- | --- |
| 커밋 하나 | migration 파일 하나 (`V..__create_ai_report_table.sql`) |
| 커밋 순서 | 파일 이름의 버전 순서 |
| `git log` (무엇이 언제 반영됐나) | `flyway_schema_history` 테이블 (적용 이력표) |
| 이미 푸시한 커밋은 고치지 않는다 | 이미 적용된 migration은 고치지 않는다 |

## 2. migration 파일

`backend/src/main/resources/db/migration/` 아래의 SQL 파일입니다.

```text
db/migration/
├── V20261021_215412__create_event_publication_table.sql   ← 이미 적용됨
└── V20261022_013012__create_ai_report_table.sql           ← 새로 추가 → 다음 기동 때 실행
```

- 파일 이름 = `V` + **버전** + `__`(밑줄 두 개) + **설명** + `.sql`
- 앱이 뜰 때 Flyway는 **아직 실행하지 않은 파일만**, 버전 순서대로 **딱 한 번** 실행합니다
- 같은 파일을 두 번 실행하지 않으므로 `CREATE TABLE IF NOT EXISTS` 같은 방어 문법이 필요 없습니다

## 3. 적용 이력표 `flyway_schema_history`

Flyway가 DB 안에 스스로 만드는 테이블입니다. "어느 파일을 언제 실행했고 성공했는가"를 기록합니다.

```text
version          | description                    | checksum    | success
20261021.215412  | create event publication table | -1234567890 | 1
```

다음 기동 때 Flyway는 이 표와 폴더 안 파일 목록을 비교해서, **표에 없는 파일만** 실행합니다.

```text
폴더:   V..215412  V..013012
이력표: V..215412
           └─ 같음 → 건너뜀
                       └─ 이력표에 없음 → 실행 후 이력표에 추가
```

## 4. 체크섬 — 적용된 파일을 고치면 왜 막히나

체크섬은 **파일 내용으로 계산한 지문(숫자)** 입니다. 한 글자만 바뀌어도 값이 달라집니다.
Flyway는 실행할 때 지문을 이력표에 저장하고, 다음 기동 때 파일의 지문과 비교합니다.

```text
팀원 A의 DB: 원래 파일로 테이블 생성, 지문 111 저장
내가 파일 수정 → 지문 222
팀원 A가 pull 후 기동 → "저장된 111 ≠ 현재 222" → 기동 실패 (checksum mismatch)
```

수정한 내용은 **이미 테이블을 만든 DB에는 반영되지 않습니다**. 파일만 바뀌고 DB는 그대로라 서로 어긋나므로, Flyway가 일부러 기동을 막습니다.
그래서 바꿀 것이 있으면 기존 파일을 고치지 않고 **새 migration**(`ALTER TABLE ...`)을 추가합니다.

## 5. 앱이 뜰 때 일어나는 일

```text
./gradlew bootRun
  ① Flyway     db/migration/*.sql 중 아직 적용 안 된 파일을 버전 순서대로 실행
               → 실행 기록을 flyway_schema_history 테이블에 남김
  ② Hibernate  Entity와 ①이 만든 테이블이 맞는지 검사만 함 (validate)
               → 다르면 기동 실패
```

- 테이블을 **만드는** 쪽은 Flyway뿐입니다
- Hibernate(JPA)는 Entity와 테이블이 일치하는지 **검사만** 합니다 (`ddl-auto: validate`)
- 그래서 Entity에 필드를 추가하고 SQL을 안 쓰면, 테이블이 바뀌지 않고 ②에서 기동이 실패합니다

다음: [02 채택 이유](02-why.md)
