# 0026. P5 결제 정보 단건 공개 조회 계약

- **상태:** 제안 — #139 사용자 확정안·구현 승인 및 P3 전달 완료, PR 리뷰 대상
- **날짜:** 2026-10-25
- **관련:** Issue #139, [ADR-0012](0012-payment-settlement-dependency.md), [P5 문서](../payment/README.md)
- **영향 받는 담당:** P5 시은 · P3 수민

## 배경

P3의 PaymentInfoReader는 Case 화면과 보고서에 결제·평가 정보를 공급하지만 현재 MockPaymentInfoStore를 사용한다. P5에는 payments 저장과 내부 Repository 조회가 있으며 다른 모듈에 공개된 결제 조회 인터페이스는 없다.

사용자는 결제 ID 단건 조회, 4개 반환 필드, 없는 결제의 Optional.empty(), P3의 사전 권한 검증과 P5의 세션 독립 조회를 확정하여 P3에 전달했다고 알렸다. P3 목업 교체는 이 작업의 범위가 아니다. 본 ADR의 구현 이름과 오류·트랜잭션 세부를 함께 리뷰한다.

## 결정

`payment.api.PaymentReader.findById(Long paymentId)`가 `Optional<PaymentSummary>`를 반환한다. 같은 백엔드 내부 Java 호출이며 HTTP 엔드포인트를 추가하지 않는다. 기존 `investigation → payment.api` 방향을 유지한다.

| 반환 필드 | 타입 | 저장값·의미 |
| --- | --- | --- |
| paymentId | Long | payments.payment_id |
| amount | Long | payments.amount, 원 단위 |
| paymentStatus | String | payments.status, P5 결제 상태 이름 |
| requestedAt | Instant | payments.created_at, 최초 결제 저장 시각(UTC) |

- 존재하는 결제는 상태에 관계없이 반환한다. 현재 상태 이름은 PENDING·DENIED·DECLINED·HELD·CAPTURED·VOIDED·EXPIRED다. 승인 참조·평가 정보는 반환하지 않는다.
- null·0 이하 입력은 IllegalArgumentException으로 거절한다. 양수 ID에 해당하는 결제가 없으면 Optional.empty()다. DB 실패를 빈 결과로 바꾸지 않는다.
- 공개 DTO는 내부 Entity·PaymentStatus enum에 의존하지 않는다. 서비스가 Entity를 조회하여 DTO로 변환한다. DTO에 Entity를 인자로 받는 변환 메서드를 두지 않는다.
- 읽기 전용 서비스는 기존 findById를 사용한다. 상태 변경·이벤트 발행·비관적 쓰기 잠금을 수행하지 않는다.
- 기본 REQUIRED 읽기 전용 트랜잭션으로 구현한다. 호출자 트랜잭션이 있으면 참여하므로 격리 수준과 영속성 컨텍스트에 따른 조회 결과를 따른다. 같은 장기 트랜잭션 안에서 매 호출마다 외부 커밋의 최신 상태가 보인다고 보장하지 않는다. 상태 변경 커밋 후 새 트랜잭션 조회를 테스트한다.

### 권한 책임

- 입력은 paymentId만 받으며 merchantId·로그인 세션을 요구하지 않는다. P5 조회 자체가 호출자를 인가하거나 가맹점 범위를 검사하지 않는다.
- P3가 화면 접근 권한과 대상 Case의 가맹점 범위를 검증한 뒤 해당 Case에 연결된 결제 ID로 호출한다. 보고서 등 내부 작업도 신뢰할 수 있는 Case 연결에서 ID를 얻어야 한다.
- 클라이언트가 보낸 임의 paymentId를 검증 없이 전달하거나 이 인터페이스를 그대로 무인가 HTTP 조회로 노출하지 않는다.
- 세션이 없는 호출 허용은 모든 사용자의 조회 허용을 뜻하지 않는다. 실제 P3 접근 제어 연결은 별도 구현·검증 대상이다.

### 호출 예시

