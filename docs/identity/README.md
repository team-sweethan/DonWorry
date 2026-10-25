# Identity — 로그인 MVP

관련: Issue #68 · #69 · #76, [ADR-0014](../decisions/0014-session-authentication.md).

## #126 CSRF 준비 (서버 검사 기본값 끔)

상세 계약·대안은 [ADR-0025](../decisions/0025-csrf-preparation.md)를 따른다.

- `security.csrf.enabled=false`가 기본값이다. 환경변수는 `DONWORRY_CSRF_ENABLED`다. false에서도 토큰 발급·전송·로그인/로그아웃 정리는 동작하며 토큰 없는 기존 변경 요청도 CSRF로 막지 않는다. 실제 활성화는 다른 담당에게 공지한 뒤 #68 5단계에서 한다.
- `GET /api/auth/csrf` → 204, `Cache-Control: no-store`. 로그인 전·비밀번호 강제 변경 중에도 호출할 수 있고, 토큰이 없으면 `XSRF-TOKEN` 쿠키를 발급한다. 로그인 세션을 새로 만들지 않는다. 쿠키는 JavaScript 읽기 가능·Path=/·SameSite=Lax이며, Secure는 요청 HTTPS 여부를 따른다. 인증용 JSESSIONID는 기존 HttpOnly 정책을 유지한다.
- 프론트 공통 `src/api/client.ts`가 POST·PUT·PATCH·DELETE 등 변경 요청에 `X-XSRF-TOKEN`을 붙인다. GET·HEAD·OPTIONS·TRACE는 토큰이 필요 없다. 토큰이 없으면 `/csrf`를 먼저 부르고 동시 발급은 공유한다. 발급 뒤에도 쿠키를 읽지 못하면 헤더 없이 보내 서버 설정에 맡기고, 같은 로그인 동안은 선행 발급을 반복하지 않는다. 쿠키를 메모리·localStorage에 복사해 보관하지 않는다.
- 검사가 켜졌을 때 누락·불일치는 403 `CSRF_001` ProblemDetail이다. 이 응답에만 거절된 쿠키를 지우고 새 토큰을 받아 한 번 재시도하며, AUTH_002·AUTH_008·네트워크·5xx와 Stream 본문은 자동 재시도하지 않는다. 세션이 바뀌거나 요청이 취소되면 대기하던 변경을 보내지 않는다.
- 수동 로그인 합성 전략에 `CsrfAuthenticationStrategy`를 추가했다. 일반·전체 로그아웃도 쿠키를 폐기하며 다음 안전한 요청 또는 다음 변경 전 `/csrf`에서 새 토큰을 받는다. #125 단일 세션 정책과 #128 본인 비밀번호 변경의 현재 세션 유지는 그대로다.
- `/internal/**`만 검사 예외를 준비했다. CakeShop 웹훅의 정확한 경로와 서버 간 인증은 #68 5단계에서 합의한다. DB·다른 모듈의 공개 Java 계약 변경은 없다. 운영 HTTPS·프록시 쿠키 확인은 #131 후속이다.
- 쿠키 저장 방식이므로 새 쿠키에 예전 헤더를 보내는 경우를 거부한다. 예전 쿠키·헤더 쌍 자체의 영구 폐기를 보장하는 세션 저장 토큰 방식은 아니다.

### #126 로컬 검증

2026-10-25, `feature/126-csrf-preparation`, 리뷰 반영 커밋 `e6c07a2` 위 최종 작업 트리(재리뷰 수정 포함)에서 실행했다. `e6c07a2` 단독의 결과가 아니다. 아래 검사 이후 추가한 내용은 이 검증 기록뿐이다.

