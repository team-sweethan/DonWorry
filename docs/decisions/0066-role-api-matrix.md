# 0066. 직원 세션 API의 역할 × 권한표와 가맹점 범위

- **상태:** 제안 — #420 P1 결정, PR 리뷰 대상
- **날짜:** 2026-11-02
- **관련:** Issue #420, #68 (2·4·5단계), [ADR-0014](0014-session-authentication.md), [ADR-0020](0020-identity-public-read-contracts.md), [ADR-0059](0059-security-default-deny.md), [ADR-0065](0065-case-session-authorization.md), [ADR-0039](0039-owner-finance-session-authorization.md) ("정산 하위 경로 · 모든 메서드 통과" 부분을 대체)
- **영향 받는 담당:** P1 주환 (SecurityConfig · 결정), P2 정후 (FDS), P3 수민 (Case), P5 시은 (결제 운영), P6 민정 (정산)

## 배경

#68은 "역할 × API 권한표"와 "가맹점 거르기 방법"을 P2~P6 합의 대상으로 남겼다. 그 사이 각 모듈이 API를 추가하면서 ADR-0059(기본 거절)에 따라 모든 직원 세션 API에 `SecurityConfig` 규칙이 등록됐고, 사장님 API는 요청 값이 아니라 세션 `merchantId`로 가맹점을 정하게 됐다. 표는 흩어진 규칙을 한 곳에 모은 적이 없어 어떤 역할이 무엇을 쓸 수 있는지 문서로 확인할 수 없었다.

#420에서 P1이 현재 코드(`dev` `32ef201`)를 기준으로 권한표를 확정하고 팀에 통보하기로 했다 (2026-11-02 사용자 결정).

## 결정

아래 표를 직원 세션 체인(`SecurityConfig`)의 권한 기준으로 삼는다. 새 API는 표의 한 줄에 맞춰 규칙을 추가하고, 맞는 줄이 없으면 이 ADR을 개정한다.

| 묶음 | 경로 (메서드는 `RoleApiMatrixTests` 참고) | 허용 |
| --- | --- | --- |
| 로그인 전 공개 | `/api/auth/login` · `logout` · `csrf`, `/api/merchant-applications` 신청 · 이메일 확인 · 첫 비밀번호 | 누구나 |
| 본인 | `/api/auth/me` · `logout-all` · `password` · `login-histories` | 로그인한 모든 역할 |
| 관리자 | `/api/admin/**` (계정 · 가맹점 · 이용 신청 심사 · 연동 키 폐기 · 사용자 로그인 이력) | `ADMIN` |
| 조회·운영 | `/api/merchants`, `/api/fds/evaluations/**`, `/api/fds/rule-policies` · `rules/**` · `rule-change-requests/**` · `blocklist/**`, `/api/payment-operations` 조회, `/api/payment-transactions` | `SECURITY_STAFF` · `ADMIN` (FDS 수정의 역할별 세부 검사는 P2 서비스가 한다) |
| 보안 직원 전용 | `/api/payment-operations/{id}/fds-reevaluations` · `fds-recovery-deliveries`, `/api/security-cases/**` 11개 경로 | `SECURITY_STAFF` (ADMIN 제외) |
| 사장님 연동 키 | `/api/owner/integration/**` | `OPERATOR` + 세션 `merchantId` |
| 사장님 보안 조치 결과 | `/api/merchant/security-cases/**` | `OPERATOR` + 세션 `merchantId` + `FDS_VIEW` |
| 사장님 정산 | `/api/v1/settlements/**` | `OPERATOR` + 세션 `merchantId` + `FINANCE_VIEW` |
| 그 밖 | 등록하지 않은 경로 · 메서드 | 거절 (비로그인 401 `AUTH_001`, 로그인 403 `AUTH_002`) |

CakeShop HMAC 경로(`MerchantSecurityConfiguration`)와 AI 서버 경로(`/internal/ai/**`, `AiServerSecurityConfiguration`)는 이 표 밖이다. 각각 서명한 가맹점 · 서비스 키로 인증한다.

가맹점 범위:

1. **사장님 API는 세션(HMAC은 서명)의 가맹점만 쓴다.** 요청의 가맹점 ID를 받지 않는다.
2. **보안 직원 · 관리자는 모든 가맹점을 본다** (#68 결정 1 `tenantScope=ALL`). 직원 API의 `merchantId` 요청 값은 표시 필터이며 권한 경계가 아니다.
3. **다른 가맹점의 데이터를 ID로 요청하면 404다.** 존재 여부를 드러내지 않는다. 사장님 Case · 정산 지출 · HMAC 결제 조회가 이미 404이며, 새 사장님 API도 같은 방식을 따른다.
4. **관리자는 Case를 보거나 판정하지 않는다.** ADR-0065와 `SecurityStaffGuard`를 유지한다. #68 초기 제안("관리자: 전부 조회")은 Case 경로에 적용하지 않는다.

수수료율의 `TenantReader` 공개는 #359(PR #360)에서 이미 구현됐으므로 이 ADR의 결정 대상이 아니다.

## 고려한 대안

| 대안 | 버린 이유 |
| --- | --- |
| P2~P6 전원 합의 후 확정 | 현재 코드가 이미 모든 경로에 규칙을 두고 있어, 합의를 기다리는 동안 문서 없는 상태만 길어진다. P1이 현재 동작을 기준으로 확정하고 통보한다 |
| 관리자에게 Case 조회 허용 | 관리자는 운영 계정이고 조사 · 판정 주체가 아니다. P3 Guard와 ADR-0065를 바꿔야 하고 시연 흐름에 필요 없다 |
| 다른 가맹점 요청에 403 | 그 ID의 데이터가 있다는 사실이 드러난다. 기존 사장님 API가 이미 404를 쓴다 |
| 가맹점 거르기를 `SecurityConfig`에서 처리 | 필터는 요청 경로만 알고 데이터의 가맹점은 모른다. 데이터 소유 확인은 각 모듈 서비스가 세션 `merchantId`로 한다 |
| 경로 접두사(`/**`)로 역할 묶기 (기존 6개 묶음 유지) | 같은 접두사에 새 API가 추가되면 규칙을 등록하지 않아도 그 역할에 열려 기본 거절을 우회한다. 정확한 메서드 · 경로로 좁힌다 (AGENTS.md 4절) |

## 영향

- API: 기존 API의 허용 역할은 그대로다. 접두사(`/**`)로 열려 있던 6개 묶음(관리자 · 정산 · FDS 관리 · FDS 평가 · 사장님 연동 키 · 로그인 이력: `/api/admin/**` · `/api/v1/settlements/**` · `/api/fds/rules/**` · `rule-change-requests/**` · `blocklist/**` · `/api/fds/evaluations/**` · `/api/owner/integration/**` · `/api/auth/login-histories/**`)을 현재 컨트롤러의 정확한 메서드 · 경로로 좁혔다. 그 아래 등록하지 않은 경로 · 메서드는 이제 기본 거절(비로그인 401 · 로그인 403)이다. 예전에는 허용 역할에게 404 · 405가 났다.
- DB: 없음.
- 화면: 없음. 화면 가드는 편의이며 이 표가 서버 기준이다.
- 테스트: `RoleApiMatrixTests`가 위 표의 직원 세션 경로 73개 × 9가지 사용자(비로그인 · 권한 있는/없는 ADMIN · 권한 있는/없는 보안 직원 · 권한 없는 사장님 · `FDS_VIEW` 사장님 · `FINANCE_VIEW` 사장님 · 가맹점 없는 사장님)를 검사한다. 권한 목록이 있는 직원으로 사장님 경로가 역할로 막히는지, 권한 목록이 빈 직원(시드 `staff_test`와 같음)으로 직원 경로가 역할만으로 열리는지 본다. 예전 접두사 아래 등록하지 않은 경로가 허용 역할에게도 열리지 않는지 따로 검사한다.
- 기존 테스트: 넓은 규칙을 전제로 한 4개를 새 정책에 맞췄다.
  - `OwnerFinanceSecurityTests`: 사장님이 `/report`의 GET만 통과하고 나머지 메서드는 403인지 본다.
  - `AuthApiTests.logoutAll_whenGet_preservesSession` · `PasswordChangeHttpTests`: GET 전체 로그아웃 · 비밀번호 변경이 405 대신 403 `AUTH_002`로 막히고 세션 · 비밀번호가 유지되는지 본다.
  - `OwnerFinanceHttpMariaDbTests`: 정산 접두사 아래 테스트 전용 경로(`auth-probe`) 대신 실제 현금흐름 설정 API를 쓴다. 등록하지 않은 경로는 권한과 관계없이 403이라 거절 검사가 의미를 잃기 때문이다. 세션 가맹점으로 저장되고 요청의 다른 가맹점 번호는 쓰이지 않는지 DB로 확인한다. 통과시키려고 약하게 바꾼 것이 아니라 ADR-0039의 "모든 메서드 통과" 결정을 이 ADR로 바꿨기 때문이다. ADR-0039 당시에는 미등록 경로가 `permitAll`이라 접두사로 넓게 잡는 편이 안전했지만, ADR-0059 이후 미등록 경로는 기본 거절이다.
- 다른 담당: 코드 수정 요청 없음. 새 API를 추가할 때 이 표의 줄을 정하고 `SecurityConfig` · `RoleApiMatrixTests`를 같은 PR에서 갱신한다.
- 문서: ADR 색인. #68의 "합의 필요" 중 권한표 · 가맹점 거르기 · 다른 가맹점 응답 · 수수료율 항목을 이 ADR로 정리한다.

## 검증

- 실행 결과는 PR의 테스트 결과에 검토 대상 커밋과 함께 기록한다.
- 미검증: 운영 배포 환경(#396), 브라우저 역할별 전체 화면 확인(#421).
