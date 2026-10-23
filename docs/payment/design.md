# P5 1차 시연 설계

**#104의 1차 축소 제안이며 P3·P6 합의 전이다.** 기준·범위·미결 C번호는 [README](README.md)를 따른다. 이 문서의 계약은 필요한 정보와 책임을 정의한다.
현재 테이블·컬럼·타입은 migration SQL, 모듈 의존 방향과 이벤트 타입 위치는 ADR-0012를 따른다.
새 보조 저장·DTO 필드명·URL·HTTP 코드·복구 세부는 합의 전 제안이다.

## 1. 결제 흐름과 상태

```mermaid
flowchart TD
    A["CakeShop 결제 접수"] --> B["PENDING 영속 저장"]
    B --> F["P2 FDS 평가"]
    F -->|DENY| D["DENIED · 승인 없음"]
    F -->|ERROR 또는 기술 실패| E["승인 금지 · 오류 기록 및 복구"]
    F -->|ALLOW 또는 REVIEW| U["모의 카드 승인"]
    U -->|업무 거절| N["DECLINED · Case 없음"]
    U -->|ALLOW 승인 성공| C["PENDING 유지 · CAPTURE 요청"]
    U -->|REVIEW 승인 성공| H["HELD 저장 · HELD 이벤트"]
    H --> P["P3 Case · P4 조사 · 직원 판정"]
    P -->|ACCEPT| C2["CAPTURE 요청"]
    P -->|REJECT| V["P5 VOID 요청 영속 접수"]
    C --> S["P6 매입·정산 예정금 동시 저장"]
    C2 --> S
    S --> X["커밋 후 완료 통지 → P5 CAPTURED"]
    V --> Y["P5 실행 성공 → VOIDED"]
```

| 상태 | 의미·진입 조건 |
| --- | --- |
| PENDING | 최초 접수 후 평가·승인·매입 대기. ALLOW 승인 성공만으로 변경하지 않음 |
| DENIED | FDS DENY. 카드 승인 요청 없음 |
| DECLINED | ALLOW/REVIEW지만 카드 승인 업무 거절 |
| HELD | REVIEW + 승인 성공 + 매입 보류. HELD 통지를 통해 즉시 Case 연결 |
| CAPTURED | P6의 실제 매입 성공을 확인하고 P5가 반영 |
| VOIDED | P5의 실제 승인 취소 성공 확인 |
| EXPIRED | 카드 승인 만료 확인. 주문 기한 만료와 다름 |

FDS의 ERROR와 기술 타임아웃은 DENIED/DECLINED가 아니다. 초기 PENDING 기록과 확인 가능한 오류를 남기며,
최종 유효 판정을 얻기 전 승인하지 않는다. 복구·재시도 세부 기준은 C2에서 정한다. 카드 승인 기술 실패도 업무 거절 DECLINED로 바꾸지 않는다. 기존 내부 규칙의 UNKNOWN 판단과 P6 완료 이벤트 계약은 별개다.
P2가 전달한 판정을 사용하며 점수 계산을 P5에 복제하지 않는다. 점수 임계값·경계는 P2 계약과 코드에서 관리한다.

ALLOW의 CAPTURE에는 직원 판정·Case가 필요 없다. REVIEW 승인 실패에는 HELD·Case를 만들지 않는다.
HELD 1건도 통지한다. Case 병합의 30분은 P3 정책이며 P5 만료 시간이 아니다.
직원 ACCEPT는 재승인이 아니며, REJECT는 환불이 아닌 승인 취소 요청이다.
ACCEPT 접수 이후에도 실제 매입 성공 전까지 HELD를 유지하는 안을 제안한다(C3·C4 합의 대기). 별도 APPROVED 상태를 추가하지 않고,
상태만으로 매입을 허용하지 않는다. 해당 승인과 직원 ACCEPT에 연결된 유효 조치 요청이 있어야 한다.

### W1 내부 규칙 구현

