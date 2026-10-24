# Identity — 로그인 MVP

관련: Issue #68 · #69 · #76, [ADR-0014](../decisions/0014-session-authentication.md).

## 구현

- POST /api/auth/login: 아이디를 trim · 소문자로 정규화하고 BCrypt로 인증한다. 성공하면 사용자 정보와 조회 권한을 반환한다.
- GET /api/auth/me: 세션 사용자 정보로 응답하며 DB를 다시 조회하지 않는다.
- POST /api/auth/logout: Security 로그아웃 필터에서 세션과 인증 정보를 제거하고 204를 반환한다. 비로그인 · 반복 요청도 204다. GET은 로그아웃하지 않는다.
- 없는 아이디 · 틀린 비밀번호 · DISABLED 계정은 같은 401 AUTH_004 ProblemDetail로 응답한다. 입력 누락 · 공백 · 아이디 100자 초과(DB login_id 컬럼 길이) · 잘못된 JSON은 400("아이디와 비밀번호를 확인해 주세요.")이다. 400이면 DB를 조회하지 않는다.
- 계정 조회 중 DB 오류 등 인증 처리 자체가 실패하면 500 AUTH_005 ProblemDetail로 응답하고 error 로그를 남긴다. 처리하지 않으면 Security 필터가 401 AUTH_001(로그인 필요)로 바꿔 장애가 드러나지 않는다.
- 첫 로그인은 세션을 만들고, 기존 세션이 있으면 ID를 바꾼 뒤 인증을 명시적으로 저장한다.
- 세션에는 Entity · 비밀번호 · 비밀번호 해시를 보관하지 않는다. 별도의 Serializable 사용자 객체만 저장한다. 이 객체의 role은 UserRole enum이고, 응답 JSON의 role은 문자열("ADMIN")로 내보낸다.
- ADMIN 권한은 role로 계산한다. tenantScope는 OPERATOR=MERCHANT, SECURITY_STAFF·ADMIN=ALL이며 기능 권한과 별개다. ADMIN 권한은 FDS_VIEW · FINANCE_VIEW다.
- ADMIN 외 역할은 비밀번호 검증이 성공한 뒤 user_permissions를 읽어 세션 사용자 객체에 담고 permissions로 반환한다. 검증 전에 읽으면 있는 아이디만 DB를 한 번 더 조회해 응답 시간으로 계정 존재 여부가 드러난다. 권한을 바꾸면 다시 로그인해야 반영된다. 권한 필드가 없는 이전 세션은 권한 없음으로 본다.
- 시연 계정 시드: DEMOSHOP 가맹점(pg_fee_rate 0.020000 임시값), 사장님 boss(OPERATOR, FDS_VIEW · FINANCE_VIEW), 보안 직원 staff_test(SECURITY_STAFF, 추가 권한 없음). 비밀번호는 둘 다 1234다.
- #124에서 공개 Java 조회 계약과 프론트 tenantScope 타입을 추가했다. DB 스키마 변경은 없다. /me 외 접근 허용과 CSRF 비활성화는 ADR-0014대로 유지한다.

## 검증

2026-10-22, feature/76-auth-api에서 기준 커밋 79366e3 위의 #76 미커밋 작업 트리로 실행했다. 아래 결과는 기준 커밋 단독의 결과가 아니다.

- cd backend && ./gradlew spotlessApply check mariaDbTest: 통과.
- 일반 테스트 84개, MariaDB 테스트 14개. 실패 · 오류 · 건너뜀 0개.
- AuthApiTests: Repository를 대체하고 실제 BCrypt · 인증 Provider · Service · 필터로 로그인 성공/실패, 정규화, 입력 오류, 세션 생성 · ID 변경, /me, 로그아웃, 세션 인증 직렬화와 비밀정보 제외를 확인했다.
- AuthHttpTests: Testcontainers MariaDB와 실제 임의 포트 서버를 실행하고 테스트 계정 조회만 대체했다. HTTP 쿠키로 로그인 → me → 재로그인 → 로그아웃 → me(401)를 검증했다. JSESSIONID 발급 · 변경, HttpOnly · SameSite=Lax, GET 로그아웃 방지, 폐기된 쿠키 재사용 실패도 확인했다.
- 기존 모듈 경계 · 계층 검사와 Flyway 전체 적용 · JPA validate가 포함된다.

## 미검증 · 리뷰 사항

- 시드 ADMIN 계정으로 서버 기동 후 curl 로그인 → me → logout → me(401) 순서는 담당자가 직접 실행해 통과를 확인했다 (실행 기록은 저장소에 없음).
- AUTH_003은 #68 결정 20에 따라 다음 새 에러 코드에 사용한다. 이번 조회 계약은 새 HTTP 에러 코드를 추가하지 않는다.
- #68·#124에서 tenantScope를 역할 기준으로 확정했다. OPERATOR의 merchantId가 null이어도 MERCHANT이며 전체 조회로 승격하지 않는다. DB 제약은 후속 계정 관리, 실제 데이터 필터는 각 담당과 합의 후 구현한다.
- 로그인 후 계정이 DISABLED로 바뀌어도 기존 세션에는 즉시 반영되지 않는다. #76 리뷰에서 유지로 결정했다. /me 외 API가 열려 있어 /me만 막는 효과가 없으므로 #68 5단계 서버 API 차단 때 다시 본다.
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
