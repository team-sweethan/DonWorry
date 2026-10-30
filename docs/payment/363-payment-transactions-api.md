# #363 거래 내역 조회 HTTP 계약

- 상태: feature 브랜치 구현. 병합 · 배포 전이다. 검사 결과는 PR "테스트 결과"를 따른다.
- 관련: [Issue #363](https://github.com/team-sweethan/DonWorry/issues/363) — P5 · P1 담당 확인 완료 (2026-10-30). 화면은 보안 직원 메뉴 "결제 > 거래 내역" (`/security/transactions`)
- 결정 이유 · 버린 대안: [ADR-0051](../decisions/0051-payment-transactions-read.md)
- [#188 운영 조회](188-payment-operations-api.md)와 별개다. 운영 조회는 미처리 후보만 오래된 순으로 주고, 이 API는 모든 결제를 최신순으로 준다. 운영 조회 계약은 바꾸지 않는다.

## 1. 경로와 권한

| 요청 | 역할 |
| --- | --- |
| `GET /api/payment-transactions` | 모든 결제 목록 (FDS 통과 · 차단 · 매입 완료 포함) |

- P1 SecurityConfig에서 운영 조회와 같은 규칙으로 GET · HEAD를 검사한다. 인증된 SECURITY_STAFF · ADMIN만 허용하고 OPERATOR는 거절한다 (비로그인 401 · OPERATOR 403)
- SecurityConfig의 마지막 규칙이 `anyRequest().permitAll()`이므로, 이 경로를 등록하지 않으면 로그인 없이 조회된다. 경로를 바꿀 때는 SecurityConfig도 같이 바꾼다
- 전체 가맹점(정지 가맹점 포함)을 볼 수 있으며 `merchantId`는 검색 조건이다
- 성공 · 실패 응답은 `Cache-Control: no-store`

## 2. 입력

모든 조건은 AND다. 모르는 파라미터 · 같은 파라미터 두 번 · 빈 값은 400이다 (오타 난 조건을 무시하고 거르지 않은 목록을 주지 않는다).

| 파라미터 | 형식 · 기본값 | 의미 |
| --- | --- | --- |
| `from` | ISO-8601 offset 시각, 기본 `to - 7일` | 결제 시각(`payments.created_at`) 하한, 포함 |
| `to` | ISO-8601 offset 시각, 기본 요청 시각 | 결제 시각 상한, 제외 |
| `merchantId` | 양수 Long | 가맹점 ID 일치 |
| `status` | PaymentStatus 이름 (대문자) | 현재 결제 상태 일치 |
| `orderRef` | 양끝 공백 없는 1~100자 | 주문번호 전체 일치, 대소문자 구분 (부분 검색 아님) |
| `customerRef` | 양끝 공백 없는 1~100자 | 고객 참조 전체 일치, 대소문자 구분 (부분 검색 아님) |
| `page` | 0 이상, 기본 0 | 페이지 번호 |
| `size` | 1~100, 기본 20 | 페이지 크기. 최대 100은 P3 `payment-links`가 한 번에 받는 최대 개수와 같다 |

- 기간은 `from < to`, 최대 31일. `payments.created_at`에 색인이 없어 전체 기간 조회를 막는다 (색인 추가는 이번 범위 밖, P5 확인)
- 정렬은 `payment_id` 내림차순 (= 생성 순서, PK)
- `payments`의 참조 칸은 DB 기본 비교(`utf8mb4_unicode_ci`)라 대소문자를 무시한다. `payment_orders`는 `utf8mb4_bin`이라 `ORD-A`와 `ord-a`가 다른 주문일 수 있으므로, 이 조회는 `utf8mb4_bin`으로 다시 비교한다

## 3. 응답

```json
{
  "items": [
    {
      "paymentId": 16,
      "createdAt": "2026-10-30T02:02:00Z",
      "merchantId": 1,
      "customerRef": "customer-abc",
      "orderRef": "ORD-20261030-0036",
      "attemptNo": 2,
      "amount": 600000,
      "paymentStatus": "HELD",
      "fdsResult": "REVIEW",
      "evaluationId": 41,
      "cardDisplayLabel": "신한 ****1234",
      "ipCountryCode": "KR",
      "maskedIpAddress": "211.234.*.*",
      "maskedDeviceId": "dev-7f3a…",
      "maskedBuyerEmail": "ch***@gmail.com"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "hasNext": false,
  "from": "2026-10-23T02:10:00Z",
  "to": "2026-10-30T02:10:00Z"
}
```

- `from` · `to`는 실제로 적용한 기간이다
- 같은 주문을 다시 결제하면 결제 행이 새로 생긴다. `attemptNo`로 몇 번째 시도인지 구분한다 (`payment_requests`는 결제당 최대 1행)
- 요청 기록(`payment_requests`)이 없는 결제도 목록에 나온다. 이때 `attemptNo` · `fdsResult` · `evaluationId` · 신호 칸은 `null`이다
- `fdsResult`는 `ALLOW` · `REVIEW` · `DENY` · `ERROR` 또는 평가 전 `null`. `evaluationId`는 평가 결과가 있을 때만 준다 (경보 상세 `/security/alerts/{evaluationId}`)
- 사건(Case) 연결은 이 응답에 없다. payment는 investigation을 참조할 수 없으므로(ADR-0012) 화면이 P3 `GET /api/security-cases/payment-links`로 합친다
- 가맹점 이름도 이 응답에 없다. 화면이 기존 `GET /api/merchants`로 바꾼다

## 4. 응답에 넣지 않는 값과 가리기

원문은 payment 모듈 밖으로 나가지 않는다. 가리기는 서버에서 하고, 가린 칸은 이름에 `masked`를 붙인다.

- 응답에 없음: 카드 토큰 · IP 주소 원문 · 기기 ID 원문 · 이메일 원문 · 승인 참조(`authorization_ref`)
- 카드: 가맹점이 보낸 표시값(`cardDisplayLabel`)만
- 고객 참조: 원문 (보안 직원 · 관리자 화면이고, 사건 화면 · AI 보고서도 원문을 보여 준다. 고객 거르기에 필요 — #363 P5 확인)

| 칸 | 원문 → 응답 |
| --- | --- |
| `maskedIpAddress` | `211.234.10.5` → `211.234.*.*`, `2001:db8:85a3::8a2e` → `2001:db8:*` (앞 2묶음) |
| `maskedDeviceId` | `dev-7f3a9c21-abcd` → `dev-7f3a…` (앞 8자), 8자 이하는 절반만 (`dev12` → `de…`) |
| `maskedBuyerEmail` | `chk990727@gmail.com` → `ch***@gmail.com`, `@` 앞이 2자 이하면 1자만 (`ab@b.com` → `a***@b.com`) |

알 수 없는 모양이면 일부도 보여 주지 않고 `***`로 가린다 (예외로 목록 전체가 실패하지 않게 한다).

## 5. 오류

ProblemDetail + `code`. 실패를 200으로 바꾸지 않는다.

| 상황 | 결과 |
| --- | --- |
| 비로그인 · OPERATOR | 401 · 403 (P1 공통 응답) |
| 잘못된 조건 · 31일 초과 기간 | 400 `PAYMENT_TRANSACTIONS_INVALID_QUERY` |
| DB 연결 · 조회 실패 | 503 `PAYMENT_TRANSACTIONS_UNAVAILABLE` |
| 그 밖의 오류 | 500 `PAYMENT_TRANSACTIONS_INTERNAL_ERROR` |

## 6. 하지 않는 것

- 구매 상품(상품명 · 수량): #334로 P6 `order_fulfillment_items`에 저장되지만 픽업 예정 정보를 받은 주문에만 있고 P6 공개 읽기 창구가 없다 → 후속
- 결제 상태 변경 · 조치 실행 (조회만 한다)
- DB 변경 · AI 조사 Tool 데이터 변경
