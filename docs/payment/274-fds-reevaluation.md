# #274 명시적 FDS 재평가 구현 기록

- 기준: `dev 8787710`, 작업 브랜치 `feature/274-fds-reevaluation`.
- 계약: [ADR-0036](../decisions/0036-fds-failure-analysis-and-reevaluation.md), [#190 저장·API 계약](190-fds-recovery-contract.md).
- 현재 범위: 내부 접수·실행 서비스, READY → REQUESTED → 종료 전이, P2 재평가·현재 평가 교체·모의 승인 연결. 재평가 HTTP POST·CSRF 연결은 6절을 따른다. 전달 복구 POST 구현은 #275 범위다. 실행 단계의 상세 내용은 5절을 따른다.

## 1. 공개 계약 확인

P2 FdsReevaluationRequester.reevaluate에 원본 UUID·새 UUID·retryId 및 최초 결제 입력을 전달한다. P2 재평가와 실제 평가 저장은 REQUIRED, P5 authorizeInTransaction은 MANDATORY이므로 후속 실행 트랜잭션에 함께 참여시키는 구조다. 실행 서비스가 해당 트랜잭션을 열며, 원자성은 5절의 통합 테스트로 검증한다.

P1 CurrentStaff는 현재 세션의 userId·role을 제공한다. 기존 CookieCsrfTokenRepository와 GET /api/auth/csrf를 재사용하되 두 POST를 전역 설정과 관계없이 보호하는 matcher 및 SECURITY_STAFF 전용 HTTP 권한 설정은 6절에 추가했다. 이번 접수 서비스는 CurrentStaff로 역할과 실행자만 검사한다.

## 2. 접수 구현

- PaymentFdsReevaluationAdmissionService.admit(paymentId)는 REQUIRES_NEW로 접수 정보를 먼저 커밋한다. CurrentStaff로 SECURITY_STAFF·양수 직원 ID를 확인한 다음 입력과 업무 데이터를 검사한다. 재접수도 권한 검사를 생략하지 않는다.
- payments → payment_requests → payment_fds_recoveries 순서로 PESSIMISTIC_WRITE 잠금을 잡는다. 기존 REQUESTED·COMPLETED 작업은 최초 키·직원·시각을 보존하여 내부 접수 스냅샷으로 반환한다.
- MANUAL_REVIEW는 새 UUID에 해당하는 현재 ERROR가 있을 때만 기존 결과 반환 대상으로 다룬다. 미실행 분석 거절은 ANALYSIS_NOT_ALLOWED, 접수 후 결과 미반영 수동 종료는 PAYMENT_STATE_CHANGED로 거절한다.
- 신규 접수는 READY + PENDING + 승인 참조 없음 + NEEDS_REVIEW + 현재 평가와 원본 ERROR 일치를 확인한다. UUID·평가 ID·ERROR 결과·오류 코드·평가 시각을 대조한다.
- PaymentFdsRecovery.requestReevaluation이 새 평가 UUID·retryId·최초 직원·접수 시각을 한 번 저장하고 REQUESTED로 전환한다. 최초 ERROR·보고 키·P2 분석 사유는 변경하지 않는다.
- 분석 대기·불가·자료 부재·상태 변경은 내부 예외의 reason으로 구분한다. HTTP 409/503 등으로 변환하는 전용 예외 처리기는 후속이다.
- PaymentFdsReevaluationAdmission은 후속 실행에 사용할 내부 스냅샷이다. 작업 UUID·retryId를 HTTP 응답으로 노출하지 않는다.
- P2 재평가·승인·이벤트를 호출하지 않는다. 기존 recordEvaluation의 불변성 검사도 유지한다. DB 컬럼·migration 변경은 없다.

## 3. 잠금 대기 제한

재평가 전용 Repository 쿼리에 `jakarta.persistence.lock.timeout=5000`을 적용한다. 제한은 잠금 쿼리 각각 5초이며 HTTP 전체 처리 제한을 의미하지 않는다. 기존 결제 경로의 잠금 정책은 바꾸지 않는다.

세션의 innodb_lock_wait_timeout을 직접 SET/복원하는 방법은 연결 풀에 설정을 남길 위험과 복원 예외 처리를 추가하므로 사용하지 않는다. 현재 Hibernate/MariaDB에서 쿼리 단위 제한이 적용되는지는 실제 잠금 경합 테스트로 확인한다. P2 내부 잠금·실행 트랜잭션의 유한 대기 및 데드락→503 검증은 7절을 따른다.

기존 CakeShop 신규 접수는 주문을 잠근 뒤 결제를 잠글 수 있다. 이번 경로는 결제 이후 주문을 잠그지 않으므로 주문으로 돌아가는 역순 잠금을 추가하지 않는다. 후속 실행에서도 이 순서를 유지한다.

## 4. 검증 범위와 남은 작업

- 접수 키·최초 직원 불변, 원본·현재 결제/평가 보존, 분석 대기/거절, 자료 부재와 없는 결제, 상태·원본 불일치, 동시 접수, 외부 트랜잭션 롤백 후 접수 보존을 검사한다.
- 종료 작업 재사용, ADMIN/OPERATOR/익명 거절, 세 테이블 각각 잠금 제한·실패 후 READY 보존·재접수를 검사한다.
- P2 실행·평가 교체·승인 재개·종료 처리는 5절에 구현했다. 재평가 HTTP 입력/오류 계약과 두 POST의 CSRF·권한·no-store는 6절에 구현했다.
- #273의 P2 분석 사유는 보존한다. 실행 전 상태 불일치의 수동 종료와 사유 처리 방식은 5절을 따른다.
- 운영 DB 적용과 HTTP 동작은 이번 단계의 검증 대상이 아니다.

### 접수 단계 실행 결과 — 2026-10-28

기준: `8787710` 위 미커밋 작업본.

- `cd backend && ./gradlew spotlessApply check mariaDbTest --tests '*PaymentFdsReevaluationAdmissionMariaDbTests'`: BUILD SUCCESSFUL.
- 일반 테스트 554개(기존 skipped 1개), 접수 MariaDB 테스트 18개, 실패·오류 0.
- 세 테이블별 잠금 테스트는 4~15초 안에 잠금 예외가 발생하는지 검사했고 모두 통과했다. 동일 접수 재시도 성공, 실패 후 READY 유지, 세션 대기 설정값 유지도 확인했다.
- 문서 링크 검사와 `git diff --check`: 통과.
- 미검증: 이번 접수 변경 후 MariaDB 전체 회귀는 아직 실행하지 않았다. HTTP 오류 매핑·CSRF·실제 재평가 실행 및 운영 DB 적용은 후속이다.


## 5. 재평가 실행·승인·종료 연결

- PaymentFdsReevaluationService는 NOT_SUPPORTED에서 접수 서비스와 실행 서비스를 순서대로 호출한다. C 접수 커밋과 D 실행 커밋을 분리한다.
- PaymentFdsReevaluationExecutionService는 REQUIRES_NEW에서 payments → payment_requests → 복구 행을 잠그고 저장 retryId를 대조한다. 접수·실행 모두 PaymentFdsStaffAccessService를 통해 CurrentStaff의 SECURITY_STAFF를 검사한다.
- P2 FdsReevaluationRequester만 호출한다. 저장된 원본/새 UUID·retryId와 불변 결제 입력을 사용하며 미수집 기기·카드·국가 입력은 null이다. P2 내부 Repository를 참조하지 않는다.
- P2 REQUIRED 평가·계보 저장, P5 현재 평가 변경, MANDATORY 승인·이벤트 등록, AUTHORIZED 및 복구 종료는 같은 실행 트랜잭션에 참여한다.
- 응답 UUID·평가 ID·결과 필드를 검증한다. 원본 ID 재사용·다른 UUID·잘못된 필드는 내부 불일치로 거부하고 전체 실행을 롤백한다.
- PaymentRequest.applyReevaluation은 원본 ERROR 보존·REQUESTED·동일 결제·새 UUID를 대조한 별도 전이다. recordEvaluation의 불변성 검사는 그대로 유지한다.
- ALLOW/REVIEW/DENY는 기존 승인·거절 규칙 후 COMPLETED가 된다. 승인 거절도 초기 처리 완료이며 결제 상태는 DECLINED다. ERROR는 현재 평가를 ERROR로 반영하고 NEEDS_REVIEW·MANUAL_REVIEW로 끝낸다. 새 보고·추가 평가를 자동 생성하지 않는다.
- 실행 시 상태가 달라졌으면 금융 상태를 되돌리지 않고 MANUAL_REVIEW·completed_at을 저장한다. 실행 결과의 거절 사유를 흐름 서비스에서 커밋 후 예외로 전달하므로 수동 종료 기록이 롤백되지 않는다.
- REQUESTED 실행 중 기술 실패는 D 전체를 롤백하고 C의 키·직원·접수 시각은 남긴다. 재요청은 같은 작업으로 재개한다. 종료된 요청은 새 평가·승인 없이 현재 요약을 반환한다.
- COMPLETED는 P3/P6 커밋 후 후속 처리 완료를 뜻하지 않는다. 응답은 실행 당시 결제 스냅샷이며 후속 조회에서 매입 완료 등이 반영될 수 있다.

### 수동 종료 사유 처리

추가 DB 컬럼 없이 현재 계약을 유지한다. reason_code에는 P2 분석 사유만 보존한다. 접수된 작업이 MANUAL_REVIEW이며 새 UUID의 ERROR가 현재 평가에 반영되지 않았다면 실행/재접수의 거절 사유는 PAYMENT_STATE_CHANGED다. GET 요약의 reasonCode는 기존 분석 사유 표현을 유지한다. 구체적인 변경 이력을 별도 감사 정보로 영속화하는 기능은 추가하지 않았다.

### 잠금과 HTTP 후속 범위

이번 단계는 P5 세 행의 쿼리별 5초 제한을 재사용한다. P2 내부 잠금 대기 설정은 변경하지 않았다. P2 잠금 경합·실행 트랜잭션 제한과 실제 데드락의 HTTP 503 검증은 7절에 추가했다. HTTP 진입점과 CSRF 검사는 6절에서 함께 연결한다.

### 실행 단계 검증 결과 — 2026-10-28

기준: `8787710` 위 미커밋 작업본(접수·실행 구현 포함).

- `cd backend && ./gradlew spotlessApply check mariaDbTest --tests '*PaymentFdsReevaluation*MariaDbTests'`: BUILD SUCCESSFUL. 접수 18개 + 실행 14개, 실패·오류 0.
- `cd backend && ./gradlew spotlessApply check mariaDbTest`: BUILD SUCCESSFUL. MariaDB 전체 426개, 실패·오류·건너뜀 0. 일반 테스트 결과는 554개 중 기존 건너뜀 1개, 실패·오류 0이며 전체 회귀 실행에서는 변경이 없어 UP-TO-DATE로 재사용했다.
- 실제 P2 ALLOW 평가·계보 저장, REVIEW/DENY/ERROR 분기, 승인 거절, 동일 작업 재요청·동시 실행, 지연 분석의 종료 상태 보존을 검사했다. 결과 분기 테스트는 평가 대역이 실제 P2 트랜잭션 안에서 평가 행을 저장한다.
- P2 저장 후·승인 후·커밋 직전 예외와 UUID/평가 ID 불일치에서 평가·승인·이벤트 전체 롤백, 기존 접수 키로 재개를 확인했다. 접수 이후 결제·평가 변경 시 수동 종료 커밋과 P2 미호출도 확인했다.
- 첫 실행의 테스트 설정 오류는 MANDATORY 서비스 프록시를 설정 중 호출한 문제였다. Spy 대상 객체에 대역을 설정하도록 고쳤으며, 운영 코드의 트랜잭션 조건은 완화하지 않았다.
- `python3 .github/scripts/check_md_links.py`, `git diff --check`: 통과.
- 미검증·후속: HTTP POST/오류 매핑/CSRF/no-store, P2 내부 잠금 경합·실행 전체 제한·데드락 HTTP 응답, 운영 DB 적용. 다른 담당 공개 계약과 DB 스키마 변경은 없다.

## 6. 재평가 HTTP·오류·CSRF 연결

- `POST /api/payment-operations/{paymentId}/fds-reevaluations`를 PaymentFdsReevaluationController에 추가했다. 양수 Long ID만 허용하고 쿼리 문자열·본문은 거절한다. Service에서 발생한 IllegalArgumentException은 입력 오류로 오인하지 않고 500으로 처리한다.
- SECURITY_STAFF의 SessionUser만 보안 필터에서 허용한다. ADMIN·OPERATOR·익명은 입력/존재 확인 전에 거절한다. 서비스의 CurrentStaff 검사도 유지한다.
- PaymentFdsReevaluationExceptionHandler는 400/404/409/503/500을 #190의 code로 반환한다. 409는 기존 reasonCode를 제공한다. 잠금 대기·데드락 등 DataAccessException과 트랜잭션 시작 실패·시간 초과는 503이며 원문 SQL·예외 메시지는 노출하지 않는다. 서비스의 인증·권한 실패는 AUTH_001/AUTH_002로 반환한다.
- SecurityConfig의 기존 체인에서 정확한 두 POST(재평가·전달 복구)에 CSRF와 SECURITY_STAFF 권한을 강제했다. 전역 플래그가 false여도 CookieCsrfTokenRepository와 SPA 헤더 검사를 사용한다. 다른 경로는 기존 전역 설정을 따른다. 전달 복구 Controller는 아직 구현하지 않았다.
- 두 POST의 보안 필터 실패까지 Cache-Control: no-store를 적용한다. Controller와 예외 처리기도 동일한 정책을 적용한다. 202는 추가하지 않는다.
- P1 보안 체인 변경은 사용자 승인된 ADR-0036 범위이며 새 인증 체계나 다른 모듈 내부 참조를 추가하지 않았다. DB 변경은 없다.
- HTTP 단계 당시 남은 검증(7절에서 수행): P2 내부 잠금 경합과 실행 트랜잭션 시간 제한, 실제 DB 데드락의 HTTP 응답. 이번 HTTP 오류 테스트의 잠금 실패는 대역으로 검증하며 기존 P5 행 잠금 제한은 접수 MariaDB 검사로 확인한다.

### HTTP 단계 검증 결과 — 2026-10-28

기준: `8787710` 위 미커밋 작업본(접수·실행·HTTP·보안 변경 포함).

- `cd backend && ./gradlew spotlessApply check mariaDbTest`: BUILD SUCCESSFUL. 일반 테스트 576개(기존 건너뜀 1개), MariaDB 전체 427개, 실패·오류 0.
- 신규 HTTP 계약 테스트 22개: 전역 CSRF false/true에서 각각 11개. 쿠키·헤더 일치/누락/불일치, 권한 우선 검사, 입력 거절, 오류 응답의 원문 비노출, no-store, 다른 경로·메서드의 기존 정책을 검증했다.
- MariaDB에 HTTP → 실제 P2 → 모의 승인 → 저장 → 같은 POST 재요청 사례 1개를 추가했다. 평가·승인·이벤트가 중복 생성되지 않는지 검사했다. 기존 접수 18개·실행 14개도 전체 회귀에 포함됐다.
- 첫 HTTP 검사에서 발견한 no-store 헤더의 기존 기본값 처리와 테스트용 csrf()의 저장소 교체 영향을 수정했다. 두 경로의 헤더를 명시적으로 설정하고 HTTP 계약 테스트는 실제 CookieCsrfTokenRepository의 쿠키/헤더 조합을 사용한다.
- `python3 .github/scripts/check_md_links.py`, `git diff --check`: 통과.
- 미검증: 위의 P2 잠금 경합·실행 전체 제한·실제 DB 데드락 HTTP 응답, 운영 배포. #275 전달 복구 구현은 별도 후속이다. 커밋·PR은 아직 만들지 않았다.

## 7. 실행 시간 제한과 실제 잠금 경합

- D 실행에 `@Transactional(timeoutString="${payment.fds-reevaluation.execution-timeout-seconds:15}")`를 적용했다. 기본 15초의 남은 시간을 P2 REQUIRED와 P5 승인·저장이 공유한다. 접수 C의 별도 커밋과 P5 쿼리별 5초 잠금 제한은 유지한다.
- P2 Repository나 DB 세션의 대기 설정은 변경하지 않는다. 시간 초과와 데드락은 기존 503 계약으로 반환하며 D의 변경은 롤백하고 C의 작업 키로 재개한다.
- 제한은 DB 트랜잭션 예산이며 Java 스레드 강제 중단이나 HTTP 전체 응답 시간 보장이 아니다. 접수·커넥션 획득·네트워크 응답·커밋 후 P3/P6 처리는 포함하지 않는다. [ADR-0036](../decisions/0036-fds-failure-analysis-and-reevaluation.md)에 대안과 영향 범위를 기록했다.
- 테스트에서는 실행 예산을 5초로 설정한다. 실제 P2 원본 평가 행 잠금, 같은 트랜잭션의 두 DB 지연 작업으로 누적 예산 소진, 역순 잠금으로 실제 MariaDB deadlock(오류 1213)을 유발한다. 503 후 원본 평가/승인/이벤트 보존과 같은 UUID·retryId·최초 직원/시각으로 재개를 확인한다.
- 데드락 fixture의 임시 평가 행은 희생 트랜잭션 선택을 안정화하기 위한 테스트 데이터이며 fixture 트랜잭션에서 모두 롤백한다. 운영 코드에는 역순 잠금이나 자동 재시도를 넣지 않는다.

### 잠금·실행 제한 단계 검증 결과 — 2026-10-28

기준: `8787710` 위 미커밋 작업본(접수·실행·HTTP·보안·실행 제한 포함).

- `cd backend && ./gradlew spotlessApply mariaDbTest --tests '*PaymentFdsReevaluationMariaDbTests.http_when*'`: BUILD SUCCESSFUL, 신규 3개 통과. P2 행 잠금과 누적 실행 시간 제한은 각각 약 5.3초, 실제 데드락·재개는 약 0.6초에 검사를 마쳤다(테스트 실행 예산 5초).
- 첫 데드락 fixture에서는 반대편 트랜잭션이 희생돼 재평가가 200으로 완료됐다. fixture에 롤백할 임시 평가 행을 추가해 실행 트랜잭션이 희생되도록 했으며 SQLException의 MariaDB 오류 1213을 직접 확인했다. 운영 로직은 이 테스트 조정을 위해 변경하지 않았다.
- `cd backend && ./gradlew spotlessApply check mariaDbTest`: BUILD SUCCESSFUL. 일반 테스트 576개(기존 건너뜀 1개), MariaDB 전체 430개, 실패·오류 0. 신규 경합 3개도 전체 실행에서 다시 통과했다.
- `python3 .github/scripts/check_md_links.py`, `git diff --check`: 통과.
- 미검증: 운영 배포 환경의 부하·15초 설정 적정성, 네트워크 장애·커넥션 풀 고갈, JVM 정지 등을 포함한 HTTP 전체 응답 시간. DB 트랜잭션 제한을 이 범위의 강제 종료 보장으로 해석하지 않는다.
- #275 전달 복구와 기존 UUID 결과 복원은 별도 후속이며 #190 전체 완료를 뜻하지 않는다. 커밋·PR은 아직 만들지 않았다.

## 8. 호출 절차와 평가 횟수

직원 로그인 후 `GET /api/auth/csrf`로 현재 `XSRF-TOKEN` 쿠키를 발급받는다. 세션 쿠키와 함께 쿠키 값을 `X-XSRF-TOKEN` 헤더에 넣어 본문·쿼리 없는 재평가 POST를 호출한다. 로그인 과정에서 토큰이 바뀔 수 있으므로 로그인 전 토큰을 재사용하지 않는다. 503/500이면 상세 GET으로 현재 상태를 확인하고 같은 POST로 재개한다. 자동 반복 호출은 하지 않는다.

최초 ERROR는 평가 시도 1, 저장된 새 UUID는 평가 시도 2이며 새 UUID를 추가 생성하지 않는다. 별도 평가 횟수 컬럼을 추가하지 않고 원본/새 UUID 계보와 단일 복구 행으로 제한한다. `payment_requests.attempt_no`는 주문의 결제 시도 번호이므로 재평가 횟수로 바꾸지 않는다. 재평가 ERROR는 새 오류 보고 없이 MANUAL_REVIEW로 종료한다.
