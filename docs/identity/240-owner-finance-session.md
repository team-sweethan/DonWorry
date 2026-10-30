# #240 사장님 정산·자금관리 세션 연결 계약

관련: [Issue #240](https://github.com/team-sweethan/DonWorry/issues/240), [ADR-0039](../decisions/0039-owner-finance-session-authorization.md), [ADR-0038](../decisions/0038-settlement-identity-dependency.md).

## 적용 범위

P1은 `/api/v1/settlements`와 그 하위 경로 전체에 세션 인가를 적용한다.
정상 조건은 `OPERATOR` + `FINANCE_VIEW` + `merchantId` 존재다. 읽기·쓰기 조건은 같고 ADMIN·SECURITY_STAFF는 제외한다.
이 정책은 P1 결정·사용자 구현 승인 기준이며 P6 동의까지 확인된 것은 아니다.

공통 인가는 P6 Controller 부재나 기능 비활성에도 유지된다. 허용된 요청이 아직 없는 업무 경로에 도달하면 404일 수 있으며, 인증 성공이 업무 구현 완료를 뜻하지 않는다.

## P6에서 사용할 공개 Java 계약

| 계약 | 반환·오류 | 사용 |
| --- | --- | --- |
| `CurrentStaff.findCurrent()` | `Optional<StaffSummary>`; 비로그인·익명은 empty, 예상 밖 인증 principal은 내부 오류 | 사장님을 포함한 현재 로그인 계정 확인 |
| `StaffSummary` | `userId`, `role`(String), `merchantId`, `permissions`(변경 불가 List) | 역할·권한·가맹점 확인 |
| `TenantReader.findById(merchantId)` | 없는 가맹점만 empty; INACTIVE도 반환; null·0 이하 ID는 IllegalArgumentException; DB 오류는 전파 | 이름·상태·PG 수수료율이 업무상 필요한 경우 조회 |

`CurrentStaff`는 세션 정보이며 DB를 재조회하지 않는다. 계정·권한 변경과 가맹점 정지는 기존 P1 관리 API가 세션을 무효화한다.
최초 #240 연결에서는 수수료율을 추가하지 않았다. #356에서 `TenantSummary.pgFeeRate()`를 추가한다. 값은 저장된 `BigDecimal` 비율이며 `0.02 = 2%`다. 없는 가맹점·DB 오류를 임의 수수료율로 대체하지 않는다. P6 정산 계산 연결은 P6 후속이며 카드 수수료 모의 정책 2%는 유지한다. 상세 계약은 [ADR-0048](../decisions/0048-tenant-pg-fee-read-contract.md)을 따른다.

P6는 내부 `service/`의 기존 접근 어댑터에 `CurrentStaff`를 연결한다. 권장 처리 순서는 다음과 같다.

1. `findCurrent()`가 empty면 업무 처리를 차단한다.
2. `role`이 `OPERATOR`인지, `permissions`에 `FINANCE_VIEW`가 있는지, `merchantId`가 있는지 확인한다. 실패하면 전체 가맹점 조회로 넓히지 않는다.
3. 확인한 `merchantId`를 업무 서비스·Repository 조회/변경 조건으로 전달한다. 배치·비동기는 세션을 다시 읽지 않고 필요한 ID를 요청 처리 중 명시적으로 전달한다.

어댑터 이름·시그니처와 예외 타입은 P6 로컬 코드에 맞춰 연결한다. P1이 P6 포트를 새로 정의하거나 업무 구현을 복제하지 않는다.
P6에서 identity 내부 `Entity`·`Repository`·`SessionUser`·`AuthErrorCode`·`SecurityContext`를 참조하지 않는다.

## 업무 데이터의 가맹점 범위

- 요청 본문·쿼리의 `merchantId`는 조회·생성 대상 가맹점을 결정하지 않는다. 신규 저장 값도 인증 가맹점에서 정한다.
- 수정·삭제·반복 지출 중단 등은 자원 ID와 인증 가맹점을 함께 조건으로 사용한다. 삭제 멱등 처리도 가맹점 조건을 생략하지 않는다.
- 타 가맹점 자원 요청은 거절하고 데이터가 바뀌지 않아야 한다. 해당 오류 상태·코드는 P6 기존 업무 계약을 확인해 확정한다. 404는 제안이며 아직 확정하지 않았다.
- 인증 어댑터가 연결되지 않았으면 차단을 유지한다. 요청 값·시연 가맹점·서버 간 인증으로 대체하지 않는다.

## HTTP 오류와 프론트 연결

| 조건 | P1 필터 응답 |
| --- | --- |
| 비로그인·만료/무효화된 세션 | 401 `AUTH_001` |
| 역할·기능 권한·가맹점 조건 불충족 | 403 `AUTH_002` |
| 강제 비밀번호 변경 대상 | 기존 403 `AUTH_008` |
| CSRF 활성 환경의 변경 요청 토큰 오류 | 기존 403 `CSRF_001` (인가보다 먼저 검사될 수 있음) |

필터 응답은 `application/problem+json`이다. P6 업무 예외는 P6 모듈 처리기로 응답하며 identity 내부 오류 클래스·처리기를 import하지 않는다.
공통 클래스 위치는 보류 상태이므로 이번 연결에서 새 공유 예외 체계를 만들지 않는다.

#235·#236은 `frontend/src/api/client.ts`의 `api()`를 사용한다. 이 함수가 `/api`를 붙이므로 예를 들어 `/v1/settlements/report`를 전달한다.
기존 쿠키·CSRF·세션 변경 처리를 유지하고 401/403이나 통신 실패를 0원·예시 데이터로 바꾸지 않는다.
픽업 PUT은 [HMAC 계약](219-merchant-hmac.md)의 별도 경로·`CurrentMerchant`를 사용한다.

## 통합 시 확인과 완료 구분

- P1: 공통 인가·공개 API 사용 계약·테스트 수신부 검증. P6 세부 경로·어댑터 확인을 기다리지 않고 진행한다.
- P6: 로컬 어댑터 시그니처, `/report` 외 세부 경로·메서드와 기존 오류 계약 확인. 모든 사장님 API를 보호 범위에 연결한다.
- 공동 후속: 실제 #228·#229 및 #230·#231 API의 읽기·쓰기, 타 가맹점 조회·수정·삭제·중단 차단과 데이터 불변, 정지·세션 만료를 검증한다. #235·#236 화면의 실제 인증 연결도 별도로 확인한다.

테스트 수신부는 실제 P6 서비스·Repository를 호출하지 않으므로 업무 데이터 격리의 검증 근거로 쓰지 않는다.
현재 실행 기록은 PR에 남기며 실제 P6 통합·브라우저·30분 비활동 대기·운영 HTTPS는 별도 미검증이다. P1 작업만으로 #240을 종료하지 않는다.
