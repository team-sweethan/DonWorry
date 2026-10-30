# 0051. 보안 직원 거래 내역 조회 — 모든 결제 · 서버 가리기

- **상태:** 제안 — #363 P5 · P1 확인, PR 리뷰 대상
- **날짜:** 2026-10-30
- **관련:** #363, [#363 HTTP 계약](../payment/363-payment-transactions-api.md), [#152 조회 설계](../payment/152-payment-operations-design.md), [ADR-0012](0012-payment-settlement-dependency.md)
- **영향 받는 담당:** P5 시은 (payment) · P1 주환 (SecurityConfig) · P4 현규 (화면)

## 배경

보안 직원 메뉴 "결제 > 거래 내역"(`/security/transactions`)은 빈 화면이다. 사건(Case)에 걸린 결제는 사건 화면에서 보이지만, FDS를 통과한 결제처럼 사건에 걸리지 않은 결제는 볼 곳이 없다. 예를 들어 사건을 조사하다 "이 고객이 사건 전에 무엇을 결제했나"를 확인할 수 없다.

기존 운영 조회(`/api/payment-operations`, #188)는 "처리할 일 확인"용이라 미처리 후보만 오래된 순으로 주고 카드 · 기기 · IP 칸이 없다. #152 설계는 운영 조회 기본 응답에서 고객 참조 원문을 뺐다.

## 결정

- payment 모듈에 새 HTTP 조회 `GET /api/payment-transactions`를 둔다. 데이터 주인(P5) 안에서 가린 뒤 내보낸다
- **모든 결제**를 최신순(`payment_id` 내림차순)으로 준다. 기간은 기본 최근 7일, 최대 31일
- 거르기: 가맹점 · 결제 상태 · 주문번호 · 고객 참조. 주문번호 · 고객 참조는 **대소문자까지 정확히 일치**한다. `payments`의 두 칸은 DB 기본 비교(`utf8mb4_unicode_ci`)라, 조회에서 `utf8mb4_bin`으로 비교한다
- 카드 토큰 · IP 원문 · 기기 ID 원문 · 이메일 원문 · 승인 참조는 응답에 넣지 않는다. IP · 기기 ID · 이메일은 서버에서 가린 값만, 카드는 가맹점이 보낸 표시값만 준다
- 고객 참조는 원문으로 준다. #152 운영 조회의 "기본 제외"와 다르다. 이유:
  - 보안 직원 · 관리자만 보는 화면이다
  - 사건 화면 · AI 보고서도 이미 원문을 보여 준다
  - "이 고객의 다른 결제" 거르기에 필요하다
- 권한은 P1 `SecurityConfig`에서 운영 조회와 같은 규칙(SECURITY_STAFF · ADMIN, GET · HEAD)으로 검사한다. 마지막 규칙이 `anyRequest().permitAll()`이라 등록은 필수다
- 사건 연결과 가맹점 이름은 이 응답에 넣지 않는다. 화면이 기존 `GET /api/security-cases/payment-links`(#309)와 `GET /api/merchants`로 합친다
- AI 조사 Tool이 받는 데이터는 바꾸지 않는다. 사람은 모든 결제를 보고, AI는 필요한 만큼만 받는다 (#352)

## 고려한 대안

| 대안 | 버린 이유 |
| --- | --- |
| 운영 조회(`/api/payment-operations`)를 넓혀 모든 결제를 주기 | "미처리 확인"이라는 운영 조회의 뜻 · 분류 · 정렬이 바뀌고, 이미 확인한 #152 · #188 계약을 흔든다 |
| investigation 모듈에 사건 링크까지 합친 조회 API | 결제 원문 신호를 `payment/api`로 다른 모듈에 내보내야 한다. payment → investigation 의존은 ADR-0012가 막으므로 합치는 쪽이 위층이어야 하는데, 원문을 모듈 밖으로 내는 비용이 더 크다 |
| 원문을 보내고 화면에서 가리기 | 원문이 브라우저 · 네트워크 기록까지 간다. 화면 가드는 인가가 아니다 (AGENTS.md 5절) |
| 고객 참조도 가리기 | 고객으로 거르는 이 화면의 주 용도를 못 쓴다. 이미 원문을 보여 주는 사건 화면과도 어긋난다 |
| `payments.created_at` 색인 추가 | P5 표 migration이 필요하다. 시연 규모에서는 최대 31일 제한으로 충분하다 (P5 확인) |
| `payments` 참조 칸을 `utf8mb4_bin`으로 바꾸는 migration | 같은 칸을 쓰는 다른 P5 조회의 동작까지 바뀐다. 이번에는 이 조회 안에서만 정확히 비교한다 |
| 구매 상품(상품명 · 수량) 칸 포함 | #334로 P6 `order_fulfillment_items`에 저장되지만 픽업 예정 정보를 받은 주문에만 있고 P6 공개 읽기 창구가 없다. 후속 |

## 영향

- API: 새 HTTP `GET /api/payment-transactions`. 기존 운영 조회 · `payment/api` · 이벤트는 그대로
- DB: 변경 없음 (`payments` · `payment_requests` 읽기만)
- 화면: `/security/transactions`가 이 API + 기존 사건 링크 · 가맹점 목록 API로 그린다 (#363 PR ②)
- 테스트: MariaDB 권한(비로그인 401 · OPERATOR 403) · 원문 미노출 · 요청 기록 없는 결제 · 기간 · 거르기 · 대소문자 구분, 단위 조건 검사 · 가리기
- 남은 것: 같은 대소문자 무시 비교가 P5 운영 조회의 `orderRef` 거르기와 `PaymentRepository`의 고객별 최근 결제 조회(#338 AI Tool이 사용)에도 있다. 이번 범위 밖이며 P5와 따로 정한다

## 검증

실행 결과는 #363 PR에 실행 커밋 · 명령 · 미검증과 함께 기록한다.
