# 0064. 돈워리 호스팅 결제창과 결제 상태 웹훅

- **상태:** 제안 — 로컬 샌드박스 구현·확인 완료, 저장소 구현 전. P1 확인 완료(2026-11-02, PR #403). 사후 거절 시 정산 보장은 P6 확인 필요
- **날짜:** 2026-11-01 (2026-11-02 개정: P1 확인 반영, 사후 거절을 이행 전·후로 나눔, 웹훅 역할 분담)
- **관련:** #402, #149, #154, #242, #255, [ADR-0037](0037-merchant-hmac-authentication.md), [ADR-0059](0059-security-default-deny.md), [ADR-0058](0058-captured-refund-contract.md)
- **영향 받는 담당:** P5(결제 — 구현, CakeShop 연동 포함), P1(`identity/api/MerchantWebhookSigner` 구현, `SecurityConfig` 리뷰), P6(사후 거절 시 정산 보장 — 4절), CakeShop 저장소(`team-sweethan/cakeshop_refactoring`). P2·P3는 기존 결제 흐름을 그대로 쓰므로 계약 변경 없음

## 배경

- #149로 CakeShop 서버가 서명(ADR-0037)한 `POST /api/payments`로 결제를 요청하고 조회할 수 있다. 이 방식은 카드·기기·IP 같은 위험 신호를 **CakeShop 서버가 모아 보내야** 한다. 구매자 브라우저의 기기·IP를 가맹점이 정확히 대신 보내기 어렵고, 카드 정보를 가맹점 서버가 다루게 된다.
- 실제 PG(Toss·이니시스 등)는 PG가 결제창을 띄우고 가맹점은 결제를 준비·확인만 한다. 돈워리도 같은 구조여야 FDS가 구매자 브라우저의 신호를 직접 받는다.
- HELD 결제는 보안 직원 판정까지 몇 시간이 걸릴 수 있다. 판정 결과(VOIDED 등)를 CakeShop이 알 방법이 조회뿐이라 #154에서 별도 통지 도입 여부를 검토 중이었다.
- CakeShop 실제 연동은 #242에서 담당·범위 재협의 전까지 보류 상태다(2026-10-28 P1 결정). 이 ADR은 P5 인수자가 연동 방식을 제안하고 재개를 요청하는 근거다.
- 로컬 샌드박스(원본과 분리된 복사본, 원격 없음)에서 아래 결정대로 돈워리·CakeShop을 구현했고, 결제 완료·HELD → 직원 거절 → CakeShop 주문 취소·웹훅 실패 후 재전송을 화면과 두 DB로 확인했다.

## 결정

**돈워리가 결제창을 호스팅하고, 결제 상태가 바뀌면 가맹점에 서명한 웹훅을 보낸다. 가맹점은 웹훅 본문을 믿지 않고 기존 서명 조회로 다시 확인해 반영하며, 웹훅을 놓쳐도 조회 배치로 맞춘다.**

### 1. 결제 준비 — `POST /api/payment-sessions` (가맹점 서버 → 돈워리)

- 인증: ADR-0037 가맹점 HMAC 서명 + `Idempotency-Key`(UUID). 같은 키·같은 내용이면 같은 세션, 다른 내용이면 409 `IDEMPOTENCY_KEY_CONFLICT`
- 요청: `orderRef`, `customerRef`(숫자 문자열), `amount`, `orderCreatedAt`, `orderName`, `buyerEmail`(선택), `successUrl`, `failUrl`, `notifyUrl`(선택). 금액·주문번호·고객 참조·주문 시각 규칙은 `POST /api/payments`(#149)와 같다. **카드·기기·IP는 받지 않는다**
- `successUrl`과 `failUrl`은 같은 출처(scheme·host·port)여야 한다. 이 출처가 결제창이 결과를 알리는 유일한 대상이다
- 응답: `sessionKey`(`pss_` + 128비트 난수), `checkoutUrl`(`{공개 주소}/checkout/{sessionKey}`), `expiresAt`(= `orderCreatedAt` + 10분, #149 결제 접수 기한과 같음). 기한이 지난 주문은 409 `ORDER_EXPIRED`
- 세션은 결제 시도 키(`attemptKey`)를 하나 갖는다. 결제창에서 여러 번 눌러도 같은 시도라 결제가 두 번 생기지 않는다

### 2. 결제창 — 공개 API와 화면 (구매자 브라우저 → 돈워리)

| 경로 | 하는 일 |
| --- | --- |
| `GET /api/checkout/{sessionKey}` | 가맹점 이름·주문명·금액·만료 여부·모의 카드 목록·결과(있으면)·취소 주소·가맹점 출처 |
| `POST /api/checkout/{sessionKey}/payments` | 모의 카드 선택 + 기기 ID로 결제. 기존 결제 접수·FDS 평가·모의 승인 흐름(`PaymentRequestFlowService`)을 그대로 탄다. IP는 요청에서 돈워리가 직접 읽는다 |
| `GET /api/checkout/{sessionKey}/result` | 처리 중이면 결제창이 다시 조회 |

- **로그인·쿠키를 쓰지 않는다.** 권한은 추측할 수 없는 세션 키뿐이다. 응답에 가맹점 내부 값(`customerRef`·`notifyUrl`)을 넣지 않고 `Cache-Control: no-store`를 붙인다
- **CSRF 검사에서 제외한다.** CSRF는 쿠키로 인증하는 요청을 지키는 장치인데 이 경로는 쿠키 인증이 없다. 또 결제창은 다른 사이트(CakeShop) 안의 iframe으로 열리므로 돈워리의 `SameSite=Lax` 쿠키(`XSRF-TOKEN`)가 전송되지 않는다. 검사를 켜 두면 결제 버튼이 403이 된다. CSRF 검사는 #416(ADR-0063)에서 기본으로 켜졌으므로 결제창 구현 PR에서 이 세 경로를 반드시 제외한다
- ADR-0059 기본 거절 아래에서 위 세 경로만 정확한 메서드·경로로 연다. ADR-0063이 직원 체인(`SecurityConfig`)에 새 CSRF 예외를 금지하므로, CakeShop 서명·AI 서버처럼 **결제창 전용 체인 `identity/security/CheckoutSecurityConfiguration`**(세션·쿠키 없음, CSRF 끔, 다른 메서드는 403)에 둔다(2026-11-02 구현 PR, P1 리뷰)
- 결과: 카드 승인(`authorized`)이고 상태가 `PENDING`·`HELD`·`CAPTURED`면 `successUrl?paymentKey=&orderId=&amount=`, 그 밖에는 `failUrl?code=`, 구매자가 닫으면 `failUrl?code=USER_CANCELED`
- 화면 `/checkout/:sessionKey`(돈워리 프론트, 로그인 없음): iframe 안에서 열렸으면 높이 변화(`donworry-checkout-resize`)와 결과 이동(`donworry-checkout`)을 `postMessage`로 **가맹점 출처에만** 보낸다. 가맹점은 `event.origin`을 확인하고 같은 출처 주소로만 이동한다

### 3. 결제 상태 웹훅 (돈워리 → 가맹점 `notifyUrl`)

- 언제: 결제창에서 결제가 접수된 뒤, 그리고 그 결제의 (상태, 승인 여부)가 마지막으로 보낸 값과 달라질 때마다. 예) `HELD` → 직원 거절 → `VOIDED`
- 본문(JSON): `eventId`(`evt_{paymentId}_{상태}_{A|N}` — 같은 상태 변화는 같은 ID), `type`(`payment.status_changed`), `paymentId`, `orderRef`, `paymentStatus`, `authorized`, `amount`, `occurredAt`
- 헤더: `X-Merchant-Code`, `X-Timestamp`, `X-Event-Id`, `X-Signature`
- 서명: 가맹점 API 서명(ADR-0037)과 **같은 가맹점 키**, 다른 접두어 `DONWORRY-WEBHOOK-V1`. 서명 원문은 `DONWORRY-WEBHOOK-V1\n{가맹점 코드}\n{timestamp}\n{eventId}\n{sha256(body) hex}`. 접두어가 달라 웹훅 서명을 API 요청 서명으로 재사용할 수 없다. 가맹점은 시각을 ±300초로 검사한다
- 서명기는 키를 가진 identity가 `identity/api/MerchantWebhookSigner`로 공개한다. payment는 키를 직접 읽지 않는다
- 역할(2026-11-02 P1 합의): identity 서명 기능은 P1이 구현하고, 돈워리 웹훅 발송과 CakeShop 수신·검증은 P5가 구현한다
- 웹훅을 이번 범위에 넣는 이유: 4절처럼 "제작 전이면 취소"로 하려면 직원 거절을 CakeShop이 제작 시작 전에 빨리 알아야 한다. 조회 배치(60초)만으로는 그사이 제작이 시작될 수 있다
- 전송: 업무 트랜잭션 밖의 스케줄러(기본 5초)가 보낸다. 2xx면 보낸 상태를 기록하고 끝, 아니면 5초부터 두 배씩 최대 5분 간격으로 다시 보낸다. 타임아웃 3초, 리다이렉트를 따르지 않는다
- 가맹점 쪽 규칙: 서명·시각 확인 → **본문은 믿지 않고 `paymentId`로 서명 조회(#149)해서 반영** → 같은 결과면 아무것도 바꾸지 않는다(중복·순서 역전 안전) → 2xx 응답. 웹훅을 놓쳐도 조회 배치(최근 결제를 주기 조회)로 맞춘다

### 3-1. 서명 조회 응답에 `authorized` 추가 (#149 조회 계약 확장)

- `GET /api/payments/{paymentId}`·`GET /api/payment-orders`(주문번호 조회) 응답(`MerchantPaymentResponse`)에 `authorized`(boolean, 카드 승인이 끝났는지)를 **추가**한다. 값은 승인 참조(`authorizationRef`)가 있는지로만 정하고, 승인 참조 원문은 계속 내보내지 않는다
- 이유: `PENDING`은 "승인 전 평가 중·오류로 멈춤"과 "승인 후 매입 대기"를 함께 담는다(#149 설계). 웹훅 본문을 믿지 않고 조회로 반영하려면, 조회만으로 승인 여부를 알 수 있어야 4절 규칙(승인되면 결제 완료)을 지킬 수 있다. 결제창을 닫았거나 웹훅을 놓쳐 조회 배치로 복구할 때도 같다
- 필드 추가만 하므로 이 값을 모르는 기존 호출자(`scripts/demo/cakeshop_pay.py` 등)는 그대로 동작한다. 새 `PaymentStatus`는 만들지 않는다
- 결제창 결과(2절)와 웹훅 본문의 `authorized`도 같은 기준으로 계산한다

### 4. CakeShop이 결과를 해석하는 규칙

- 조회 응답의 `authorized=true`이고 상태가 `PENDING`·`HELD`·`CAPTURED`면 주문은 결제 완료다(ALLOW 승인 후 매입 대기, 또는 REVIEW → HELD). HELD도 승인은 끝났으므로 구매자를 기다리게 하지 않는다. `authorized=false`인 `PENDING`은 아직 처리 중이다
- 매입(CAPTURED)·정산은 돈워리 안의 일이며 CakeShop 주문 상태를 바꾸지 않는다

#### 4-1. 사후 거절(HELD → 직원 거절 → VOIDED): 이행 전·후로 나눈다 (2026-11-02, PR #403 P1·P4 논의)

직원 거절은 카드 승인 취소(VOID)라 고객 돈은 빠져나가지 않는다. 그런데 CakeShop은 HELD를 결제 완료로 보고 주문을 진행했을 수 있다.

| CakeShop 주문 상태 (VOIDED 웹훅을 받았을 때) | CakeShop이 하는 일 | 손실 |
| --- | --- | --- |
| 제작 시작 전 | 주문 취소(재고·쿠폰 복구, 고객 알림) | 없음. 범죄자도 얻는 것이 없다 |
| 제작·픽업이 이미 시작됨 | 주문을 유지하고 돈워리에 "이행됨"을 알린다 | **돈워리(PG)가 부담**한다. 가맹점 정산금을 보장한다 |

- "제작 시작" 여부는 CakeShop이 **자기 주문 상태로 판단**한다. 돈워리는 지금 주문 생성(예정 정보)·픽업 완료만 받고(CakeShop PR #20, P6 `PUT /api/v1/order-fulfillments/{orderId}/schedule`·`/completion`) 제작 시작 신호는 받지 않는다
- 이유: 가맹점이 검토 상태를 확인하거나 제작을 미룰 필요가 없어 소상공인 부담을 늘리지 않는다(P1 의견). 제작 전이면 취소되므로 범죄자가 공짜로 상품을 얻는 경우를 줄인다(P4 의견)
- 남는 위험: 이미 제작된 경우에는 상품이 나간다. HELD 판정을 빨리 끝내는 운영(판정 기한)으로 줄이는 것을 후속으로 본다
- "이행됨" 알림 경로(새 API 또는 #20 fulfillment 확장)와, VOIDED 결제를 P6가 어떻게 지급할지(현재 VOIDED는 매입 전이라 정산 행이 없을 수 있다)는 P6와 정한다

### 5. 저장

- P5 소유 새 테이블 `payment_sessions`: 세션 키, 가맹점, 멱등 키, 시도 키, 주문 값, 세 주소, 상태(`OPEN`·`SUBMITTED`), 연결 결제 ID, 만료 시각, 마지막으로 알린 상태·승인 여부, 알림 시도 횟수·다음 시각
- `payment_id`는 같은 담당(P5) `payments`로 물리 FK, `merchant_id`는 P1 `merchants` 논리 참조(AGENTS.md 4절)
- 별도 이벤트 저장(outbox) 테이블은 두지 않는다. 세션 하나는 결제 하나만 알리므로 "마지막으로 알린 값"과 현재 결제 상태의 차이로 다시 보낼지 정한다

### 6. 주소와 실행 환경

코드에 주소를 넣지 않는다. `localhost`는 설정 기본값과 테스트에만 쓴다.

| 설정 | 어디 | 뜻 |
| --- | --- | --- |
| `payment.http.enabled=true` | 돈워리 | 가맹점 결제 API·결제창 API·웹훅 발송을 켠다(기존 결제 API와 같은 스위치) |
| `DONWORRY_PUBLIC_BASE_URL` | 돈워리 | 결제창 주소의 앞부분. 이용 신청 메일이 쓰는 기존 값과 같다(돈워리 화면 공개 주소) |
| `DONWORRY_PAYMENT_ENABLED=true` | CakeShop | 돈워리 결제 선택을 켠다 |
| `DONWORRY_PAYMENT_BASE_URL` | CakeShop | 돈워리 API 주소 |
| `DONWORRY_PAYMENT_SHOP_BASE_URL` | CakeShop | 결제창이 돌려보낼 주소·웹훅 받을 주소의 앞부분(CakeShop 공개 주소) |
| `DONWORRY_MERCHANT_HMAC_CAKESHOP` | 양쪽 OS 환경 변수 | 같은 가맹점 키(ADR-0037, `docs/identity/246-cakeshop-provisioning.md`) |

- 결제창 이동·결과 전달은 **구매자 브라우저**가 하므로 브라우저가 두 주소에 닿기만 하면 된다
- 웹훅은 **돈워리 서버가 CakeShop 서버로** 보낸다. 돈워리 서버에서 `DONWORRY_PAYMENT_SHOP_BASE_URL`에 닿지 못하면(예: 돈워리는 서버, CakeShop은 개인 PC) 웹훅은 실패·재시도만 하고, CakeShop 조회 배치(기본 60초)가 대신 맞춘다. 반영이 늦을 뿐 결과는 같다
- CakeShop이 https면 돈워리도 https여야 한다. 아니면 브라우저가 iframe을 혼합 콘텐츠로 막는다
- 로컬에서 둘 다 `localhost`로 열면 호스트가 같아 세션 쿠키(`JSESSIONID`)가 겹친다(돈워리 직원 로그인 시 CakeShop 로그아웃). 로컬 안내는 CakeShop을 `http://127.0.0.1:8080`으로 연다. 이러면 운영처럼 "다른 사이트 iframe" 조건도 로컬에서 재현된다

## 고려한 대안

| 대안 | 버린 이유 |
| --- | --- |
| #149처럼 CakeShop이 카드·기기·IP를 모아 `POST /api/payments`로 보내기 | 구매자 브라우저의 기기·IP를 가맹점이 대신 보내 FDS 근거를 신뢰하기 어렵고, 가맹점 서버가 카드 정보를 다룬다. 서버 간 결제 API는 시연 스크립트용으로 유지한다 |
| 결제창을 새 창(팝업)·전체 이동으로만 열기 | 팝업 차단과 화면 이탈이 생긴다. iframe 레이어를 기본으로 하고, 결과는 `redirectUrl`이라 전체 이동에도 그대로 쓸 수 있다 |
| 웹훅 없이 CakeShop 조회만 | HELD 판정은 몇 시간 뒤라 짧은 주기 조회는 부하가 크고, 긴 주기는 VOIDED 반영이 늦다. 조회는 유실 복구용 배치로만 둔다. 4-1의 "제작 전이면 취소"는 거절을 빨리 알아야 효과가 있다(2026-11-02 P1이 조회만으로 갈지 물어 다시 검토함) |
| 웹훅 본문을 그대로 믿고 반영 | 위조·재전송·순서 역전을 모두 가맹점이 따로 막아야 한다. 서명 확인 뒤에도 조회로 확인하면 본문은 "다시 볼 때"라는 신호로만 쓰인다 |
| 웹훅 전용 키를 따로 발급 | 키 발급·교체·폐기 절차가 두 벌이 된다. 같은 키에 접두어로 용도를 나누면 서로 재사용할 수 없다 |
| 웹훅 서명을 payment가 직접 계산 | 가맹점 키는 identity 내부(`MerchantSecretService`)에 있다. payment가 키를 읽으면 모듈 경계(AGENTS.md 4절)를 깬다 |
| outbox 테이블로 이벤트마다 행 저장 | 세션당 결제가 하나라 "마지막으로 알린 값"만으로 충분하다. 행이 쌓이는 저장·정리 비용만 생긴다 |
| 결제창 API를 CSRF 검사 안에 두기 | 다른 사이트 iframe에서는 `SameSite=Lax` 쿠키가 오지 않아 CSRF를 켜면 결제가 막힌다. 쿠키 인증이 없는 경로라 CSRF가 지킬 대상도 없다 |
| 승인돼도 매입(CAPTURED)까지 CakeShop 결제 완료를 미루기 | HELD는 직원 판정까지 오래 걸려 구매자가 결제창에서 기다릴 수 없다. 승인은 끝났으므로 완료로 보고, 거절되면 웹훅으로 취소한다 |
| 승인 여부를 조회에 넣지 않고 웹훅 본문의 `authorized`만 쓰기 | 웹훅은 놓칠 수 있고 본문은 믿지 않기로 했다. 조회 배치로 복구할 때 승인된 `PENDING`과 평가 중 `PENDING`을 구분할 수 없다 |
| 승인 후 매입 대기를 나타내는 새 `PaymentStatus` 추가 | P2·P3·P6가 읽는 상태 계약이 모두 바뀐다. 가맹점 조회에 필드 하나를 더하는 것으로 충분하다 |
| (4-1) 사후 거절이면 이행 여부와 관계없이 항상 주문 취소, 손실은 가맹점 부담 | 돈워리가 "결제 완료"로 안내한 거래를 믿고 진행한 가맹점에게 손실을 넘긴다. 서비스 취지(소상공인 부담 감소)와 맞지 않는다 |
| (4-1) HELD면 직원 판정까지 가맹점이 제작·배송을 보류(실제 PG Adyen 방식, 가맹점 계약에 명시) | 손실은 없지만 가맹점이 검토 상태를 확인하고 제작을 미뤄야 한다. 판정이 몇 시간 걸리면 주문도 그만큼 늦어진다 |
| (4-1) 항상 돈워리가 손실 부담(제작 전이어도 주문 유지) | 제작 전에 막을 수 있는 거래까지 범죄자가 상품을 얻게 된다 |

## 영향

- API: 새 가맹점 API `POST /api/payment-sessions`, 공개 API `/api/checkout/{sessionKey}` 3개, 가맹점에 보내는 웹훅. 모두 `payment.http.enabled=true`일 때만 켜진다. 기존 서명 조회 응답에 `authorized` 필드를 추가한다(3-1, 필드 추가만이라 기존 호출자 영향 없음). `POST /api/payments` 요청 계약은 바뀌지 않는다
- 모듈: identity가 `identity/api/MerchantWebhookSigner`를 새로 공개한다(P1 구현, payment → identity 의존은 기존 허용 범위). `SecurityConfig`·`MerchantSecurityConfiguration` 경로 등록은 P5가 구현 PR에 넣고 P1이 리뷰한다(AGENTS.md 4절)
- 정산: 4-1 사후 거절 중 이행된 거래의 돈워리 부담분 지급(P6 확인 후 별도 구현)
- DB: P5 migration 1개(`payment_sessions`)
- 화면: 돈워리 프론트 공개 화면 `/checkout/:sessionKey`. CakeShop 결제 화면의 모달·웹훅 수신·조회 배치는 CakeShop 저장소
- 테스트: 세션 생성·멱등·만료·같은 출처 검사, 조회 응답 `authorized`(승인 전·후 `PENDING`, HELD, DECLINED), 결제창 결제 중복 방지, 결과 주소, 웹훅 서명 고정 벡터(돈워리·CakeShop 같은 값), 재전송 간격, CSRF를 켠 상태의 결제창 결제
- 문서: `docs/payment/`에 연동 안내, #154는 이 ADR로 결정 기록

## 남은 결정 (구현 전 또는 후속)

- [x] P1: #242 재개와 담당 — CakeShop 연동은 P5(수민)가 맡는다 (2026-11-02)
- [x] P1: 가맹점 세션 생성은 HMAC 체인, 결제창 API는 정확한 경로만 공개, CSRF 제외 — 동의. `SecurityConfig` 변경은 P1 리뷰 (2026-11-02)
- [x] P1: 웹훅 포함. `identity/api/MerchantWebhookSigner`는 P1이 구현 (2026-11-02)
- [x] P1: 로컬 쿠키 충돌은 CakeShop을 `127.0.0.1`로 여는 안내 (2026-11-02)
- [ ] P6: 4-1 사후 거절 시 돈워리 부담분의 정산 지급 방식, VOIDED 결제의 정산 행 생성 시점, CakeShop "이행됨" 신호를 받는 경로
- [ ] 4-1 남는 위험을 줄일 HELD 판정 기한(운영)
- [ ] CakeShop 저장소: 이 연동은 Draft PR #22(서명 요청·조회 클라이언트) → #24(결제 수단 선택·결제창 모달) 위에 올린다. 두 PR을 먼저 정리한다
- [ ] 운영: CakeShop `deploy/server.env.example`에 6절 값 추가, 돈워리 운영 프록시 설정 확인(배포 설정이 이 저장소에 없음)
- [ ] 운영 `notifyUrl`은 https만 허용하고 내부 주소(사설·루프백 IP)를 막는다. 가맹점이 등록한 주소만 쓰게 할지(가맹점 설정)도 정한다
- [ ] 웹훅 재전송 한도(예: 24시간 뒤 중단, 조회 배치에 맡김)와 운영에서 실패를 보는 방법
- [ ] 운영 프록시가 `/checkout`을 다른 사이트 iframe에 띄우는 것을 막지 않는지(`X-Frame-Options`·`frame-ancestors`), 결제창의 `Referrer-Policy: no-referrer`
- [ ] 결제 접수 기한(10분)이 지난 뒤 늦게 승인된 결제를 누가 취소하는지
- [ ] 고객 취소(환불) 연결은 ADR-0058(환불 후에도 `CAPTURED` 유지, 취소 작업으로 표현)에 맞춰 별도 결정. 그때 웹훅이 환불을 어떻게 알릴지 정한다
- [ ] #255(중위험도 매입·정산 보류, 손실은 PG 부담 제안)와 4-1의 관계 정리. 4-1은 "제작 이후 손실은 PG 부담"을 받아들인 것이며, 중위험도 매입 여부는 바꾸지 않는다

## 검증

- 로컬 샌드박스(이 저장소 `2bd7f87` 기준 복사본)에서 구현해 확인했다
  - `./gradlew check`·관련 MariaDB 테스트 통과, CakeShop 테스트 통과
  - 카드 A·B 결제 → CAPTURED → CakeShop 결제 완료
  - 새 기기 + 처음 쓰는 카드 → FDS 50점 HELD → 직원 REJECT → VOIDED → 웹훅(`evt_3_VOIDED_A`) → CakeShop 주문 취소·재고 복구·고객 알림
  - 승인 직후 결제창을 닫음 → 웹훅(`evt_4_CAPTURED_A`)으로 CakeShop 결제 완료
  - 웹훅 서명 고정 벡터를 돈워리·CakeShop 테스트에서 같은 값으로 확인
- 미검증
  - 현재 `dev`(ADR-0059 기본 거절, #163 이후)에서의 구현과 검사 — 저장소 PR에서 다시 한다
  - 서로 다른 사이트(운영 주소, 또는 로컬 `127.0.0.1`과 `localhost`)의 iframe·쿠키·CSRF 동작 — 샌드박스는 둘 다 `localhost`였다
  - 돈워리 서버에서 CakeShop에 닿지 않을 때 조회 배치만으로 맞춰지는 것(샌드박스에서는 웹훅 실패 → 재전송만 확인)
  - 운영 HTTPS·프록시 헤더(#396 범위)
