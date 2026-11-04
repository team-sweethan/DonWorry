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
- httpMethod: 원래 요청 메서드.
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

## 보관 정책 — 사용자 확정, 자동 삭제 미구현

- 일반 원본·IP: 발생 후 90일 보관, 이후 삭제.
- 탐지·조사에 사용된 원본: 조사 종료 후 90일 보관, 이후 삭제.
- 진행 중인 조사 근거: 자동 삭제 제외.

이는 제품 정책이며 법정 기간을 확정한 것이 아니다. 사용자가 승인한 조사 근거 물리 삭제 금지 원칙의 만료 예외다.
525는 수집·발행까지 구현한다. 조사 사용·종료 정보가 연결되기 전에는 날짜만으로 원본을 자동 삭제하지 않는다.
삭제 배치는 후속 작업이며 P2의 보호할 eventId·종료 정보, 미완료 이벤트·복사본·백업도 함께 다뤄야 한다.
추가 사용자 승인 대기 상태가 아니다. 실제 삭제가 아직 작동하지 않는다는 구현 한계다.

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

실행 결과를 확인한 뒤 추가한다.

미검증: P2 실제 수신·룰·정지 연결, 반복 탐지 → 정지 → 해제 후 재정지 방지 전체 흐름,
자동 삭제·90일 실시간 경과·운영 DB·배포·Notion 동기화.
