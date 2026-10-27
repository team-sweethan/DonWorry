# 0037. CakeShop 요청의 HMAC 인증과 가맹점 신원

- **상태:** 제안 — #219 사용자 구현 승인, P6 연결 제외. nonce DB 스키마 사용자 승인, 구현·PR 리뷰 대상
- **날짜:** 2026-10-27
- **관련:** #219, #149, #68, [ADR-0012](0012-payment-settlement-dependency.md), [ADR-0014](0014-session-authentication.md), [ADR-0025](0025-csrf-preparation.md), [ADR-0030](0030-cakeshop-payment-attempts.md)
- **영향 받는 담당:** P1 identity·CakeShop, P5. P6는 후속 공개 경계 소비자이며 이번 변경 대상이 아니다.

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
- INACTIVE 가맹점의 유효 서명 GET은 허용하고 POST는 403이다. 자기 결제·주문 범위 404는 P5가 검사한다.
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
