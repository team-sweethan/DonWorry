# 0061. 돈워리 호스팅 결제창과 결제 상태 웹훅

- **상태:** 제안 — 로컬 샌드박스 구현·확인 완료, 저장소 구현 전. P1 확인 필요(#242 재개, 보안 경로, 웹훅 서명기 위치)
- **날짜:** 2026-11-01
- **관련:** #402, #149, #154, #242, #255, [ADR-0037](0037-merchant-hmac-authentication.md), [ADR-0059](0059-security-default-deny.md), [ADR-0058](0058-captured-refund-contract.md)
- **영향 받는 담당:** P5(결제 — 구현), P1(identity·`SecurityConfig`·CakeShop 연동 담당 협의), CakeShop 저장소(`team-sweethan/cakeshop_refactoring`). P2·P3·P6는 기존 결제 흐름을 그대로 쓰므로 계약 변경 없음

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
- **CSRF 검사에서 제외한다.** CSRF는 쿠키로 인증하는 요청을 지키는 장치인데 이 경로는 쿠키 인증이 없다. 또 결제창은 다른 사이트(CakeShop) 안의 iframe으로 열리므로 돈워리의 `SameSite=Lax` 쿠키(`XSRF-TOKEN`)가 전송되지 않는다. 검사를 켜 두면 `security.csrf.enabled=true`(#68)가 되는 순간 결제 버튼이 403이 된다
- ADR-0059 기본 거절 아래에서 위 세 경로만 정확한 메서드·경로로 연다(P1 `SecurityConfig` 리뷰)
- 결과: 카드 승인(`authorized`)이고 상태가 `PENDING`·`HELD`·`CAPTURED`면 `successUrl?paymentKey=&orderId=&amount=`, 그 밖에는 `failUrl?code=`, 구매자가 닫으면 `failUrl?code=USER_CANCELED`
- 화면 `/checkout/:sessionKey`(돈워리 프론트, 로그인 없음): iframe 안에서 열렸으면 높이 변화(`donworry-checkout-resize`)와 결과 이동(`donworry-checkout`)을 `postMessage`로 **가맹점 출처에만** 보낸다. 가맹점은 `event.origin`을 확인하고 같은 출처 주소로만 이동한다

### 3. 결제 상태 웹훅 (돈워리 → 가맹점 `notifyUrl`)

- 언제: 결제창에서 결제가 접수된 뒤, 그리고 그 결제의 (상태, 승인 여부)가 마지막으로 보낸 값과 달라질 때마다. 예) `HELD` → 직원 거절 → `VOIDED`
- 본문(JSON): `eventId`(`evt_{paymentId}_{상태}_{A|N}` — 같은 상태 변화는 같은 ID), `type`(`payment.status_changed`), `paymentId`, `orderRef`, `paymentStatus`, `authorized`, `amount`, `occurredAt`
- 헤더: `X-Merchant-Code`, `X-Timestamp`, `X-Event-Id`, `X-Signature`
- 서명: 가맹점 API 서명(ADR-0037)과 **같은 가맹점 키**, 다른 접두어 `DONWORRY-WEBHOOK-V1`. 서명 원문은 `DONWORRY-WEBHOOK-V1\n{가맹점 코드}\n{timestamp}\n{eventId}\n{sha256(body) hex}`. 접두어가 달라 웹훅 서명을 API 요청 서명으로 재사용할 수 없다. 가맹점은 시각을 ±300초로 검사한다
- 서명기는 키를 가진 identity가 `identity/api/MerchantWebhookSigner`로 공개한다. payment는 키를 직접 읽지 않는다
- 전송: 업무 트랜잭션 밖의 스케줄러(기본 5초)가 보낸다. 2xx면 보낸 상태를 기록하고 끝, 아니면 5초부터 두 배씩 최대 5분 간격으로 다시 보낸다. 타임아웃 3초, 리다이렉트를 따르지 않는다
- 가맹점 쪽 규칙: 서명·시각 확인 → **본문은 믿지 않고 `paymentId`로 서명 조회(#149)해서 반영** → 같은 결과면 아무것도 바꾸지 않는다(중복·순서 역전 안전) → 2xx 응답. 웹훅을 놓쳐도 조회 배치(최근 결제를 주기 조회)로 맞춘다

### 4. CakeShop이 결과를 해석하는 규칙

- 카드 승인(ALLOW, 또는 REVIEW → HELD)이면 주문은 결제 완료다. HELD도 승인은 끝났으므로 구매자를 기다리게 하지 않는다
- 직원 거절(HELD → VOIDED)이면 CakeShop이 주문을 취소한다(재고·쿠폰 복구, 고객 알림)
- 매입(CAPTURED)·정산은 돈워리 안의 일이며 CakeShop 주문 상태를 바꾸지 않는다

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
| 웹훅 없이 CakeShop 조회만 | HELD 판정은 몇 시간 뒤라 짧은 주기 조회는 부하가 크고, 긴 주기는 VOIDED 반영이 늦다. 조회는 유실 복구용 배치로만 둔다 |
| 웹훅 본문을 그대로 믿고 반영 | 위조·재전송·순서 역전을 모두 가맹점이 따로 막아야 한다. 서명 확인 뒤에도 조회로 확인하면 본문은 "다시 볼 때"라는 신호로만 쓰인다 |
| 웹훅 전용 키를 따로 발급 | 키 발급·교체·폐기 절차가 두 벌이 된다. 같은 키에 접두어로 용도를 나누면 서로 재사용할 수 없다 |
| 웹훅 서명을 payment가 직접 계산 | 가맹점 키는 identity 내부(`MerchantSecretService`)에 있다. payment가 키를 읽으면 모듈 경계(AGENTS.md 4절)를 깬다 |
| outbox 테이블로 이벤트마다 행 저장 | 세션당 결제가 하나라 "마지막으로 알린 값"만으로 충분하다. 행이 쌓이는 저장·정리 비용만 생긴다 |
| 결제창 API를 CSRF 검사 안에 두기 | 다른 사이트 iframe에서는 `SameSite=Lax` 쿠키가 오지 않아 CSRF를 켜면 결제가 막힌다. 쿠키 인증이 없는 경로라 CSRF가 지킬 대상도 없다 |
| 승인돼도 매입(CAPTURED)까지 CakeShop 결제 완료를 미루기 | HELD는 직원 판정까지 오래 걸려 구매자가 결제창에서 기다릴 수 없다. 승인은 끝났으므로 완료로 보고, 거절되면 웹훅으로 취소한다 |

## 영향

- API: 새 가맹점 API `POST /api/payment-sessions`, 공개 API `/api/checkout/{sessionKey}` 3개, 가맹점에 보내는 웹훅. 모두 `payment.http.enabled=true`일 때만 켜진다. 기존 `POST /api/payments`·조회는 바뀌지 않는다
- 모듈: identity가 `identity/api/MerchantWebhookSigner`를 새로 공개한다(payment → identity 의존은 기존 허용 범위). `SecurityConfig`·`MerchantSecurityConfiguration`에 경로 등록(P1)
- DB: P5 migration 1개(`payment_sessions`)
- 화면: 돈워리 프론트 공개 화면 `/checkout/:sessionKey`. CakeShop 결제 화면의 모달·웹훅 수신·조회 배치는 CakeShop 저장소
- 테스트: 세션 생성·멱등·만료·같은 출처 검사, 결제창 결제 중복 방지, 결과 주소, 웹훅 서명 고정 벡터(돈워리·CakeShop 같은 값), 재전송 간격, CSRF를 켠 상태의 결제창 결제
- 문서: `docs/payment/`에 연동 안내, #154는 이 ADR로 결정 기록

## 남은 결정 (구현 전 또는 후속)

- [ ] P1: #242 보류 해제와 CakeShop 쪽 구현 담당
- [ ] P1: 결제창 공개 경로 3개와 CSRF 제외 등록, 웹훅 서명기를 `identity/api`에 두는 것
- [ ] P1: 로컬 쿠키 충돌을 `127.0.0.1` 안내로 둘지, 돈워리 세션 쿠키 이름을 바꿀지
- [ ] CakeShop 저장소: 이 연동은 Draft PR #22(서명 요청·조회 클라이언트) → #24(결제 수단 선택·결제창 모달) 위에 올린다. 두 PR을 먼저 정리한다
- [ ] 운영: CakeShop `deploy/server.env.example`에 6절 값 추가, 돈워리 운영 프록시 설정 확인(배포 설정이 이 저장소에 없음)
- [ ] 운영 `notifyUrl`은 https만 허용하고 내부 주소(사설·루프백 IP)를 막는다. 가맹점이 등록한 주소만 쓰게 할지(가맹점 설정)도 정한다
- [ ] 웹훅 재전송 한도(예: 24시간 뒤 중단, 조회 배치에 맡김)와 운영에서 실패를 보는 방법
- [ ] 운영 프록시가 `/checkout`을 다른 사이트 iframe에 띄우는 것을 막지 않는지(`X-Frame-Options`·`frame-ancestors`), 결제창의 `Referrer-Policy: no-referrer`
- [ ] 결제 접수 기한(10분)이 지난 뒤 늦게 승인된 결제를 누가 취소하는지
- [ ] 고객 취소(환불) 연결은 ADR-0058(환불 후에도 `CAPTURED` 유지, 취소 작업으로 표현)에 맞춰 별도 결정. 그때 웹훅이 환불을 어떻게 알릴지 정한다
- [ ] #255(중위험도 매입·정산 보류 제안)가 채택되면 4절 해석 규칙을 다시 본다

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
