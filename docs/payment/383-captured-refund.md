# #383 CAPTURED 전액 환불 — 조사와 전달 계약 제안

기준: `dev 2053542`, `feature/383-captured-refund`. 2026-10-31 사용자 요청으로 1단계 조사만 수행했다. [#383](https://github.com/team-sweethan/DonWorry/issues/383)·[#153](https://github.com/team-sweethan/DonWorry/issues/153)·[P6 #233](https://github.com/team-sweethan/DonWorry/issues/233)의 요구사항과 현재 코드를 대조했다. 이슈의 과거 “착수 대기” 문구와 구분하며 기능 구현·담당 합의·활성화 완료를 뜻하지 않는다.

## 1. 적용할 정책

[ADR-0054](../decisions/0054-full-cancellation-policy.md)에 따라 고객 환불은 결제 전액이며 정산 완료 여부로 차단하지 않는다. 시작 통지·진행 중 정산 보류는 필수가 아니다. P5는 환불 성공을 확정해 전달하고 P6가 정산 전 지급 제외 또는 정산 후 차감·이월을 처리한다. 고객 환불액과 정산 차감액은 같다고 가정하지 않는다.

HELD 고객 취소의 P3 연결 대기와 별도로 조사할 수 있다. 이번 작업에서 P3 Case나 P6 정산 내부를 구현하지 않는다.

## 2. 현재 구현과 재사용 범위

| 영역 | 확인한 코드 | 재사용할 부분 / 남은 작업 |
| --- | --- | --- |
| 매입 사실 조회 | `settlement.api.CaptureReader`, `CaptureSnapshot`, `CaptureReaderImpl` | paymentId·captureId·captureStatus·captureAmount·capturedAt 및 정산 유무/상태를 제공. 별도 읽기 트랜잭션. 빈 결과는 매입 전 확정이 아님 |
| 환불 가능성 판단 | `PaymentCancellationDecisionService` CAPTURED 분기 | 승인 참조·원 요청·매입 COMPLETED/금액/시각·기존 직원 CAPTURE 성공 근거를 대조. 정상이어도 현재 NOT_READY. 정산 상태로 거절하지 않음 |
| 공통 접수 | `PaymentCancellationTransactionService` | 결제 소유 잠금, 요청 키 내용 일치, 기존 결과 재조회, 기존 작업 LINKED 재사용. 현재 ACCEPTED는 모두 customerVoid로 보내므로 REFUND 분기 추가 필요 |
| 공통 저장 | `PaymentCancellationRequest`, `PaymentCancellation` | 가맹점별 요청 키 유일성, 결제당 금융 작업 하나. REFUND·양수 captureId·전액 amount·PROCESSING/UNKNOWN/SUCCEEDED/FAILED 저장 지원 |
| 결과 조회·HTTP | `PaymentCancellationResultService`, 기존 취소 POST/GET | 요청/작업 기반 조회와 성공·거절·대기 응답 재사용. REFUND는 현재 모든 결과에서 결제 CAPTURED를 요구하므로 환불 후 상태 계약과 함께 검토 |
| HELD 실행 | `PaymentCustomerVoidService`, `PaymentVoider` | 원자 저장·성공 발행·롤백 구조는 참고 가능. 승인 취소용 실행기/이벤트를 매입 후 환불로 재사용하지 않음 |
| P6 매입/정산 생성 | `CaptureTransactionService` | 매입 완료·정산 예정금·CaptureCompleted를 같은 트랜잭션에 저장. 주문 잠금 후 저장하며 기존 매입은 내용 대조 후 재사용 |
| P6 정산 완료 | `SettlementCompletionTransactionService`, `SettlementCompletionPolicy` | 주문 → 정산 잠금 후 완료. 현재 정책은 취소 연동 전임을 명시. 취소 수신과 같은 잠금 규칙으로 단일 반영되도록 P6 후속 구현 필요 |

기존 SQL `V20261031_183416__create_payment_cancellations.sql`은 REFUND의 captureId를 필수로 하고 P6 captures로 물리 FK를 걸지 않는다. 이번 조사에서는 SQL·Entity를 변경하지 않는다. 새 스키마가 필요하다는 결론도 내리지 않는다.

## 3. 아직 없는 구현과 주의점

- 현재 `PaymentStatus`에는 REFUNDED가 없고 `Payment`에 환불 완료 전이가 없다. #381 문서도 환불 후 결제 상태를 #383 계약으로 남겨 뒀다. CAPTURED를 유지하고 취소 작업으로 표현할지 새 상태를 둘지 아직 결정하지 않는다. VOIDED는 매입 전 승인 취소이므로 환불 완료로 대용하지 않는다.
- 현재 checkout의 P5에는 환불 실행기가 없고 P6 공개 api에는 환불 성공 수신 계약이 없다. #233의 cancellationId/paymentId/cancellationAmount/canceledAt은 임시 필드 제안이며 확정 타입이 아니다.
- P6의 SettlementStatus.CANCELED 값은 있지만 현재 Settlement에는 취소 전이 메서드가 없다. 완료 정산 차감·이월·미회수 잔액 구조와 성공 수신 구현도 이번 checkout에서 확인되지 않는다. 다른 담당의 로컬 브랜치 구현은 조사 범위 밖이다.
- `CaptureSnapshot`에는 merchantId가 없다. P5는 자기 결제의 소유를 검사하고 매입 ID·금액을 대조한다. P6 수신 시에는 자기 매입/정산의 소유·금액 대조가 필요하며, 추가 조회 필드가 필요한지는 계약 단계에서 확인한다.
- P5 결제 잠금은 P6 정산 배치를 잠그지 않는다. 환불 당시 읽은 settlementStatus로 지급 제외/완료 후 차감을 미리 결정하지 않고 P6가 실제 수신/반영 트랜잭션에서 판단해야 한다.
- 고객 환불 성공과 P6 반영 성공은 다르다. P6 전달 실패를 환불 실패로 바꾸거나 재전달 때 환불기를 다시 호출해서는 안 된다.

## 4. P6와 다음 단계에서 확인할 계약

| 확인할 항목 | 확인할 구체적인 내용 |
| --- | --- |
| 성공 결과 타입·필드 | #233 임시 4필드의 타입/이름, captureId·merchantId 추가 필요 여부. 고객 전액과 정산 차감액 구별 |
| 멱등성 | P5에 저장된 cancellationId를 재전달 때 유지. 같은 ID의 다른 내용·같은 결제의 상충 결과는 어떻게 검출할지 |
| 전달 시점·방식 | 성공 확정 저장과 발행 기록을 원자적으로 남기고 커밋 후 수신. 시작/실패/미확정 통지는 성공 이벤트로 보내지 않음 |
| 배치 경합 | 정산 전 지급 제외 또는 정산 후 차감 중 하나만 반영. P6 주문/정산 잠금 순서와 신규 수신 경로 조율 |
| 수수료·차감 | 고객은 결제 전액 환불. 수수료 반환 여부, 가맹점 차감액 계산식은 P6 확인 |
| 실패 복구 | P5 성공 후 전달 실패·P6 저장 실패의 재전달, 재시도 운영 담당/수단. 성공 원본 ID·금액·완료 시각 보존 |
| 활성화 | 실제 P6 수신·원자성·멱등성 검증 전에는 환불 실행을 NOT_READY로 유지하는 연결 순서 확인 |

의존 방향은 `payment → settlement`다. 이벤트로 전달한다면 타입은 **settlement.api**에 두어야 한다([ADR-0012](../decisions/0012-payment-settlement-dependency.md)). P6가 payment.api의 고객 VOID 이벤트를 import하는 구조나 P5가 P6 repository를 직접 수정하는 구조는 사용하지 않는다. 구체적인 신규 계약 채택 시 ADR에 대안과 API/DB/화면/테스트 영향을 함께 기록한다. 이번 문서는 코드 조사이며 신규 계약을 확정하지 않는다.

## 5. P5 후속 설계와 검증 계획

1. P6와 4절 전달 계약을 확인하고 P5 환불 성공 후 결제 상태·CakeShop 조회 표현을 정한다.
2. 기존 공통 접수에서 VOID/REFUND 실행을 분리한다. 원 요청의 서버 금액·확인한 captureId를 사용한다. 요청 키·결제당 작업 유일성은 기존 구조를 재사용한다.
3. 모의 환불기의 성공/명시적 거절/기술 오류와 외부 결과 불명 상태를 구분한다. HELD의 외부 부작용 없는 모의 실행 방식을 참고하되, 실제 외부 금융 호출을 같은 DB 트랜잭션에 넣는 것으로 확대하지 않는다.
4. 성공 저장·전달 원자성과 재조회/재전달을 구현하고 실제 P6 수신부와 통합 검증한다.

후속 검사 시나리오: 자동/직원 매입 완료, P5/P6 상태·매입 ID·금액 불일치, 정산 전/후 전액 환불, 같은/다른 요청 키 반복·동시 요청, 성공/거절/기술 오류/미확정, 응답 유실 재조회, P6 실패 재전달 시 환불 재실행 없음, 정산 배치 동시 실행 시 한 번 반영. 현재 저장 테스트의 REFUND fixture를 실제 환불 실행 검증으로 해석하지 않는다.

## 6. 이번 단계 산출물·검증

- 문서 추가 및 payment README 연결만 변경했다. 코드·스키마·외부 계약 타입·이슈 상태는 변경하지 않았다. 담당자에게 메시지를 보내지 않았다.
- `python3 .github/scripts/check_md_links.py`, `git diff --check`로 문서 링크·공백을 검사한다.
- 미검증: 실행 코드 변경이 없어 backend check/mariaDbTest는 실행하지 않았다. 환불 실행·P6 수신·정산 반영의 통합 검증은 미실시이며 #383 완료가 아니다.

## 7. P5 → P6 성공 전달 계약 제안 (2단계)

다음은 2단계에서 작성한 계약안이다. 이후 사용자가 전달한 P6 긍정 회신은 9절, 타입·모의 실행 기반 구현은 11절에 기록한다. **실제 P6 수신과 운영 활성화는 아직 미완료다.** 설계 결정의 대안·영향은 [ADR-0058](../decisions/0058-captured-refund-contract.md)에 기록한다.

### 전달 타입과 필드

제안 이름: `settlement.api.PaymentRefundSucceeded`. 매입 후 고객 전액 환불 성공만 나타낸다. #233의 임시 4필드 이름을 유지하고 매입/소유 대조를 위한 두 필드를 추가하는 안이다.

| 필드 | 제안 타입·의미 | 값의 근거 |
| --- | --- | --- |
| cancellationId | 양수 Long, 재전달에도 같은 멱등 식별자 | P5 payment_cancellations의 저장 ID |
| paymentId | 양수 Long, 환불한 결제 | P5 저장 결제 ID |
| captureId | 양수 Long, 환불 대상 매입 | P6 공개 조회로 확인 후 P5 작업에 저장한 captureId |
| merchantId | 양수 Long, 소유 가맹점 | 인증 요청 본문이 아닌 P5 저장 결제의 소유 |
| cancellationAmount | 양수 Long, 원 단위 고객 전액 환불액 | P5 결제 금액과 P6 매입 금액을 대조한 작업 amount |
| canceledAt | 필수 Instant, UTC 마이크로초, 실제 환불 성공 확정 시각 | P5 작업의 completedAt |

이벤트에는 정산 상태·차감액·수수료·고객 사유·업무 요청 키·승인 참조를 넣지 않는다. P6는 자기 매입/정산 기록으로 식별자·가맹점·전액을 대조하고, 수수료 정책에 따른 정산 차감액을 계산한다. cancellationAmount가 곧 정산 차감액이라는 계약이 아니다. 금액/시각/ID는 재전달 때 다시 생성하지 않는다.

### 발행·수신과 고객 응답

1. P5는 결제 잠금 아래 기존 원 요청/직원 매입/매입 완료 근거를 대조하고 REFUND 작업을 만든다. 기존 작업이 있으면 저장 결과를 반환한다.
2. P5가 환불 성공을 확인한 뒤 SUCCEEDED·completedAt과 Modulith 발행 기록을 같은 트랜잭션으로 확정한다. 이번 모의 실행 제안은 외부 금융 부작용이 없는 실행만 대상으로 한다.
3. 고객 취소 API의 성공은 **고객 환불 성공**을 뜻한다. P6 정산 반영 완료를 뜻하지 않는다. 이후 재조회는 기존 #381 결과 계약을 따른다.
4. P6는 커밋 후 새 트랜잭션으로 이벤트를 받는다. 실제 반영 시점의 정산 상태를 잠금 아래 확인하고 정산 전 지급 제외 또는 완료 후 별도 차감 중 하나만 반영한다. 완료 정산의 원 기록은 보존한다.
5. P6 저장 실패는 수신 실패로 남긴다. P5 환불 성공을 취소하거나 FAILED로 바꾸지 않으며, 미완료 publication의 동일 결과만 재전달한다.

실제 수신자가 없으면 발행 기록만으로 전달을 보장할 수 없다. P6 실제 리스너/영속 멱등성·정산 배치 경합·실패 복구를 확인한 뒤 P5 실행을 활성화한다. 테스트 리스너 통과만으로 운영 연결 완료라 하지 않는다.

### 중복·충돌·동시 실행

| 상황 | 제안 동작 |
| --- | --- |
| 같은 cancellationId와 동일한 전체 필드 | 이미 반영했다면 아무 금액도 다시 바꾸지 않음 |
| 같은 cancellationId인데 필드가 다름 | 덮어쓰거나 정상 중복으로 무시하지 않고 계약 충돌로 남김 |
| 같은 paymentId/captureId에 다른 성공 cancellationId | 전액 1회 범위에 어긋나므로 충돌로 남김 |
| 취소 반영이 정산 완료보다 먼저 잠금 획득 | 지급 제외 후 배치가 완료 대상으로 삼지 않음 |
| 정산 완료가 먼저 잠금 획득 | 완료 기록 보존 후 별도 차감 반영 |
| 늦은 성공 이벤트·수신 재시도 | canceledAt만으로 과거 정산 상태를 추정하지 않고 현재 저장 상태와 기존 반영을 대조 |
| 매입/정산 대상 미존재·소유/금액 불일치 | 수신 완료로 버리지 않음. 일시적 복구 대상과 계약 충돌을 구분해 확인 |

중복 수신 기록과 정산 변경은 같은 P6 트랜잭션으로 확정해야 한다. 정확한 P6 저장 구조는 P6 담당 소유이며 이 제안에서 테이블·컬럼을 지정하지 않는다. P6 수신은 기존 주문 → 정산 잠금 순서와 충돌하지 않도록 설계해야 한다. P5 잠금이나 이벤트 멱등 키만으로 이 경합이 해결된다고 보지 않는다.

### 실패·미확정과 재전달 책임

- 모의 환불 명시적 거절: REFUND/FAILED와 안정적인 거절 코드를 보존하고 성공 이벤트를 발행하지 않는 안을 제안한다. 다른 키로 새 작업을 만들어 실패를 우회하지 않는다.
- 외부 부작용 없는 모의 실행의 기술 오류: 전체 트랜잭션 롤백 후 같은 요청 키로 명시적 재요청. 실제 외부 실행의 타임아웃을 롤백만으로 안전하게 재실행할 수 있다고 확대하지 않는다.
- 실제 외부 실행 결과 불명: 성공 이벤트를 보내지 않고 P5가 UNKNOWN으로 추적·조회·복구해야 한다. 실제 카드사/자동 복구는 이번 범위 밖이며 모의 예외를 임의로 UNKNOWN 성공으로 바꾸지 않는다.
- 환불은 성공했으나 P6 반영 실패: 저장된 이벤트를 재전달한다. 환불 실행기를 호출하지 않는다. P6는 수신 실패 원인/저장 복구, P5는 환불 성공 원본을 확인한다.
- publication 재전달 실행 수단·권한·담당·재시도/충돌 확인 절차는 P6 및 기존 이벤트 복구 담당과 확정한다. 자동 재전달이 이미 운영된다고 가정하지 않는다.

## 8. P5 환불 상태 표현 추천안

**2단계 추천안: 결제 CAPTURED는 매입 사실로 보존하고, 고객 환불 성공은 기존 취소 작업 REFUND/SUCCEEDED로 표현한다.** 이후 진행 요청에 따라 11절의 P5 기반에 이 표현을 적용했다. CakeShop 화면 계약과 운영 연결까지 완료됐다는 의미는 아니다.

- 취소 응답 operation=REFUND, requestStatus=SUCCEEDED 또는 ALREADY_CANCELED, completedAt으로 환불 완료를 확인한다. paymentStatus=CAPTURED만으로 환불 여부를 판단하지 않는다.
- 기존 매입·직원 CAPTURE 성공·P3 판정 결과를 소급해서 바꾸지 않는다. 고객 환불을 HELD 고객 VOID 이벤트로 전달하지 않는다.
- CakeShop은 저장한 취소 요청 키로 결과를 조회해 주문 취소를 반영해야 한다. 결제 상태만 읽는 화면·운영 조회에도 환불 구분이 필요한지 소비자 영향 확인을 함께 한다. 다른 저장소 화면을 이번에 임의 수정하지 않는다.
- 새 PaymentStatus.REFUNDED 대안은 명확한 단일 상태 표시가 장점이나, 결제 조회·주문 중복 방지·직원 결과 재조회·P3/P4 근거·프론트 타입 등 상태 소비자를 함께 변경해야 한다. 현재 #381 구조와 분리된 변경 범위이므로 우선안으로 삼지 않는다.
- 이 추천안은 결제별 환불 작업이 실제로 조회/표시되는 것을 전제로 한다. 기존 화면이 CAPTURED만 보여 주는 문제를 해결했다고 간주하지 않는다.

## 9. 담당 확인 기록

사용자는 민정님께 전달한 6개 필드의 성공 결과 전달·중복 반영 방지·실패 시 결과 재전달 방식에 대해 “괜찮다고했어”라고 회신했다. 이를 P6 전달 방향 확인으로 기록한다. 정확한 Java 타입 이름·P6 저장 구현·수수료 반환/차감 계산식·운영 재전달 담당/수단까지 확정된 것으로 확대하지 않는다. 전달문 초안은 사용자의 선호에 따라 문서에서 제거했다.

## 10. 2단계 산출물·검증

- 성공 필드·멱등성·원자성·배치 경합·실패 재전달 계약안, P5 상태 표현 추천안 및 P6 전달문을 작성했다.
- ADR-0058에 대안과 API/DB/화면/테스트 영향을 기록했다. 담당 확인 전 제안 상태다.
- 문서 링크·공백 검사를 수행한다. 실행 코드·DB를 변경하지 않았으므로 backend check/mariaDbTest는 실행하지 않는다. 실제 환불 실행·P6 수신·정산 반영·복구는 미구현/미검증이다.

## 11. P5 모의 전액 환불 기반 구현

사용자의 다음 작업 요청에 따라 기존 CAPTURED·REFUND/SUCCEEDED 분리 표현으로 P5 실행 기반을 구현한다. 이는 외부 화면의 최종 표현이나 모든 상태 소비자 연결 완료를 뜻하지 않는다.

- `PaymentCancellationDecisionService`는 정합한 CAPTURED를 ACCEPTED 후보로 반환하고, 공통 접수 서비스가 VOID/REFUND 실행과 준비 확인을 구분한다. 동일 키·기존 작업 재조회는 금융 실행이나 새 매입 조회를 하지 않는다.
- `PaymentCustomerRefundService`는 저장 금액·매입 근거로 REFUND 작업을 생성하고 외부 부작용 없는 `PaymentRefunder` 모의 실행기를 호출한다. 성공은 작업 SUCCEEDED/완료 시각과 이벤트 발행을 원자적으로 처리한다. 결제 CAPTURED·원 매입 상태 시각·승인 참조는 보존한다.
- 명시적 거절은 REFUND_DECLINED/FAILED, 식별된 모의 기술 실패와 null은 안전한 503으로 전체 롤백한다. 예상 밖 오류·발행 실패도 롤백하며 실제 외부 UNKNOWN 복구를 구현한 것으로 간주하지 않는다.
- `settlement.api.PaymentRefundSucceeded`는 6개 필드의 양수 식별자/금액 및 UTC 마이크로초 시각을 검증한다. 정산 차감액은 계산하지 않는다.
- `PaymentRefundCompletion` 운영 Bean은 등록하지 않는다. 실제 P6 수신·복구 연결 전에는 접수 저장/실행 전에 NOT_READY이며 기존 HELD 비활성 조건과 독립적이다.
- P6 실제 수신·정산 저장은 변경하지 않았고 새 스키마도 없다. 테스트에서는 발행 어댑터와 수신 대역만 등록한다.

검증 기준: `2053542` 위 #383 구현 작업 트리, 2026-10-31. 아직 미커밋이다.

- 선행: `cd backend && ./gradlew -I /tmp/donworry-381-test-defaults.gradle spotlessApply test --tests '*PaymentCancellationDecisionServiceTests' mariaDbTest --tests '*PaymentCustomerRefundMariaDbTests' --console=plain` 통과(41초).
- 전체: `cd backend && ./gradlew -I /tmp/donworry-381-test-defaults.gradle spotlessApply check mariaDbTest --console=plain`. 일반 1,245건(실패/오류 0, 기존 skip 1), 모듈/계층 검사 통과. MariaDB 662건 중 1건 실패(6분 47초). 새 환불 10건은 모두 통과했으나 전체 BUILD는 FAILED다.
- 실패: 기존 `PaymentHeldListenerTests.on_sameEventTwice_linksPaymentOnce`가 publication 완료 2건을 30초 안에 확인하지 못했다. 같은 테스트 로그에 MariaDB 1213 데드락과 CaseIntake의 기존 Case 재사용이 기록됐다. 이 관측만으로 원인 해결이나 이번 변경과의 무관함을 단정하지 않는다. P3 코드/테스트와 대기 시간은 변경하지 않았다.
- 분리 재실행: `cd backend && ./gradlew -I /tmp/donworry-381-test-defaults.gradle mariaDbTest --tests '*PaymentHeldListenerTests' --tests '*PaymentCustomerRefundMariaDbTests' --console=plain` 통과(32초). 테스트/구현 코드 변경 없이 기존 리스너와 새 환불을 함께 재실행했다. 전체 실행의 실패 기록을 대체하지 않는다.
- 새 환불 검증: 정산 전/후 성공 및 매입 상태·시각 보존, 동일/다른 키 결과 재사용, 명시적 거절 보존, 기술 실패/null/예상 밖 오류/발행 실패 전체 롤백과 같은 키 재시도, P6 대역 수신 실패 재전달 시 금융 재실행 없음, 동일/다른 키 동시 요청 시 작업 1개.
- 임시 init script는 개인 파일을 바꾸지 않고 Test의 `ai.server.enabled=false`만 지정한다. 새 DB 변경 없이 기존 전체 Flyway와 JPA 매핑을 사용한다.
- `python3 .github/scripts/check_md_links.py`, `git diff --check`: 통과.

미검증/미해결: 전체 suite에서 관측한 기존 리스너 실패 원인, 실제 P6 수신·배치 경합/차감 이월·운영 재전달·CakeShop 표현·실제 카드사/UNKNOWN 복구. #383은 완료하지 않는다. 커밋·PR·운영 활성화는 하지 않았다.

## 12. 실제 HTTP 경로 및 비활성 검증 보강

환불 기반에 실제 HMAC/HTTP 검증을 추가했다. 새 `PaymentRefundHttpMariaDbTests`는 테스트 발행 어댑터·P6 수신 대역과 모의 CaptureReader를 사용하므로 실제 P6 정산 연결 검증은 아니다.

- 활성/정지 가맹점의 기존 CAPTURED 전액 환불: POST 200, REFUND/SUCCEEDED, 저장된 금액·완료 시각, no-store·Location, 동일 키 재요청/GET 동일 결과, 다른 키 ALREADY_CANCELED·한 번 실행.
- 사유가 바뀐 같은 키 409, 타 가맹점 결제/결과 404, 부분 금액·가맹점 지정 본문 400과 실행 없음.
- 명시적 환불 거절 422, 조회 200/FAILED, 다른 키로 재요청해도 재실행 없음.
- 모의 기술 오류 503과 내부 오류 내용 비노출, 요청/작업 롤백·GET 404, 같은 키 재요청 후 성공.
- 기존 `PaymentHttpMariaDbTests`에 실제 ALLOW 결제 → P6 매입 완료 → 운영 발행 어댑터 없는 환불 503/NOT_READY → 요청·작업 미생성과 CAPTURED 보존을 추가했다. 테스트 어댑터의 성공을 운영 활성화로 오인하지 않도록 기본 컨텍스트에서도 검증한다.

선행 검사: `cd backend && ./gradlew -I /tmp/donworry-381-test-defaults.gradle spotlessApply mariaDbTest --tests '*PaymentRefundHttpMariaDbTests' --tests '*PaymentHttpMariaDbTests' --console=plain` 통과(32초).

이전 HELD 리스너 시간 초과는 [#275 과거 검증 기록](275-fds-delivery-concurrency.md#pr-최종-점검--2026-10-29)에도 동일한 테스트/조건으로 기록되어 있다. 새 환불 코드가 원인이라고 단정하지 않으며 P3 구현/테스트·대기 시간은 변경하지 않았다. 최종 전체 검사 결과는 아래에 기록한다.


최종 전체 검사(2026-10-31, `2053542` 위 HTTP 검증 보강 작업 트리):

- `cd backend && ./gradlew -I /tmp/donworry-381-test-defaults.gradle spotlessApply check mariaDbTest --console=plain`: BUILD SUCCESSFUL(5분 53초).
- 일반 1,245건(실패/오류 0, 기존 skip 1), MariaDB 668건(실패/오류/skip 0). 모듈/계층 검사·전체 Flyway/JPA 매핑 포함.
- 새 환불 HTTP 5건, 기존 HTTP에 기본 환불 미연결 1건을 추가한 16건, 환불 Service MariaDB 10건 포함. 선행 HTTP 21건도 모두 통과했다.
- 기존 HELD 리스너 4건은 이번 전체 검사에서 통과했다. 이전 시간 초과의 근본 원인을 수정한 것으로 주장하지 않고 11절 실패 이력과 함께 남긴다.
- 문서 링크·diff 공백 검사 통과. 검사 후 실행/테스트 코드는 변경하지 않고 이 기록만 추가했다.
- 실제 P6 수신·정산 배치 경합/차감/이월·운영 재전달·CakeShop 화면·외부 카드사/UNKNOWN 복구는 미검증이다. NOT_READY 유지, 미커밋이며 PR·활성화는 하지 않았다.
