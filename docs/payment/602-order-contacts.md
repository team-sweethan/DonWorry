# #602 결제 준비 요청의 주문자 · 픽업자 연락처

최초 작성: 2026-11-06 · 근거: [ADR-0082](../decisions/0082-insider-access-security.md) 2절(P5 `payment_order_contacts`) · 요청 계약 원본: [ADR-0064](../decisions/0064-hosted-checkout-and-payment-webhook.md) 2절

보안 직원이 사건에서 주문자를 확인할 수 있게 가맹점이 결제 준비 때 주문자 연락처를 보낸다.
서버는 원문을 DB에 두지 않는다. 이름 · 전화는 암호문으로, 전화는 같은 번호인지 대조할 지문(HMAC)으로만 저장한다.
열람(가린 값 · 대조 · 사유를 적은 원문 1회)은 조각 5에서 만든다. 이 문서는 받기와 저장만 다룬다.

## 1. 요청 칸 (`POST /api/payment-sessions`, 모두 선택)

ADR-0064 2절의 요청 칸에 아래 4칸이 더해진다. 보내지 않으면 지금과 똑같이 동작한다.

| 칸 | 최대 | 규칙 |
| --- | --- | --- |
| `ordererName` | 50 | 앞뒤 공백을 지운 1~50자, 제어 문자 불가 |
| `ordererPhone` | 30 | 하이픈 · 공백을 지운 뒤 `0`으로 시작하는 9~11자리 숫자. `+82` 같은 국가 번호는 받지 않는다 |
| `pickupName` | 50 | `ordererName`과 같은 규칙. 원문은 저장하지 않는다 |
| `pickupPhone` | 30 | `ordererPhone`과 같은 규칙. 원문은 저장하지 않는다 |

- 형식이 틀리면 `400 INVALID_REQUEST`. 세션에 고정되는 값이라 `buyerEmail`과 같이 만들 때 막는다
- 같은 요청 키(`Idempotency-Key`)로 다시 보내면 연락처도 비교한다. 정규화한 값(공백 · 하이픈 제거)이 같으면 같은 세션, 다르면 `409 IDEMPOTENCY_KEY_CONFLICT`
- 주문자 칸 필수 전환은 후속이다 (ADR-0082 합의 5번). CakeShop이 칸을 실제로 보내게 하는 작업은 CakeShop 저장소 몫이다

## 2. 저장 (`payment_order_contacts`, P5)

세션 1건당 최대 1행. 세션과 같은 트랜잭션으로 저장해 하나만 남지 않게 한다. 칸 설명은 [ERD](../erd/README.md#602-p5-주문자-연락처-2026-11-06).

- 주문자 이름 · 전화가 모두 없으면 행을 만들지 않는다
- 픽업자는 "주문자와 다름"(`pickup_differs`)만 남긴다 (11/03 팀 회의). 픽업자를 보내지 않으면 NULL
- `request_digest`: 같은 요청 키 재요청의 연락처 비교용 HMAC. 픽업자 값도 넣는다
- 행은 고치지 않는다. 보관 기간 정리(실험안: 결제 후 540일 뒤 암호문 파기)는 2차다 (ADR-0082 합의 6번)

## 3. 키 (`donworry.pii.*`)

| 설정 | 환경 변수 | 내용 |
| --- | --- | --- |
| `donworry.pii.encryption-key` | `DONWORRY_PII_ENCRYPTION_KEY` | AES-256-GCM 키, 32바이트 Base64 |
| `donworry.pii.hmac-key` | `DONWORRY_PII_HMAC_KEY` | HMAC-SHA256 키, 32바이트 Base64, 암호화 키와 다른 값 |

- `openssl rand -base64 32`로 각각 만든다. 저장소에 적지 않는다. 운영은 환경 변수로만 넣는다
- 둘 중 하나라도 비어 있으면 연락처를 저장하지 않고 결제는 그대로 진행한다 (시작할 때 경고 로그 1줄)
- 값이 Base64가 아니거나 32바이트가 아니면 앱이 시작하지 않는다 — 잘못 넣은 키로 읽을 수 없는 암호문이 쌓이지 않게 한다
- 저장한 뒤 키를 바꾸면 기존 암호문을 읽지 못한다. 키 교체(`key_id`)는 후속이다
- 암호문에는 가맹점 ID와 칸 종류를 AAD로 묶는다. 다른 가맹점 · 다른 칸으로 옮겨 붙이면 풀리지 않는다

## 4. 로그

요청 객체의 `toString()`은 `buyerEmail` · 이름 · 전화를 찍지 않는다. 암호화 · 복호화 실패 예외에도 값을 넣지 않는다.
