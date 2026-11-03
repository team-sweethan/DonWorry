# P6 영업일 D+3 정산 정책 및 ERD 확장안

- 담당: P6 민정 / Issue #465 / 2026-11-03
- 정책: P6 담당 사용자 결정. Notion 동기화·팀 배포 합의 완료를 의미하지 않는다.
- PR 1 구현: 매입일 기반 일정, 영업일 달력, 기존 미완료 일정 전환, 픽업 독립성.
- 후속 제안: 가맹점 일정산 원장·환불·이월 차감. 아래 후속 테이블은 이번 SQL에 만들지 않는다.

## 1. 정산 정책

| 항목 | 기준 |
| --- | --- |
| 기준일 D | `captures.captured_at`을 Asia/Seoul 날짜로 변환한 날 |
| 정산일 | D를 제외하고 세 번째 영업일. 매입 시각이 달라도 같은 한국 날짜면 같은 날 |
| 영업일 | 토·일·한국 공휴일(대체·선거·추가 지정 휴일 포함)을 제외한 달력의 영업일 |
| 저장 | 정산일 한국 00:00을 UTC DATETIME(6)로 저장. 72시간 경과와 다름 |
| 지급 간주 | 배치가 실제 COMPLETED로 커밋한 금액을 가맹점 입금액으로 간주. 외부 은행 송금은 없음 |
| 실행 시각 | 기존 기본값 매일 03:00 Asia/Seoul 유지. 최종 운영 시각은 팀 공유·설정 대상 |
| 완료 시각 | 예정일 00:00이 아니라 배치가 실제 완료한 `completed_at` |
| 지연 배치 | 기한 지난 미완료 건도 처리. 휴일에 복구 실행하면 실제 처리일에 완료 집계 |
| 픽업·상품 | 주문 상세 표시 전용. 픽업 시각이나 도착 순서가 정산 금액·정산일을 바꾸지 않음 |
| 계산 | 매입·수수료 스냅샷·정산 예정 생성·완료 이벤트 기록은 기존 단일 트랜잭션 유지 |

예: 2026-11-06 금요일 매입 → 11/09 월(D+1), 11/10 화(D+2), 11/11 수(D+3).
주말 매입도 다음 영업일부터 세며, 휴일 매입 자체를 거절하지 않는다.

신규 매입부터 `scheduled_at`이 확정된다. `expected_settlement_at`은 이전 조회 계약 호환을 위해 동일한 값을 저장한다.
픽업 전 EXPECTED와 픽업 후 SCHEDULED로 나누던 분류는 신규 정산에 적용하지 않는다. 기존 응답 필드를 삭제하지 않으며 EXPECTED는 과거 데이터 호환용이다.

## 2. PR 1 ERD: 달력 추가, 기존 컬럼 재사용

### settlement_business_days (신규, P6)

| 컬럼 | 타입 | NULL | 제약·의미 |
| --- | --- | --- | --- |
| business_day_id | BIGINT | N | PK AUTO_INCREMENT |
| calendar_date | DATE | N | UNIQUE, 한국 날짜 |
| business_day | BOOLEAN | N | 영업일 true / 휴일 false |
| source_version | VARCHAR(50) | N | 근거 자료의 스냅샷 버전 KR-20261103 |

날짜마다 한 행을 저장한다. 2026-01-01~2027-12-31 730일을 초기 제공한다.
없는 날짜는 평일로 추측하지 않고 예외로 거절한다. 매입일과 D+3까지 필요한 날짜가 모두 있어야 한다.
달력 부족 시 매입·정산 트랜잭션이 롤백되므로 기존 전달 기록을 통한 복구가 필요하다. 자동 재전달 기능 추가를 뜻하지 않는다.
2027년 말 이전에 다음 연도를 추가해야 한다. 임시공휴일·법 개정 시 새 migration으로 달력을 갱신하고 이미 확정한 미완료 일정의 조정 범위를 별도 검토한다.
완료 원장은 달력 변경으로 재계산하지 않는다. 완료 배치는 저장된 scheduled_at만 사용하며 당시의 D+3를 현재 달력으로 다시 계산하지 않는다.

### 기존 P6 테이블

| 테이블·컬럼 | PR 1 변경 |
| --- | --- |
| captures | 컬럼·원본 기록 유지 |
| settlements.expected_settlement_at | 미완료 전환 및 신규 생성 시 D+3 예정일로 저장 |
| settlements.scheduled_at | D+3 정산 가능일 00:00 KST의 UTC 시각 |
| settlements.completed_at | 실제 완료 시각 유지 |
| settlements 금액·수수료 | 원래 적용한 요율·원 단위 금액 유지 |
| order_fulfillments | 기존 예정·실제 픽업 시각 유지, 정산일 계산에서 분리 |
| order_fulfillment_items | 상품명·수량·순서 스냅샷 유지 |

픽업 API 두 PUT 경로·HMAC 인증·요청 JSON·P5 매입 이벤트는 바꾸지 않는다.
`LINKED`는 같은 가맹점·주문 매입과 정합성을 확인했다는 뜻이며, 픽업으로 정산일을 변경했다는 뜻은 아니다.