- `cd backend && ./gradlew spotlessApply check mariaDbTest`: 통과. 일반 323개 중 성공 322개·기존 건너뜀 1개(`LocalSettlementDemoTests`), MariaDB 114개 성공, 실패·오류 0개. ModularityTests·ArchitectureTests·Flyway 전체 적용·JPA validate 포함.
- 검사를 켠 실제 Security 필터로 토큰 누락·헤더 누락·불일치 403 CSRF_001, 정상 토큰, 내부 경로 예외, 로그인 전 무세션 발급, 수동 로그인 후 새 쿠키+옛 헤더 거부, 일반·전체 로그아웃의 토큰 폐기를 확인했다. 기본 설정의 기존 인증·관리자·업무 회귀 검사도 통과했다.
- Testcontainers MariaDB와 실제 HTTP 쿠키 저장소로 익명 발급 → 로그인 → 토큰 폐기·재발급 → 옛 헤더 거부·로그인 유지 → 일반/전체 로그아웃 → 익명 새 토큰·me 401을 확인했다. 기존 로컬 DB나 운영 DB는 변경하지 않았다.
- `cd frontend && npm run lint && npm run format:check && npm run test && npm run build`: 통과. 테스트 46개 성공. CSRF 헤더·발급 공유·쿠키 삭제 후 새 토큰으로 한 번 재시도·쿠키 삭제 속성(Max-Age=0·Path=/)·쿠키를 읽지 못할 때 헤더 없이 전송과 선행 발급 반복 생략·오류 구분·세션 세대·취소·실패 복구·로그인/로그아웃 후 재발급·Stream 재전송 금지·발급 중 401의 만료 전파를 검사했다. 기존 lint 경고 4개·번들 크기 경고는 유지된다.
- `python3 .github/scripts/check_md_links.py`, `git diff --check`: 통과.

미검증: 실제 브라우저에서 프론트·백엔드 통합 동작, HTTPS·프록시 환경 쿠키, 실제 30분 만료 대기, Notion 정책 동기화. 공용 환경 CSRF 활성화·CakeShop 웹훅 예외 경로·서버 간 인증은 #68 5단계 후속이며 이번 작업에서 활성화하지 않았다. 원격 CI 결과는 PR에서 별도로 확인한다.

## #129 관리자 계정·권한 관리

계약과 대안은 [ADR-0024](../decisions/0024-admin-account-management.md), [상세 설계](129-admin-account-design.md)를 따른다.

