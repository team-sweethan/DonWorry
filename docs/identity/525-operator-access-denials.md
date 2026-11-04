# #525 사장님 403 원본 저장·이벤트 발행

관련: [Issue #525](https://github.com/team-sweethan/DonWorry/issues/525),
[정본 #515](https://github.com/team-sweethan/DonWorry/issues/515),
[공개 계약 #521](521-operator-security-contracts.md), [ADR-0080](../decisions/0080-operator-access-denials.md).

## 승인·시작 기준

2026-11-04 P1 사용자가 #525 초안 구조대로 구현하고 Notion은 후속 반영하도록 승인했다.
이는 AGENTS.md 4절의 Notion 선행 반영 순서에 대한 이번 작업의 사용자 승인 예외다.
저장소 ERD·ADR에 소유·필드·보관 정책을 먼저 기록했다. Notion 기록·동기화 완료를 뜻하지 않는다.

원래 PR #524의 `f6d603e`에서 분기했으며, #524 병합을 확인한 뒤 `dev 27063d6` 위로 재배치했다.
두 커밋의 파일 내용이 동일한 것을 확인했고 525 변경을 보존했다.
작업 브랜치는 `feature/525-operator-access-denials`다.

## 구현

### 기록 범위와 장애

JsonAccessDeniedHandler까지 도달한 AccessDeniedException 중 인증된 SessionUser의 역할이 OPERATOR인 경우만 기록한다.
단순히 모든 403 또는 SecurityConfig 거절만을 뜻하지 않는다. 앞으로 서비스의 처리되지 않은 AccessDeniedException이
이 핸들러까지 전파되면 포함된다. 현재 PaymentFdsStaffAccessService의 예외는 MVC 처리기가 응답하므로 포함되지 않는다.

CSRF 403·직원 403·401은 제외한다. 비밀번호 강제 변경 필터는 직접 응답하므로 기록 서비스로 오지 않는다.
동시 로그아웃으로 인증·principal이 사라졌으면 기록을 건너뛴다.

핸들러는 기록 시도 후 기존 응답 작성기를 부른다. INSERT·이벤트 발행·커밋 실패를 받아도 원래 403 AUTH_002를 유지한다.
실패 로그는 userId·예외 클래스만 기록하며 DB 오류 원문·stack trace·요청 본문·쿼리스트링은 기록하지 않는다.

### 저장과 이벤트

OperatorAccessDenialService가 REQUIRES_NEW 트랜잭션을 열고 다음을 수행한다.

1. userId로 DB 계정을 한 번 읽고 실제 OPERATOR인지 확인한다. 없거나 직원이면 저장하지 않는다.
2. DB 계정의 merchantId로 원본을 저장한다. loginId도 같은 계정 조회 결과에서 얻는다. 세션 값을 복사하지 않는다.
3. 원본 INSERT·flush 후 PK denialId를 OperatorAccessDenied.eventId로 발행한다.
4. 같은 트랜잭션을 커밋한 뒤 커밋 후 리스너가 받는다. 실패 시 원본·발행 기록이 함께 롤백된다.

필터에는 기존 트랜잭션이 없으므로 서비스가 직접 경계를 연다. 외부 트랜잭션이 있어도 독립 커밋한다.
P2 리스너가 아직 없으면 원본만 남고, 그 사이의 원본을 나중에 자동으로 재발행하지 않는다.

저장 구조는 [ERD #525](../erd/README.md#525-p1-사장님-403-원본-기록-2026-11-04)를 따른다.
userId·merchantId는 같은 P1 테이블에만 물리 FK를 두며 삭제 연쇄는 없다.
Entity는 Immutable이고 Repository에는 saveAndFlush만 공개한다. 기록 수정·조회 HTTP API는 만들지 않는다.

### 요청 정보

- path: request.getServletPath(). 쿼리스트링·컨텍스트 경로 제외. 최대 512 Unicode 문자로 자르며 surrogate pair를 자르지 않는다.
- occurredAt: identityClock UTC Instant를 마이크로초로 자른다. 원본 DATETIME(6)은 UTC LocalDateTime으로 명시 변환하고 이벤트는 같은 Instant다.
- clientIp: 공통 ClientIpResolver 사용. 신뢰되지 않은 프록시 헤더를 직접 읽지 않는다. 확인 불가는 null이다.
- httpMethod: HTTP token의 앞 최대 10자(MAX_HTTP_METHOD_LENGTH)를 원본·이벤트에 동일하게 기록한다. 10자 이하는 보존한다. 인가·deniedType 판정은 자르기 전 요청 메서드로 한다.
- loginId: 원본 테이블에는 저장하지 않고 DB 계정에서 읽은 값으로 이벤트에만 전달한다.

### deniedType과 인가

OperatorAccessRoutes에 사장님 전용 경로를 모으고 SecurityConfig와 분류기가 같은 목록을 쓴다.
기존 인가의 역할·권한·가맹점 조건과 규칙 순서는 유지한다.

- 연동 관리: GET 기본 경로, POST 키 발급·보기·재발급.
- 정산: 기존 보고서·자금 설정·지출·반복 지출의 메서드·경로.
- 자금 예측·부족 조회: GET만 해당한다. 다른 메서드를 거절하는 기존 규칙도 유지한다.
- 사장님 사건: GET 목록·상세.

메서드·경로가 일치하면 PERMISSION_MISSING, 나머지는 OUT_OF_ROLE이다.
접두사만 같거나 미등록 메서드는 사장님 경로로 보지 않는다. 공통 /api/auth/**는 사장님 전용 목록에 넣지 않는다.
이 분류는 인가 결과를 대신하지 않는다.

## 보관 정책 — 사용자 개정, IP 정리 미구현

- `client_ip`: 발생 시각(`occurred_at`) 기준 1년 보관 후 NULL 처리.
- 원본 행: 조사 근거이므로 IP 정리 작업에서 삭제하지 않는다. 행 보관 기간·종료 기준은 조사 기록 보관 정책에서 따로 정한다.
- 기존 일반 90일·조사 종료 후 90일 행 삭제 정책은 이 결정으로 대체한다. 행을 무기한 보관하기로 결정한 것은 아니다.

법 조항의 참고 근거·제8조 적용 범위·2년 보관 대상 예외는 [ADR-0080 보관 정책](../decisions/0080-operator-access-denials.md#보관-정책)을 따른다.
IP NULL 처리만으로 남은 행의 개인정보까지 모두 파기됐다고 보지 않는다.
IP 정리는 후속 이슈에서 구현한다. 현재 코드는 수집·발행만 하며 IP를 자동으로 지우지 않는다.
후속 범위에는 원본·P2 복사본·이벤트·백업의 IP와 날짜 경계·재실행·실패 복구 검증이 포함된다.

## 검증

검증은 실제 실행한 작업본·커밋을 기준으로 아래 기록한다. 기존 524의 통과 결과로 이번 저장·발행을 검증했다고 표현하지 않는다.

계획:

- 일반 검사: 기존 역할·권한 보안 회귀, 경로 분류, 핸들러 대상·응답·장애.
- MariaDB: 전체 Flyway·JPA validate, 실제 HTTP 403 → 원본·이벤트,
  권한 부족, CSRF·직원·비로그인·강제 변경 제외, INSERT/커밋 실패·롤백,
  DB 가맹점 사용, 긴 Unicode·쿼리 제외·공통 IP·UTC 마이크로초·외부 롤백과 독립 커밋.
- 테스트 원본은 임시 DB에서만 정리한다. 기존 테스트 cleanup에도 새 사용자 FK의 자식 원본 정리를 추가한다.

### 초기 경로 통합 검사

기준 `f6d603e` 위 초기 경로 통합 작업본에서 실행했다. 현재 저장·발행 코드의 검증 근거는 아니다.

- 선택 보안 검사: 신규 분류 18건 포함 69건 통과.
- `./gradlew check --console=plain --max-workers=2`: 일반 1,434건 중 기존 1건 skip, 실패·오류 0.
- 기준을 `27063d6`로 옮긴 뒤 check 재확인: BUILD SUCCESSFUL, 모든 작업 UP-TO-DATE.

### 저장·발행 최종 실행 기록

2026-11-04, 구현 커밋 `7032765`와 동일한 코드·테스트·설정 작업본에서 실행했다. 실행 도중 해당 작업본을 커밋했고, 검증 기록 커밋 ce66131까지 backend 변경은 없었다. 이후 리뷰 보완 검증은 아래 별도 기록을 따른다.

- `cd backend && ./gradlew spotlessApply check mariaDbTest --console=plain --max-workers=2`: **BUILD SUCCESSFUL**, 6분 5초.
- 일반 1,440건 중 1,439 통과·기존 1건 skip. MariaDB 817건·별도 Asia/Seoul 정산 JVM 20건 모두 통과. XML failures/errors 모두 0.
- 신규 경로 18건·핸들러 6건·MariaDB 13건 전부 통과. 위 계획의 원본·이벤트 일치, INSERT/커밋 장애, 독립 커밋, 제외 대상, 세션/DB 차이, Unicode·긴 HTTP 경로·쿼리 제외·IP·UTC·DB FK/CHECK를 확인했다.
- 전체 Flyway 적용·JPA validate·ModularityTests·ArchitectureTests·기존 인가 HTTP 회귀를 포함한다.
- `python3 .github/scripts/check_md_links.py`, `git diff --check`: 통과.
- [PR #527](https://github.com/team-sweethan/DonWorry/pull/527), base dev. PR 생성 당시 최신 dev `c72c161`은 P6 프론트·문서 변경이며 `27063d6` 이후 backend 변경은 없다.
- 원격 CI는 별도 확인 대상이다. 프론트·AI는 변경이 없어 로컬 검사를 실행하지 않았다.

미검증: P2 실제 수신·룰·정지 연결, 반복 탐지 → 정지 → 해제 후 재정지 방지 전체 흐름,
IP NULL 처리·1년 실시간 경과·조사 기록의 행 보관 기간·운영 DB·배포·Notion 동기화.


### PR #527 Codex 리뷰 — 긴 HTTP 메서드 보완

리뷰는 http_method VARCHAR(10)을 넘는 사용자 정의 메서드가 INSERT를 실패시켜 수집이 누락될 수 있음을 지적했다.
수정 전 서비스→MariaDB 재현에서 LONGMETHOD1·26자 메서드가 DataIntegrityViolationException으로 실패하고 10자 경계는 통과했다.
실제 HTTP+유효 CSRF에서는 현재 기본 방화벽이 LONGMETHOD1을 400으로 거절해 핸들러까지 도달하지 않는 것도 확인했다.

저장 경계를 보완해 원본·이벤트 모두 HTTP token의 앞 10자로 제한한다. 요청 자체를 변경하지 않으며 인가/분류에는 원문 메서드를 쓴다.
기존 migration·DB 컬럼은 유지한다. Entity와 발행자가 공개 길이 상수를 공유하고 #521 계약·ADR-0080에 기록했다.
2026-11-04, `ce66131` 위 리뷰 보완 작업본에서 다음을 실행했다.

- 수정 전 신규 4건 중 10자 경계·실제 HTTP 방화벽 거절은 통과, 11자·26자 저장 2건은 MariaDBDataTruncation으로 실패했다.
- 수정 후 `cd backend && ./gradlew spotlessApply check mariaDbTest --tests '*OperatorAccessDenialMariaDbTests' -x settlementSeoulMariaDbTest --console=plain --max-workers=2`: BUILD SUCCESSFUL, 48초.
- 일반 1,440건 중 기존 1건 skip, 관련 MariaDB 17건 전부 통과. XML failures/errors 0. 모듈·계층·포맷 검사 포함.
- `python3 .github/scripts/check_md_links.py`, `git diff --check`: 통과.
- 이번 보완에서는 MariaDB 전체 817건·별도 Asia/Seoul 정산 검사를 재실행하지 않았다. 위 전체 검사 결과는 보완 전 `7032765` 코드 기준이다. 신규 원격 CI는 별도 확인 대상이다.


### PR #527 병합 전 보관 정책 문서 개정

사용자 요청으로 IP 1년 후 NULL 처리와 조사 원본 행 보존을 분리했다. ADR-0080·ERD·identity 안내를 함께 갱신한다.
이번 변경은 문서만이며 backend 코드·migration·테스트는 변경하지 않는다.
법령 원문은 2026-11-04 국가법령정보센터에서 확인했다. IP 정리 구현·행 보관 기간 확정·법 적용 판정·Notion 반영은 완료하지 않았다.
