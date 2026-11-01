# P6 정기 지출 관리 API와 ERD

관련 #231. 4단계 백엔드 범위이며 화면은 기존 목업을 유지한다.
`settlement.owner-http.enabled=true`에서 등록한다. 기존 P6 사장님 접근 검사로 인증된 가맹점만 사용하며 요청의 merchantId는 사용하지 않는다.
최초 잔액 설정 이후 등록할 수 있고 시작일은 한국 날짜 오늘 이후, 잔액 기준일 이후여야 한다.

## API

공통 경로: `/api/v1/settlements/cash-flow/recurring-expenses`

| 메서드 | 하위 경로 | 결과 |
| --- | --- | --- |
| GET | 없음 | 소유 규칙 목록, 중단 규칙 포함 |
| POST | 없음 | 규칙 등록, 201 |
| DELETE | `/{id}` | 오늘부터 발생 중단, 204. 반복 중단은 동일 결과 |
| GET | `/occurrences?from=YYYY-MM-DD&to=YYYY-MM-DD` | 기간 내 미완료 예정 회차와 실제 완료 지출 |
| PUT | `/{id}/occurrences/{date}/completion` | 원래 예정일 회차의 실제 지출 완료. 본문 없음 |

등록 본문: `title`, `amount`, `category`, `memo`(선택), `frequency`, `startDate`, `endDate`(선택).
주기는 DAILY/WEEKLY/MONTHLY, 분류는 MATERIAL/RENT/PAYROLL/UTILITIES/OTHER.
제목 1~100자, 메모 최대 500자, 금액은 원 단위 양수이며 JavaScript 안전 정수 상한 이하다.
종료일은 시작일 이후이고 포함한다. 매주는 시작일과 같은 요일, 매달은 시작일의 일자를 사용한다.
31일 규칙은 짧은 달에만 말일로 보정하고 다음 달에는 다시 31일을 사용한다.

회차 응답: `recurringExpenseId`, `title`, `expenseDate`, `amount`, `category`, `memo`, `occurrencePaymentId`, `paidAt`, `paidDate`.
미완료는 마지막 세 필드가 null이다. 완료는 최초 완료 ID·시각과 저장한 금액을 반환한다.
`expenseDate`는 원래 예정일, `paidAt`은 실제 완료 UTC 시각, `paidDate`는 한국 날짜다.
미래 회차를 오늘 미리 지출할 수 있다. 그 회차는 오늘 실제 내역에서만 조회하고 미래 예정액에 다시 포함하지 않는다.
중단 후에도 이미 완료한 회차 재전달은 성공하며 최초 결과를 유지한다. 아직 완료하지 않은 중단일 이후 회차는 409다.

기간 양끝 포함 최대 366일, 반환 최대 10,000건이다. 초과는 422로 기간 축소를 요청한다.
이미 다른 날 완료한 회차는 확장 전에 제외하며, 완료 이력 자체의 개수로 예정 조회를 거절하지 않는다.
규칙별 완료 예정일 조회는 UNIQUE 규칙·예정일과 366일 기간 제한으로 최대 366개이며, 완료 지출은 실제 지출일 기준으로 별도 조회한다.
조회 기준은 미완료의 예정일과 완료의 실제 한국 지출일이다. 규칙 중단 후 과거 완료 내역도 계속 조회한다.
예정일이 지났다는 이유로 자동 완료하지 않는다. 완료 취소·금액 수정·소급 입력은 이번 범위에서 제공하지 않는다.
규칙 변경은 기존 규칙 중단 후 새 규칙 등록으로 처리한다. 일회성 지출로 이미 입력한 같은 비용은 의미상 자동 중복 제거하지 않으므로 중복 입력에 주의한다.
응답은 no-store, 잘못된 입력 400, 타 가맹점·없는 규칙 404, 최초 설정 없음/무효 회차 409, DB 장애 503이다.

## ERD

신규 `V20261030_181043__create_recurring_expenses_with_payments.sql`만 추가한다. 이미 공유된 SQL은 수정하지 않는다.

### recurring_expenses — 규칙

| 컬럼 | 타입 | NULL | 의미 |
| --- | --- | --- | --- |
| recurring_expense_id | BIGINT AUTO_INCREMENT | N | PK |
| merchant_id | BIGINT | N | P1 논리 참조, 물리 FK 없음 |
| title | VARCHAR(100) | N | 지출명 |
| amount | BIGINT | N | 회차당 원 단위 양수 |
| category | VARCHAR(30) | N | 분류 |
| memo | VARCHAR(500) | Y | 메모 |
| frequency | VARCHAR(30) | N | DAILY/WEEKLY/MONTHLY |
| start_date | DATE | N | 한국 날짜 시작일 |
| end_date | DATE | Y | 마지막 발생일 |
| stopped_from | DATE | Y | 이 날짜부터 발생 중단 |
| created_at / updated_at | DATETIME(6) | N | UTC |

### recurring_expense_payments — 완료 회차

| 컬럼 | 타입 | NULL | 의미 |
| --- | --- | --- | --- |
| payment_id | BIGINT AUTO_INCREMENT | N | P6 지출 완료 PK. P5 결제 ID와 무관 |
| recurring_expense_id | BIGINT | N | 규칙 참조, P6 내부 물리 FK |
| merchant_id | BIGINT | N | 소유 가맹점, 논리 참조 |
| occurrence_date | DATE | N | 원래 발생 예정일 |
| title / amount / category / memo | 규칙과 동일 | memo만 Y | 완료 당시 값 복사 |
| paid_at | DATETIME(6) | N | 최초 실제 완료 UTC 시각, 변경하지 않음 |

`UNIQUE(recurring_expense_id, occurrence_date)`로 같은 회차의 중복 완료를 방지한다.
규칙 가맹점·시작일, 완료 가맹점·예정일/완료 시각 인덱스로 기간 조회한다.
확정된 자동 실행이나 출금·은행 연동 기능이 아니라 사용자가 실제 지출을 기록하는 기능이다.