- `/admin/users` 목록·검색·역할/상태 필터·페이지 나누기, `/admin/users/new` 생성, `/admin/users/:id` 권한·정지/복구·비밀번호 초기화를 제공한다.
- `GET /api/admin/users?page=0&size=20&keyword=&role=&status=`: items·page·size·totalElements. role/status는 필터 사용 시에만 보내며, size는 1~100이다. `GET /api/admin/users/{id}`는 상세다. 둘 다 비밀번호·해시를 반환하지 않는다.
- `POST /api/admin/users`: loginId·displayName·role·merchantId. 아이디 `[a-z0-9_]{3,30}`, 이름 1~100자, 역할은 OPERATOR·SECURITY_STAFF만 허용한다. OPERATOR는 ACTIVE 가맹점이 필수이고 다른 역할은 merchantId를 받지 않는다. 생성 시 기능 권한은 비어 있다. 응답은 201 `{user, temporaryPassword}`다.
- `PUT /api/admin/users/{id}/permissions`: `{permissions:[...]}` 최종 집합. OPERATOR만 FDS_VIEW·FINANCE_VIEW를 설정한다. ADMIN의 조회 권한은 기존 역할 계산이다.
- `PUT /api/admin/users/{id}/status`: `{status:"ACTIVE"|"DISABLED"}`. 자기 정지는 거절한다. 역할·소속 변경과 계정 삭제 API는 없다.
- `POST /api/admin/users/{id}/password-reset`: `{user, temporaryPassword}`를 반환하고 다음 로그인에서 변경을 요구한다. 자기 초기화도 가능하며 성공 응답 후 다음 요청부터 현재 세션이 만료된다.
- `GET /api/admin/merchants/options?keyword=`: ACTIVE 가맹점의 ID·이름을 최대 50개 반환한다. 이름으로 검색하며 생성·정지는 #130이다.
- 관리자 API는 ADMIN만 허용한다. 비로그인 401 AUTH_001, 비관리자 403 AUTH_002. 입력 오류 400 ADMIN_001, 없는 계정 404 ADMIN_002, 중복 아이디 409 ADMIN_003, 자기 정지 400 ADMIN_004, ACTIVE 가맹점 누락 400 ADMIN_005, 비사장님 권한 변경 400 ADMIN_006이다. DB 장애는 500 AUTH_007이다.
- 임시 비밀번호는 SecureRandom·기존 PasswordPolicy로 만들고 BCrypt만 DB에 저장한다. 원문은 생성·초기화 응답에 한 번만 포함하고 응답은 no-store다. 화면은 메모리 모달에만 표시하고 닫으면 제거한다. 응답을 잃으면 다시 초기화하며 자동 재시도·재조회는 없다.
- `platform_users.password_change_required`와 역할/가맹점 CHECK, INSERT 전용 `admin_change_histories`를 새 migration 세 개로 추가했다. 기존 시드는 false를 유지한다. 이력은 수행자·대상·유형·안전한 변경 전후 값·UTC 시각만 저장하고 비밀번호·해시는 제외한다. 이력 실패 시 계정 변경도 롤백한다.
- 변경은 수행자·대상 실제 loginId의 고정 분할 잠금을 인덱스 순서로 획득한다. 수행자 세션·DB 상태 재확인 → REQUIRES_NEW 커밋 → 대상 expireAll 뒤 잠금을 해제한다. 같은 상태·권한 집합은 이력·세션 종료를 반복하지 않는다. 가맹점 정지와의 경쟁 조건은 #130 후속이다.
- `/login`·`/me`에 passwordChangeRequired를 추가했다. true면 업무 메뉴를 숨기고 변경 화면으로 이동한다. 인증 경로를 제외한 해당 세션의 `/api/**`는 403 AUTH_008이며, 클라이언트는 오래된 로그인에서 온 알림을 무시한다. 본인 변경 성공 시 DB·현재 세션 플래그를 해제하고 me를 갱신한다. 현재 세션 ID는 유지한다.
- 강제 변경 중에는 임시 비밀번호를 새 비밀번호로 다시 쓸 수 없다 (400 AUTH_009). 일반 본인 변경은 #128 정책 그대로다. 전체 역할·가맹점별 서버 인가·CSRF는 #68 5단계다. 익명 업무 API 허용이 남아 있으므로 #129만으로 전체 API 접근 통제가 완료된 것으로 보지 않는다.
- Notion 선행 반영은 이번 작업에 한해 사용자 승인으로 저장소 ERD·ADR 기록으로 대체했다. Notion 최신 ERD 확인·동기화는 미수행이다.

### #129 검증

2026-10-25, `feature/129-admin-account-management`, 기준 `dev c19f94f` 위 최종 #129 작업 트리에서 실행했다. 기준 커밋 단독의 결과가 아니다. 아래 최종 검사 이후 변경은 검증 기록뿐이다.

- `cd backend && ./gradlew spotlessApply check mariaDbTest`: 통과. 일반 307개 중 성공 306개·기존 건너뜀 1개(`LocalSettlementDemoTests`), MariaDB 109개 성공. 실패·오류 0개. 전체 Flyway·JPA validate·ModularityTests·ArchitectureTests 포함.
- 실제 MariaDB·HTTP 쿠키로 관리자 영역 401·403, 생성·중복·입력·ACTIVE 가맹점, 자기 정지 금지, 권한 회수·상태 복구·임시 비밀번호 일회 응답, 강제 변경 전후 현재 세션·me, 자기 초기화 응답과 세션 종료를 검사했다.
- 이력 INSERT 실패 시 생성·상태·비밀번호·권한 변경 롤백과 기존 세션 보존, DB CHECK의 역할/가맹점·이력 대상 NULL 조합을 검사했다. 단위 테스트는 실제 커밋 이후 종료, 커밋 실패 시 종료 없음, 수행자 세션 재확인, 같은 값 재요청을 검사했다.
- latch로 로그인→관리자 변경과 관리자 변경→로그인의 두 순서를 정지·권한·초기화 각각 검사했다. DB 악센트 별칭의 로그인도 동일 잠금에서 새 상태를 사용한다.
- `cd frontend && npm run lint && npm run format:check && npm run test && npm run build`: 통과. 테스트 34개 성공. 신규 테스트는 관리자 요청 계약·검색 인코딩·자동 재시도 없음, 강제 변경 경로와 오래된 알림 무시, me 갱신 중 폼 경로 유지와 실패·401을 검사한다. 기존 lint 경고 4개와 번들 크기 경고는 남아 있다.
- `python3 .github/scripts/check_md_links.py`, `git diff --check`: 통과.

