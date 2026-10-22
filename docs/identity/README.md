# Identity — ADMIN 로그인 MVP

관련: Issue #68 · #69 · #76, [ADR-0014](../decisions/0014-session-authentication.md).

## 구현

- POST /api/auth/login: 아이디를 trim · 소문자로 정규화하고 BCrypt로 인증한다. 성공하면 관리자 정보와 조회 권한을 반환한다.
- GET /api/auth/me: 세션 사용자 정보로 응답하며 DB를 다시 조회하지 않는다.
- POST /api/auth/logout: Security 로그아웃 필터에서 세션과 인증 정보를 제거하고 204를 반환한다. 비로그인 · 반복 요청도 204다. GET은 로그아웃하지 않는다.
- 없는 아이디 · 틀린 비밀번호 · DISABLED 계정은 같은 401 AUTH_004 ProblemDetail로 응답한다. 입력 누락 · 공백 · 아이디 100자 초과(DB login_id 컬럼 길이) · 잘못된 JSON은 400("아이디와 비밀번호를 확인해 주세요.")이다. 400이면 DB를 조회하지 않는다.
- 계정 조회 중 DB 오류 등 인증 처리 자체가 실패하면 500 AUTH_005 ProblemDetail로 응답하고 error 로그를 남긴다. 처리하지 않으면 Security 필터가 401 AUTH_001(로그인 필요)로 바꿔 장애가 드러나지 않는다.
- 첫 로그인은 세션을 만들고, 기존 세션이 있으면 ID를 바꾼 뒤 인증을 명시적으로 저장한다.
- 세션에는 Entity · 비밀번호 · 비밀번호 해시를 보관하지 않는다. 별도의 Serializable 사용자 객체만 저장한다. 이 객체의 role은 UserRole enum이고, 응답 JSON의 role은 문자열("ADMIN")로 내보낸다.
- ADMIN 권한은 role로 계산한다. user_permissions는 조회하지 않는다. ADMIN이고 merchantId가 null이면 tenantScope=ALL, ADMIN 권한은 FDS_VIEW · FINANCE_VIEW다.
- DB · 프론트 · 다른 모듈 계약 변경은 없다. /me 외 접근 허용과 CSRF 비활성화는 ADR-0014대로 유지한다.

## 검증

2026-10-22, feature/76-auth-api에서 기준 커밋 79366e3 위의 #76 미커밋 작업 트리로 실행했다. 아래 결과는 기준 커밋 단독의 결과가 아니다.

- cd backend && ./gradlew spotlessApply check mariaDbTest: 통과.
- 일반 테스트 84개, MariaDB 테스트 14개. 실패 · 오류 · 건너뜀 0개.
- AuthApiTests: Repository를 대체하고 실제 BCrypt · 인증 Provider · Service · 필터로 로그인 성공/실패, 정규화, 입력 오류, 세션 생성 · ID 변경, /me, 로그아웃, 세션 인증 직렬화와 비밀정보 제외를 확인했다.
- AuthHttpTests: Testcontainers MariaDB와 실제 임의 포트 서버를 실행하고 테스트 계정 조회만 대체했다. HTTP 쿠키로 로그인 → me → 재로그인 → 로그아웃 → me(401)를 검증했다. JSESSIONID 발급 · 변경, HttpOnly · SameSite=Lax, GET 로그아웃 방지, 폐기된 쿠키 재사용 실패도 확인했다.
- 기존 모듈 경계 · 계층 검사와 Flyway 전체 적용 · JPA validate가 포함된다.

## 미검증 · 리뷰 사항

- 시드 ADMIN 계정으로 서버 기동 후 curl 로그인 → me → logout → me(401) 순서는 담당자가 직접 실행해 통과를 확인했다 (실행 기록은 저장소에 없음).
- AUTH_005 코드 값과 문구는 이번에 새로 정했다. AUTH_003은 비어 있지만 예약 여부를 확인하지 못했다.
- ADMIN 외 역할의 tenantScope는 #76에서 미정이다. 현재 구현은 null, permissions는 빈 목록이며 역할 확장 전 응답 계약 확정이 필요하다. 관리자 가맹점 정보가 비정상적으로 존재하는 경우에도 ALL을 반환하지 않는다.
- 로그인 후 계정이 DISABLED로 바뀌어도 기존 세션에는 즉시 반영되지 않는다. #76의 MVP 범위를 따르며 후속 검토 대상이다.
- 로그인 실패 문구는 #76의 초안인 “아이디 또는 비밀번호가 올바르지 않습니다.”를 사용한다.
