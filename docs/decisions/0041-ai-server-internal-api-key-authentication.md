# 0041. AI 서버 내부 API 서비스 키 인증

- **상태:** 제안 — #266 P1 동의(identity/security에 P4가 추가), 구현·PR 리뷰 대상
- **날짜:** 2026-10-28
- **관련:** #266, #253, #254, [ADR-0009](0009-ai-investigation-call-structure.md), [ADR-0025](0025-csrf-preparation.md), [ADR-0037](0037-merchant-hmac-authentication.md)
- **영향 받는 담당:** P1 identity(보안 체인 추가), P4 investigation/report·ai-server

## 배경

ai-server는 Spring의 `/internal/ai/**`를 부른다. Tool API(`/internal/ai/tools/*`)로 결제·FDS 근거를 읽고, 결과 콜백(`/internal/ai/reports/{reportId}/result`)으로 보고서를 보낸다.
ADR-0009 결정 1은 이 경로를 직원 로그인과 별개인 서비스 키(헤더)로 인증한다고 정했지만, 보안 체인 작업은 "P1과 협의"로 남아 있었다.
#253에서는 P1 부재로 P4 컨트롤러 안의 `AiServerKeyGuard`가 키를 임시로 확인했다. 기본 체인(`SecurityConfig`)은 `/internal/**`를 CSRF 예외·permitAll로 두기 때문에, 새 내부 컨트롤러가 확인 코드를 빠뜨리면 그 경로는 열린다.

## 결정

- identity에 `/internal/ai/**` 전용 SecurityFilterChain(`AiServerSecurityConfiguration`, `@Order(2)`)을 둔다. 가맹점 HMAC 체인(`@Order(1)`) 다음, 기본 직원 체인보다 먼저 선택된다.
- 헤더 `X-Internal-Api-Key`를 Spring 설정 `ai.server.api-key`(환경변수 `AI_SERVER_API_KEY`)와 `MessageDigest.isEqual`로 비교한다. ai-server의 `AI_SERVER_INTERNAL_API_KEY`와 같은 값이다.
- 키 설정이 비어 있으면 모든 요청을 거절한다. 헤더 누락·불일치·중복·256자 초과도 거절한다. 실패는 401 `AI_SERVER_AUTH_001` ProblemDetail로 직원·가맹점 거절과 같은 형식이다.
- 세션을 읽거나 만들지 않는다(STATELESS·NullSecurityContextRepository). CSRF·form 로그인·Basic·로그아웃·요청 캐시는 끈다. 직원 세션은 서비스 키를 대신하지 않는다.
- 체인은 `ai.server.enabled`와 무관하게 항상 등록한다. 기능이 꺼져 컨트롤러가 없어도 키 없는 요청은 404 대신 401이다.
- 키는 investigation 설정 클래스(`AiServerProperties`) 대신 문자열 설정값으로 읽는다. identity의 `allowedDependencies`는 바꾸지 않는다.
- 필터는 Bean으로 등록하지 않고 체인 안에서 만든다. Bean으로 두면 Spring Boot가 모든 요청에 서블릿 필터로 걸기 때문이다.
- P4는 `AiServerKeyGuard`와 컨트롤러의 헤더 확인을 지운다. Tool API 스위치 `ai.tools.enabled`는 `ai.server.enabled`로 합친다. ai-server를 켜는 설정이 키 포함 3개에서 2개(`AI_SERVER_ENABLED`·`AI_SERVER_API_KEY`)로 준다.
- 기본 체인 `SecurityConfig`의 `/internal/**` CSRF 예외·permitAll은 바꾸지 않는다. CakeShop 웹훅 등 다른 내부 경로 정책은 #68 범위다.

## 버린 대안

| 대안 | 이유 |
| --- | --- |
| 컨트롤러마다 키 확인 유지 (`AiServerKeyGuard`) | 새 내부 컨트롤러가 확인을 빠뜨리면 기본 체인의 permitAll로 열린다. 인증 위치가 보안 담당 영역 밖에 있다 |
| `SecurityConfig` 기본 체인 안에 경로 규칙 추가 | 직원 세션·강제 비밀번호 변경·CSRF 설정과 서버 간 인증이 한 체인에 섞인다 (ADR-0037과 같은 이유) |
| `ai.server.enabled`일 때만 체인 등록 | 꺼진 상태에서 `/internal/ai/**`가 직원 체인의 permitAll로 넘어간다 |
| identity가 `AiServerProperties`를 직접 주입 | identity → investigation 의존이 생겨 ModularityTests를 깨고 의존 방향을 거꾸로 만든다 |
| HMAC 서명 | 요청 본문 무결성까지 보장하지만, 같은 팀이 운영하는 내부 서버 하나를 위해 서명·nonce 저장이 과하다. 키 노출 대응은 키 교체로 한다 |
| `ai.tools.enabled` 별도 유지 | 키 인증이 생겨 따로 막을 이유가 없고, 조사관은 켰는데 Tool이 꺼진 어긋난 설정을 만든다 |

## 영향과 검증

- API: `/internal/ai/**` 401 응답 본문이 P4 오류 형식에서 `AI_SERVER_AUTH_001` 보안 형식으로 바뀐다. 상태 코드·경로·성공 응답은 그대로다. ai-server는 상태 코드만 본다.
- 설정: `AI_TOOLS_ENABLED`(`ai.tools.enabled`)는 더 읽지 않는다. Tool API는 `AI_SERVER_ENABLED=true`일 때 생긴다.
- DB·화면·직원 API·가맹점 HMAC: 변경 없음.
- 테스트: `AiServerSecurityTests`(키 없음·틀림·중복·과다 길이·설정 비어 있음·직원 세션 분리·기능 꺼짐 401·다른 경로 기존 정책), 기존 P4 HTTP 테스트의 401이 보안 체인에서 나오는지 확인한다.
- 키 배포·교체·HTTPS는 후속이다. 이 ADR의 작성은 구현·검증 완료를 뜻하지 않는다. 실행 기록은 #266 PR에 적는다.