미검증: 브라우저에서 목록·생성·상세·임시 비밀번호 모달·강제 변경 완료·자기 초기화 흐름의 수동 확인(Chrome 접근이 허용되지 않음), Notion 최신 ERD·동기화, 실제 운영 데이터의 CHECK 사전 점검, 실제 30분 만료 대기. 기존 로컬 DB나 운영 DB에 migration을 적용하지 않고 Testcontainers MariaDB에서 검증했다. 다중 서버, #130 가맹점 정지와의 동시성, #68 5단계 전체 인가·CSRF는 후속 범위다.

## 구현

- POST /api/auth/login: 아이디를 trim · 소문자로 정규화하고 BCrypt로 인증한다. 성공하면 사용자 정보와 조회 권한을 반환한다.
- GET /api/auth/me: 세션 사용자 정보로 응답하며 DB를 다시 조회하지 않는다.
- POST /api/auth/logout: Security 로그아웃 필터에서 세션과 인증 정보를 제거하고 204를 반환한다. 비로그인 · 반복 요청도 204다. GET은 로그아웃하지 않는다.
- POST /api/auth/logout-all: 로그인한 본인의 전체 세션을 만료시키고 현재 세션·인증·쿠키를 정리해 204를 반환한다. 비로그인은 401 AUTH_001이다. 사용자 ID는 인증 정보에서 읽는다.
- 계정당 유효 로그인은 1개다. 새 로그인은 이전 세션을 만료시키며, 이전 세션의 다음 요청은 401 AUTH_001이다. 등록·조회 기준은 권한·이름과 무관한 userId다. 세션은 서버 메모리에 유지한다.
- 없는 아이디 · 틀린 비밀번호 · DISABLED 계정은 같은 401 AUTH_004 ProblemDetail로 응답한다. 입력 누락 · 공백 · 아이디 100자 초과(DB login_id 컬럼 길이) · 잘못된 JSON은 400("아이디와 비밀번호를 확인해 주세요.")이다. 400이면 DB를 조회하지 않는다.
- 계정 조회 중 DB 오류 등 인증 처리 자체가 실패하면 500 AUTH_005 ProblemDetail로 응답하고 error 로그를 남긴다. 처리하지 않으면 Security 필터가 401 AUTH_001(로그인 필요)로 바꿔 장애가 드러나지 않는다.
- 첫 로그인은 세션을 만들고, 기존 세션이 있으면 ID를 바꾼 뒤 인증을 명시적으로 저장한다.
- 세션에는 Entity · 비밀번호 · 비밀번호 해시를 보관하지 않는다. 별도의 Serializable 사용자 객체만 저장한다. 이 객체의 role은 UserRole enum이고, 응답 JSON의 role은 문자열("ADMIN")로 내보낸다.
- ADMIN 권한은 role로 계산한다. tenantScope는 OPERATOR=MERCHANT, SECURITY_STAFF·ADMIN=ALL이며 기능 권한과 별개다. ADMIN 권한은 FDS_VIEW · FINANCE_VIEW다.
- ADMIN 외 역할은 비밀번호 검증이 성공한 뒤 user_permissions를 읽어 세션 사용자 객체에 담고 permissions로 반환한다. 검증 전에 읽으면 있는 아이디만 DB를 한 번 더 조회해 응답 시간으로 계정 존재 여부가 드러난다. 권한을 바꾸면 다시 로그인해야 반영된다. 권한 필드가 없는 이전 세션은 권한 없음으로 본다.
- 시연 계정 시드: DEMOSHOP 가맹점(pg_fee_rate 0.020000 임시값), 사장님 boss(OPERATOR, FDS_VIEW · FINANCE_VIEW), 보안 직원 staff_test(SECURITY_STAFF, 추가 권한 없음). 비밀번호는 둘 다 1234다.
- #124에서 공개 Java 조회 계약과 프론트 tenantScope 타입을 추가했다. DB 스키마 변경은 없다. #128까지 /me·/logout-all·/password가 인증을 요구하며 다른 경로의 접근 허용과 CSRF 비활성화는 유지한다.
- #129는 `/api/admin/**`를 ADMIN으로 제한하고 `/login`·`/me`에 `passwordChangeRequired`를 추가한다. 강제 변경 계정의 인증 경로 외 `/api/**`는 403 AUTH_008이다. 그 외 익명 업무 API·서버 간 경로와 CSRF 정책은 기존 #68 5단계 범위를 유지한다.