## 3. 기존 DB 전환

1. 운영 적용 전 백업 및 배치·매입/픽업 쓰기를 중지한다. 구버전 앱과 동시 기동하지 않는다.
2. 새 Flyway가 달력 생성·초기 자료 적재 후 기존 SCHEDULED만 매입일 D+3로 전환한다.
3. COMPLETED·CANCELED의 전체 행은 그대로 유지한다. 매입 금액·수수료도 변경하지 않는다.
4. 매입 누락·금액 불일치·달력 범위 부족이면 일정 UPDATE 전에 거절한다. 정상값을 NULL로 덮어쓰지 않는다.
5. 이 경우 MariaDB DDL 특성상 달력 생성까지 반영됐을 수 있다. 자동 repair·이력 삭제 금지. 담당자가 오류와 부분 적용 상태를 확인하고 별도 복구한다.
6. 앱 배포 후 일정/완료/상세 정보를 확인하고 쓰기를 재개한다. 과거 데이터 삭제를 요구하지 않는다.

당장 개인 `donworry` DB에 적용하거나 지우지 않는다. PR 검증은 격리 DB에서 한다.
달력 지원 연도 밖의 오래된 미완료 건이 있다면 적용 전에 해당 연도 달력을 확장해야 한다.

## 4. 후속 환불 정책 (이번 PR 미구현)

- P5가 고객 전액 환불을 담당한다. P6는 성공이 확정된 `PaymentRefundSucceeded`만 반영한다.
- 부분 환불은 이번 정책 범위 밖이다. `cancellationAmount`가 원 매입 금액과 같은지 검증한다.
- 카드·PG 수수료 모두 반환하는 모의 정책이다. 현재 요율로 다시 계산하지 않고 원래 저장한 수수료·순정산액을 사용한다.
- 매입 원본 COMPLETED·원 금액은 유지하고 별도 환불 조정 원장에 기록한다.
- 지급 전 환불: 해당 순정산액 전부를 지급 대상에서 제외한다.
- 지급 후 환불: 이미 지급한 순정산액을 가맹점의 다음 지급에서 차감한다. 부족분은 이후 지급으로 이월한다.
- 고객 환불 10,000원, 원 카드 수수료 200원, 원 PG 수수료 100원이라면 P6 제외/회수 금액은 9,700원이다. 고객 환불액 10,000원을 다시 정산에서 빼지 않는다.
- 지급액은 음수로 만들지 않는다. `실지급=max(지급대상 순액-회수대기액,0)`.
- 같은 cancellationId는 한 번만 반영. 동일 ID의 다른 내용은 충돌로 거절한다.
- 배치와 환불은 동일 가맹점 조정 잔액/지급 대상을 같은 순서로 잠근 뒤 판단한다. 미리 조회한 상태만으로 지급 전·후를 결정하지 않는다.
- 고객 환불 재실행과 P6 차감 재처리는 구분한다. P6 실패가 고객 환불을 취소하지 않는다.

## 5. 단계별 ERD 확장

2단계 #474에서 merchant_payouts/merchant_payout_items를 구현한다. [상세 ERD](daily-payouts.md)의 origin 출처 필드를 포함한다. 나머지 환불/배분/잔액 테이블은 후속 제안이며 아직 SQL을 생성하지 않는다.

모든 ID는 BIGINT AUTO_INCREMENT, 금액 BIGINT(KRW), 시각 UTC DATETIME(6), 상태 VARCHAR.
다른 담당 테이블은 논리 참조만 하며 아래 P6 내부 관계만 물리 FK 후보이다.

| 테이블 | 주요 컬럼 | 제약·역할 |
| --- | --- | --- |
| merchant_payouts | payout_id, merchant_id, payout_date DATE, status, settlement_total, refund_deduction_total, payout_amount, completed_at, created_at, updated_at | UNIQUE(merchant_id,payout_date). 가맹점·일자별 지급 합계와 실행 상태 |
| merchant_payout_items | payout_item_id, payout_id, settlement_id, settlement_amount, created_at | payout_id·settlement_id 내부 FK, UNIQUE(settlement_id). 원 정산의 지급 포함 근거 |
| settlement_refund_adjustments | adjustment_id, cancellation_id, payment_id, capture_id, settlement_id, merchant_id, refund_amount, card_fee_return, pg_fee_return, adjustment_amount, remaining_amount, disposition, canceled_at, created_at, updated_at | UNIQUE(cancellation_id), settlement_id 내부 FK. 고객 환불액과 순정산 회수액을 구분. disposition=UNPAID_EXCLUSION/RECOVERY |
| payout_refund_allocations | allocation_id, payout_id, adjustment_id, applied_amount, created_at | 두 내부 FK, UNIQUE(payout_id,adjustment_id). 어떤 환불을 어느 지급에서 얼마나 차감했는지 추적 |
| merchant_settlement_balances | balance_id, merchant_id, outstanding_refund_amount, updated_at | UNIQUE(merchant_id). 가맹점별 회수 대기 잔액 및 직렬화 잠금 기준. 조정·배분 원장과 같은 트랜잭션에서 갱신 |

