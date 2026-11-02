# P6 현금흐름 예측 API — 5단계

관련 #230, #231. 기존 DB의 읽기·계산만 수행하며 신규 migration은 없다.
실제 은행 잔액·미발생 주문 매출·AI 예측·자동 출금·시간 단위 부족 예측은 제공하지 않는다.

## 조회와 인증

`settlement.owner-http.enabled=true`에서 기존 사장님 세션 인가를 사용한다.
OPERATOR와 FINANCE_VIEW 권한을 가진 인증 가맹점만 조회하며 merchantId 입력은 사용하지 않는다.

- `GET /api/v1/settlements/cash-flow/forecast?from=YYYY-MM-DD&to=YYYY-MM-DD`
- `GET /api/v1/settlements/cash-flow/shortages?from=YYYY-MM-DD&to=YYYY-MM-DD` — 동일 계산의 shortages 배열

날짜는 한국 시간, 양끝 포함 최대 366일이다. from은 최초 잔액 기준일 이후이며 to는 한국 날짜 오늘+365일까지다.
기준일이 오래되어도 과거 누적 합계 조회에는 366일 제한을 적용하지 않는다.
최초 설정 없으면 409, 잘못된 날짜·기간은 400, 계산 상세 대상 10,000건 초과 또는 금액 범위 초과는 422다.
DB 장애는 503, 성공·오류 모두 no-store다. 한도 초과 시 일부만 반환하거나 합계를 잘라내지 않는다.
예정 정산 후보(연체 포함)는 최대 10,000건이며, 후보 한도 초과는 기간 축소만으로 해결되지 않을 수 있다.

## 잔액과 시간 기준

- 현재 잔액 = 최초 잔액 + 기준일 시작부터 asOf 직전까지 COMPLETED 정산금 - 실제 완료 일회성·정기 지출.
- 완료 정산은 completedAt, 완료 지출은 paidAt을 한국 날짜로 환산한다. 원래 예정일에 다시 차감하지 않는다.
- 과거 날짜는 실제 완료 거래만 반영한다. 미래/오늘은 현재 기록과 미완료 예정 지출·정산 유입을 이어 계산한다.
- 예정일이 오늘보다 이전인 미완료 일회성·정기 지출은 예측에서 제외한다. 실제 지출 완료로 자동 변경하거나 오늘로 이월하지 않는다. 사용자가 실제 완료를 기록하면 paidAt 날짜에 반영한다. 연체 지출 내역은 기존 지출/회차 조회 API에서 확인한다.
- 조회 기간이 미래에서 시작하면 오늘부터 from 전날까지의 예정 거래도 시작 잔액에 반영한다. carryForwardMovements에 그 근거를 반환한다.
- 최초 잔액은 기준일 시작 시점의 잔액이라는 기존 계약을 따른다. 기준일 이전 거래는 중복 반영하지 않는다.
- 정기 회차는 이미 완료한 원래 예정일을 제외한다. 중단 후 완료 이력도 실제 지출에 포함한다.

미래 정산은 scheduledAt(실제 픽업+72시간)을 우선하고 없으면 expectedSettlementAt(예상 픽업+72시간)을 사용한다.
자격 시각 이상인 첫 배치에 유입된다고 가정한다. 기본 배치는 한국 시간 03시이며 정각은 당일, 정각 이후는 다음 날이다.
배치의 `settlement.completion.cron` 설정을 함께 사용한다. cron이 Spring 중지 값 `-`이면 예상 정산이 필요한 조회는 409로 일정을 확인하도록 요청한다. 배치 활성 여부는 batchEnabled, 사용 cron은 batchCron으로 반환한다.
배치가 비활성이어도 예측은 해당 cron 실행을 가정한 값이다. 실제 지급 약속이 아니며 프론트 연결 시 이 가정을 표시해야 한다.
이미 지난 배치의 미완료 정산은 임의 입금 처리하거나 다음 배치로 이월하지 않고 overdueIncome으로 제외한다.
일정 미정은 undatedIncome, 취소는 canceledIncome으로 제외한다. 이 세 값은 요청 기간 합계가 아닌 조회 시점의 가맹점 범위 별도 진단 금액이다.
예측은 일 단위 마감 잔액이므로 같은 날 입금 전 출금에 따른 일중 부족은 표시하지 않는다.

## 응답과 근거

- baseDate/openingBalance: 최초 설정. asOf: 이번 조회 기준 UTC 시각.
- currentBalance/completedIncomeSinceBase/paidExpensesSinceBase: 실제 기록 기준 현재 잔액과 누적 근거.
- periodOpeningBalance: from 직전 마감 잔액. totalIncome/totalExpenses: 요청 기간 내 실제·예정 합계.
- closingBalance: to 마감 잔액. firstShortageDate/lowestBalance/maximumShortage: 요청 기간의 첫 음수 날짜·최저 마감 잔액·최대 부족액.
- days: 날짜별 시작·마감 잔액, completedIncome/scheduledIncome/expectedIncome/paidExpenses/plannedExpenses 및 movements.
- movements: source(SETTLEMENT/EXPENSE/RECURRING_EXPENSE), referenceId, 원래 occurrenceDate, 반영 date, title, amount, classification, eligibleAt, reflectedAt. 반복 지출의 referenceId는 규칙 ID이고 occurrenceDate와 함께 회차를 식별한다.
- classification: COMPLETED/SCHEDULED/EXPECTED는 유입, PAID/PLANNED는 지출이다. eligibleAt은 정산 자격 시각, reflectedAt은 완료 시각 또는 예상 배치 시각이다.
- shortages: 연속 음수 마감 날짜의 from/to/days/maximumAmount. 0원은 부족이 아니며 회복 후 재발은 별도 구간이다. 조회 시작 전에 시작한 부족은 요청 기간 경계로 잘라 반환한다.

해당 날짜 movements와 carryForwardMovements, 누적 실제 합계를 통해 부족의 원인을 확인한다.
금액은 원 단위 정수이며 각 합계·잔액의 절댓값이 JavaScript 안전 정수 상한(9,007,199,254,740,991)을 넘으면 422다.
같은 정산/같은 지출 회차가 중복 입력된 계산은 오류로 처리한다.
REPEATABLE_READ 읽기 트랜잭션에서 합계와 상세를 같은 스냅샷으로 읽고, 기준일 이전 실제 내역은 DB DECIMAL 합계로 누적한다.

## 후속

프론트 실제 API 연결은 #236의 6단계에서 별도 PR로 진행한다. 기존 목업은 유지한다.
P5 취소 이벤트 연동·대출 상품 API·운영 서버/실제 은행 지급 검증은 이 PR의 완료 범위가 아니다.