## 검증

2026-10-22, feature/76-auth-api에서 기준 커밋 79366e3 위의 #76 미커밋 작업 트리로 실행했다. 아래 결과는 기준 커밋 단독의 결과가 아니다.

- cd backend && ./gradlew spotlessApply check mariaDbTest: 통과.
- 일반 테스트 84개, MariaDB 테스트 14개. 실패 · 오류 · 건너뜀 0개.
- AuthApiTests: Repository를 대체하고 실제 BCrypt · 인증 Provider · Service · 필터로 로그인 성공/실패, 정규화, 입력 오류, 세션 생성 · ID 변경, /me, 로그아웃, 세션 인증 직렬화와 비밀정보 제외를 확인했다.
- AuthHttpTests: Testcontainers MariaDB와 실제 임의 포트 서버를 실행하고 테스트 계정 조회만 대체했다. HTTP 쿠키로 로그인 → me → 재로그인 → 로그아웃 → me(401)를 검증했다. JSESSIONID 발급 · 변경, HttpOnly · SameSite=Lax, GET 로그아웃 방지, 폐기된 쿠키 재사용 실패도 확인했다.
- 기존 모듈 경계 · 계층 검사와 Flyway 전체 적용 · JPA validate가 포함된다.

## 미검증 · 리뷰 사항

- 시드 ADMIN 계정으로 서버 기동 후 curl 로그인 → me → logout → me(401) 순서는 담당자가 직접 실행해 통과를 확인했다 (실행 기록은 저장소에 없음).
- AUTH_003은 #128에서 현재 비밀번호 불일치에 사용한다. #124 조회 계약은 새 HTTP 에러 코드를 추가하지 않았다.
- #68·#124에서 tenantScope를 역할 기준으로 확정했다. OPERATOR의 merchantId가 null이어도 MERCHANT이며 전체 조회로 승격하지 않는다. DB 제약은 후속 계정 관리, 실제 데이터 필터는 각 담당과 합의 후 구현한다.
- 로그인 후 계정이 DISABLED로 바뀌어도 기존 세션에는 즉시 반영되지 않는다. #125에서 세션 종료 서비스를 제공하며, 계정 정지·권한 변경·비밀번호 초기화 연결은 최신 #68·#129 범위를 따른다. 전체 업무 API의 역할·가맹점별 차단은 #68 5단계다.
- 로그인 실패 문구는 “아이디 또는 비밀번호가 올바르지 않습니다.”로 #76 리뷰에서 확정했다. 계정 존재 여부를 드러내지 않도록 모든 실패에 같은 문구를 쓴다.

## #124 공개 조회 계약

상세 계약·버린 대안: [ADR-0020](../decisions/0020-identity-public-read-contracts.md).