잔여 회수액 = 조정 원금 - 지급 전 제외/후속 지급의 배분 합계. 잔액 캐시는 원장으로 대조 가능해야 한다.
정산 완료 후 환불돼도 과거 완료 정산금·completed_at을 덮어쓰지 않는다. 실제 잔액은 향후 지급의 차감 후 금액으로 계산하도록 후속 조회/예측 PR에서 전환한다.
P5 기존 이벤트 필드는 cancellationId/paymentId/captureId/merchantId/cancellationAmount/canceledAt이며 변경을 요구하지 않는다.
P5 활성화·이벤트 재전달·회수불가 종료 정책은 후속 연동 검증 범위다.

## 6. PR 순서

| PR | 범위 |
| --- | --- |
| 1 | 본 정책·ERD·ADR, D+3 계산/달력, 미완료 일정 전환, 픽업 분리 |
| 2 | 가맹점별 일정산 지급 원장과 배치 |
| 3 | 환불 성공 이벤트 수신·중복 방지·조정 원장 (P5 활성화 별도) |
| 4 | 지급 전 제외 및 배치 경합 보호 |
| 5 | 지급 후 회수·후속 차감·잔액 이월 |
| 6 | 차감 후 실제 유입 기반 정산 조회·현금흐름 |
| 7 | 새 정산 모델에 맞는 화면 개편 |
| 8 | P5 활성화·CakeShop 전체 연동·후속 정리 |

## 7. 달력 근거

날짜·휴일 수치는 출처의 사실 데이터만 사용한다. 금융기관별 영업일 계약과 같다고 주장하지 않는 프로젝트 정책이다.

- [우주항공청 2026년 월력요항](https://www.kasi.re.kr/kor/post/newsMaterial/32031): 설·추석·대체휴일 및 지방선거일 포함.
- [인사혁신처 2026-04-29 공휴일 변경](https://www.mpm.go.kr/mpm/comm/newsPress/newsPressRelease/?boardId=bbs_0000000000000029&cntId=4250&mode=view&pageIdx=1): 2026년 노동절·제헌절 추가.
- [우주항공청 2027년 월력요항](https://www.kasa.go.kr/prog/plcyBrf/brief/kor/sub01_01_04/view.do?plcyBrfNo=431): 2027년 휴일·대체휴일. 주 5일 기준 2026 휴일 120일, 2027 휴일 119일과 시드 대조.
- [한국천문연구원 2027년 음양력 대조](https://astro.kasi.re.kr/life/pageView/5?search_month=05&search_year=2027): 음력 4/8은 양력 5/13.
- [한국천문연구원 월력요항](https://astro.kasi.re.kr/life/post/almanac): 연도별 추가/변경 시 대조 대상.

새 임시공휴일 지정 시 다음 migration에 날짜와 출처 버전을 명시한다. 지원 연도와 최종 검토일을 배포 전에 확인한다.

## 8. 이번 변경의 검증 범위

- `backend: spotlessApply check`: 일반 테스트 1,360개, 실패/오류 0, 건너뜀 1. ModularityTests·ArchitectureTests 포함.
- `frontend: lint / format:check / test / build`: 통과. 테스트 296개. 기존 번들 크기 경고는 남아 있다.
- 별도 생성한 로컬 MariaDB 12.3 격리 DB: 이전 63개 migration → 새 migration 업그레이드, 완료 기록 보존, 기존 미완료 일정 전환, 휴일 수·연도 경계, JPA validate, 픽업 없이 완료, 완료 뒤 픽업/상품 저장, 반복 완료 확인.
- 같은 격리 DB의 P6 선택 통합 테스트 33개 통과 (서명 픽업 인증·현금흐름·픽업·상품·완료·조회 7개 클래스). 로컬 검증 보조 설정으로 DB 연결만 바꿨으며 저장소 공용 테스트 설정은 수정하지 않았다.
- 추가 격리 DB에서 달력 범위 부족 시 migration이 일정 변경 전에 거절하는 것을 확인했다.
- 팀 표준 `mariaDbTest`는 의존 작업 `settlementSeoulMariaDbTest`의 컨테이너 생성 시 OCI/cgroup `pids` 오류로 실행되지 못했다. MariaDB 11.8 전체 UTC/Seoul 검사는 CI 결과 확인이 필요하다. 로컬 12.3 결과로 대체 완료라고 주장하지 않는다.
- 실제 배포·P5/CakeShop 연동·환불 활성화는 미검증이다. 개인 donworry DB는 변경/초기화하지 않았다.

PR 리뷰 보완: 완료 단계의 현재 달력 재계산을 제거해 저장된 정산일을 보존한다. CI의 현금흐름 호환 자료는 신규 D+3 일정을 NULL로 명시해 과거 EXPECTED·날짜 미정 상황을 재현하도록 수정했다.

MariaDB CI 후속 보완: 실제 HMAC 픽업 인증 테스트에도 남아 있던 픽업 +72시간 기대값을 매입일 D+3로 변경했다. 매입 후 서명된 픽업 예정·완료를 재전달해도 저장된 정산일을 유지하는 것을 추가 확인했다.