- [PaymentStatus](../../backend/src/main/java/com/donworry/payment/entity/PaymentStatus.java): 정본의 결제 상태 7개. JPA Entity가 아닌 enum이다.
- [PaymentRules](../../backend/src/main/java/com/donworry/payment/service/PaymentRules.java): 저장·호출·발행 없이 다음 상태와 다음 단계를 반환하는 순수 규칙이다.
- 입력 State는 결제 상태와 승인 사실(NOT_REQUESTED/SUCCEEDED/DECLINED/UNKNOWN)을 함께 받는다. ALLOW 승인 후의 PENDING을 신규 접수로 오인하지 않는다.
- FDS 결과는 점수가 아닌 내부 FdsVerdict를 입력받는다. P2 DTO·승인 결과의 변환과 채택 평가 고정은 W2에서 연결한다.
- FDS ERROR는 RECORD_FDS_ERROR, 승인 기술 실패는 UNKNOWN과 RECONCILE_AUTHORIZATION을 반환한다. 실제 오류 기록·조회·복구는 아직 수행하지 않는다.
- 직원 판정은 HELD에서 CAPTURE/VOID 요청 종류만 반환한다. 판정 후 상태·영속 접수·실행 결과는 이 메서드로 변경하지 않는다.
- 승인된 결제·승인 불명·종료 결제에서 FDS/신규 승인 단계를 재시작하면 예외로 거절한다. 실제 요청 멱등성과 동시성은 저장 계층 작업이다.
- State와 내부 enum은 payment 내부 구현용이다. 공유 DTO·DB 컬럼·Spring Bean 등록을 추가하지 않는다.

## 2. 데이터 소유권과 최소 저장

현재 P5가 소유하는 테이블은 `payments`이며, [생성 SQL](../../backend/src/main/resources/db/migration/V20261022_145100__create_payments_table.sql)에 8개 컬럼이 있다.
전체 업무 테이블 14개의 소유·관계는 [ERD](../erd/README.md)를 따른다. 초기 FDS Baseline의 테이블·컬럼 합계는 현재 스키마 집계로 사용하지 않는다.

| payments 컬럼 | 의미 |
| --- | --- |
| payment_id | P5 결제 식별자 |
| merchant_id | 가맹점 원천 식별자, 다른 모듈 물리 FK 없음 |
| customer_ref | 고객 참조 |
| order_ref | CakeShop 주문 참조 |
| amount | 결제 금액. 외부 요청 금액의 신뢰 검증은 C1 후속 과제 |
| status | 위 P5 상태 |
| authorization_ref | 성공한 승인 식별자. 승인 전 NULL |
| created_at | 최초 결제 접수 시각 |

`auth_expires_at`은 사용하지 않는다. P2의 `rule_set_version`도 이번 범위에 없다.
기존 컬럼 타입·길이는 SQL 기준이다. PK와 참조 ID는 `BIGINT`, 금액은 원 단위 `BIGINT`, 시각은 UTC `DATETIME(6)`이다.
현재 `payments`에는 주문·요청 키 UNIQUE가 없다. 주문별 중복 접수 정책과 보조 저장 제약은 C1·C6에서 정한다.
같은 소유 담당의 테이블끼리만 물리 FK를 사용하고, 담당이 다르면 ID 논리 참조를 사용한다. 같은 investigation 안의 P3/P4도 논리 참조다. 핵심 기록을 임의 삭제하지 않는다.

### 2.1. 기존 저장 구조를 사용하는 범위

- P5 `payments`: 결제 상태와 승인 참조. P6 매입·P3 판정 데이터를 직접 변경하지 않는다.
- P6 `captures`: `payment_id` UNIQUE인 매입 기록. 기존 `capture_id`와 `captured_at`을 완료 통지에 사용한다.
- P6 `settlements`: `capture_id`·`payment_id` 각각 UNIQUE인 정산 기록. 1차는 SCHEDULED까지다.
- P3 `action_handoffs`: 판정별 인계, 멱등 키, `target_operation_id`, `accepted_at`. 매핑 변경은 P3 합의 대상이다.

