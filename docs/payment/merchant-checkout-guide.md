# 돈워리 결제 연동 안내

쇼핑몰에서 돈워리 결제창으로 결제를 받는 방법입니다. 쇼핑몰 서버를 개발하는 분이 따라 할 수 있게 순서대로 적었습니다.

> 먼저 [시크릿 키 발급과 연결 확인](../identity/354-merchant-integration.md)을 마쳐 주세요. 돈워리 화면의 **내 연동 정보**에 "연동 완료"가 보이면 시작할 수 있습니다.

## 한눈에 보기

1. 구매자가 주문하면 쇼핑몰 **서버**가 돈워리에 결제를 준비합니다 → 결제창 주소를 받습니다
2. 구매자 브라우저에서 결제창을 엽니다 → 구매자가 카드를 고르고 결제합니다
3. 결제가 끝나면 돈워리가 구매자를 쇼핑몰의 **성공 주소** 또는 **실패 주소**로 돌려보냅니다
4. 쇼핑몰 서버가 돈워리에 **결제 결과를 조회**해 주문에 반영합니다
5. 그 뒤 결제 상태가 바뀌면 돈워리가 쇼핑몰 서버로 **알림(웹훅)**을 보냅니다

카드번호 · 구매자 기기 · IP는 쇼핑몰이 다루지 않습니다. 결제창이 구매자에게서 직접 받습니다.

**가장 중요한 규칙:** 주문 상태는 항상 **4번 조회 결과**로 정합니다. 성공 주소에 붙어 온 값이나 웹훅 내용은 구매자나 제3자가 꾸밀 수 있으니 "조회해 보라"는 신호로만 씁니다.

## 1. 준비물

| 준비물 | 설명 |
| --- | --- |
| 가맹점 코드 · 시크릿 키 | 내 연동 정보에서 발급. 쇼핑몰 **서버** 설정에만 보관합니다 |
| 돈워리 API 주소 | 연동할 환경의 주소 (운영 담당에게 확인) |
| 성공 주소 · 실패 주소 | 결제 뒤 구매자가 돌아올 쇼핑몰 페이지. **두 주소는 같은 도메인 · 포트**여야 합니다 |
| 알림 받을 주소 (권장) | 결제 상태 변경 알림을 받을 쇼핑몰 서버 주소. 운영에서는 https 공개 주소만 됩니다 |

## 2. 요청에 서명하기