- `CurrentStaff.findCurrent()`: 모든 역할의 로그인 계정을 `StaffSummary(userId, role, merchantId, permissions)`로 반환한다. 세션을 읽고 DB는 재조회하지 않는다. 비로그인·익명·인증 미완료는 empty, 인증된 예상 밖 principal은 내부 오류다. 권한은 /login·/me와 같은 계산을 사용하며 변경 불가 사본이다.
- `TenantReader.findById(merchantId)`: 기존 merchants에서 `TenantSummary(merchantId, name, status)`를 반환한다. ACTIVE·INACTIVE 모두 반환하고 없는 가맹점만 empty다. 잘못된 ID는 IllegalArgumentException이며 DB 오류는 전파한다. 수수료율은 공개하지 않는다.
- /login·/me의 tenantScope는 OPERATOR=MERCHANT, SECURITY_STAFF·ADMIN=ALL이다. ALL은 기능 권한을 부여하지 않는다. 프론트 타입에서 null을 제거했다.
- api에는 인터페이스·record만 두고 구현은 service에 둔다. identity는 다른 모듈에 의존하지 않는다. P3·P5 연결, SecuritySignalReader, 서버 접근 제한·CSRF 활성화는 이번 범위에 없다.

### #124 검증

2026-10-24, `feature/124-identity-public-contracts`의 기준 커밋 `c680eb9` 위 #124 미커밋 작업 트리에서 실행했다. 기준 커밋 단독의 결과가 아니다.

- `cd backend && ./gradlew spotlessApply check mariaDbTest`: 통과. 일반 테스트 245개 중 성공 244개·건너뜀 1개, MariaDB 테스트 74개 성공. 실패·오류 0개. Flyway 전체 적용·JPA validate·모듈 경계·계층 검사를 포함한다.
- 일반 검사에서 건너뛴 것은 기존 `LocalSettlementDemoTests.processCapture_whenLocalSample_persistsCaptureAndScheduledSettlement`다. #124에서 테스트를 비활성화하지 않았다.
- `cd frontend && npm run lint && npm run format:check && npm run test && npm run build`: 통과. 테스트 21개 성공. lint의 기존 경고 4개와 빌드 번들 크기 경고는 남아 있다.
- `git diff --check`: 통과.

미검증: 브라우저 수동 확인, P3·P5의 실제 호출 연동(이번 범위 밖), 전체 서버 인가·CSRF·세션 즉시 무효화(후속 이슈). ADR 상태는 PR 리뷰 전 제안으로 유지한다.

위 #76 검증은 당시 작업의 이력이다.

### 최신 dev 통합 후 재검증

2026-10-24, 최신 dev `e06cfe7`을 반영한 구현 커밋 `f5d4c41`의 코드로 전체 검사를 다시 실행했다. 이후 변경은 ADR 목록 정리와 이 검증 기록뿐이다.

- `cd backend && ./gradlew spotlessApply check mariaDbTest`: 통과. 일반 테스트 266개 중 성공 265개·기존 건너뜀 1개, MariaDB 테스트 83개 성공. 실패·오류 0개.
- `cd frontend && npm run lint && npm run format:check && npm run test && npm run build`: 통과, 테스트 21개 성공. 기존 lint 경고 4개·번들 크기 경고는 유지된다.
- ADR-0019가 dev에서 먼저 사용되어 이번 ADR은 ADR-0020으로 변경했다.

## #125 단일 세션과 전체 로그아웃

상세 계약·버린 대안: [ADR-0021](../decisions/0021-single-session-and-logout-all.md).

- 로그인은 AuthService → `UserSessionService.establishAuthentication`에서 동시 로그인 검사(최대 1개, 새 로그인 우선) → 세션 ID 변경 → 세션 등록 순서로 처리한다. 세션 목록은 `UserIdSessionRegistry`가 userId로 묶는다.
- 끊긴 세션 쿠키의 첫 요청은 경로와 무관하게(로그인 포함) 401 AUTH_001이고, 그다음 요청부터 다시 로그인할 수 있다. 일반 로그아웃은 204를 유지한다.
- 재사용 서비스: `expireAll(userId)`, `expireAllExcept(userId, retainedSessionId)`. #128·#129·#130에서 연결한다.
- 화면에는 모든 기기 로그아웃 버튼을 두지 않는다. 단일 세션이라 일반 로그아웃과 결과가 같다. API는 유지한다.