보조 테이블 3개는 현재 ERD·SQL에 없다. 이를 전제로 한 상세 설계는 [후속 검토](future-design.md)와 [ADR-0010](../decisions/0010-payment-operation-storage.md)에 분리한다.
기존 테이블만으로 모든 실패·중복·경합 문제가 이미 해결된 것은 아니다. 특히 P5가 CAPTURE 요청과 직원 판정을 재시작 후에도 연결할 방법은 C3·C6에서 정해야 한다. 필요하면 담당 ERD 합의 후 별도 스키마 작업으로 진행한다.

## 3. 모듈 간 계약

P2는 현재 코드 계약이다. P3·P6·CakeShop의 아래 계약은 구현 전 제안이며, 이벤트 메타데이터와 구체 DTO는 담당 합의 후 확정한다. ID는 Long, 시각은 Instant(UTC), 금액은 Long(원 단위)을 기준으로 한다.

### 3.1. P2 평가 — fds/api 소유

현재 공개 계약은 [FDS 계약 ADR](../decisions/0014-fds-evaluation-api-contract.md)과 `fds.api` 코드를 따른다. ADR 상태는 제안이며, 재시작 이후 멱등 보장·오류 코드 목록은 별도 합의 대상이다.

| DTO | 필수 필드 | 선택 필드·규칙 |
| --- | --- | --- |
| FdsEvaluationRequest | evaluationRequestId(UUID), paymentId(Long, 양수), customerRef(String, 공백 불가), amount(long, 양수), paymentRequestedAt(Instant) | 모든 필드 필수. paymentRequestedAt은 결제 요청 시각이며 평가 완료 시각과 구분 |
| FdsEvaluationResponse | evaluationRequestId(UUID), evaluationId(Long), result(ALLOW/REVIEW/DENY/ERROR), evaluatedAt(Instant) | 정상 결과는 riskScore(Integer) 0 이상·errorCode null. ERROR는 riskScore null·errorCode 필수 |

기존 초안의 merchantId·orderRef·RiskSignals는 현재 P2 요청 DTO에 없다. 추가가 필요하면 P2와 계약을 먼저 합의한다.
P5 내부 입력은 `payment.service.PaymentEvaluationInput`으로 준비하며 요청 ID와 결제 요청 시각을 전달받아 보존한다. 고정된 모의 값은 테스트 코드에만 둔다.
`PaymentEvaluationService`는 이 입력을 공개 요청 DTO로 변환해 `FdsEvaluator.evaluate()`를 한 번 호출하고 응답을 그대로 반환한다. P5 호출 서비스는 생성자 주입을 받는 일반 클래스이며, 현재 P2에는 #87의 평가 구현 Bean이 있다. P5 Spring Bean 등록과 실제 결제 흐름 연결은 별도 작업이다. 테스트 대역은 테스트 코드에만 둔다. 현재 호출 메서드는 결제 저장·승인·상태 분기를 수행하지 않는다.
요청 형식 오류는 IllegalArgumentException으로 거절한다. 기록 가능한 평가 실패는 ERROR 응답, 응답을 만들 수 없는 호출 실패는 예외로 전달한다. 자동 재시도는 이번 호출 구현에 포함하지 않는다.

동일 evaluationRequestId의 기술 재전송과 명시적 재평가를 구분한다. 응답의 요청·결제 연관성을 확인한 뒤 저장한다.
P2는 요청 재료로 평가하고 P5 Entity·Repository를 역참조하지 않는다.

#### #94 호출·분기 연결 구현

`PaymentEvaluationService`에 다음 메서드를 추가했다. 기존 `evaluate(input)`의 응답 반환 계약은 유지한다.

