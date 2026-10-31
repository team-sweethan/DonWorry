# #382 HELD 고객 전액 승인 취소 — 조사와 실행·전달 계약 제안

기준: `dev 2bd7f87`(PR #386 병합), 2026-10-31. 브랜치 `feature/382-held-cancellation`.
1단계 코드 조사에 이어 2단계 실행·전달 계약을 제안한다. 상세안은 7~11절과 [ADR-0057](../decisions/0057-held-customer-void-contract.md)을 따른다. 1~2단계에서는 실행 코드·DB·공개 계약을 변경하지 않았다. 후속 P5 구현 범위는 12절을 따른다. P3 계약·Case 정책은 여전히 담당 합의 전이다.

관련: #382, [#380 정책](380-cancellation-stage-policy.md), [#381 저장/실행 경계](381-cancellation-storage.md), [직원 조치 ADR](../decisions/0027-payment-actions.md), [의존 방향](../decisions/0012-payment-settlement-dependency.md).

## 1. 기존 직원 승인 취소 흐름

`CaseDecisionService`가 직원 판정과 인계를 저장한 뒤 `HandoffRequested`를 발행한다. P3의 `PaymentFinancialActionGateway`는 `payment.api.PaymentActionRequester`로 요청한다.

P5 `PaymentActionTransactionService`는 별도 트랜잭션에서 다음을 수행한다.

1. `PaymentActionAdmissionService`가 결제 쓰기 잠금을 잡는다.
2. 기존 키/조치/판정을 비교하고 HELD·승인 참조를 확인한 뒤 직원 조치를 저장한다.
3. VOID이면 `PaymentVoider`를 호출한다. 현재 구현은 외부 호출·독립 금융 부작용이 없는 `MockPaymentVoider`다.
4. 성공은 `Payment.completeVoid()`와 직원 조치 성공을 반영하고, 명시적 거절은 결제 HELD 유지·직원 조치 실패로 기록한다.
5. `PaymentActionResult`를 같은 트랜잭션에서 발행한다. P3는 커밋 후 기존 인계와 결제/판정/유형/키를 대조한다.

기술 예외·null 응답은 현재 모의 실행의 전체 변경을 롤백한다. 같은 키 재요청으로 다시 수행하는 테스트가 있다. 이 규칙을 외부 카드사 타임아웃에 그대로 적용하면 안 된다.

## 2. 재사용과 분리

| 항목 | 확인 결과 |
| --- | --- |
| `PaymentVoider`, `AuthorizationVoidCommand/Result` | 모의 취소 실행 경계 재사용 후보. 저장된 승인 참조를 사용하고 성공/명시적 거절을 구분한다. UNKNOWN 결과는 현재 반환 타입에 없다. |
| `Payment.completeVoid()` | 승인된 HELD만 VOIDED로 전이하며 원 승인 참조를 보존한다. 성공 확인 후 사용한다. |
| 결제 쓰기 잠금 | 고객 `findOwnedForCancellation`과 직원 `findByIdForUpdate`는 같은 payments 행을 잠근다. 상태 판단·기록·실행 순서의 공통 기준으로 사용한다. |
| #381 요청·작업·결과 조회 | 기존 인증·키 비교·소유 확인·DEFERRED 재판단·LINKED·기존 VOID 결과·응답 매핑을 재사용한다. 새 공통 API/테이블을 만들 필요는 현재 조사에서 발견하지 못했다. |
| 직원 `PaymentActionRequester` | 고객 취소 진입점으로 사용하지 않는다. decisionId 필수이며 직원 판정·인계 의미가 있다. |
| `payment_actions`, `PaymentActionResult` | 고객 기록/성공 통지를 대신하지 않는다. 가짜 decisionId나 직원 actionId를 만들지 않는다. |

## 3. P5 연결 지점과 구현 순서 제안

1. `PaymentCancellationDecisionService`의 HELD 분기에서 현재 `CANCELLATION_NOT_READY`인 정상 후보만 실행 대상으로 연결한다. 승인 참조·원 요청 REVIEW/AUTHORIZED 정합성, 직원 CAPTURE 여부·P6 매입 사실 검사는 유지한다. P6 빈 조회만으로 이미 발행한 매입을 취소할 수 있다고 판단하지 않는다.
2. `PaymentCancellationTransactionService`가 기존 요청을 ACCEPTED로 기록/전환하고, 같은 결제의 VOID 작업을 `PaymentCancellation.start(...)`로 생성한다. 이미 작업이 있으면 기존 결과만 반환한다.
3. 현재 모의 취소는 한 트랜잭션에서 실행하고 결제/작업/합의된 성공 발행을 함께 커밋하는 안을 우선 검토한다. 동기 실행을 위해 PROCESSING만 먼저 커밋하거나 복구 경로 없는 워커를 만들지 않는다.
4. 모의 성공은 VOIDED + SUCCEEDED, 명시적 거절은 HELD + FAILED로 기록한다. 기술 예외/null은 롤백하여 신규 요청/작업을 남기지 않고, 기존 DEFERRED/REVIEW_REQUIRED 기록은 보존하는 안이다. 실패 응답을 성공 또는 임의 UNKNOWN으로 바꾸지 않는다.
5. 실제 외부 부작용이 있는 실행기로 바꿀 때는 결과 불명·복구·멱등 실행 계약을 별도로 설계해야 한다. #381의 UNKNOWN 저장 기능이 있다는 사실만으로 해당 실행/복구가 구현된 것은 아니다.

## 4. 직원 조치·매입 경합 보완

고객 경로는 이미 직원 작업을 읽지만, `PaymentActionAdmissionService`는 고객 `payment_cancellations`를 아직 읽지 않는다. 서로 다른 테이블의 UNIQUE만으로 두 실행을 막을 수 없다.

- 직원 CAPTURE 접수가 먼저 커밋: 고객은 DEFERRED. 이미 발행한 매입 요청을 철회하지 않는다.
- 직원 VOID 성공이 먼저 커밋: 고객은 기존 직원 취소 사실을 반환하고 새 금융 작업을 만들지 않는다.
- 고객 VOID 성공이 먼저 커밋: 이후 직원 신규 조치를 차단해야 한다. 현재도 VOIDED는 PAYMENT_NOT_HELD로 거절되지만 P3 업무 표현은 별도 확인이 필요하다.
- 고객 작업이 PROCESSING/UNKNOWN이면 직원 CAPTURE/VOID를 추가 실행하지 않도록 결제 잠금 안에서 검사해야 한다.
- 고객 작업 FAILED는 결제가 HELD로 남는다. 고객 새 키로 재실행은 금지된다. 이 상태에서 직원의 후속 CAPTURE/VOID를 허용할지는 P3 운영 복구 계약과 함께 정해야 한다. 현재 #381 결과 조회는 실패한 VOID 작업에 HELD를 요구하므로, 단순 허용하면 기존 취소 결과 조회도 어긋날 수 있다.
- 기존 직원 키 재조회는 기존 결과를 유지해야 한다. 신규 거절 검사 위치 때문에 정상 재조회가 다른 응답으로 바뀌지 않도록 검사한다.
- P6 매입 완료 반영도 결제 잠금을 사용한다. 이미 발행된 매입·늦은 완료까지 포함한 실제 양방향 경합은 후속 테스트 대상이다.

## 5. P3·CakeShop 계약 확인 사항

현재 `PaymentActionResultListener`는 decisionId와 기존 ActionHandoff가 있는 직원 결과만 받는다. 고객 취소에는 해당 판정/인계가 없으므로 그대로 보내면 매칭 실패다.

`CaseDecisionService`는 Case·증거·보고서·기존 판정을 확인하지만 고객 취소 사실을 직접 확인하는 경로가 없다. `CaseStatusService`도 Case 연결 결제 수·직원 판정 수·인계·보고서로 상태를 계산하며 P5 VOIDED만으로 자동 종료하지 않는다. 고객 취소 뒤 직원 요청이 PAYMENT_NOT_HELD로 거절되면 현재 P3는 인계 실패로 반영할 수 있다.

P3와 정할 항목:

- 고객 취소 성공을 어떤 별도 공개 이벤트/조회 계약으로 전달할지. 이벤트를 선택하면 의존 방향상 타입은 payment.api, P3는 커밋 후 멱등 수신한다.
- 미판정 결제의 고객 취소를 Case에 어떻게 표시하고 직원 판정 대상에서 제외할지. 여러 결제가 묶인 Case 전체를 임의로 종료하지 않는다.
- 직원 판정은 저장됐지만 P5 접수보다 고객 취소가 먼저 끝난 경우, 기존 판정/인계의 표시와 종료 규칙.
- HELD 이벤트가 늦게 도착하는 순서 역전, 중복 성공 통지, 수신 실패 후 재전달 처리.
- 고객 취소 FAILED 이후 직원 조치 허용 여부와 응답 코드. 새 거절 코드를 추가하면 P3 Gateway의 RESPONSE_ONLY_CODES도 함께 검토한다. 기존 충돌 코드를 정상 고객 취소에 재사용하면 P3 오류 로그/표시가 부정확해질 수 있다.

이는 담당 확인 항목이며 P3 엔티티/테이블을 P5에서 직접 수정하지 않는다. 합의한 외부 계약 변경은 ADR에 남기고 수신 구현·검증 후 활성화한다. 필수 수신부 미연결 상태에서 NOT_READY를 무조건 제거하지 않는다.

CakeShop은 #381 취소 응답/GET과 기존 결제 GET을 사용한다. 취소 결과를 받기 전 완료로 표시하지 않아야 하며 실제 주문 상태 반영은 별도 저장소 확인 대상이다. 현재 별도 CakeShop 취소 웹훅 구현을 전제로 하지 않는다.

## 6. 다음 구현의 검증 항목

- 정상 HELD 취소 성공, 명시적 거절, 기술 예외/null, 커밋 실패와 기존 거절 기록 보존.
- 같은 키·다른 키 반복/동시 요청, FAILED/UNKNOWN 결과 재사용과 추가 실행 차단.
- 직원 CAPTURE/VOID 선행과 고객 취소 선행의 양방향 경합, 늦은 매입 완료, 기존 직원 키 재조회 회귀.
- P3 합의 후 고객 성공·중복·순서 역전·수신 실패 재전달. 전달만 복구하고 금융 실행은 반복하지 않는지 확인.
- 기존 `PaymentActionVoidMariaDbTests`, `PaymentActionCaptureMariaDbTests`, `PaymentActionAdmissionMariaDbTests`와 #381 취소 서비스/HTTP 회귀.

이번 조사에서는 위 테스트 코드를 읽었으며 실행하지 않았다. 기존 테스트 존재를 고객 취소 구현·검증 완료로 표시하지 않는다. 실행 코드·DB 변경이 없어 backend check/mariaDbTest는 미실행이다. 문서 링크·공백 검사만 실행한다.

## 7. 모의 실행·오류 처리 제안

현재 외부 금융 부작용이 없는 `MockPaymentVoider`에 한정한다. 기존 직원 VOID처럼 결제 잠금을 유지한 하나의 P5 트랜잭션에서 요청 접수 → 작업 생성 → 모의 실행 → 결과 저장/성공 이벤트 발행을 수행한다. 실제 카드사 호출을 이 트랜잭션 안에 넣는 설계가 아니다.

| 결과 | 결제·작업 저장 | 고객 POST / GET | 다음 행동 |
| --- | --- | --- | --- |
| 성공 | VOIDED + SUCCEEDED, 승인 참조 보존. 성공 이벤트 발행 기록과 원자 커밋 | POST 200 SUCCEEDED, GET 200 SUCCEEDED | 같은 키는 기존 결과. 다른 키는 ALREADY_CANCELED |
| 명시적 거절 DECLINED | HELD + FAILED, 내부 VOID_DECLINED. 요청/작업 커밋, 성공 이벤트 없음 | 최초 키 POST 422 CANCELLATION_DECLINED, 다른 키 POST 409 CANCELLATION_PREVIOUSLY_FAILED. GET 200 FAILED | 자동 재실행 없이 CONTACT_SUPPORT |
| 모의 실행의 식별 가능한 기술 오류/null | 이번 트랜잭션 전체 롤백. 새 요청/작업 없음. 기존 DEFERRED/REVIEW_REQUIRED는 이전 값 유지 | 503 CANCELLATION_UNAVAILABLE. requestStatus·새 Location 없음 | 같은 키 GET 확인 후 같은 내용 재요청 |
| DB·잠금·커밋 오류 | 트랜잭션 경계에서 실패. 응답만으로 커밋 여부를 단정하지 않음 | 503 CANCELLATION_UNAVAILABLE. 저장 상태 추측 없음 | 같은 키 GET 확인. 기록이 없더라도 원 요청 미도달이라고 단정하지 않음 |
| 예상 밖 프로그래밍 오류 | 롤백. 원문 예외 비노출 | 기존 500 CANCELLATION_INTERNAL_ERROR | 같은 키 조회·운영 확인 |
| 실행 또는 필수 성공 수신부 미준비 | 기존 NOT_READY 유지. 신규 접수 행 없음 | 503 CANCELLATION_NOT_READY | 연동 준비 후 명시적 재요청 |

알려진 실행 기술 오류를 구분할 내부 예외 경계를 두고, 모든 RuntimeException을 503으로 덮지 않는다. DB 커밋이 실제 완료됐지만 HTTP 응답이 유실된 경우는 실패로 되돌리지 않고 다음 조회에서 성공 결과를 반환한다.

PROCESSING은 이번 동기 모의 실행 트랜잭션 내부의 시작 상태이며 별도 선행 커밋하지 않는다. 이 실행기는 외부 결과 불명을 만들지 않으므로 UNKNOWN을 새로 기록하지 않는다. 기존 UNKNOWN 작업이 있으면 #381 조회/재실행 차단 규칙을 유지한다. 실제 외부 실행기로 전환할 때만 영속 접수·금융 멱등 키·결과 확인·재시작 복구를 별도 설계한다.

현재 금액·승인 참조·가맹점·시각은 서버 기록에서 얻는다. `acceptedAt`은 최초 요청 시각과 구분하고, 결과 확정의 UTC 마이크로초 `completedAt`을 결제 상태 변경·작업 결과·성공 통지에서 일관되게 사용하도록 구현 시 점검한다. 단순 조회나 성공 재요청으로 시각을 갱신하지 않는다.

## 8. 직원 신규 조치 차단 계약 제안

기존 직원 요청 키가 있으면 내용 대조 후 그 결과를 먼저 반환한다. 신규 직원 요청에 대해서만 같은 결제 잠금 안에서 고객 작업을 확인한다. 고객 경로의 직원 작업 확인과 대칭으로 구현한다.

| 고객 VOID 작업 | 직원 신규 CAPTURE/VOID | 제안 코드 | 이유 |
| --- | --- | --- | --- |
| 없음 | 기존 직원 조건 사용 | 기존 코드 | DEFERRED/REVIEW_REQUIRED 요청 기록만으로 직원을 막지 않음 |
| SUCCEEDED | REJECTED | PAYMENT_CANCELED_BY_CUSTOMER | 금융 거래가 이미 취소됨 |
| PROCESSING / UNKNOWN | REJECTED | PAYMENT_CANCELLATION_IN_PROGRESS | 추가 금융 실행 금지 |
| FAILED | REJECTED | PAYMENT_CANCELLATION_REVIEW_REQUIRED | 실패한 고객 취소를 직원 요청으로 우회하지 않음. 별도 복구 정책 필요 |

위 코드는 기존 `PaymentActionReceipt`의 REJECTED 응답에 담는 **신규 접수 거절 코드 제안**이다. `payment_actions`를 새로 만들거나 `PaymentActionResult`를 발행하지 않는다. P3 `PaymentFinancialActionGateway.RESPONSE_ONLY_CODES`에 등록하고, 정상 업무 조건을 P3 버그로 기록하지 않도록 매핑한다. 이 제안을 적용하기 전 P3 합의가 필요하다.

고객 FAILED 이후 직원 처리까지 차단하는 안은 기존 실패 결과 보존과 이중 실행 방지를 우선한 MVP 제한이다. 해당 결제는 HELD로 남으므로 자동 해결된 것처럼 표시하지 않는다. 운영 확인 담당·복구 경로가 후속으로 필요하며 행 삭제/새 키로 잠금을 해제하지 않는다. 직원 매입 허용을 택한다면 `PaymentCancellationResultService`의 실패 VOID/HELD 대조와 P3 표현까지 함께 변경해야 한다.

## 9. P3 성공 전달 계약 제안

### 이벤트

제안 타입: `payment.api.PaymentCustomerVoidSucceeded`. HELD 고객 승인 취소 성공만 나타내며 직원 결과나 CAPTURED 환불 이벤트를 대신하지 않는다.

| 필드 | 형식·근거 |
| --- | --- |
| cancellationId | 양수 Long. 영속 고객 취소 작업 ID, 멱등 식별자 |
| paymentId | 양수 Long. 취소된 결제 |
| merchantId | 양수 Long. 원 결제의 소유자 |
| amount | 양수 Long. 원 단위 전액, 저장된 결제/작업과 동일 |
| completedAt | 필수 Instant, UTC 마이크로초. 실제 모의 성공 확정 시각 |

고객 사유 원문·업무 요청 키·승인 참조·고객 개인정보·가짜 decisionId를 넣지 않는다. `cancellationId`를 직원 actionId/targetOperationId로 바꾸어 저장하지 않는다. 시작·거절·기술 오류에는 이 이벤트를 보내지 않는다.

P5는 결제 VOIDED·작업 SUCCEEDED와 같은 트랜잭션에서 한 번 발행한다. 롤백이면 업무 기록과 발행 기록이 함께 롤백된다. HTTP 성공은 P5 금융 결과가 확정됐다는 뜻이며 P3 비동기 반영 완료를 보장하지 않는다. 재요청은 성공 이벤트를 새로 발행하지 않으며, P3 전달 실패는 기존 Modulith publication을 재전달한다. 금액·완료 시각을 새로 만들거나 모의 취소기를 다시 호출하지 않는다.

### 수신과 순서 역전

- P3가 커밋 후 새 트랜잭션으로 수신하고 cancellationId 기준으로 중복 반영을 막는다. 동일 ID의 paymentId·merchantId·amount·completedAt이 다르면 덮어쓰지 않고 충돌로 남긴다. 같은 결제의 다른 성공 ID도 정상 중복으로 삼지 않는다.
- Case가 이미 있으면 결제/가맹점 소유를 대조하고 해당 연결 결제에만 고객 취소 완료 사실을 반영한다. Case의 다른 결제에 영향을 확대하지 않는다.
- 취소 성공이 PaymentHeld보다 먼저 도착할 수 있다. **사용자 선택에 따라 취소 원본은 P5에만 두고, P3는 공개 조회로 확인하며 Case 반영 이력만 남긴다.** 별도 P3 취소 보관 테이블은 만들지 않는다. Case 연결 시에도 P5를 조회한다. Case가 아직 없는 성공 이벤트는 수신 실패로 남겨 기존 publication 재전달 대상으로 유지하는 안을 검증한다. 조회와 Case 커밋 사이의 경합 때문에 이벤트를 단순히 버려서는 안 된다.
- 늦은 HELD가 취소 사실을 덮거나 다시 판정 대상으로 추가하지 않도록 한다. 연결/취소/직원 판정은 P3 내부에서 동일 결제와 Case 기준으로 직렬화하고, 구체적 잠금 순서는 P3 저장 설계에서 확정한다.
- P3 반영 실패는 예외로 남겨 publication 완료 처리를 하지 않는다. 데이터 미존재가 일시적 순서 역전인지 계약 충돌인지 구분해야 한다.

### Case·직원 판정 표시

권장 업무 의미는 '직원이 거절함'이 아닌 '고객이 승인 취소함'이다. 미판정 결제는 새 직원 판정 대상에서 제외하되 원 결제 연결·조사/보고서·기존 이력을 삭제하지 않는다.

이미 직원 판정이 저장됐지만 P5 접수 전에 고객이 취소했다면 원 판정은 보존하고 '고객 취소로 실행하지 않음'을 별도로 표시한다. P5 취소 성공은 직원 금융 작업 성공이 아니므로 인계를 ACCEPTED로 위장하지 않는다. P5의 접수 거절 응답과 취소 성공 이벤트는 어느 순서로 와도 같은 최종 표시가 되어야 한다.

P3의 현재 CLOSED 계산은 전체 결제에 직원 판정과 ACCEPTED 인계가 있는 경우를 전제로 한다. 제안은 고객 취소가 확인된 결제를 '금융 조치가 끝난 결제'로 별도 계산하고, 나머지 결제의 판정/금융 작업이 끝났을 때만 종료 가능하게 하는 것이다. 모든 결제가 고객 취소된 경우 조사 자체를 끝낼지, 보안 조사만 계속할지는 P3 정책 확인이 필요하다. **CaseStatus/HandoffStatus의 새 값·보고서 무효화·evidenceVersion 변경은 확정하지 않는다.**

## 10. 구현·활성화 순서 제안

1. P5 모의 실행·결과 보존과 양방향 신규 조치 차단을 준비한다. P3 응답 코드/이벤트 계약 합의 전에는 운영 경로의 NOT_READY를 해제하지 않는다.
2. P5 공개 조회와 P3 이벤트/접수 거절 매핑, Case 이력의 중복 방지·순서 역전 처리, 판정 제외 및 종료 정책을 연결한다. 별도 P3 취소 보관 테이블은 추가하지 않는다. Case 업무 정책은 별도 확인한다.
3. 합의한 공개 이벤트와 실제 운영 수신자를 연결하고 트랜잭션 원자성·중복·순서 역전·실패 재전달을 검증한다. 테스트용 리스너만으로 운영 수신 준비를 판단하지 않는다.
4. HELD 실행을 활성화하고 실제 HTTP 성공/거절·같은 키 재조회·직원 조치 경합을 검사한다. CAPTURED NOT_READY와 #383 범위는 유지한다.

추가 스키마 없이 P5 실행 기반을 준비할 수 있으나, 이번 단계는 계약 문서만 작성한다. 모듈 의존 방향은 변경하지 않으며 P6 성공 통지·정산 차감은 #383/#233이다.

## 11. 2단계 산출물과 검증

- 실행 결과별 저장·HTTP·재요청 계약, 직원 신규 조치 차단 코드, P3 성공 이벤트 필드·중복·순서 역전·Case 처리·활성화 조건을 제안했다.
- [ADR-0057](../decisions/0057-held-customer-void-contract.md)에 대안과 영향 범위를 기록했다. 담당 승인·전달 완료로 표시하지 않는다.
- 현재 코드의 모의 실행기, 취소 결과 조립, P3 CasePayment/ActionHandoff·CaseIntakeService·InvestigationRules를 대조했다.
- 문서 링크·공백 검사를 실행했다. 실행 코드·DB·테스트를 바꾸지 않았으므로 backend check/mariaDbTest는 실행하지 않았다. 이벤트·P3 저장·케이크샵 실제 흐름은 미구현/미검증이다.

## 12. P5 모의 실행 기반 구현 (3단계)

기준: `a7f0dcc` 조사·계약 문서 커밋 위 `feature/382-held-cancellation` 작업 트리. 신규 DB 변경은 없다.

- `PaymentCancellationDecisionService`는 정합한 HELD를 ACCEPTED 후보로 반환한다. `PaymentCancellationTransactionService`는 실제 준비 여부를 확인한 뒤 요청 기록·`PaymentCustomerVoidService` 실행·결과 조립을 같은 결제 잠금/트랜잭션에 둔다. CAPTURED 실행은 계속 NOT_READY다.
- `PaymentCustomerVoidService`는 기존 모의 취소기를 사용한다. 성공은 VOIDED·SUCCEEDED·성공 발행, DECLINED는 HELD·FAILED/VOID_DECLINED다. 결제 상태 전이 시각을 작업·이벤트 completedAt에 재사용한다. 식별 가능한 `AuthorizationVoidUnavailableException`과 null은 안전한 503으로 변환하며 예상 밖 예외는 기존 500 경계를 유지한다.
- `payment.api.PaymentCustomerVoidSucceeded` 타입을 추가하고 필수 양수 식별자/전액/UTC 마이크로초 시각을 검증한다. P3 계약 확인 전 타입이며 실제 P3 수신자는 추가하지 않았다.
- 운영 발행 어댑터 `PaymentCustomerVoidCompletion` Bean은 **등록하지 않았다**. 준비 확인 시 어댑터가 없으면 요청/작업 저장·모의 실행 전에 NOT_READY를 반환한다. HTTP 활성 플래그만 켜거나 이벤트 타입을 추가했다고 실행 가능해지지 않는다. 후속 P3 운영 수신/복구 계약과 검증이 끝난 뒤 P5 발행 어댑터를 연결해야 한다.
- 테스트에서만 명시적인 발행 어댑터와 Modulith 수신 대역을 등록한다. 실제 영속 발행 기록·커밋 후 수신·실패 재전달을 검사하며 P3 Case 구현 검증으로 해석하지 않는다.
- `PaymentActionAdmissionService`는 기존 직원 키 재조회·충돌 검사 후 신규 요청만 고객 작업과 대조한다. SUCCEEDED, PROCESSING/UNKNOWN, FAILED에 각각 8절의 접수 거절 코드를 반환한다. 고객 요청 기록만 DEFERRED/REVIEW_REQUIRED인 경우에는 직원 실행을 막지 않는다.
- P3 `PaymentFinancialActionGateway`에는 위 3개 코드의 응답 전용 매핑만 추가했다. 존재하지 않는 직원 결과 이벤트를 기다리지 않도록 한다. 현재 인계 실패 표시는 기존 동작을 유지하며, 고객 취소 별도 표시·Case 종료·판정 제외·고객 성공 수신은 미구현이다.
- 초기 통합 검사는 Mockito가 테스트용 lambda 발행 어댑터를 spy할 수 없어 컨텍스트 로딩에서 실패했다. 명시적 테스트 어댑터 클래스로 바꾸었으며 검증 조건은 유지한다.

미검증/미완료: 실제 P3 수신·Case/판정·순서 역전 처리, 운영 발행 어댑터/실행 활성화, CakeShop 실제 성공 흐름, 외부 카드사 및 UNKNOWN 복구, 고객 FAILED 후 운영 복구 정책. P1/P6의 API·스키마 변경은 없다. 이번 단계로 #382를 완료 처리하지 않는다.

선행 검사:

- `./gradlew -I /tmp/donworry-381-test-defaults.gradle spotlessApply test --tests '*PaymentCustomerVoid*' --tests '*PaymentCancellationDecision*' mariaDbTest --tests '*PaymentCustomerVoidMariaDbTests' --console=plain`: BUILD SUCCESSFUL(39초). 판단/응답 단위 10건(기존 6 + 신규 4), 새 MariaDB 18건 모두 통과.
- MariaDB에서는 성공/동일·다른 키 결과 보존, 명시적 거절/직원 우회 차단, 식별된 기술 오류/null/예상 밖 예외 롤백, 이전 확인 필요 기록 보존, 커밋/발행 실패 원자성, 수신 실패 publication 재전달, 고객 선행의 같은 키/다른 키/직원 CAPTURE/VOID 경합, 직원 CAPTURE/VOID 선행, PROCESSING/UNKNOWN의 직원 실행 차단, 늦은 매입 완료 거절을 확인했다.
- 직원 CAPTURE 선행 검사는 결제 잠금 안의 실제 접수 커밋을 대상으로 한다. P6 외부 금융 실행이나 실제 P3 고객 취소 수신을 검증했다고 해석하지 않는다.

최종 전체 검사(2026-10-31, `a7f0dcc` 위 P5 구현 미커밋 작업 트리):

- `cd backend && ./gradlew -I /tmp/donworry-381-test-defaults.gradle spotlessApply check mariaDbTest --console=plain`: BUILD SUCCESSFUL(5분 50초). 일반 1,220건(실패/오류 0, 기존 skip 1), MariaDB 651건(실패/오류/skip 0). 전체 Flyway/JPA validate와 모듈/계층 검사 포함.
- 임시 Gradle init script는 개인 로컬 파일을 변경하지 않고 Test 작업의 `ai.server.enabled=false`만 지정한다. 실제 AI 서버 호출을 켠 개인 설정 대신 저장소 기본값으로 검증했다.
- 새 고객 VOID MariaDB 18건 외에 기존 직원 VOID 9건, 공통 취소 Service 18건, 실제 HMAC HTTP 15건을 포함해 통과했다. 운영 발행 어댑터가 없는 기본 Service/HTTP의 NOT_READY·기록 미생성 및 이전 기록 보존도 회귀 검증했다.
- `python3 .github/scripts/check_md_links.py`, `git diff --check`: 통과. 검사 후 구현/테스트 코드는 변경하지 않고 이 검증 기록만 추가했다.
- 문서 제안은 `a7f0dcc`에 커밋했다. 위 구현·검증 기록은 아직 미커밋이며 PR·운영 활성화는 하지 않았다. P3 계약 미확정·실제 Case 수신 미검증 범위는 앞의 기록을 유지한다.

## 13. P5 원본 조회를 통한 P3 연결 (4단계 수정안)

12절 구현은 `45ef576`에 커밋했다. 커밋 직전 `spotlessApply`는 UP-TO-DATE였으며 12절 전체 검사의 구현/테스트 코드와 동일하다.

사용자는 별도 P3 취소 보관 대신 **P5를 취소 사실의 원본으로 두고, P3가 공개 API로 확인하며 Case에 반영한 이력만 남기는 방향**을 선택했다. 앞선 별도 P3 영속 보관 제안은 대체한다. 이 선택은 Case 종료 정책까지 승인한 것으로 해석하지 않는다.

### 공개 조회에 필요한 계약

현재 `PaymentReader.findById`의 `PaymentSummary`는 결제 ID·금액·상태·최초 요청 시각만 반환한다. `VOIDED`만으로는 직원 승인 취소와 고객 승인 취소를 구별할 수 없다. 기존 고객 HTTP 조회는 가맹점/요청 키를 전제로 하므로 P3가 그 키를 보관하거나 HTTP를 호출하는 방식으로 재사용하지 않는다.

P5의 별도 내부 공개 조회 경계를 추가하는 안으로 진행한다. 결제 ID와 가맹점 범위로 P5가 기존 `payment_cancellations`와 결제의 정합성을 확인하고, 고객 VOID 성공인 경우에만 cancellationId·paymentId·merchantId·amount·completedAt을 반환한다. 요청 키·사유·승인 참조는 공개하지 않는다. 직원 VOID, 성공 전/실패한 고객 취소, CAPTURED 환불을 고객 VOID 성공으로 반환하지 않는다. 잘못된 입력·소유 불일치·데이터 불일치·DB 오류를 정상 미취소로 숨기지 않는 계약을 구현 시 명시한다.

### 연결 지점과 순서 역전

| 지점 | 변경 방향 |
| --- | --- |
| 고객 VOID 성공 수신 | P5 공개 조회로 성공 사실과 이벤트를 대조하고 Case를 잠근 뒤 반영 이력 기록·상태 재계산 |
| `CaseLinkService.link` | 늦게 온 HELD도 연결 시 P5 원본을 확인. 조사 연결은 보존하고 취소 결제를 금융 판정 대상으로 복원하지 않음 |
| `CaseDecisionService.decide` | 새 판정/인계 저장 전 P5 조회로 고객 취소 성공 확인. 취소된 결제의 새 금융 판정 차단 |
| `CaseStatusService.refresh` / `InvestigationRules` | P5 성공 사실을 기준으로 금융 처리 완료 여부 계산. 다른 결제의 미판정·대기·실패는 유지. 구체적인 Case 종료 정책은 아직 미확정 |
| `HandoffService` / 조회 API | 기존 판정·인계를 보존하고 고객 취소로 실행되지 않았음을 구분. 직원 성공으로 위장하지 않음 |

Case 생성 전 성공 이벤트는 기존 Modulith publication을 미완료로 남겨 재전달한다. Case 연결 경로의 P5 조회와 함께 사용하여, 연결 쪽이 취소 커밋 전 상태를 읽고 수신 쪽은 아직 미커밋인 Case를 못 보는 경합도 처리한다. 해당 재전달의 실행 경로·운영 복구 절차는 활성화 전에 확인한다. publication은 전달 기록이며 새로운 P3 취소 원본 테이블이 아니다.

조회는 실행 잠금이 아니다. P3 조회 직후 P5에서 고객 취소가 확정되는 경우에는 기존 P5 결제 잠금과 신규 직원 요청 차단으로 이중 실행을 막고, 성공 이벤트로 P3 표시를 수렴시킨다. P3 Case 잠금과 P5 금융 잠금을 함께 획득하는 구조를 추가하지 않는다. MariaDB 트랜잭션 격리로 오래된 스냅샷을 읽는 경우도 테스트한다.

P3 이력은 Case 잠금 아래 cancellationId 기준으로 한 번만 기록한다. 기존 `CaseHistory`에는 결제/취소 식별자와 반영에 필요한 변경 사실만 남기며 금융 원본 전체를 복제하지 않는다. 중복 이벤트나 상태 재계산은 P5 원본과 이력을 대조한다. Case가 없는 이벤트를 완료 처리해 버리는 방식은 채택하지 않는다.

### 구현 순서와 검증

1. P5 내부 공개 조회와 테스트를 추가한다. 기존 테이블을 재사용하며 migration은 필요 없다.
2. 실제 P3 수신·HELD 연결·Case 이력 멱등 처리를 연결한다. HELD 먼저/취소 먼저, 동시 커밋, 중복, 수신 실패 후 재전달을 검사한다.
3. 판정 제외·인계 표시와 Case 종료 정책을 확정하고 연결한다. 모든 결제가 취소되었을 때 조사까지 끝낼지는 별도 업무 결정이다.
4. 실제 P3 수신자로 전체 검증 후 P5 발행 어댑터를 등록한다. HTTP 성공/거절·재조회와 직원 조치 경합을 검증한다.

필수 검증은 고객/직원 VOID 구분, 가맹점 범위·원본 불일치, 실패/미완료 취소 제외, 반복 조회 무변경, 이벤트 중복·순서 역전·재전달, 판정과 취소 경합, 다중 결제 Case의 조기 종료 방지다.

이번 단계는 공개 조회의 부족한 정보와 연결 지점을 코드로 대조하고 설계를 수정했다. 새 조회 API·P3 연결 코드는 아직 미구현이며 운영 NOT_READY를 유지한다. 문서 링크·공백 검사만 수행하고, 새 연결 시나리오는 미검증이다. 별도 P3 테이블 생성 승인은 더 이상 이 방향의 선행 조건이 아니다.
