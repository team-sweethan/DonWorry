# #149 CakeShop 결제 요청·조회 설계

- 상태: **사용자 저장 구조 방향 승인. 저장 기반 구현, 접수/재개 서비스·HTTP·담당 인증 연결은 후속.**
- 기준: `dev 84698bb`, 2026-10-25. migration 전체를 버전 순으로 읽고 P5 생성·승인·완료·조회와 P2 평가 재호출 코드를 대조했다.
- [Issue #149](https://github.com/team-sweethan/DonWorry/issues/149), [ADR-0030](../decisions/0030-cakeshop-payment-attempts.md), [ERD 제안](../erd/README.md#149-p5-cakeshop-결제-저장-제안).
- 2026-10-25 사용자는 payment_orders·payment_requests 신규와 payments.status_changed_at·decline_code 추가 방향에 동의했다. 이를 저장 구조 승인으로 기록하며 실제 DB 적용·타 담당 회신·Notion 반영 완료와 구분한다. 상세 제약과 연동 세부는 아래 설계 기준으로 검토한다.

## 1. 쉬운 설명과 범위

한 주문에서 결제를 거절당하면 다시 결제할 수 있다. 그러나 응답을 못 받아 같은 요청을 다시 보냈다고 결제가 두 번 생겨서는 안 된다.

따라서 **주문 하나를 관리하는 기록**과 **그 주문의 결제 시도마다 남는 기록**을 구분한다. 같은 요청 식별값은 같은 시도이고, 최종 거절 후 다른 식별값을 보내야 새 시도다. payments 한 행은 한 시도이며 기존 paymentId·P2 평가·P6 매입·P3 Case 연결은 그대로 유지한다. 직원 조치용 payment_actions는 최초 결제 요청 저장소로 사용하지 않는다.

P5: HTTP 요청·조회, 가맹점 범위, 결제 처리·중복 방지·복원. P1/CakeShop: 고객·주문 소유권, 주문 기한·금액·재고·쿠폰, API 호출·화면·서버 조회. API Key 소유와 인증 인터페이스는 P1 수락 대기다. 환불·별도 통지·장기 보류 자동 취소는 별도 작업이다.

## 2. 확정 수준

| 구분 | 내용 |
| --- | --- |
| P1 답변으로 방향 확인 | 서버 간 호출·가맹점 API Key, 서버 확정 금액, 시도별 동일 키 재전송, DENIED/DECLINED 후 조건부 새 시도, 요청·조회 우선 |
| P1 답변으로 방향 확인 | 요청 전 10분 기한, 결과 미확인/PENDING/HELD 동안 자동 만료 보류, 최종 거절 후 기한 경과 시 주문 만료 |
| 사용자 결정·추가 전달, 상대 수락 대기 | 재고 사전 예약·결과별 해제·재확보, P1 identity의 키 발급/검증/폐기, 장기 보류 정책 공동 확정 |
| 이번 문서의 새 제안 | 아래 URI·JSON·HTTP 코드·기한 필드·고객 참조 변환·저장 2개 테이블·상태 시각 의미·복구 단계 |
| 별도 미정 | 최대 보류 시간·취소 경합, 장기 서버 조회 주기/종료, 정지 가맹점의 기존 결제 조회 허용 정책 |

## 3. HTTP 계약 제안

### 인증·입력

서버 간 HTTPS 호출. 키 헤더 이름·오류 코드·CSRF 정확한 예외 범위는 P1과 확정한다. P5는 identity.api가 제공하는 **인증된 merchantId**를 사용한다. 세션 CurrentStaff를 서버 인증 대신 쓰지 않는다. body/query의 merchantId를 신뢰하지 않고 이번 요청에는 포함하지 않는다. 기존 PaymentReader의 세션 비의존 Java 계약은 바꾸지 않는다.

P1이 가맹점 인증 공개 계약을 제공하기 전에는 운영용 무인증 대체 구현을 만들지 않는다. DTO·내부 서비스 테스트는 대역으로 가능하지만 인증 실제 연결 미검증으로 표시한다. TenantReader로 가맹점 존재/상태를 검사하며 정지 가맹점의 신규 결제는 금지한다. 기존 건 조회 허용 여부는 결과 확인 필요성과 키 폐기 정책을 함께 P1과 정한다.

| 요청 | 의미 |
| --- | --- |
| POST /api/payments | 최초 결제 요청 또는 같은 시도의 재전송 |
| GET /api/payments/{paymentId} | 해당 가맹점의 특정 시도 조회 |
| GET /api/payment-orders?orderRef=...&beforeAttemptNo=...&size=20 | 해당 가맹점 주문의 최신 시도와 과거 시도 조회 |

POST 헤더: `Idempotency-Key: <UUID>` 제안. 키는 가맹점 범위이며 원문 키·API Key를 로그에 남기지 않는다. 요청 본문 예:

```json
{
  "orderRef": "ORDER-20261025-0001",
  "customerRef": "42",
  "amount": 25000,
  "orderCreatedAt": "2026-10-25T10:00:00Z"
}
```

- orderRef: orders.order_number 원문, 비어 있지 않은 100자 이하. 대소문자 보존·앞뒤 공백 거절. DB 비교도 대소문자를 구분하도록 collation을 명시하는 안이다.
- customerRef: orders.member_id를 양수 10진 문자열로 표현하는 제안. 선행 0 없이 최대 19자리 및 Long 범위. 외부로 돌려줄 필요는 없다.
- P2는 현재 customerRef만으로 반복 결제를 집계한다. 다중 가맹점의 member_id 충돌을 피하려면 내부 customerRef를 `merchantId:memberId`로 만드는 안을 P2·P3와 확인한다. P2/PaymentHeld에는 같은 내부 참조를 전달한다. 기존 원시 참조 데이터는 추정 변환하지 않는다. 이 항목 확정 전 전역 고객 식별을 가정하지 않는다.
- amount: 양수 Long, KRW 정수. CakeShop 서버가 확정한 금액을 신뢰하는 인증된 호출 계약이며 P5가 상품 가격을 재계산하지 않는다.
- orderCreatedAt: 최초 주문 생성 시각. UTC Instant, 마이크로초 이하 정밀도, 미래 시각 거절(서버 시계 동기화 필요). P5는 최초 기한을 +10분으로 계산해 보존한다. 주문 기한을 요청마다 늘릴 수 없다. 이 추가 필드 제공 가능 여부는 P1 확인 대상이다.
- 같은 주문의 customerRef·amount·orderCreatedAt은 모든 시도에서 동일하다. 금액 변경은 새 주문번호를 사용한다는 제한을 P1과 확인한다.
- 주문이 여전히 결제 대기이고 재고·쿠폰을 확보했는지는 CakeShop 책임이다. P5는 외부 주문 DB를 확인했다고 주장하지 않는다.

### 단건 응답

```json
{
  "paymentId": 101,
  "orderRef": "ORDER-20261025-0001",
  "attemptNo": 1,
  "paymentStatus": "PENDING",
  "requestedAt": "2026-10-25T10:01:00Z",
  "statusChangedAt": "2026-10-25T10:01:00Z",
  "amount": 25000,
  "declineCode": null
}
```

- POST: PENDING/HELD면 202, 최종 상태 스냅샷이면 200 제안. 최초/반복 모두 같은 응답 구조이며 Location에 단건 조회 주소를 제공한다. 202는 결제 성공이 아니다.
- GET: 조회 성공 200. Cache-Control: no-store. 조회는 읽기 전용이며 평가·승인·재전달을 실행하지 않는다.
- DENIED/DECLINED가 담긴 정상 조회/접수 결과와 형식·인증·처리 예외를 구분한다. 결과가 거절됐다고 HTTP 오류로 숨기지 않는다. 업무 처리 자체가 실패한 요청은 아래 ProblemDetail 오류다.
- authorizationRef, FDS 점수·규칙·내부 오류, API Key는 응답하지 않는다.
- statusChangedAt은 **P5가 현재 paymentStatus로 전이시킨 시각**이다. P6 capturedAt·P3 acceptedAt과 다르다. 생성 시 requestedAt, 동일 상태 재반영/단순 조회/중복 완료에는 갱신하지 않는다.
- declineCode는 최종 DENIED/DECLINED의 외부용 사유만: FDS_REJECTED / AUTHORIZATION_DECLINED 제안. 구체 탐지 규칙이나 승인기 내부 오류를 그대로 내보내지 않는다. 그 외 상태는 null. VOIDED는 거절이 아니라 취소 상태다.
- 이전 데이터의 실제 상태 변경 시각·거절 이유는 알 수 없으면 null. createdAt을 과거 변경 시각으로 꾸며 백필하지 않는다.

### 주문 조회

응답: orderRef, latestPayment(최신 attemptNo의 단건 DTO), attempts(내림차순 페이지), nextBeforeAttemptNo. latestPayment는 진행 중이라는 뜻이 아니며 DENIED/DECLINED일 수도 있다. 현재 주문 상태를 P5가 계산해 반환하지 않는다.

size 기본 20/최대 100, beforeAttemptNo 양수·미만 조건. 다음 페이지에도 최신 시도는 별도로 반환하며 새 시도가 생기면 최신값이 달라질 수 있다. 각 응답 안 latest/목록은 한 읽기 트랜잭션 스냅샷으로 조회한다. 오래된 응답으로 새 시도를 덮지 않도록 CakeShop은 attemptNo와 결제 ID를 대조한다. 시각은 정렬용 이벤트 버전을 대신하지 않는다.

이 주문 목록은 #149로 등록된 시도만 대상으로 한다. 기존 paymentId 단건 조회는 같은 가맹점이면 허용하되 attemptNo=null일 수 있다. 기존 동일 merchantId/orderRef 결제가 있으면 새 주문 등록을 차단해 이중 결제를 막고 별도 이관 대상으로 남긴다. 과거 결제를 자동으로 승인된 새 HTTP 요청으로 간주하지 않는다.

### 오류 제안

ProblemDetail + code. 공통 에러 클래스를 임의로 신설하지 않고 payment 범위 처리기 또는 합의된 공통 계약을 사용한다. 숫자형 최종 코드와 P1 인증 오류는 4번 단계에서 정한다.

| HTTP | 의미 코드(가칭) | 처리 |
| --- | --- | --- |
| 400 | INVALID_REQUEST | 키/JSON/기한/ID 형식 오류 |
| 401 | P1 인증 오류 | 키 누락·무효, 결제 생성 없음 |
| 403 | MERCHANT_INACTIVE | 정지 가맹점 신규 요청 금지 |
| 404 | PAYMENT_NOT_FOUND / ORDER_NOT_FOUND | 없는 건과 타 가맹점 건을 구분해 노출하지 않음 |
| 409 | IDEMPOTENCY_CONFLICT | 같은 키의 다른 내용 |
| 409 | ORDER_CONTENT_CONFLICT | 같은 주문의 금액·고객·최초 시각 변경 |
| 409 | PAYMENT_ATTEMPT_BLOCKED | 진행 중·완료·취소 등 새 시도 불허 또는 기존 미이관 주문 |
| 409 | ORDER_DEADLINE_PASSED | 새 시도 접수 기한 경과 |
| 503 | PAYMENT_PROCESSING_UNAVAILABLE | 일시 장애. 같은 키로 확인, 새 키 생성 금지 |
| 500 | PAYMENT_INTERNAL_ERROR | 예상하지 못한 내부 오류, 결과 미확정 |

식별값 검증 전·인증 전에는 결제 존재를 알려주지 않는다. 기술 오류 응답을 최종 결제 실패나 예약 해제 근거로 사용하지 않는다.

## 4. 새 시도·반복 요청 규칙

1. 인증·형식 확인 후 `(merchantId, idempotencyKey)`를 먼저 대조한다. 같은 키·같은 원 요청이면 기존 paymentId를 사용한다. 이미 끝난 옛 시도도 원래 결과를 반환한다. 새 최신 시도로 바꿔 반환하지 않는다.
2. 같은 키의 내용 변경은 기한·결제 상태에 앞서 충돌이다. 거절된 잘못된 요청까지 영구 캐시한다는 계약은 아니다.
3. 새 키라면 주문을 잠그고, 주문 내용과 최신 시도의 상태를 확인한다.
4. 최신 시도 없음 또는 DENIED/DECLINED이고 `접수 시각 < 최초 주문 시각 + 10분`일 때만 새 시도 허용. 정확히 기한에 도달하면 금지한다. PENDING/HELD/CAPTURED/VOIDED/EXPIRED는 새 시도를 차단한다.
5. 같은 키의 접수된 시도 재조회·복원은 최초 기한이 지나도 가능하다. 새 시도를 금지하는 기한이지 기존 시도를 자동 취소하는 기한이 아니다.
6. 거절 상태를 최종 상태로 유지하고 이전 시도를 다시 승인하지 않는다. CakeShop은 새 시도 전에 주문 상태·재고·쿠폰을 다시 확인한다.

PENDING은 최초 평가 중·오류로 멈춤·매입 대기를 함께 포함한다. 기술 실패 때문에 새 PaymentStatus나 가짜 DENIED를 만들지 않는다. 승인 취소 실패도 HELD 유지다. 최대 보류 기간·취소·재고 해제는 이 계약만으로 해결되지 않으며 실연동 선행 후속이다.

## 5. 저장 제안과 트랜잭션

상세 컬럼은 [ERD 제안](../erd/README.md#149-p5-cakeshop-결제-저장-제안)에 기록한다. **테이블 2개와 컬럼 2개 추가 방향은 사용자 승인, migration·Entity·Repository는 작업 브랜치에 구현**했다. 운영 DB 적용·Notion 동기화는 미수행이다. 아래 접수·재개 서비스는 후속 구현이다.

- payment_orders: 가맹점+주문별 불변 입력과 순번·잠금 기준. CakeShop 주문 전체를 복제하지 않는다.
- payment_requests: 시도별 키, paymentId, 평가 요청 UUID·응답 스냅샷·처리 단계. 한 시도에 payments 하나. 실제 결제 상태는 payments만 정본이다.
- payments: status_changed_at·decline_code 추가. 내부 PaymentReader 4필드와 payment_actions는 유지한다.

### 주문 접수

주문행 확보 → 주문 FOR UPDATE → 결제 → 요청 순으로 잠근다. 최초 주문행 INSERT 경합은 UNIQUE로 막고, 실패 트랜잭션이 끝난 뒤 새 트랜잭션에서 재조회한다. 주문행만 따로 생성돼 남아도 결제 접수를 뜻하지 않으며 시도 없는 행은 조회 시 미접수로 처리한다.

새 payments·payment_requests·주문 attempt 순번 갱신은 한 트랜잭션이다. 기존 createPendingSnapshot의 별도 커밋을 그대로 호출하면 키 없이 결제만 남을 수 있으므로 HTTP 접수에서는 같은 트랜잭션 저장을 새로 구성한다. 기존 내부 생성 진입점은 유지한다.

다른 주문이 같은 가맹점 키를 동시에 쓰면 요청 UNIQUE 실패 시 생성한 payment·순번까지 롤백한다. 실패 트랜잭션 밖에서 저장 키/입력을 대조해 재사용 또는 충돌을 반환한다. 모든 DB 오류를 키 충돌로 바꾸지 않는다.

### 평가와 승인 복원

외부 HTTP를 DB 트랜잭션 안에서 호출하지 않는다. P2는 같은 앱의 공개 Java API로 호출한다.

| 단계 | 동작·장애 시 복원 |
| --- | --- |
| RECEIVED | 결제·키·고정 evaluationRequestId 저장 완료. 커밋 뒤 저장값으로 P2 호출 |
| EVALUATED | P2 성공 응답 전체를 요청행에 저장. 저장 전 중단되면 같은 UUID·같은 입력으로 재호출 |
| AUTHORIZED | 모의 승인/거절·결제 상태·발행 기록·단계 변경을 한 트랜잭션에 저장. 재호출은 재승인하지 않음 |
| NEEDS_REVIEW | P2 ERROR 응답 보존. 자동 ALLOW·새 UUID 재평가를 하지 않고 PENDING 유지, 운영 확인 필요 |

최초 POST와 같은 키 POST가 처리 재개를 시도한다. GET은 절대 쓰지 않는다. 같은 키 동시 P2 호출은 현재 P2 UUID UNIQUE/기존 응답 재사용으로 수렴하는 것을 통합 검증해야 한다. P2는 현재 같은 UUID의 입력 변경을 검증하지 않으므로 P5가 저장 입력을 고정한다. ERROR 응답을 새 평가로 바꾸는 계약과 재평가 API는 별도 P2 확인 사항이다.

평가 응답 반영·승인 시 payments → payment_requests 잠금 아래 단계 재확인. 늦은 동시 호출은 먼저 확정된 스냅샷을 덮지 않는다. 주문이 필요한 경로는 반드시 주문부터 잠그고, 결제 잠금 후 역방향으로 주문 잠금을 얻지 않는다. P6 완료·직원 조치는 기존 payments → payment_actions 순서를 유지하고 주문행을 수정하지 않는다.

모의 승인 실행은 외부 부작용이 없어서 결제 잠금 안 실행·원자 저장이 가능하다. 평가 후 기존 authorize를 분리 커밋으로 호출한 다음 단계만 저장하면 중단 구간이 생기므로, 공통 승인 로직을 트랜잭션 내부 실행으로 추출하는 안이다. 기존 내부 서비스 계약·테스트는 보존한다. 실제 카드사로 교체할 때는 별도 실행·복구 설계가 필요하다.

AUTHORIZED 이후 매입 미완료는 기존 CaptureRequested/CaptureCompleted 발행 기록으로 복구한다. 같은 POST를 받았다고 새 매입 이벤트를 발행하지 않는다. #149는 서버 재시작 자동 처리 스케줄러를 추가하지 않는다. CakeShop 백그라운드 GET만으로 RECEIVED/NEEDS_REVIEW가 진행된다고 보장하지 않는다. 같은 키 POST 재시도 주체·상한과 장기 미처리 운영 절차를 4번 단계에서 명확히 한다.

## 6. 호환성과 이관

- 기존 ALLOW·HELD·직원 CAPTURE/VOID·PaymentReader를 유지한다. 새 상태 시각은 모든 실제 전이에서 기록하고 중복 이벤트로 바꾸지 않는다.
- payment_requests에는 기존 결제를 임의 백필하지 않는다. 이전 상태 시각·사유도 추정하지 않는다.
- 주문·키의 문자열 비교를 앱과 DB에서 일치시킨다. 순번 증가·입력 비교·범위 제한은 서비스 규칙과 실제 MariaDB 동시성 테스트로 검증한다.
- 다른 담당 테이블·Entity를 변경하지 않는다. P1 merchant/P2 evaluation은 논리 참조이며 FK 없음. P5 내부 FK만 사용하고 삭제 연쇄는 금지한다.
- migration은 담당 상세 ERD 확인 뒤 newMigration으로 생성한다. 제안 배포 단위: payments의 조회 메타데이터, 주문 접수 기반, 주문 접수 기반에 의존하는 시도 저장. 의존 파일은 같은 PR이며 버전 순서와 out-of-order 적용 영향을 확인한다.

## 7. 다음 단계에서 확인할 질문

[담당자별 쉬운 전달 문안과 회신 기록](149-integration-confirmation.md)을 작성했다. 아직 발송·추가 회신 확인 전이며, 문안 작성은 계약 확정을 뜻하지 않는다.

| 대상 | 구체적인 확인 |
| --- | --- |
| P1 | 키 발급/폐기·헤더·인증된 merchantId 인터페이스·정지 가맹점 조회·HTTP 권한/CSRF |
| P1/CakeShop | orderCreatedAt 제공, 10분 고정·동일 주문 금액/고객 불변, URI·응답·오류·페이지 규칙 |
| P1/CakeShop | 재고·쿠폰 예약 구현 수락, 재결제 시 재확보, 서버 조회 및 같은 키 POST 재시도 담당/상한 |
| P2·P3 | merchantId:memberId 고객 참조의 일관성, 기존 고객 참조 이관 여부 |
| P2 | 같은 고정 UUID로 기존 응답을 복원하는 계약과 ERROR 재평가 정책 |
| P5 담당 | 새 2개 테이블·payments 2컬럼 추가 방향은 사용자 승인. 상세 제약 검토·Notion 반영 방식 정리 |
| 별도 후속 | 최대 보류 시간·취소와 매입 경합, 예약 해제와 종료 기준. 시간 수치 미확정 |

## 8. 검증 계획과 이번 실행

후속 검사: 키 내용 충돌·같은 키/다른 키 동시 접수·서로 다른 주문 동일 키·가맹점 간 키/주문 격리, 기한 경계와 거절 후 새 시도, 오래된 키 재전송, P2 응답 전후 중단·동시 복원·ERROR 유지, 승인 커밋 실패·중복 이벤트, 상태 시각 불변·거절 코드, 과거 null·미이관 주문 차단, 요청·조회 인증/가맹점 격리·페이지·처리 중/거절 상태 의미, 기존 전체 회귀.

이번 3번 단계는 문서만 작성했다. Java/SQL/API Key/운영 정책은 미구현, backend·MariaDB·HTTP 테스트는 미실행이다. `python3 .github/scripts/check_md_links.py`와 `git diff --check`를 실행해 통과했다. 이전 작업의 통과 건수를 이번 API 검증으로 인용하지 않는다.


### 5번 단계 — 저장 기반 구현

- `payment_orders`, `payment_requests`와 `payments.status_changed_at`, `decline_code`를 Flyway migration 3개로 추가했다. 기존 행의 상태 시각·거절 사유는 추정하지 않고 NULL로 둔다.
- 가맹점별 주문/키, 주문별 시도 순번, 결제별 요청, 평가 UUID의 UNIQUE와 P5 소유 FK·단계별 CHECK를 추가했다.
- Entity에서 불변 주문 입력 대조·최초 주문 기준 10분 접수 기한, 평가 스냅샷 보존·동일 결과 재반영·충돌 거절을 제공한다. ERROR는 NEEDS_REVIEW로 보존한다.
- 신규 결제는 생성 시각을 상태 시각으로 사용한다. 실제 상태 전이만 시각을 변경하며 CAPTURED 중복 반영이나 승인 후 PENDING 유지에서는 바꾸지 않는다. 거절 사유는 FDS_REJECTED / AUTHORIZATION_DECLINED다.
- **아직 미구현:** 실제 요청 접수 서비스의 잠금·키 재사용·거절 후 재결제 정책·기존 주문 차단, P2 호출/승인 복원 연결, HTTP·인증·페이지 조회. DB UNIQUE가 HTTP 멱등 처리까지 완성했다는 뜻은 아니다.
- **계약 확인:** API Key 공개 경계·고객 참조·장기 보류 기준은 기존 확인 문서에 따라 후속 확인한다. 이번 변경은 다른 담당 코드나 테이블을 수정하지 않는다.

검증 기준: `84698bb` 위 이번 미커밋 작업 트리, 2026-10-25. `./gradlew spotlessApply check mariaDbTest` 통과(전체 Flyway 적용·JPA validate 포함). 신규 단위 테스트 5개와 MariaDB 테스트 5개에서 기한 경계·상태 시각·거절 사유·평가 스냅샷 복원/충돌·ERROR 보존·키 충돌 롤백·가맹점 격리·DB 제약·과거 NULL 호환을 검증했다. HTTP·동시 접수/재결제 서비스·P1 인증 연동·운영 DB 적용은 미검증이다. 기존 명시적 비활성 LocalSettlementDemoTests 1건은 실행 제외다.
