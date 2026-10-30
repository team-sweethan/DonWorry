# P6 정산 내역·요약 조회 API (#228)

`GET /api/v1/settlements/report?from=2026-11-01&to=2026-11-30`

- 기본 비활성. `settlement.owner-http.enabled=true` 환경에서만 등록한다.
- 사장님 세션의 `CurrentStaff`가 OPERATOR·FINANCE_VIEW·유효 가맹점 ID를 제공해야 한다. 비로그인은 401, 권한 부족은 403이다. 요청의 merchantId는 사용하지 않는다.
- from/to는 한국 날짜, 양 끝 날짜 포함, 최대 366일이다. DB 검색 구간은 한국 시작일 00시 이상·종료일 다음날 00시 미만으로 변환한다.
- 한 응답에 기간 합계(completedAmount/scheduledAmount/expectedAmount), 일별 days, 개별 정산 entries를 반환한다. 금액은 원 단위다.
- completedAmount는 실제 completed_at의 한국 날짜, scheduledAmount는 실제 픽업+72시간이 확정된 SCHEDULED, expectedAmount는 예정 픽업 기반 SCHEDULED다. 상태를 임의 변경하지 않는다.
- undatedAmount는 해당 가맹점의 날짜 미정 예정금 전체다. 날짜가 없어 기간 합계·그래프에 임의 배치하지 않는다.
- entries에는 주문번호·원금·수수료·정산금·상태·픽업 시각과 items(productName/quantity)를 담는다. 상품 수신 전이면 빈 배열이며 상품명·날짜를 만들지 않는다.
- 상품은 가맹점·주문번호로 한 번에 조회하여 입력 순서를 보존한다. 같은 이름의 상품 항목을 합치지 않는다.
- 기간 내 10,000건을 초과하면 일부 합계를 반환하지 않고 422 SETTLEMENT_PERIOD_TOO_LARGE로 기간 축소를 요구한다. 기본 정렬은 정산 ID 순이다. 현재 API는 전체 기간 데이터를 받아 화면에서 검색·페이지를 구성하는 용도다.
- 응답은 no-store. DB 조회 장애는 503이며 데이터 없음(정상 0원·빈 목록)과 구분한다.

## 범위

읽기 전용이며 매입·배치·취소·잔액·지출·현금흐름·프론트엔드 연결은 수행하지 않는다. 새 migration은 없다.
예정 날짜는 현재 저장된 예상/가능 시각 기준이며, 다음 새벽 3시 배치를 반영한 현금 유입 예측은 후속 현금흐름 단계에서 보완한다.
목업 화면을 교체하지 않는다. 실제 세션·배포 환경의 브라우저 통합은 프론트 연결 단계에서 별도 검증한다.