서비스 구현 이후 P3에서 생성자 주입으로 PaymentReader를 받아 다음처럼 사용할 수 있다. 이 예시는 P3의 권한 검사 및 P2 조회 조합을 대신하지 않는다.

```java
Optional<PaymentSummary> payment = paymentReader.findById(authorizedPaymentId);
```

위 authorizedPaymentId는 권한을 검증한 Case에 연결된 **P5 paymentId**이며 Case 연결 행의 PK가 아니다.

### 추가 — 고객별 최근 결제 (#338, P5 허락)

`PaymentReader.findRecentByCustomer(Long merchantId, String customerRef, Instant since, int limit)`가 `List<PaymentSummary>`를 반환한다. 위 "목록 조회는 별도 계약으로 확장"에 따른 첫 확장이며, P4 AI 보고서의 고객 최근 결제(`get_payment_history`, [ADR-0009](0009-ai-investigation-call-structure.md))가 쓴다.

- 같은 `merchant_id` + `customer_ref`, `created_at >= since`인 결제를 `created_at` 최근 순(같으면 `payment_id` 큰 것 먼저)으로 최대 `limit`건. `PaymentSummary` 4필드는 그대로다
- merchantId · customerRef · since가 비었거나 `limit`이 1~100 밖이면 IllegalArgumentException. 결과가 없으면 빈 목록이다
- 권한 책임은 단건 조회와 같다. P5는 호출자를 인가하지 않고, 호출자(P4)가 검증한 Case의 가맹점 · 고객으로만 부른다
- 읽기 전용 트랜잭션, 스키마 변경 없음. `(merchant_id, customer_ref, created_at)` 인덱스는 추가하지 않았다 (시연 규모. 필요하면 P5가 정한다)

## 고려한 대안

| 대안 | 버린 이유 |
| --- | --- |
| P3에서 PaymentRepository·Entity 직접 참조 | 모듈 내부 접근이며 AGENTS 4절·ADR-0012 위반 |
| HTTP 조회 엔드포인트부터 추가 | 같은 앱 내부 연동에 불필요하며 외부 인증·응답 계약까지 범위가 넓어진다 |
| P5에서 로그인 세션을 필수로 요구 | 세션이 없는 보고서 등 내부 처리에 사용할 수 없다. P3가 접근 권한과 대상 범위를 검증한다 |
| merchantId를 필수 입력에 추가 | 이번 확정안은 P3가 검증한 Case 연결 ID를 사용하는 단건 조회다 |
| P5가 FDS·Case 정보를 함께 반환 | 다른 모듈 소유 정보의 조합은 상위 P3 책임이다 |
| 목록·검색·일괄 조회 동시 구현 | 현재 단건 요구부터 충족하고 별도 계약으로 확장한다 |
| 조회마다 REQUIRES_NEW 강제 | 호출자의 조회 트랜잭션과 분리된 스냅샷을 강제하지 않는다 |

## 영향

- API: PaymentReader·PaymentSummary 추가. 기존 이벤트·HTTP 계약 변경 없음.
- DB: 기존 payments 조회만 사용. 스키마·저장 방식 변경 없음.
- 화면: 이번 작업만으로 목업 정보가 실제 정보로 바뀌지 않는다. P3가 조회 어댑터를 연결해야 한다.
- 테스트: 후속 서비스 단위 검사 및 MariaDB 저장값·시각 정밀도·상태 변경 후 조회·세션 없는 호출 검증.
- 문서: ADR 목록과 P5 README에 계약 링크 및 구현 단계를 기록한다.

## 검증

3번 단계에서 공개 인터페이스·DTO를 정의했고 4번 단계에서 조회 구현 Bean·입력 검증·Repository 조회를 연결했다. 서비스 단위 테스트와 별도로 5번 단계에서 실제 DB·트랜잭션·세션 없는 호출을 검증했다. P3 실제 연결은 미검증이다. 실행 결과는 [P5 검증 문서](../payment/verification.md)에 기록한다.
