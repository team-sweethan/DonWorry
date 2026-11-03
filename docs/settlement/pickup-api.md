# CakeShop → P6 픽업 API

## 인증

P1 공통 HMAC 경로와 서명 규칙([ADR-0037](../decisions/0037-merchant-hmac-authentication.md))을 사용한다.
`PickupAccessService`는 `identity.api.CurrentMerchant.findCurrent()`로 확인한 ID만 쓴다.
직원 세션·본문 merchantId·주문번호를 인증 수단으로 사용하지 않는다. 본문에는 merchantId를 보내지 않는다.
P1은 INACTIVE 가맹점에도 픽업 PUT 인증을 허용한다. P6는 인증된 가맹점 ID와 주문번호를 함께 사용해 해당 가맹점의 주문/매입에만 연결한다.

배포 Base URL과 키·프록시는 인프라 담당이 확인해야 한다. 상대 경로는 아래 두 가지이며 새 공개 Java API나 P5 이벤트를 추가하지 않는다.

## 예정 정보

`PUT /api/v1/order-fulfillments/{orderId}/schedule`

```json
{
  "scheduledPickupAt": "2026-11-01T06:00:00Z",
  "items": [
    { "productName": "딸기 케이크", "quantity": 2 },
    { "productName": "초코 케이크", "quantity": 1 }
  ]
}
```

- `orderId`: CakeShop `orders.order_number`와 P5 `order_ref`에 해당하는 같은 문자열. 공백 불가, 최대 100자.
- `scheduledPickupAt`: 필수 Instant. DB에 맞춰 최대 마이크로초 정밀도를 받는다.
- `items`: 필수이며 비어 있거나 null 항목을 포함할 수 없다.
- `productName`: 필수·공백만 불가·최대 150자. CakeShop 주문 상품의 이름 스냅샷을 보낸다.
- `quantity`: 필수 Integer, 1 이상. 항목 배열 순서를 보존하고 같은 이름을 임의로 합치지 않는다.
- 상품 목록 없이 `scheduledPickupAt` 필드만 보내던 구 요청 형식은 400이다. 과거 날짜의 픽업 시각 자체는 지연 전송·재전달을 위해 허용한다. HMAC 서명 시각의 유효기간과 업무 픽업 시각은 별개다.
- CakeShop 시각이 한국 현지 LocalDateTime이면 Asia/Seoul을 적용해 Instant로 변환해야 한다. 예시의 `06:00Z`는 한국 15시다. 이미 Instant/offset이 있으면 다시 9시간 보정하지 않는다.

## 실제 완료

`PUT /api/v1/order-fulfillments/{orderId}/completion`

```json
{ "pickedUpAt": "2026-11-01T06:15:00Z" }
```

필수 Instant이며 상품 목록을 다시 보내지 않는다. 저장된 상품과 예정 시각을 지우거나 바꾸지 않는다.
예정 정보보다 완료가 먼저 도착할 수도 있다. 실제 픽업이 예정 시간보다 빠른 경우도 허용한다.

## 응답

성공은 200, Cache-Control: no-store다.

```json
{ "orderId": "ORD-001", "result": "STORED", "linkStatus": "PENDING_CAPTURE" }
```

`PENDING_CAPTURE`는 픽업 저장 성공·매입 연결 대기, `LINKED`는 기존 매입/정산과 일정 연결까지 완료다.
둘 다 정산금 지급 완료를 뜻하지 않는다.

| 상황 | 결과 |
| --- | --- |
| P1 서명·시간·nonce 검증 실패 | 공통 인증 오류 응답 |
| 인증 제공자 미연결 | 503 SETTLEMENT_AUTH_NOT_READY |
| 본문·시각·상품 검증 실패 | 400 SETTLEMENT_INVALID_REQUEST |
| 같은 주문번호를 사용하는 다른 가맹점 | 독립된 주문으로 저장, 다른 가맹점 기록은 변경하지 않음 |
| 다른 상품명·수량·순서·건수의 재전달 | 409 SETTLEMENT_ORDER_ITEMS_CONFLICT |
| 상충 픽업 시각·모호한 복수 매입 | 409 SETTLEMENT_CONFLICT |
| DB 처리 오류 | 503 SETTLEMENT_UNAVAILABLE |

오류 응답은 ProblemDetail이며 상태·code를 확인한다. 실패를 200으로 바꾸지 않는다.

## 재전달

동일 시각·상품 목록의 재전달은 기존 ID·생성 시각을 유지한다. 이미 기록된 시각·목록은 자동 덮어쓰지 않는다.
기존 주문에 상품이 아직 없으면 최초 목록 수신으로 채울 수 있다. 일부 상품만 추가하는 PATCH 계약은 없다.
업무 요청을 재전달할 때는 공통 HMAC 규칙대로 새 nonce/서명을 만든다. 같은 서명 nonce 재사용은 인증에서 거절한다.
상품을 포함한 실제 전송 본문 바이트로 서명해야 하며 P1 HMAC 알고리즘 자체를 수정하지 않는다.
