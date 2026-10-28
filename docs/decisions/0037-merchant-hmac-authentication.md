# 0037. CakeShop 요청의 HMAC 인증과 가맹점 신원

- **상태:** 제안 — #219·#241 사용자 구현 승인. nonce DB 스키마 사용자 승인, 구현·PR 리뷰 대상. P6 업무 연결은 #227 후속
- **날짜:** 2026-10-27
- **관련:** #219, #149, #68, [ADR-0012](0012-payment-settlement-dependency.md), [ADR-0014](0014-session-authentication.md), [ADR-0025](0025-csrf-preparation.md), [ADR-0030](0030-cakeshop-payment-attempts.md)
- **영향 받는 담당:** P1 identity·CakeShop, P5, P6(#241 공통 인증 경로 확장). 원래 #219 범위에서 제외했던 P6 확장은 아래 후속 절을 따른다.

## 배경

P5의 결제 HTTP는 구현됐지만 인증 연결 전 기본 비활성이고, 기존 문서는 X-API-Key를 제안한다.
#219와 사용자는 HMAC으로 진행하기로 했다. 기존 merchants.secret_reference는 키 원문 대신 환경변수 참조를 저장하려고 마련돼 있다.
직원 JSESSIONID를 CakeShop 서버 인증으로 사용하거나 요청의 merchantId를 신뢰해서는 안 된다.

## 결정

- 정확한 wire format·헤더·오류·경계·운영 준비 조건은 [#219 계약](../identity/219-merchant-hmac.md)을 따른다.
- identity가 HMAC-SHA256으로 가맹점·시각·nonce·메서드·원본 경로/쿼리·Content-Type·멱등 키·본문 해시를 확인한다. 허용 시각은 ±300초다.
- 요청별 nonce를 P1 merchant_request_nonces에 영속 기록한다. 사용자가 테이블과 저장소 ERD·ADR 선행 반영, Notion 후속 반영을 승인했다. 가맹점 행 잠금 안에서 중복 확인·INSERT하고 DB UNIQUE도 유지한다.
- nonce는 업무 실행 전 별도 커밋하고 서명 유효 시간이 지난 뒤에만 지운다. HTTP 재시도는 새 서명·nonce, 결제 키·본문은 그대로 유지한다.
- 서버 간 경로 전용 SecurityFilterChain을 기존 직원 체인보다 먼저 선택한다. 세션 인증을 읽거나 저장하지 않고, 지정 메서드만 CSRF에서 제외한다.
- identity.api.CurrentMerchant / MerchantIdentity로 요청 범위의 가맹점 ID를 제공한다. P5 기존 내부 포트에 어댑터만 추가하며 Controller·업무 상태 전이를 바꾸지 않는다.
- INACTIVE 가맹점의 유효 서명 GET은 허용하고 POST는 403이다. 상태 검사 전에 nonce를 소비해 거절한 서명이 재활성화 뒤 실행되지 않게 한다. 자기 결제·주문 범위 404는 P5가 검사한다.
- 서버 간 경로 체인은 payment.http.enabled와 무관하게 등록해, 기능이 꺼져도 직원 세션 체인으로 넘어가지 않게 한다.
- 키 원문은 환경변수에서만 읽고 원문·서명·본문은 기록하지 않는다. 실제 키 배포·관리 화면·자동 교체·HTTPS 설정은 후속이다.
- P6 경로·settlement 의존과 ADR-0009의 AI 서비스 키 인증은 이번 구현에 포함하지 않는다. 기존 allowedDependencies를 변경하지 않는다.

## 버린 대안

| 대안 | 이유 |
| --- | --- |
| X-API-Key 헤더 | #219의 HMAC 선택과 다르고, 요청별 내용 무결성을 별도로 검증해야 한다 |
| 시각만 검증 | 허용 시간 내 같은 서명을 다시 사용할 수 있다 |
| 경로·본문만 서명 | 주문 조회 쿼리나 결제 멱등 키를 바꿔도 통과할 수 있다 |
| 만료 전 nonce를 밀어내는 메모리 캐시 | 재시작·서버 확장·캐시 퇴출 뒤 재전송 차단이 사라진다 |
| nonce를 결제 트랜잭션에 함께 저장 | 실패 롤백 뒤 같은 서명을 다시 사용할 수 있다 |
| 직원 세션과 같은 인증 체인으로 처리 | 직원 인증·강제 비밀번호 변경·CSRF·만료 처리와 서버 인증을 혼동하기 쉽다 |
| API 전체 CSRF 예외 또는 P6 경로 추측 | #219 범위를 넘고 미합의 경로를 노출할 수 있다 |
| 인증 완료와 동시에 P5 HTTP 기본 활성화 | CakeShop 서명 코드·키 배포·실제 HTTPS 통합 검증이 아직 없다 |

## 영향과 검증

- API: P5의 세 경로에 HMAC 필요, 새 identity 공개 읽기 경계·안전한 MERCHANT_AUTH 오류 추가. 기존 P5 업무 JSON·직원 API는 유지.
- DB: 기존 secret_reference Entity 매핑, 승인한 P1 nonce 테이블·FK·UNIQUE·만료 인덱스를 새 migration 하나로 추가.
- 화면: 변경 없음. CakeShop 저장소 서명 생성은 후속.
- 테스트: 서명·재전송·직원 인증 분리·CSRF·P5 실제 HTTP 회귀, MariaDB 영속 기록·동시성·롤백·만료 삭제 검사.
- 문서: 기존 P5 API Key 문서는 당시 제안으로 보존하고 최신 HMAC 계약으로 연결한다. 팀 메시지는 발송하지 않는다.
- 실행 기록·미검증: #219 계약 문서에 기록한다. 이 ADR의 작성은 구현·검증 완료를 뜻하지 않는다.

## #241 후속: P6 픽업 PUT 공통 인증 연결 (2026-10-27)

원래 #219의 P6 제외 범위를 #241 사용자 구현 승인과 P6가 전달한 확정 계약에 따라 확장한다.
P6 업무 구현·검증 완료를 의미하지 않는다. 모듈 의존은 이미 채택된 [ADR-0038](0038-settlement-identity-dependency.md)을 따른다.

- P6 확정 경로 `PUT /api/v1/order-fulfillments/{orderId}/schedule` · `PUT /api/v1/order-fulfillments/{orderId}/completion`만 HMAC 허용·CSRF 예외로 추가한다.
- 두 경로는 모든 메서드에서 전용 체인이 선택한다. 미지원 메서드는 405·`Allow: PUT`이며 직원 세션 체인으로 넘어가지 않는다. Controller 부재·기능 비활성에도 체인은 유지한다.
- INACTIVE 가맹점의 위 PUT은 허용한다. P6 제안과 P1 결정이 일치하며, 기존 주문의 픽업 통지가 누락돼 정산 일정이 멈추는 것을 방지한다. P5 POST 차단·GET 허용은 유지한다.
- P6는 `CurrentMerchant.findCurrent()`의 ID만 사용하고 본문 merchantId·직원 세션으로 대체하지 않는다. P6 어댑터·Controller·주문/매입 ID 대조·선도착 저장은 #226·#227에서 구현한다.
- 같은 서명 재사용은 401, 새 서명으로 같은 본문을 보내는 경우는 P6 업무 멱등 검사의 대상이다. 키 등록은 #246, CakeShop 발신 코드·실제 호출 검증은 별도 연동 작업이다.

### 버린 대안과 영향

| 대안 | 버린 이유 |
| --- | --- |
| 정지 가맹점의 픽업 PUT도 차단 | 신규 결제를 제한하려다 기존 주문의 후속 정산 통지까지 멈추게 한다 |
| order-fulfillments 전체 하위 경로를 CSRF 예외로 지정 | 아직 합의하지 않은 API까지 예외가 확대된다 |
| P6 기능 활성화 때만 HMAC 체인 등록 | 비활성·Controller 부재 시 직원 체인으로 빠질 수 있다 |
| 요청 merchantId 또는 직원 세션으로 대체 | 서버 간 신원과 사용자 신원이 섞이고 요청 값으로 타 가맹점에 접근할 수 있다 |

- API: 두 PUT에 기존 HMAC wire format 적용. 각 경로의 지원 메서드를 `Allow` 헤더와 함께 정의한다. P6 업무 JSON·응답 정책은 #227 소유다.
- DB·화면·공개 Java 계약·의존 목록: 변경 없음.
- 테스트: 두 PUT 서명·변조·만료·직원 세션 분리·정지 허용·미지원 메서드·CSRF 범위, 실제 HTTP/DB nonce 소비·장애 차단·Controller 부재, 기존 P5 회귀를 검사한다.
- 검증 기록·미검증은 [HMAC 계약](../identity/219-merchant-hmac.md)에 적는다. 테스트용 Controller·키 대역과 실제 P6/CakeShop 통합은 구분한다.
