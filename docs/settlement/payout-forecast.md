# P6 실제 지급액 조회·환불 회수 반영 예측 — 정책 전환 6단계

관련 #507. [5단계 환불 회수](refund-recovery.md)의 원장을 읽는다. 테이블·migration·금융 쓰기·P5 이벤트·인증은 변경하지 않는다. 화면 개편은 7단계, P5/CakeShop 활성화는 8단계다.

## 실제 입금과 원 거래의 구분

실제 보유 잔액의 유입은 `merchant_payouts.status=COMPLETED`의 `payout_amount`만 사용한다. 완료 정산 건별 `settlement_amount`를 추가 합산하지 않는다.

- 원 정산금 10,000원, 환불 회수 7,000원 → 실제 유입 3,000원.
- 전액 상계 지급은 원 정산이 COMPLETED여도 실제 유입 0원.
- 지급 후 환불은 이전 지급·입금을 되돌리지 않는다. 아직 회수하지 않은 금액을 현재 잔액에서 다시 차감하지 않는다.
- 과거 LEGACY_BACKFILL도 지급 원장의 실제 금액을 읽되 origin을 반환한다. 과거에도 매일 한 번 지급했다는 의미는 아니다.

## 기존 정산 조회 계약의 추가 필드

`GET /api/v1/settlements/report?from=...&to=...` (기존 컨트롤러의 경로를 따른다.)

기존 `entries`와 `completedAmount/scheduledAmount/expectedAmount`는 **원 거래 순정산금**이다. 이를 실제 입금액이라고 표시하면 안 된다. 기간과 days에 아래 필드를 추가한다.

| 필드 | 의미 |
| --- | --- |
| paidSettlementAmount | 기간 내 완료 지급의 원 정산 합계 |
| refundDeductionAmount | 그 지급에서 회수한 환불 합계 |
| paidAmount | 실제 지급액, 위 두 금액의 차이 |
| outstandingRecoveryAmount | 조회 시점의 미회수액. 과거 조회 기간 말의 채무가 아님 |
| payouts | payoutId, payoutDate, completedAt, settlementAmount, refundDeductionAmount, payoutAmount, origin |

`outstandingRecoveryAmount`는 최상위에만 있다. 기간·일별 지급은 한국 날짜 기준 실제 완료된 지급만 포함하며, 미래 일정의 회수 차감은 forecast에서 확인한다. 기존 주문 상품·수수료 상세와 지급 목록을 서로 합산하지 않는다.

## 현금흐름 계약 변경

기존 `/cash-flow/forecast`, `/cash-flow/shortages`와 권한·입력·오류 계약을 유지한다. 실제 완료 유입 Movement의 source는 **PAYOUT**, referenceId는 **payoutId**다. 예정 유입은 SETTLEMENT/settlementId를 유지한다. 두 ID 영역을 섞어 중복으로 판단하지 않는다.

- Movement.amount: 실제 또는 예측한 **차감 후 유입액**. 지출은 기존 규칙 그대로.
- 유입 Movement.settlementAmount: 차감 전 순정산금.
- 유입 Movement.refundDeductionAmount: 실제 또는 예상 환불 회수액. 지출 두 필드는 0.
- outstandingRecoveryAmount: asOf 기준 아직 회수하지 않은 전체 금액.
- projectedRefundDeduction: 오늘부터 조회 종료일까지 예정 유입에서 차감할 금액. 조회 시작 전 이월 구간도 포함.
- remainingRecoveryAmount: 조회 종료일까지 예측하고도 남은 채무. 이후 지급으로 이월된다.

예정 정산을 예상 지급일·settlementId 순으로 정렬하고 회수액을 순차 차감한다. 같은 날 개별 주문에 배정한 예상 차감은 설명용 배분이며 실제 회수 배분 원장과 동일한 주문별 배분을 보장하지 않는다. 실제 배치는 가맹점 지급 전체에서 환불 FIFO로 회수한다. 일별 총 지급액은 동일하다.

미래 기간만 조회해도 오늘부터 from 전날까지 먼저 계산하여 그 사이 소진한 회수액을 재차감하지 않는다. 원 정산 5,000원씩 이틀, 채무 7,000원이면 첫날 0원·다음 날 3,000원 유입이다. 조회만으로 배분·잔여액을 저장하지 않는다.

당일 지급이 이미 마감되었다면 같은 날짜에 늦게 추가된 정산은 다음 날짜의 배치로 예측한다. 그 외 이미 지난 배치의 미지급은 기존처럼 overdueIncome으로 별도 제외하며, 무조건 재시도 성공·입금된다고 가정하지 않는다. 미정·취소·수신된 미지급 환불은 예정 유입에서 제외한다. 배치 비활성 시 설정 cron을 가정한다는 경고도 유지한다.

## 일관성·범위·한계

REPEATABLE_READ 읽기 스냅샷과 첫 DB 조회 전 고정한 asOf를 사용한다. 원 거래 조회와 실제 지급은 동일하게 completedAt < asOf, 환불은 createdAt < asOf 기준이다. cutoff와 같거나 이후 완료된 원 거래는 원 예정일의 SCHEDULED로 분류한다. 완료일이 기간 안이어도 원 예정일이 밖이면 cutoff 이전의 그 기간 내 완료 내역에 포함하지 않는다. 개별 remaining_amount는 조회 사이 변경될 수 있으므로 미회수액은 원 조정액에서 **asOf 이전 완료 지급의 배분액**만 빼 복원한다. cutoff와 같은 완료 시각도 실제 입금·차감에서 제외한다. 일정과 상태의 모든 과거 변경을 재현하는 감사 API는 아니다.

가맹점 조건은 합계·상세·원 지급·회수 배분에 모두 적용한다. 기존 날짜·10,000건 한도·JS 안전 정수 검사를 유지한다. 채무 합계는 DB DECIMAL로 읽으며 BIGINT/JS 한도를 초과하면 잘못된 작은 값 대신 422를 반환한다. 원 거래·지급 근거 금액 역시 검사한다.

기존 완료 정산에 지급 원장이 누락됐다면 임의로 입금을 추정하지 않는다. 정상 이관은 2단계 migration의 LEGACY_BACKFILL이 담당했다. 운영자 수동 보정·실제 은행·신규 미발생 매출·부분 환불·일중 부족 예측은 범위 밖이다.

## 검증

단위 테스트: 실제 차감 후 유입·0원 지급·여러 날짜 채무 이월·미래 구간 전 차감·무유입·당일 마감·원금과 실제 지급 요약 분리·안전 정수 한도.
MariaDB: 실제 지급 배치 후 조회 대조, 과거 원장 보존, PENDING 환불 구분, cutoff 이후 회수 복원, 가맹점 격리, DECIMAL 합계와 기존 P6 회귀. 실행 결과와 환경은 PR에 적는다.

## 화면 이행

기존 자금 현황은 서버 forecast 금액을 사용하므로 실제 잔액·일별 유입은 새 계산을 따른다. 정산 달력·요약은 아직 원 거래 금액을 사용하는 화면이 있으므로 이번 변경만으로 화면 전환이 완료되지 않는다. 이번 단계는 프론트 API 타입과 PAYOUT 수신 호환 테스트만 함께 갱신한다. 7단계에서 지급·차감 표시와 부족 근거 문구, 목업·HTML을 함께 정리한다. 기존 목업은 이번 단계에서 변경하지 않는다.
