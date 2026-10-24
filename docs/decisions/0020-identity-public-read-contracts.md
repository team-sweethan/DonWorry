# 0020. Identity 공개 조회 계약과 가맹점 범위

- **상태:** 제안 — #124 구현 반영, PR 리뷰 대상
- **날짜:** 2026-10-24
- **관련:** Issue #124, 상위 Issue #68, ADR-0012, ADR-0014
- **영향 받는 담당:** P1 주환, 후속 호출 연동 P3 수민 · P5 시은

## 배경

다른 모듈이 현재 로그인한 계정과 가맹점 정보를 identity 내부 접근 없이 조회할 창구가 없다. #68은 OPERATOR의 tenantScope를 MERCHANT, SECURITY_STAFF·ADMIN은 ALL로 결정했다. 기존 응답은 ADMIN의 merchantId가 null인 경우에만 ALL이고 나머지는 null이었다.

## 결정

### 공개 계약

identity/api에는 인터페이스와 응답 record만 둔다. 구현은 service에 두고 identity의 allowedDependencies는 빈 목록을 유지한다.

| 인터페이스 | 메서드 | 반환 record |
| --- | --- | --- |
| CurrentStaff | Optional<StaffSummary> findCurrent() | userId(Long), role(String), merchantId(Long), permissions(List<String>) |
| TenantReader | Optional<TenantSummary> findById(Long merchantId) | merchantId(Long), name(String), status(String) |

- role의 값은 ADMIN·SECURITY_STAFF·OPERATOR다. 이름 CurrentStaff를 유지하되 사장님을 포함한 모든 로그인 계정을 조회한다.
- CurrentStaff는 현재 스레드의 SecurityContext에 있는 인증된 SessionUser를 읽으며 DB를 조회하지 않는다. 비로그인·익명·인증 미완료면 empty다. 인증된 principal이 SessionUser가 아니면 IllegalStateException으로 내부 오류를 드러낸다.
- 세션 문맥이 없는 배치·비동기 작업은 empty다. 사용자 ID가 필요한 작업은 요청 처리 중 얻은 ID를 명시적으로 전달해야 한다.
- permissions는 변경 불가 사본이다. ADMIN은 FDS_VIEW·FINANCE_VIEW를 계산하고, 나머지는 세션 권한을 쓴다. StaffSummary는 null 권한을 빈 목록으로 바꾸므로 이전 세션이나 다른 모듈의 테스트 데이터에서 권한을 빼도 오류가 나지 않는다.
- /login·/me와 CurrentStaff는 내부 SessionUserMapper로 같은 권한 계산을 사용한다. 계정·권한 변경은 세션에 즉시 반영되지 않는다. 세션 무효화는 #125 및 후속 계정 관리 범위다.
- TenantReader는 기존 merchants를 호출 시 조회한다. name은 display_name이고 status는 ACTIVE·INACTIVE다. INACTIVE도 반환하며 없는 ID만 empty다. null·0 이하 ID는 IllegalArgumentException, DB 장애는 원래 예외를 전파한다.
- Optional 조회 결과는 HTTP 상태를 정하지 않는다. 호출자의 인증 요구·업무 정책·오류 응답은 각 담당과 합의하여 후속 적용한다. 이번 작업은 서버 접근 정책을 활성화하지 않는다.

### tenantScope

/login·/me에서 OPERATOR는 MERCHANT, SECURITY_STAFF·ADMIN은 ALL을 반환한다. 역할 기준으로 계산하며 세션에는 중복 저장하지 않는다. 프론트 타입은 'ALL' | 'MERCHANT'이고 null을 제거한다.

ALL은 전체 가맹점 조회 범위이며 기능 권한을 부여하지 않는다. 보안 직원 권한을 자동 추가하지 않는다. OPERATOR의 merchantId가 null이어도 MERCHANT를 유지하며 ALL로 승격하지 않는다. 이 비정상 데이터의 DB 제약·수정은 후속 계정 관리에서 처리하고, 후속 가맹점 필터 구현은 조회를 거부해야 한다. 이번에는 필터를 구현하지 않는다.