```java
PaymentEvaluationResult evaluateAndDecide(
        PaymentRules.State state, PaymentEvaluationInput input);

record PaymentEvaluationResult(
        FdsEvaluationResponse evaluation, PaymentRules.Decision decision) {}
```

반환 record는 P5의 `service` 패키지에 둔다. ALLOW·REVIEW는 모두 승인 요청 판단을 반환하지만 승인 성공 후 처리가 다르므로, 원래 판정·평가 ID·오류 코드를 포함한 응답과 판단을 함께 보존한다. `Decision`만 반환하면 이 정보가 사라져 후속 승인 처리에 부족하다. P2 공개 DTO나 외부 API 계약은 변경하지 않는다.

처리 순서는 다음과 같다.

1. state·input 누락과 승인 전 PENDING 조건을 검사한다. 부적합하면 P2를 호출하지 않는다. 상태 조건은 같은 패키지에 공개한 `PaymentRules.requireUnapprovedPending()` 검증을 재사용한다.
2. 기존 `evaluate(input)`으로 평가를 한 번 호출한다.
3. null 응답 또는 요청·응답의 evaluationRequestId 불일치는 IllegalStateException으로 거절한다. P2 호출 예외는 원래 예외를 전달하며 자동 재시도하지 않는다.
4. 응답의 FdsResult를 switch로 P5 FdsVerdict에 명시적으로 매핑한다. 점수로 판정을 다시 계산하지 않는다.
5. `PaymentRules.afterFds(state, verdict)`를 호출하고 원 응답과 Decision을 함께 반환한다.

ALLOW·REVIEW는 PENDING 유지·REQUEST_AUTHORIZATION, DENY는 DENIED·NONE, ERROR는 PENDING 유지·RECORD_FDS_ERROR다. 오류 기록·승인 요청은 필요한 다음 단계의 판단이며 실제 저장·실행은 하지 않는다. 현재 State에는 paymentId가 없으므로 State와 입력이 같은 결제라는 보장은 후속 결제 흐름에서 같은 저장 데이터로 구성해 확보해야 한다.

### 3.2. P3 HELD·직원 조치 — payment/api 소유

HELD 통지는 REVIEW와 승인 성공이 저장된 뒤 전달한다. 직원 ACCEPT는 재승인 없이 매입 요청, REJECT는 승인 취소 요청이다.
P3는 판정과 `action_handoffs(REQUESTED)`를 저장·커밋한 뒤 P5 공개 API를 호출한다. 요청에는 결제·판정·조치·멱등 키의 연결이 필요하며 가맹점·권한 검증은 생략하지 않는다.

CAPTURE 완료 기반 인계 확인을 채택하면 P5가 P6 완료 결과를 반영한 뒤 직원 ACCEPT 건만 P3에 인계 완료를 통지한다. ALLOW 자동 매입에는 Case·직원 판정이 없으므로 P3 인계를 만들지 않는다.
P3는 결제와 판정·인계를 대조한 뒤 `action_handoffs.status = ACCEPTED`로 저장한다. 모든 소속 결제의 판정·인계가 끝난 경우에만 Case를 CLOSED로 변경한다.

매핑 제안은 `target_operation_id = captureId`, `accepted_at = capturedAt`이다. 후자는 완료 기록 시각을 접수 확인 근거로 쓰는 것이며 DB의 정확한 커밋 시각이나 P3 수신 시각은 아니다.
P5→P3 이벤트의 판정/인계 식별 방식·필드·키는 C3·C6에서 확정한다. P5가 P3 저장소를 직접 조회하거나 paymentId만으로 직원 조치 여부를 추정하지 않는다.

최신 dev `d9e4251`의 P3 `FinancialActionGateway.Receipt`는 status·targetOperationId·errorCode를 받고, `HandoffService.applyReceipt()`는 멱등 키로 인계를 찾아 현재 시각을 accepted_at에 사용한다. MockFinancialActionGateway는 즉시 ACCEPTED를 반환한다. 따라서 capturedAt 매핑은 현재 구현 사실이 아니라 P3 입력·시각 처리 변경이 필요한 제안이다. P3의 Receipt.UNKNOWN은 접수 여부 미확인이고, 여기서 제외하는 P6 외부 실행 UNKNOWN과 다르다.

