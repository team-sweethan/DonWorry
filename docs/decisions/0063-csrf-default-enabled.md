# 0063. 브라우저 변경 요청의 CSRF 검사 기본 활성화

- **상태:** 제안 — #408 PR B 사용자 구현 승인, PR 리뷰 대상
- **날짜:** 2026-11-02
- **관련:** #408, #68 5단계, [ADR-0025](0025-csrf-preparation.md), [ADR-0037](0037-merchant-hmac-authentication.md), [ADR-0041](0041-ai-server-internal-api-key-authentication.md)
- **영향 받는 담당:** P1~P6, CakeShop·AI 서버 인증 회귀 확인

## 배경

CSRF 쿠키 발급·프론트 헤더 전송·로그인과 로그아웃의 토큰 갱신은 구현되어 있으나 전역 검사 기본값은 false였다. #408에서 팀 공지 후 단계적으로 실제 세션 경로와 보안 검사를 적용한다. PR A의 데모 로그인 제거에 이어 PR B에서 기본 검사를 켠다.

## 결정

- `application.yml`의 `DONWORRY_CSRF_ENABLED` 기본값과 `SecurityConfig`의 속성 누락 시 기본값을 모두 true로 한다.
- 브라우저의 POST·PUT·PATCH·DELETE 등 안전하지 않은 메서드는 기존 `XSRF-TOKEN` 쿠키와 `X-XSRF-TOKEN` 헤더를 검사한다. 누락·불일치는 업무 처리 전 403 `CSRF_001`이다. 익명 로그인과 공개 POST도 토큰이 필요하다. 유효 토큰 이후 인증·인가 실패는 기존 AUTH 계약을 따른다.
- GET·HEAD·OPTIONS·TRACE는 기존처럼 검사하지 않는다. CookieCsrfTokenRepository·SPA 처리·세션 정책·프론트 재시도 계약은 유지한다.
- 명시적 false 설정은 유지한다. 기존에 항상 보호하는 신청·연동 관리·FDS 복구 경로는 false에서도 계속 검사한다. 이를 위해 기존 matcher를 유지한다.
- HMAC 전용 체인의 정확한 경로·메서드 예외와 AI 서비스 키 전용 체인을 유지한다. 직원 체인의 기존 `/internal/**` 예외도 유지하며 새 예외는 추가하지 않는다. Case 역할 규칙은 PR C에서 다룬다.
- ADR-0025의 기본값 false와 활성화 연기 결정만 대체한다. 나머지 토큰·쿠키·재시도 계약은 유지한다. ADR-0014·0032·0039 등의 과거 비활성 설명에는 본 결정이 우선한다.

## 고려한 대안

| 대안 | 버린 이유 |
| --- | --- |
| 기본 false 유지, 운영 환경변수만 true | 새 환경이나 설정 누락 시 보호가 빠진다 |
| 환경변수와 항상 보호 matcher 제거 | 기존 false 동작과 특정 경로의 강제 보호 보장을 함께 바꾸게 된다 |
| 테스트 전체에서 CSRF 끄기 | 실제 기본 설정의 회귀를 놓친다 |
| `/api/**` 또는 시연 API를 넓게 예외 처리 | 세션 기반 변경 요청 보호 목적을 훼손한다 |

## 영향

- API: 토큰 없는 변경 요청은 기본적으로 403 `CSRF_001`이다. 새 API·DB·Java 공개 계약은 없다.
- DB: 변경 없음.
- 화면: 기존 공통 클라이언트의 토큰 교환을 사용한다.
- 테스트: MockMvc 업무·인가 테스트에 유효 토큰을 제공하고, 실제 HTTP 테스트는 쿠키 저장소의 토큰을 헤더로 보낸다. 토큰 거절 테스트는 원시 요청으로 검증한다. 테스트 도우미는 업무 요청을 재시도하지 않는다.
- 도구: `scripts/demo/*.py`는 HMAC 요청이므로 토큰을 추가하지 않는다.
- 환경: 로컬·운영의 외부 설정에 `DONWORRY_CSRF_ENABLED=false` 또는 `security.csrf.enabled=false`가 남아 있는지 확인해야 한다. 저장소 밖 운영 설정은 이 변경으로 확인하거나 수정하지 않는다.

## 개발자 사용법

curl은 쿠키 저장 파일을 요청마다 공유한다. 아래 `$CSRF_TOKEN`은 쿠키 파일에서 읽은 현재 `XSRF-TOKEN` 값이다. 비밀번호는 커밋하거나 공유 로그에 남기지 않는다.

```bash
curl -c cookies.txt -b cookies.txt http://localhost:8081/api/auth/csrf
curl -c cookies.txt -b cookies.txt \
  -H "X-XSRF-TOKEN: $CSRF_TOKEN" -H 'Content-Type: application/json' \
  --data @login.json http://localhost:8081/api/auth/login
```

쿠키 파일에서 토큰 읽기 예: `awk '$6 == "XSRF-TOKEN" { print $7 }' cookies.txt`. 로그인 성공 후 기존 토큰이 삭제되므로 `/api/auth/csrf`를 다시 호출해 새 토큰을 읽는다. 이후 변경 요청에도 현재 쿠키와 같은 값을 헤더로 보낸다. 로그아웃 후에도 다음 변경 요청 전에 다시 발급받는다.

Postman도 같은 호스트의 쿠키 저장소를 유지하고 GET `/api/auth/csrf` 호출 후 `XSRF-TOKEN` 쿠키 값을 `X-XSRF-TOKEN` 헤더로 설정한다. 로그인·로그아웃 이후에는 토큰을 다시 읽는다. 일반 권한 오류를 CSRF 오류로 취급하거나 변경 요청을 무조건 재전송하지 않는다.

## 검증

기본값을 명시하지 않은 `CsrfApiTests`에서 설정 변경 전 누락·불일치 차단 테스트 2개가 실패하는 것을 확인했다. 최종 검사 명령·결과·실행 커밋은 PR 본문에 기록한다.

미검증: 실제 브라우저 통합, 운영 HTTPS·프록시와 외부 환경변수, 실제 시연 서버를 대상으로 한 HMAC Python 스크립트 실행.