### #125 검증

2026-10-24, `feature/125-single-session-logout`의 기준 커밋 `af2f2d5` 위 #125 미커밋 작업 트리에서 실행했다. 기준 커밋 단독의 결과가 아니다.

- `cd backend && ./gradlew spotlessApply check mariaDbTest`: 통과. 일반 테스트 283개 중 성공 282개·건너뜀 1개(기존 `LocalSettlementDemoTests`), MariaDB 테스트 85개 성공. 실패·오류 0개.
- 커밋 직전 동일 코드로 `cd backend && ./gradlew check mariaDbTest --rerun-tasks`를 실행해 캐시 없이 재검증했다. 일반 테스트 282개 성공·기존 1개 건너뜀, MariaDB 85개 성공. 이후 변경은 검증 기록뿐이다.
- `cd frontend && npm run lint && npm run format:check && npm run test && npm run build`: 통과. 프론트 변경은 없다. 테스트 21개 성공, lint 기존 경고와 번들 크기 경고는 남아 있다.
- `git diff --check`: 통과.

- 로컬 서버(`bootRun`, 로컬 MariaDB 3307)에 시드 계정 boss로 쿠키 저장소 두 개를 써서 curl로 확인했다: B 로그인 후 A의 /me·공개 API 401 AUTH_001, B 유지, 끊긴 A의 첫 로그인 401 후 재시도 200, 끊긴 A의 일반 logout 204(B 유지), logout-all 204 후 /me 401, 비로그인 logout-all 401, GET logout-all 405(세션 유지). 실행 기록은 저장소에 없다.

- 사용자가 브라우저에서 두 번째 로그인 후 이전 세션 만료 안내, 새 세션 유지, 전체·일반 로그아웃을 직접 확인하고 대화에서 “체크 완료”를 보고했다. 당시에는 전체 로그아웃 버튼이 있었으며, 최종 범위에서는 사용자 결정으로 버튼을 제외했다. 버튼 제거 후 프론트는 기준 커밋과 동일하다.

미검증: 버튼 제거 후 브라우저 수동 재확인, 실제 30분 타임아웃 대기(세션 파괴 이벤트 처리는 단위 테스트로 검증), #128·#129·#130의 DB 변경 연결, 다중 서버 환경(범위 밖).

## #128 본인 비밀번호 변경

상세 계약·동시성 결정·버린 대안: [ADR-0022](../decisions/0022-self-service-password-change.md).

- `POST /api/auth/password`: 인증된 본인의 `currentPassword`, `newPassword`를 받아 현재 비밀번호 확인 → 공통 정책 검사 → BCrypt 해시 저장·커밋 → 현재 세션을 제외한 세션 종료 후 204를 반환한다. 현재 세션 ID·인증은 유지한다.
- 현재 비밀번호 불일치는 400 `AUTH_003`, 정책 위반은 400 `AUTH_006`, 비로그인·만료는 401 `AUTH_001`이다. 입력 누락·잘못된 JSON은 안전한 400, DB·트랜잭션 오류는 내부 원인 없는 500 `AUTH_007`이며 원인은 error 로그에만 남긴다. 변경 성공과 `AUTH_003`·`AUTH_006` 실패는 userId와 코드만 info 로그로 남긴다.
- `PasswordPolicy.validate(loginId, password)`는 코드 포인트 8~64자·UTF-8 72바이트 이하·ASCII 영문과 숫자 포함·아이디와 다른 값을 요구한다. 한글·특수문자·공백을 허용하며 trim·대소문자 변환을 하지 않는다. 잘못된 Unicode는 거절한다. 기존 비밀번호 확인에는 새 정책을 적용하지 않는다.
- 로그인과 변경은 실제 저장된 loginId에 따른 고정 분할 잠금을 공유한다. 로그인은 잠금 키 조회 후 잠금 안에서 DB를 다시 읽고 인증·권한 조회·세션 등록을 끝낸다. DB collation의 악센트 별칭도 동일 계정 잠금을 사용한다. 변경은 잠금 대기 후 세션을 재확인하고 실제 DB 커밋과 선택 종료까지 잠금을 유지한다.
- `/account/password`는 세 역할 공통 헤더에서 진입한다. 업무 권한 없는 사장님도 접근한다. 입력 확인·중복 제출 방지·성공 시 폼 초기화, 변경 API의 401 만료 전파를 제공한다.
- schema·migration·모듈 공개 계약 변경 없음. #129는 공통 정책과 화면을 재사용하되 강제 변경 상태·접근 제한·완료 후 `/me` 갱신을 추가해야 한다. #129·#130의 DB 변경과 로그인 간 동시성은 별도 후속 범위다.