VOID의 접수 증거·식별자·통지 시점은 별도 미결이다. CAPTURE 축소를 근거로 VOID를 이미 구현했거나 완료 후 통지로 합의했다고 간주하지 않는다. 실제 승인 취소 성공 전 VOIDED는 금지한다.

### 3.3. P6 요청·완료 결과 — settlement/api 소유

의존 방향 `payment → settlement`와 타입 위치는 ADR-0012를 유지한다. P6는 P5를 호출·import하지 않는다.

요청은 P6가 매입·정산을 저장하는 데 필요한 결제 ID·가맹점·주문 참조·금액과 수수료 계산 재료를 담아야 한다. `orderRef`와 `captures.order_id` 매핑, 수수료 제공 경로·단위·반올림은 C4에서 정한다. 완료 통지 필드를 줄이는 것이 요청을 paymentId 하나로 줄인다는 뜻은 아니다.

완료 이벤트의 업무 필드는 다음 3개를 제안한다. 구현된 DTO가 아니다.

```java
record CaptureCompleted(Long paymentId, Long captureId, Instant capturedAt) {}
```

| 필드 | 목적 |
| --- | --- |
| paymentId | P5 대상 결제 조회·CAPTURED 반영 |
| captureId | 기존 P6 매입 PK로 결과 추적. P3 인계 대상 ID로 사용 제안 |
| capturedAt | 기존 매입 완료 기록 시각. 재전달 시 원래 값 유지 |

성공 전용 이벤트이므로 `executionStatus = SUCCEEDED`를 별도로 넣지 않는다. `eventId` 등 전달·추적 메타데이터는 C5의 공통 방식으로 정하며, 위 3개 업무 필드가 전달 보장 전체를 뜻하지 않는다.

| 이전 초안 필드 | 목적 | 이번 제안 |
| --- | --- | --- |
| p5OperationId | P5 작업·중계 요청과 결과 연결 | 별도 작업 테이블을 전제하지 않으므로 필수에서 제외. 요청 상관관계 자체는 C3·C6에서 해결 |
| targetOperationId | P6 작업 식별 | 완료 이벤트에서는 실제 기존 PK인 captureId로 표현 |
| authorizationRef | 원 승인과 실행 결과 대조 | P6 완료 필수에서 제외. P5 승인·요청 검증 책임은 유지 |
| attemptNo | 여러 실행 시도의 순서 검증 | 시도별 결과 프로토콜과 함께 후속 검토 |
| resultRef | 성공 결과의 참조 | 별도 외부값을 생성하지 않고 captureId로 추적 |
| UNKNOWN | 외부 실행 성공 여부를 알 수 없는 상태 | 외부 실행 없는 P6 완료 계약에서는 제외 |

### 3.4. 조회·CakeShop

조회는 가맹점 범위 안에서 결제 ID·주문 참조·금액·상태·승인 참조 등 필요한 값을 공개 API로 제공한다. Case 권한 검증과 여러 모듈 데이터 조합은 상위 investigation이 맡는다.
CakeShop에는 실제 P5 결제 상태를 제공하며 HELD·PENDING이나 Case CLOSED를 최종 결제 성공으로 표시하지 않는다. 조회·통지 DTO, 인증, 중복·역순 처리와 수신 확인은 C1·C7에서 정한다. 미구현 history의 sequence_no를 필수 version으로 요구하지 않는다.

## 4. 접수·저장·통지 시점 — 채택 전 제안

접수는 담당 모듈이 요청을 영속적으로 받아 처리한다는 뜻이고, 실행 완료는 실제 매입을 마쳤다는 뜻이다. 1차 제안에서는 별도 접수 커밋 없이 **성공한 매입·정산 저장을 접수 확인의 근거로 함께 사용**한다.