## 고려한 대안

| 대안 | 버린 이유 |
| --- | --- |
| Entity 또는 내부 UserRole enum 반환 | 다른 모듈이 identity 내부 타입에 의존한다. 공개 record와 문자열 계약으로 제한한다. |
| 호출자가 SecurityContext를 직접 읽기 | 세션 구현과 Spring Security에 각 모듈이 결합한다. |
| CurrentStaff 호출마다 DB 조회 | 기존 세션 기반 /me와 일관성이 깨지고 조회 비용이 늘어난다. 즉시 무효화는 후속 세션 정책으로 처리한다. |
| 없는 가맹점에 identity가 일괄 HTTP 404 예외 발생 | 내부 Java 조회를 HTTP 및 호출자의 업무 정책과 결합한다. |
| INACTIVE를 empty로 반환 | 없는 가맹점과 정지 가맹점을 구별할 수 없다. |
| 수수료율도 반환 | P6 합의 전이며 settlement → identity 의존은 ADR-0012에서 보류다. |

## 영향

- API: identity 공개 Java 계약 두 개를 추가하고 /login·/me의 tenantScope를 역할별로 채운다. P3·P5 호출 코드는 변경하지 않는다.
- DB: 기존 merchants의 조회용 Entity·Repository만 추가한다. 공개 조회에 필요한 ID·이름·상태만 매핑하며 쓰기 메서드는 제공하지 않는다. 스키마·마이그레이션 변경 없음.
- 화면: AuthUser 타입과 테스트 데이터를 변경한다. 데모 로그인·가맹점 필터·메뉴 정책은 후속 범위다.
- 테스트: 세션 조회·권한 사본·비정상 principal, 역할별 응답, 실제 MariaDB 가맹점 조회와 장애 전파를 검증한다.
- 문서: identity README와 ADR 목록 갱신. 새 HTTP API·이벤트·모듈 의존은 없다.

## 검증

2026-10-24, `feature/124-identity-public-contracts`의 기준 커밋 `c680eb9` 위 #124 미커밋 작업 트리에서 실행했다. 기준 커밋 단독의 결과가 아니다.

- `cd backend && ./gradlew spotlessApply check mariaDbTest`: 통과. 일반 테스트 245개 중 성공 244개·건너뜀 1개, MariaDB 테스트 74개 성공. 실패·오류 0개. Flyway 전체 적용·JPA validate·모듈 경계·계층 검사를 포함한다.
- 일반 검사에서 건너뛴 것은 기존 `LocalSettlementDemoTests.processCapture_whenLocalSample_persistsCaptureAndScheduledSettlement`다. #124에서 테스트를 비활성화하지 않았다.
- `cd frontend && npm run lint && npm run format:check && npm run test && npm run build`: 통과. 테스트 21개 성공. lint의 기존 경고 4개와 빌드 번들 크기 경고는 남아 있다.
- `git diff --check`: 통과.

미검증: 브라우저 수동 확인, P3·P5의 실제 호출 연동(이번 범위 밖), 전체 서버 인가·CSRF·세션 즉시 무효화(후속 이슈). ADR 상태는 PR 리뷰 전 제안으로 유지한다.

### 최신 dev 통합 후 재검증

2026-10-24, 최신 dev `e06cfe7`을 반영한 구현 커밋 `f5d4c41`의 코드로 전체 검사를 다시 실행했다. 이후 변경은 ADR 목록 정리와 이 검증 기록뿐이다.

- `cd backend && ./gradlew spotlessApply check mariaDbTest`: 통과. 일반 테스트 266개 중 성공 265개·기존 건너뜀 1개, MariaDB 테스트 83개 성공. 실패·오류 0개.
- `cd frontend && npm run lint && npm run format:check && npm run test && npm run build`: 통과, 테스트 21개 성공. 기존 lint 경고 4개·번들 크기 경고는 유지된다.
- ADR-0019가 dev에서 먼저 사용되어 이번 ADR은 ADR-0020으로 변경했다.
