# 0059. 직원 보안 체인의 기본 거절 (`anyRequest().denyAll()`)

- **상태:** 기본 거절 유지, Case 경로의 임시 `permitAll` 항목만 대체됨 (→ [0065](0065-case-session-authorization.md)); 그 외 제안·PR 리뷰 대상
- **날짜:** 2026-11-01
- **관련:** Issue #392, #68, #390, [ADR-0014](0014-session-authentication.md) (그 외 경로 `permitAll` 유지 항목을 대체), [ADR-0041](0041-ai-server-internal-api-key-authentication.md), [ADR-0032](0032-production-admin-startup.md)
- **영향 받는 담당:** P1 주환 (SecurityConfig), P2 정후 (FDS Mock), P3 수민 (Case · 시연 결제), P4 현규 (보고서 진행 조회), P5 · P6 (새 API 등록)

## 배경

ADR-0014는 로그인 연동 전 다른 담당 개발을 막지 않으려고 `/api/auth/me` 외 경로를 `permitAll`로 두었다. 이후 역할 규칙이 하나씩 추가됐지만 직원 체인(`SecurityConfig`)의 마지막 규칙은 여전히 `anyRequest().permitAll()`이었다.

- 등록을 빠뜨린 새 API는 로그인 없이 열린다. #363 문서와 ADR-0051은 이 때문에 "등록 필수"를 따로 적어야 했다
- `POST /api/demo/held-payments`는 인증도, 운영에서 끄는 조건도 없었다
- `/internal/**` 중 AI 체인(`/internal/ai/**`) 밖의 경로도 직원 체인의 `permitAll`로 넘어갔다 (ADR-0041 배경)

## 결정

- 직원 체인의 마지막 규칙을 `anyRequest().denyAll()`로 바꾼다. 비로그인은 401(`AUTH_001`), 로그인 사용자는 403(`AUTH_002`)이다
- 공개 경로는 와일드카드 없이 **정확한 메서드 · 경로**만 연다: 로그인 · 로그아웃 · CSRF 토큰(GET · HEAD), 이용 신청 4개 POST와 설정 조회(GET · HEAD), `/error`
- 컨트롤러 · 서비스 안 Guard(`SecurityStaffGuard` · `MerchantViewerGuard`)로만 검사하던 Case 경로 11개는 지금 있는 메서드 · 경로만 정확히 열어 두고, 역할 규칙 이전은 담당 확인 후 한다
- 인증 없는 시연용 API(`/api/demo/held-payments`, FDS Mock 평가 3개)는 `security.demo-api.enabled`로 연다. 기본값 `true`(로컬 · 시연 · 테스트), 운영 `false`이며 운영에서 `true`면 기동하지 않는다
- 새 API를 추가하는 담당은 같은 PR에서 `SecurityConfig`에 권한 규칙을 추가한다. 추가하지 않으면 401 · 403으로 바로 드러난다

## 고려한 대안

| 대안 | 버린 이유 |
| --- | --- |
| `permitAll` 유지, 리뷰로 등록 누락 잡기 | 누락이 곧 무인증 노출이다. 실제로 문서마다 "등록 필수" 경고를 반복하고 있었다 |
| `anyRequest().authenticated()` | 로그인만 하면 역할과 무관하게 등록 안 된 API에 닿는다. 사장님 계정으로 직원 API를 부르는 경우를 막지 못한다 |
| Case · 시연 API까지 이번에 역할 규칙으로 잠그기 | P3 · P4 테스트 3개(`CaseFlowTests` · `MerchantCaseFlowTests` · `AiInvestigationFlowTests`)가 Spring Security 세션 없이 `CurrentStaff`를 대역으로 바꿔 호출한다. 담당 파일 수정이 필요해 확인 후로 미룬다 |
| 열어 둘 경로를 `/api/security-cases/**`처럼 접두사로 등록 | 같은 접두사로 추가된 새 API가 Guard를 빠뜨리면 다시 열린다. 기본 거절의 효과가 사라진다 |
| 시연 결제 API를 운영 프로필(`@Profile`)로 컨트롤러째 끄기 | P3 컨트롤러 수정이 필요하다. 보안 설정에서 막으면 P1 파일만 바뀌고, FDS Mock의 기존 운영 기동 검사와 같은 방식으로 맞출 수 있다 |

## 영향

- API: 등록하지 않은 경로 · 메서드는 401 · 403. POST 전용 경로에 GET을 보내는 등 잘못된 메서드는 405 대신 401 · 403. 운영에서 시연 결제 · FDS Mock은 401 · 403
- DB: 없음
- 화면: 없음. 프런트 `src/api/`가 부르는 경로를 대조해 모두 등록돼 있음을 확인했다
- 테스트: `identity/security` 테스트의 "등록 안 된 경로는 404" 기대를 401 · 403으로 바꾸고, 공개 경로 · 접두사 우회 · 시연 API 끄기 · 실제 서버 `/error` 경유 테스트를 추가했다
- 문서: ADR-0014 상태 갱신. ADR-0051 · #363 · #152 문서의 "마지막 규칙이 `permitAll`" 서술은 당시 기록으로 두고, 현재 기준은 이 ADR이다

## 검증

- `cd backend && ./gradlew check mariaDbTest` 통과 (#392 PR 기록)
- 미검증: 세 역할로 실제 화면을 눌러 본 확인, 운영 배포 환경(HTTPS · 프록시) 확인