| 단계 | 저장 | 전달 |
| --- | --- | --- |
| P6 | captures COMPLETED + settlements SCHEDULED + 완료 이벤트 발행 기록을 같은 트랜잭션으로 저장 | 커밋 후 P5에 CaptureCompleted |
| P5 | 적법한 매입 요청과 결과를 대조해 payments CAPTURED 및 필요한 후속 통지 발행 기록을 같은 트랜잭션으로 저장 | 커밋 후 직원 ACCEPT 건은 P3 인계 완료, CakeShop에는 실제 결제 결과 |
| P3 | 판정·인계 대조 후 ACCEPTED 및 합의된 대상 ID·시각 저장 | 모든 판정·인계 완료 조건을 만족할 때만 Case CLOSED |

P3·P5·P6 전체를 한 트랜잭션으로 묶지 않는다. 발행 기록은 업무 저장과 함께 남기고 소비자는 커밋 후 처리한다. 현재 Event Publication Registry 기반은 있지만 업무 이벤트·리스너·재발행은 이 기준 커밋에 구현되지 않아 전달 보장은 미검증이다.

이 안에서는 CAPTURE 완료가 확인되기 전에는 그 인계를 ACCEPTED로 만들 수 없다. **기존 정본의 실행 성공 전 Case 종료 가능 정책을 CAPTURE 경로에서 축소**하므로 P3·P6와 [ADR-0016](../decisions/0016-payment-mvp-capture-completion.md) 합의가 필요하다. 채택 전에는 기존 정책을 대체하지 않는다.

## 5. 실패·중복·경합

- P6 업무 저장 실패: 매입·정산·완료 발행 기록을 함께 롤백하고 성공을 통지하지 않는다. P3 인계는 성공 확인 전 REQUESTED 유지가 제안이다. 영구 거절/인계 FAILED 기준, 실패 기록 위치와 재시도 주체·한도는 C3·C4·C6에서 정한다. 전체 롤백만으로 `captures.FAILED`가 저장됐다고 볼 수 없다.
- P6 저장 성공 후 통지 실패: 매입을 새로 만들지 않고 커밋된 완료 통지를 재전달한다. 응답 유실을 매입 실패로 바꾸지 않는다.
- 중복 요청: `captures.payment_id` UNIQUE와 기존 본문 대조를 사용해 같은 결제의 매입·정산을 중복 생성하지 않는다. 유니크 제약만으로 재전달·경합 처리가 완성되지는 않는다.
- P5/P3 소비 실패: 각 소비 트랜잭션을 롤백하고 기존 이벤트를 재처리한다. CAPTURED 반영과 P3 통지 발행 기록이 원자적이어야 P5 반영 후 P3 통지 누락을 복구할 수 있다.
- 중복 완료: 같은 결제·매입 결과는 재반영하지 않는다. 없는 결제, 다른 매입 ID, VOIDED 등 양립할 수 없는 상태는 무조건 CAPTURED로 덮어쓰거나 처리 완료로 버리지 않고 대조·복구 대상으로 둔다. 필요한 검증 근거와 보존 방식은 C4·C6에서 확정한다.
- CAPTURE/VOID 경합: P5의 요청 선택·재시도 정책과 영속 근거로 반대 조치를 차단해야 한다. 비동기 실행 전체를 짧은 P5 행 잠금만으로 보호할 수 있다고 가정하지 않는다.
- CakeShop 통지 실패는 금융 성공을 되돌리지 않는다. 재통지·조회 복구는 C7에서 정한다.

UNKNOWN 프로토콜 제외가 통지 유실·DB 실패·기본 이력·멱등성 요구를 없애지는 않는다. 실패 시 상태와 복구 경로를 확정·검증하기 전에는 전체 시연 완료로 표시하지 않는다.
