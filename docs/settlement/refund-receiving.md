# 3단계 환불 성공 수신·조정 원장 (#478)

현재 후속 변경: [4단계 #488](unpaid-refund-exclusion.md)에서 공통 가맹점 잠금과 미지급 제외를 연결했다. 미지급은 수신과 함께 APPLIED/UNPAID_EXCLUSION으로 확정하고 지급 후 환불만 PENDING으로 유지한다. 아래는 3단계 도입 기록이며 운영 활성화는 여전히 후속이다.

## 범위

기존 `settlement.api.PaymentRefundSucceeded`를 발행 커밋 후 수신해 원 매입·정산과 대조하고 조정 원장에 `PENDING`으로 저장한다. 고객 환불 실행, 지급 전 제외, 지급 후 회수·이월, 지급액·현금흐름 변경은 이번 범위가 아니다. 원 매입·정산·지급 기록은 변경하지 않는다.

`settlement.refund-receiving.enabled=true`일 때만 리스너를 등록한다. 기본은 비활성이다. **후속 금융 반영 구현 전에는 운영에서 활성화하지 않는다.** 현재 지급 배치는 이 원장의 PENDING을 제외하지 않으므로 수신 성공을 정산 차감 완료로 해석하면 안 된다. P5의 실제 환불 발행 구현·활성화도 별도이며 P6에서 P5 서비스 구현체를 만들지 않는다.

## 기존 이벤트 계약

| 필드 | 타입 | 검증 |
| --- | --- | --- |
| cancellationId | Long | 양수, 취소 건의 불변 식별자 |
| paymentId | Long | 양수, 원 결제 |
| captureId | Long | 양수, 원 매입 |
| merchantId | Long | 양수, 원 가맹점 |
| cancellationAmount | Long | 양수, 원 매입·정산 대상 금액과 같은 전액 |
| canceledAt | Instant | P5의 환불 성공 시각, UTC 마이크로초 |

매입은 COMPLETED와 완료 시각이 있어야 하고 정산은 SCHEDULED 또는 COMPLETED여야 한다. 모든 원본 식별자·가맹점·금액을 대조한다. 부분 환불, 누락된 원본, 불일치 요청은 예외로 남기며 성공 기록으로 바꾸지 않는다. 다른 모듈 Entity·Repository와 P5 테이블은 조회하지 않는다.

## 금액과 처리 방향

원 거래의 수수료와 순정산액을 스냅샷으로 저장한다. 현재 가맹점 수수료로 재계산하지 않는다. 총액 10,000원, 카드 200원, PG 100원, 순정산액 9,700원이면 고객 환불액은 10,000원이고 향후 제외·회수할 조정액은 9,700원이다. 수수료 전액 반환 가정에 따른 프로젝트 정책이며 실제 금융기관 계약을 뜻하지 않는다.

수신 시점에 지급 전/후를 확정하지 않고 `disposition=NULL`로 둔다. 후속 단계에서 지급 배치와 같은 잠금·트랜잭션 기준으로 실제 반영할 때 방향을 결정한다. 수신과 적용 사이에 지급이 확정될 수 있기 때문이다.

## ERD: settlement_refund_adjustments

실제 타입·제약은 [신규 SQL](../../backend/src/main/resources/db/migration/V20261103_154941__create_settlement_refund_adjustments.sql)이 정본이다. 기존 migration은 수정하지 않는다.

| 컬럼 | 타입 | NULL | 의미 |
| --- | --- | --- | --- |
| adjustment_id | BIGINT | N | PK, AUTO_INCREMENT |
| cancellation_id | BIGINT | N | P5 논리 참조, UNIQUE |
| payment_id | BIGINT | N | P5 논리 참조, UNIQUE |
| capture_id | BIGINT | N | P6 captures FK |
| settlement_id | BIGINT | N | P6 settlements FK, UNIQUE |
| merchant_id | BIGINT | N | 가맹점 논리 참조 |
| refund_amount | BIGINT | N | 고객에게 환불한 전액 |
| card_fee_return | BIGINT | N | 원 카드 수수료 반환액 |
| pg_fee_return | BIGINT | N | 원 PG 수수료 반환액 |
| adjustment_amount | BIGINT | N | 원 순정산액, 향후 제외·회수 대상 |
| remaining_amount | BIGINT | N | 최초 조정액, 향후 미회수액 |
| status | VARCHAR(30) | N | PENDING / APPLIED |
| disposition | VARCHAR(30) | Y | NULL / UNPAID_EXCLUSION / RECOVERY |
| canceled_at | DATETIME(6) | N | P5 성공 시각, UTC |
| created_at / updated_at | DATETIME(6) | N | P6 수신·수정 시각, UTC |

인덱스 `(status, merchant_id, adjustment_id)`는 후속 가맹점별 미적용 조회용이다. 모듈 간 물리 FK는 없다. 전액 환불 한정으로 결제·정산별 조정은 최대 한 건이다.

DB는 금액 비음수, 수수료+조정액=환불액, 잔여액 범위를 검증한다. PENDING은 방향 미정·잔여액=조정액이다. APPLIED와 두 방향은 후속 적용을 위한 예약 상태이며 이번 코드에는 전환 메서드가 없다. APPLIED는 방향 필수이고 UNPAID_EXCLUSION은 잔여액 0이다. RECOVERY는 이후 이월을 위해 잔여액을 허용한다. 재전달이 미래 APPLIED의 잔여액을 초기화하지 않는 것도 검사한다.

## 중복·동시 요청·실패

- 동일 취소 ID·동일 여섯 필드: 기존 원장을 그대로 반환하며 저장·금액 재계산을 하지 않는다.
- 동일 취소 ID의 내용 변경 또는 동일 정산의 다른 취소 ID: 충돌로 거절한다.
- READ_COMMITTED 새 트랜잭션에서 원 정산 행을 비관적 쓰기 잠금한다. 같은 결제의 동시 재전달은 직렬화한다.
- 서로 다른 결제가 같은 취소 ID를 동시에 쓰는 경우 UNIQUE 위반은 오류로 남기며 정상 중복으로 숨기지 않는다.
- DB 저장 실패 시 수신 트랜잭션은 롤백한다. 실패 원장을 성공으로 남기지 않는다. 예외는 Modulith에 전달해 원 발행 기록을 미완료로 남긴다.

리스너는 `@ApplicationModuleListener`의 커밋 후 비동기 전달을 사용하되 `NOT_SUPPORTED`로 외부 트랜잭션을 만들지 않는다. 서비스가 REQUIRES_NEW 수신 저장 트랜잭션을 소유해 연결을 이중 점유하지 않는다. 원장 커밋 뒤 발행 완료 표시가 실패하면 같은 이벤트를 재전달해 멱등 처리할 수 있다. 테스트는 연결 풀 1개에서도 정상 수신·완료 표시·실패 기록 재전달을 확인한다.

리스너가 비활성인 동안 발행한 이벤트는 이 리스너용 발행 기록이 생기지 않는다. 활성화 순서·과거 결과 복구는 P5와 별도 검증해야 한다. 운영자 재전달 UI, 자동 재시도 횟수·간격, P5 환불 재실행은 이번 범위가 아니다.

## 검증

단위 검사는 원본 대조·스냅샷·중복·충돌·저장 예외·기본 비활성을 확인한다. MariaDB 검사는 신규 Flyway·JPA 매핑·제약·동시 중복·롤백 후 재처리·재전달·원 거래와 지급 보존을 확인한다. 실제 실행 결과와 환경 제한은 PR에 기록한다. 기존 개인 DB 초기화, 운영 적용, 실제 P5/CakeShop 환불 활성화를 뜻하지 않는다.