쇼핑몰 서버가 돈워리 API를 부를 때는 모든 요청에 서명 헤더 4개를 붙입니다. 자세한 규칙은 [서명 규칙](../identity/219-merchant-hmac.md#1-요청과-서명)에 있습니다.

| 헤더 | 값 |
| --- | --- |
| `X-Merchant-Code` | 가맹점 코드 |
| `X-Timestamp` | 지금 시각 (UTC Unix 초) |
| `X-Nonce` | 요청마다 새 UUID (소문자) |
| `X-Signature` | 아래 9줄을 시크릿 키로 HMAC-SHA256 한 값 (소문자 hex) |

```python
import base64, hashlib, hmac, time, uuid

def sign(merchant_code, secret_key_base64, method, path, body=b"", content_type="", idempotency_key=""):
    timestamp, nonce = str(int(time.time())), str(uuid.uuid4())
    message = "\n".join([
        "DONWORRY-HMAC-V1", merchant_code, timestamp, nonce, method, path,
        content_type, idempotency_key, hashlib.sha256(body).hexdigest(),
    ])
    key = base64.b64decode(secret_key_base64)  # 키 문자열이 아니라 디코딩한 바이트로 서명
    signature = hmac.new(key, message.encode("utf-8"), hashlib.sha256).hexdigest()
    return {"X-Merchant-Code": merchant_code, "X-Timestamp": timestamp,
            "X-Nonce": nonce, "X-Signature": signature}
```

- 본문은 **실제로 보내는 바이트 그대로** 서명합니다. JSON을 다시 만들면 서명이 달라집니다
- `Content-Type`과 `Idempotency-Key`를 보내면 그 값도 서명에 넣습니다

## 3. 결제 준비

`POST /api/payment-sessions` · 헤더 `Content-Type: application/json`, `Idempotency-Key: <주문마다 새 UUID>`

```json
{
  "orderRef": "ORDER-20261111-0001",
  "customerRef": "1024",
  "amount": 18000,
  "orderCreatedAt": "2026-11-11T02:51:30Z",
  "orderName": "딸기 생크림 케이크",
  "successUrl": "https://shop.example.com/orders/1/pay/success",
  "failUrl": "https://shop.example.com/orders/1/pay/fail",
  "notifyUrl": "https://shop.example.com/webhooks/donworry",
  "buyerEmail": "buyer@example.com"
}
```

| 칸 | 필수 | 설명 |
| --- | --- | --- |
| `orderRef` | ✅ | 쇼핑몰 주문번호 (100자 이하) |
| `customerRef` | ✅ | 쇼핑몰 고객 번호 (숫자). **같은 고객은 늘 같은 값**으로 보내 주세요. 이상 결제 탐지가 고객별 이력을 이 값으로 봅니다 |
| `amount` | ✅ | 결제 금액 (원) |
| `orderCreatedAt` | ✅ | 주문 시각 (UTC). **이 시각부터 10분 안에** 결제를 마쳐야 합니다 |
| `orderName` | ✅ | 결제창에 보일 주문명 (100자 이하) |
| `successUrl` · `failUrl` | ✅ | 돌아올 주소. 같은 도메인 · 포트, `#`은 쓸 수 없습니다 |
| `notifyUrl` | | 알림 받을 주소 (6절) |
| `buyerEmail` | | 구매자 이메일 |
| `ordererName` · `ordererPhone` · `pickupName` · `pickupPhone` | | 주문자 · 받는 사람 연락처 |

응답 (200):

```json
{
  "sessionKey": "pss_…",
  "checkoutUrl": "https://<돈워리 화면 주소>/checkout/pss_…",
  "expiresAt": "2026-11-11T03:01:30Z"
}
```

- 응답을 못 받았으면 **같은 `Idempotency-Key` · 같은 본문**으로 다시 보내세요. 같은 결제창이 돌아옵니다
- `checkoutUrl`은 그 결제의 열쇠입니다. 로그나 공유 링크에 남기지 마세요

| 오류 | 뜻 | 할 일 |
| --- | --- | --- |
| 400 `INVALID_REQUEST` | 값 형식이 틀림 | 필수 칸, 두 주소의 도메인, 이메일 형식 확인 |
| 409 `ORDER_EXPIRED` | 주문 후 10분이 지남 | 새 주문으로 다시 |
| 409 `IDEMPOTENCY_KEY_CONFLICT` | 같은 키로 다른 내용을 보냄 | 주문마다 새 키 사용 |
| 401 · 403 `MERCHANT_AUTH_*` | 서명 · 키 · 연동 상태 문제 | [연결 확인 오류 표](../identity/354-merchant-integration.md#2-연결-확인-요청) 참고 |
| 500 · 503 | 처리 결과를 알 수 없음 | 같은 키 · 같은 본문으로 다시 |

## 4. 결제창 열기

`checkoutUrl`을 구매자 브라우저에서 엽니다. 두 가지 방법이 있습니다.

**페이지 이동** — 가장 간단합니다. 결제가 끝나면 돈워리가 성공 · 실패 주소로 보냅니다.

**쇼핑몰 화면 위 레이어(iframe)** — 결제창이 부모 창에 메시지를 보냅니다.

```js
const checkoutOrigin = new URL(checkoutUrl).origin
window.addEventListener('message', (event) => {
  if (event.origin !== checkoutOrigin) return              // 결제창이 보낸 것만
  const data = event.data
  if (data?.type === 'donworry-checkout-resize') frame.style.height = `${data.height}px`
  if (data?.type === 'donworry-checkout') {
    const target = new URL(data.redirectUrl)
    if (target.origin === location.origin) location.assign(target.href)  // 내 쇼핑몰 주소로만 이동
  }
})
```

- 다른 메시지(브라우저 확장 프로그램 등)도 들어오므로 출처와 `type`을 꼭 확인하세요
- 쇼핑몰이 https면 돈워리 주소도 https여야 합니다

## 5. 결과 확인

결제가 끝나면 구매자가 돌아옵니다.

| 돌아오는 주소 | 붙는 값 | 뜻 |
| --- | --- | --- |
| 성공 주소 | `?paymentKey=1010&orderId=ORDER-…&amount=18000` | 카드 승인됨 |
| 실패 주소 | `?code=…` | 결제 안 됨. 거절이면 `FDS_REJECTED` 같은 사유 코드, 구매자가 [결제 취소]를 누르면 `USER_CANCELED` |

성공 주소로 왔다면 **반드시 조회**해서 확인합니다.

`GET /api/payments/{paymentKey}` (서명 필요)

```json
{
  "paymentId": 1010,
  "orderRef": "ORDER-20261111-0001",
  "paymentStatus": "CAPTURED",
  "authorized": true,
  "amount": 18000,
  "declineCode": null
}
```

주문번호 · 금액이 내 주문과 같은지 확인하고, 아래 표대로 반영합니다. 결제 번호를 모르면 `GET /api/payment-orders?orderRef=<주문번호>`로 찾을 수 있습니다.

| 조회 결과 | 구매자에게 보일 것 | 사장님에게 보일 것 |
| --- | --- | --- |
| `authorized: true` + `PENDING` 또는 `CAPTURED` | 결제 완료 | 결제 완료 |
| `authorized: true` + `HELD` | 결제 완료 · 주문 상태 "주문 확인 중" | **결제 확인 중** |
| `VOIDED` | 주문 취소 · "카드 청구는 되지 않습니다" | 결제 취소 |
| `authorized: false` + `PENDING` | 처리 중 | 처리 중 |
| `DENIED` · `DECLINED` · `EXPIRED` | 결제 실패 | 결제 실패 |

### `HELD`(결제 확인 중)일 때

돈워리 보안 직원이 이 결제를 확인하고 있다는 뜻입니다. 카드 승인은 이미 됐고, 보통 1시간 안에 결과가 나옵니다.

- **구매자에게는 "검토 중" · "보안 확인" 같은 말을 보여 주지 마세요.** 부정 사용자에게 신호가 됩니다. "주문 확인 중"으로 충분합니다
- 사장님은 ① 결과를 기다리거나 ② 구매자에게 직접 확인한 뒤 진행하거나 ③ 주문을 취소할 수 있습니다
- 결과를 기다리지 않고 제작 · 발송을 진행했는데 나중에 **승인 취소(`VOIDED`)되면 손실은 가맹점 부담**입니다. 진행 전에 사장님께 한 번 더 확인받는 화면을 두는 것을 권합니다
- 확인이 끝나면 `CAPTURED`(결제 완료) 또는 `VOIDED`(승인 취소)로 바뀌고 알림이 옵니다

정산 예정일은 결제가 확정(매입)된 날부터 영업일 3일 뒤입니다. 확인이 늦어지면 정산도 그만큼 늦어집니다.

## 6. 알림(웹훅) 받기

결제 상태가 바뀌면 돈워리가 `notifyUrl`로 `POST`를 보냅니다. 예) `HELD` → 보안 직원 확인 → `VOIDED`

```json
{
  "eventId": "evt_1016_VOIDED_A",
  "type": "payment.status_changed",
  "paymentId": 1016,
  "orderRef": "ORDER-20261111-0002",
  "paymentStatus": "VOIDED",
  "authorized": true,
  "amount": 650000,
  "occurredAt": "2026-11-11T03:03:47.716Z"
}
```

받는 쪽 순서:

1. 서명을 확인합니다 (아래 코드). 시각이 5분 넘게 차이 나면 거절합니다
2. **본문 내용은 믿지 말고** `paymentId`로 다시 조회합니다 (5절)
3. 조회 결과로 주문을 반영합니다. 이미 같은 상태면 아무것도 바꾸지 않습니다
4. 2xx로 답합니다

```python
def verify_webhook(headers, raw_body, merchant_code, secret_key_base64):
    if headers["X-Merchant-Code"] != merchant_code:
        return False
    if abs(time.time() - int(headers["X-Timestamp"])) > 300:
        return False
    message = "\n".join([
        "DONWORRY-WEBHOOK-V1", headers["X-Merchant-Code"], headers["X-Timestamp"],
        headers["X-Event-Id"], hashlib.sha256(raw_body).hexdigest(),
    ])
    expected = hmac.new(base64.b64decode(secret_key_base64), message.encode("utf-8"), hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, headers["X-Signature"])
```

알아 둘 점:

- 같은 알림이 **여러 번** 올 수 있습니다(같은 `eventId`). 조회 결과로 반영하면 문제없습니다
- 알림은 **그 시점의 최신 상태**만 보냅니다. 바로 확정되는 결제는 `PENDING` 없이 `CAPTURED` 알림 하나만 올 수 있습니다
- 2xx로 답하지 않으면 5초, 10초, 20초… 최대 5분 간격으로 다시 보내고, 하루가 지나면 그만둡니다

## 7. 놓친 알림 맞추기

방화벽이나 서버 장애로 알림을 못 받을 수 있습니다. 결과가 아직 정해지지 않은 최근 주문(처리 중 · `HELD`)을 1분 정도 간격으로 조회해 맞춰 주세요. 알림을 못 받는 환경에서도 반영이 늦어질 뿐 결과는 같아집니다.

## 8. 취소

`POST /api/payments/{paymentId}/cancellations` (서명 + `Idempotency-Key`)

```json
{ "reason": "고객 요청" }
```

| 결제 상태 | 결과 |
| --- | --- |
| `HELD` | 바로 승인 취소됩니다 (200, `requestStatus: "SUCCEEDED"`). 결제는 `VOIDED`가 되고 알림이 옵니다 |
| `CAPTURED` | 아직 지원하지 않습니다 (503 `CANCELLATION_NOT_READY`) |

결과는 응답 `Location` 헤더의 주소(`GET /api/payment-cancellation-requests/{requestKey}`)로 다시 확인할 수 있습니다.

## 9. 테스트하기

테스트 환경의 결제창은 실제 카드사와 연결되지 않은 **모의 카드**를 씁니다. 카드번호는 입력하지 않습니다.

- 결제창에는 **한빛카드** 한 장이 보입니다. [+ 카드 추가]를 누르면 그 결제창에서만 쓰는 **누리카드**가 새로 등록됩니다
- 모의 카드사는 결제를 거절하지 않습니다. 결과(결제 완료 · 결제 확인 중 · 거절)는 돈워리의 이상 결제 탐지가 정합니다
- 결제창에서 [결제 취소]를 누르면 실패 주소로 `code=USER_CANCELED`가 옵니다
- 결제 확인 중(`HELD`)이나 거절 결과를 시험해야 하면 돈워리 운영 담당에게 요청해 주세요

## 10. 참고 구현

연동 예시가 필요하면 함께 개발한 케이크 쇼핑몰(CakeShop)의 구현을 참고할 수 있습니다. 저장소 열람이 필요하면 돈워리 운영 담당에게 요청해 주세요.