### #128 검증

2026-10-24, `feature/128-password-change`, 기준 `dev f279bb7` 위 #128 미커밋 작업 트리에서 실행했다. 기준 커밋 단독 결과가 아니다. 아래 최종 검사 이후 변경은 문서 기록뿐이다.

- `cd backend && ./gradlew spotlessApply check mariaDbTest`: 통과. 일반 테스트 302개 중 301개 성공·기존 1개 건너뜀, MariaDB 테스트 93개 성공. 실패·오류 0개. Flyway 전체 적용·JPA validate·모듈 경계·계층 검사를 포함한다.
- 실제 MariaDB·HTTP 쿠키로 세 역할의 변경·기존 `1234` 확인·새 비밀번호만 로그인 성공·현재 세션 유지·새 로그인 우선·선택 종료·타 사용자 보호·안전한 400·401을 검사했다. 선택 종료는 단일 세션 정책과 구분하여 이전 실제 세션을 테스트에서 재등록한 조건으로 검사했다.
- latch로 비밀번호 변경 중 옛 비밀번호 로그인 대기·거절(악센트 별칭 포함), 옛 비밀번호 검증을 끝낸 로그인과 기존 세션 변경의 경합·만료 세션 복구 방지를 검사했다. 단위 테스트는 문자·바이트 경계·잘못된 Unicode·DTO 비밀정보 제외·커밋 후 종료·커밋 실패 시 종료 없음·롤백을 검사한다.
- 잠금 키 추가 조회 도입 후 기존 로그인 테스트가 1회 조회 기대 때문에 실패했다. 최종 테스트는 로그인 2회 조회와 `/me` 무조회를 각각 검증하고 통과했다. 기대값 조정은 ADR-0022의 인증 흐름 변경에 따른 것이다.
- `cd frontend && npm run lint && npm run format:check && npm run test && npm run build`: 통과. 테스트 25개 성공. 기존 lint 경고 4개와 빌드 번들 크기 경고는 유지된다. 변경 요청 원문·쿠키 전송, 400과 401 구분, 이전 세션의 늦은 401 무시, 세 역할·무권한 사장님 공통 경로 접근을 검사했다.
- cmux 브라우저와 로컬 서버에서 사장님 `boss`·보안 직원 `staff_test` 로그인 → 헤더 링크 → 변경 화면 진입을 확인했다. 사장님 화면의 새로고침·로그인 유지, 확인값 불일치, 현재 비밀번호 불일치 안내를 확인했다. 시드 비밀번호는 변경하지 않았다.
- `git diff --check`: 통과.

미검증: 관리자·무권한 사장님의 브라우저 수동 확인, 브라우저에서 변경 성공 후 폼 초기화·세션 만료 이동·중복 제출 동작(HTTP·상태 전이 자동 테스트와 구분), 실제 30분 대기, 다중 서버(범위 밖). 전체 업무 인가·CSRF 활성화와 #129·#130 연결, Notion 정책 동기화는 이번 범위에서 수행하지 않았다.
