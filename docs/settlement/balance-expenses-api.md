# P6 최초 잔액·일회성 지출 API (#229)

2026-10-30 사용자 승인 범위. 최초 잔액 불변과 실제 지출 완료를 서버에 적용한다.
이 문서는 P6 ERD 추가안이기도 하며 Notion 동기화·팀 전체 ERD 합의 완료를 뜻하지 않는다.

## ERD

| 테이블 | 주요 컬럼·제약 |
| --- | --- |
| cash_flow_settings | setting_id BIGINT PK, merchant_id BIGINT UNIQUE 논리 참조, opening_balance BIGINT(0 이상), base_date DATE, created_at/updated_at UTC DATETIME(6) |
| planned_expenses | expense_id BIGINT PK, merchant_id BIGINT 논리 참조, title VARCHAR(100), expense_date DATE(예정일), amount BIGINT(양수), category VARCHAR(30), memo VARCHAR(500) NULL, paid_at UTC DATETIME(6) NULL, created_at/updated_at UTC DATETIME(6), deleted_at UTC DATETIME(6) NULL |

금액 상한은 JavaScript 안전 정수 9007199254740991원이다. 가맹점 FK는 만들지 않는다. 최초 잔액은 설정 후 수정하지 않는다.
최초 설정은 독립 REQUIRES_NEW·READ_COMMITTED 트랜잭션에서 처리한다. 기본 REPEATABLE_READ의 이전 조회 스냅샷과 동시 INSERT 충돌을 피하고, UNIQUE·no-op INSERT·현재 행 잠금 조회로 한 값을 확정한다. 호출자의 외부 트랜잭션 롤백에 설정 저장을 묶지 않는다.
지출 category는 MATERIAL/RENT/PAYROLL/UTILITIES/OTHER다. 완료 지출의 삭제·금액 수정은 금지하고 paid_at/deleted_at 동시 존재도 DB 제약으로 차단한다.
두 테이블은 신규 migration으로 생성한다. 기존 공유 migration·개인 DB는 변경하지 않는다. 예전 로컬 초안 SQL 두 개와 중복 적용하지 않는다.

## API

`/api/v1/settlements/cash-flow` 이하. `settlement.owner-http.enabled=true`일 때만 등록한다.
CurrentStaff의 OPERATOR·FINANCE_VIEW·가맹점 ID를 검증한다. 요청 가맹점 ID로 대상 변경 불가. 응답은 no-store다.

| 메서드 | 경로 | 동작 |
| --- | --- | --- |
| GET | /settings | 최초 잔액·기준일, 미설정 404 |
| PUT | /settings | openingBalance/baseDate. 최초 기준일은 한국 시간 오늘. 같은 내용 재전달은 허용, 다른 내용은 409 SETTLEMENT_SETTING_IMMUTABLE |
| GET | /expenses?from=&to= | 한국 날짜 양 끝 포함 최대 366일. 미완료는 예정일, 완료는 실제 완료일로 조회 |
| POST | /expenses | title/expenseDate/amount/category/memo. 최초 잔액 설정 후 기준일 이후 예정 지출 생성 |
| PUT | /expenses/{id} | 미완료 지출 수정 |
| DELETE | /expenses/{id} | 미완료 지출 소프트 삭제 |
| PUT | /expenses/{id}/completion | 서버 현재 시각으로 완료. 반복 요청은 원래 시각 보존. 삭제 건은 404 |

없는 지출·다른 가맹점 지출은 404, 완료 지출 수정·삭제는 409다. 예정일이 지나도 자동 완료하지 않는다.
완료 시각은 서버가 기록하며 클라이언트가 paidAt을 지정하지 않는다. 실제 은행 출금 확인이 아닌 사장님 수동 기록이다.
완료 취소·과거 실제 지출 시각 소급·수정 이력 원장·정기 지출·보유 잔액 계산·예측·화면 교체는 후속이다.
새 POST는 별도 지출을 생성한다. 생성 요청 재전송의 멱등 키는 이번 계약에 없다. 완료 PUT은 멱등이다.

## 검증

단위: 잔액 불변·완료 멱등·완료 수정/삭제 차단·타 가맹점 차단·입력 검증.
격리 MariaDB: 최초 설정 동시성, 실제/예정일 구분, 지출 소유권·금액 보존. 실행 결과는 PR에 별도 기록한다.
