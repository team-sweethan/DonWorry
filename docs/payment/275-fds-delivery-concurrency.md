# #275 Modulith 동시 재전달 검증

- 조사·검증일: 2026-10-29
- 기준: 최신 dev `b5e0299`, 작업 브랜치 `feature/275-fds-recovery-delivery`
- 범위: 실제 MariaDB와 설치된 Modulith 2.1.1의 동시 재전달 동작을 확인하는 특성 테스트. 운영 복구 API·DB 스키마는 변경하지 않는다.
- 관련: [복구 계약](190-fds-recovery-contract.md), [ADR-0036](../decisions/0036-fds-failure-analysis-and-reevaluation.md), GitHub #275·#191.

## 검증 방식

실제 P2 공개 보고 API로 분석 이벤트를 발행하고, P5 분석 서비스에 한 번 실패를 주입해 FAILED publication을 만든다. 이후 실제 `IncompleteEventPublications` API를 호출한다. 이벤트 테이블의 상태를 SQL로 조작하지 않는다.

필터는 FAILED·완료 시각 없음·이벤트 타입·결제 ID·보고 ID·원본/현재 평가 UUID·P5 리스너를 모두 검사한다. CountDownLatch로 두 요청의 순서를 고정해 시간 기반 운에 의존하지 않는다.

1. 첫 요청이 FAILED publication을 조회하고 필터에서 대기한다.
2. 두 번째 요청이 같은 publication을 재전달한다.
3. 실제 상태가 PROCESSING 또는 COMPLETED가 된 것을 DB에서 확인한다.
4. 첫 요청의 필터를 통과시킨다.
5. 같은 publication의 수신 서비스가 두 번 호출되고, 처리 시도 수가 최초 실패 포함 3으로 증가하는지 확인한다.

비교 테스트에서는 순차 재전달 후 완료 상태를 새로 조회하면 추가 전달이 발생하지 않는지 확인한다(최초 실패 + 재전달 1회 = 처리 시도 2).

## 확인한 제약과 의미

- `EventPublication`의 상태는 조회 시점의 스냅샷이다. predicate에서 FAILED만 선택해도 이후 상태 변경을 원자적으로 막지 않는다.
- 2.1.1 JDBC 구현의 `markResubmitted`는 `ID = ? AND STATUS != 'RESUBMITTED'` 조건으로 갱신한다. 오래된 FAILED 스냅샷을 가진 호출은 현재 PROCESSING/COMPLETED인 행도 다시 RESUBMITTED로 바꿀 수 있다.
- P5 수신 멱등 처리로 READY가 유지되고 새 평가·승인·retryId는 생성되지 않는다. 하지만 수신 코드 중복 실행 자체는 발생하므로 #275의 진행 중·완료 제외 조건을 충족한 것으로 볼 수 없다.
- `TargetEventPublication`은 현재 starter 구성의 컴파일 의존성으로 노출되지 않아, 수신자 식별 검증을 위해 `spring-modulith-events-core`를 **testImplementation에만** 추가했다. 운영 코드에서 사용할 공개 경계·의존성은 아직 결정하지 않았다.
- 첫 실행의 시도 수 단언은 최초 전달을 제외해 1 작게 예상했다. 라이브러리가 최초 전달도 포함함을 확인하고, 실제 수신 호출 수와 함께 검증하도록 수정했다.

## 다음에 확정할 사항

1. 상태 선택과 재전달 시작 사이의 경합을 막는 방법. 단순 FAILED 필터·추가 조회·P5 멱등 처리만으로 해결했다고 보지 않는다.
2. 후보: 라이브러리의 FAILED 조건 원자적 전환을 지원하는 보완안, 또는 모든 재전달 진입점에 적용되는 공통 실행 보호 경계. 단일 인스턴스 메모리 잠금만으로 다중 인스턴스까지 보호한다고 가정하지 않는다.
3. 공통 이벤트 담당과 #191 영향 범위를 확인한다. 이 검증에서 DB 직접 상태 수정이나 임의 이벤트 발행으로 우회하는 운영 코드는 추가하지 않았다.
4. 프로세스 중단으로 남은 PUBLISHED/PROCESSING/RESUBMITTED를 실제 실행 중과 구분하는 절차. 이 문서의 테스트는 프로세스 강제 종료·재시작을 검증하지 않았다.

특성 테스트 통과는 위 경합 현상이 재현됐다는 뜻이며, #275 운영 복구가 안전하게 구현됐다는 뜻이 아니다.

## 실행 결과

기준: `b5e0299` 위 미커밋 검증 작업본.

- `cd backend && ./gradlew spotlessApply check mariaDbTest --tests '*PaymentFds*MariaDbTests'`: BUILD SUCCESSFUL.
- 일반 테스트 600개(기존 건너뜀 1개), 선택 실행한 P5 FDS MariaDB 테스트 56개(신규 경합 3개 포함), 실패·오류 0.
- PROCESSING 경합: 최초 실패 이후 같은 publication의 수신 서비스가 2회 실행됐다. 완료 경합에서도 동일하게 2회 실행됐다. 단일 복구 결과 READY·평가 행 1개·승인 참조 없음·retryId 없음은 유지됐다.
- 순차 요청: 완료 후 다시 조회하면 재전달하지 않아 수신 호출 수가 최초 실패 포함 2회로 유지됐다.
- `python3 .github/scripts/check_md_links.py`, `git diff --check`: 통과.
- 미검증: MariaDB 전체 회귀, 실제 프로세스 종료·재시작, 다중 앱 인스턴스, 경합 방지책 적용 후 동작. 이번 작업은 검증 전용이며 운영 코드·DB 변경과 커밋·PR 생성은 하지 않았다.

## 단일 인스턴스 전용 잠금 적용 — 2026-10-29

사용자 승인으로 `PaymentFdsDeliveryGuard`를 추가했다. singleton Bean의 잠금 하나를 모든 전달 복구에 공유하며, `tryLock()` 실패는 `PaymentFdsDeliveryBusyException`으로 즉시 거절한다. 자동 대기·재시도하지 않는다. 정상·예외 모두 finally에서 잠금을 해제하고 같은 스레드 중첩 실행도 거절한다.

운영 전달 복구 서비스는 권한·입력 검사 후 guard.execute 콜백 안에서 상태를 새로 읽고 보고·재전달·최종 조회를 수행해야 한다. 이번 단계에서는 실제 Modulith 조회·재전달 호출을 이 경계로 감싼 통합 테스트로 검증한다. Controller는 아직 없어 HTTP 503 매핑 자체는 후속 구현이다. 리스너와 명시적 재평가에는 이 잠금을 적용하지 않는다.

추가 검사:

- FAILED 조회를 멈춘 첫 복구 요청이 잠금을 가진 동안 두 번째 요청은 거절되며 조회·재전달 콜백이 실행되지 않는다.
- 첫 요청이 잠금을 해제한 후 PROCESSING 또는 COMPLETED 상태에서 재요청하면 최신 조회 결과로 제외된다. 최초 실패를 제외한 수신은 1회이며 처리 시도 수는 2로 유지된다.
- 복구 예외 후 다른 스레드가 잠금을 얻을 수 있으며 중첩 호출 거절 후에도 다음 호출이 정상 실행된다.
- 잠금 없는 기존 특성 테스트도 유지해, 라이브러리 자체의 경합이 사라졌다고 오인하지 않도록 한다.

이 방식은 단일 앱 인스턴스와 동일 잠금을 거치는 재전달 진입점을 전제로 한다. 다른 결제의 복구도 하나씩 실행한다. 다중 인스턴스·공통 재전달 도구 도입 시 재설계가 필요하다. 결정과 대안은 [ADR-0036](../decisions/0036-fds-failure-analysis-and-reevaluation.md)에 기록했다.

### 잠금 적용 후 실행 결과

기준: `b5e0299` 위 미커밋 작업본(전용 잠금 포함).

- `cd backend && ./gradlew spotlessApply check mariaDbTest --tests '*PaymentFds*MariaDbTests'`: BUILD SUCCESSFUL.
- 일반 테스트 600개(기존 건너뜀 1개), P5 FDS MariaDB 59개, 실패·오류 0. 경합 클래스 6개에는 잠금 없는 재현 3개와 잠금 적용 검사 3개가 포함된다.
- PROCESSING/COMPLETED 양쪽에서 중첩 복구 요청은 콜백 진입 전에 거절됐다. 잠금 해제 후 재요청은 최신 상태를 읽어 제외됐으며 재전달 수신은 1회였다. 복구 결과 READY·평가 행 1개·승인 참조 없음·retryId 없음도 유지됐다.
- 예외 후 잠금 해제, 다른 스레드의 재진입, 같은 스레드 중첩 실행 거절을 확인했다.
- `python3 .github/scripts/check_md_links.py`, `git diff --check`: 통과.
- 미검증: 전체 MariaDB 회귀, 전달 복구 HTTP 503 매핑, 프로세스 종료·재시작, 다중 앱 인스턴스 및 잠금을 우회하는 다른 재전달 경로. 커밋·PR은 아직 만들지 않았다.

## 내부 전달 복구 서비스 연결 — 2026-10-29

위 단계의 후속으로 `PaymentFdsDeliveryService.recover(paymentId)`를 구현했다. `spring-modulith-events-core`는 이제 운영 implementation 의존성이며, 실제 HTTP 진입점은 아직 없다.

- 직원 권한·ID 확인 → 공통 잠금 → 짧은 원본 조회 트랜잭션 → P2 재보고 → 정확히 일치하는 FAILED publication 재전달 → 현재 요약 반환 순서다.
- 저장 원본으로 최초 보고와 같은 failureReportId·평가 UUID·관찰 시각·입력을 복원한다. 미수집 선택 필드를 새로 채우지 않는다.
- P5 행 잠금은 P2/Modulith 호출 전에 해제한다. READY 등 진행된 상태에서는 호출 없이 요약만 반환한다.
- 다른 결제·원본 UUID·수신자·이벤트는 제외한다. 진행 중 상태는 그대로 두고, 누락·모호한 전달 기록은 DELIVERY_RECORD_UNAVAILABLE, 원본 불일치는 RECOVERY_DATA_MISSING으로 거절한다.
- 새 평가·retryId·승인을 만들지 않고, 비동기 분석 완료를 기다리지 않는다. 반환 요약은 WAITING_ANALYSIS일 수 있다.
- HTTP 200/409/503 매핑·Controller·CSRF 통합 검사는 다음 단계다. 프로세스 중단 및 다중 인스턴스 복구는 여전히 범위 밖이다.

### 서비스 연결 후 실행 결과

기준: `b5e0299` 위 미커밋 작업본(전달 복구 서비스 포함).

- `cd backend && ./gradlew spotlessApply check mariaDbTest --tests '*PaymentFds*MariaDbTests'`: BUILD SUCCESSFUL.
- 일반 테스트 600개(기존 건너뜀 1개), P5 FDS MariaDB 72개, 실패·오류 0. 이 문서의 경합·서비스 클래스는 19개다.
- 원본 보고 동일성, 미전송 보고 복구, 기존 FAILED 재전달, 다른 결제·원본 UUID·수신자 및 완료 기록 제외, 세 진행 상태 제외, 기록 누락 거절을 확인했다.
- P2 호출 시 별도 트랜잭션에서 P5 세 행의 잠금을 획득해 스냅샷 잠금이 해제됐음을 확인했다. P2 실패 후 원본 보존·다음 호출 복구와 서비스의 전용 잠금 사용도 확인했다.
- 문서 링크 검사와 `git diff --check`: 통과.
- 미검증: MariaDB 전체 회귀, 전달 복구 HTTP/CSRF 연결, 실제 프로세스 중단·재시작, 다중 인스턴스 및 잠금 우회 경로. DB 스키마 변경·커밋·PR 생성은 하지 않았다.

## 전달 복구 HTTP 연결 — 2026-10-29

`PaymentFdsDeliveryController`가 `POST /api/payment-operations/{paymentId}/fds-recovery-deliveries`를 서비스에 연결한다. 양수 Long ID만 받고, 본문(공백 포함)·쿼리는 거절한다. 응답은 기존 `PaymentFdsRecoveryResponse`이며 현재 상태를 200으로 반환한다. WAITING_ANALYSIS도 200이며 완료를 뜻하지 않는다. 202를 사용하지 않는다.

`PaymentFdsDeliveryExceptionHandler`는 이 Controller에만 적용한다.

| HTTP | code | 조건 |
| --- | --- | --- |
| 400 | FDS_RECOVERY_DELIVERY_INVALID_REQUEST | ID·본문·쿼리 형식 오류 |
| 404 | PAYMENT_OPERATIONS_NOT_FOUND | 결제 없음 |
| 409 | FDS_RECOVERY_DELIVERY_NOT_ALLOWED | RECOVERY_DATA_MISSING 또는 DELIVERY_RECORD_UNAVAILABLE을 reasonCode로 제공 |
| 503 | FDS_RECOVERY_DELIVERY_UNAVAILABLE | 전용 잠금 경합·DB 장애·트랜잭션 시작/시간 제한 실패·이벤트 실행기 작업 거절 |
| 500 | FDS_RECOVERY_DELIVERY_INTERNAL_ERROR | 계약 불일치 및 예상하지 못한 오류 |

성공·오류 모두 no-store이며 SQL·원문 예외는 숨긴다. 비동기 수신이 나중에 실패하면 HTTP 성공을 소급 변경하지 않고 현재 상태를 GET으로 확인한다.

기존 `SecurityConfig`의 두 POST matcher를 그대로 재사용한다. SECURITY_STAFF만 허용하며 ADMIN·OPERATOR는 거절한다. 전역 `security.csrf.enabled`가 false/true 어느 쪽이든 XSRF-TOKEN 쿠키와 X-XSRF-TOKEN 헤더를 검사한다. 인증/권한/CSRF 오류는 기존 AUTH_001·AUTH_002·CSRF_001을 유지한다. 보안 실패는 Controller·서비스 호출 전 차단하며 다른 경로의 CSRF 정책은 변경하지 않는다.

P1 보안 설정·DB·P2 계약 변경은 없다. HTTP 테스트는 false/true 양쪽에서 권한·CSRF·입력·상태·오류 원문 미노출을 확인하고, MariaDB 테스트는 실제 HTTP부터 P2 보고/Modulith 수신까지 및 전용 잠금 503·전달 기록 누락 409를 연결한다. 아래 실행 결과와 이전 단계의 HTTP 미검증 기록을 구분한다.

### 전체 회귀에서 발견한 테스트 격리 문제

첫 전체 MariaDB 실행은 453개 중 기존 `PaymentRequestFlowMariaDbTests.submit_whenCardDeclines_preservesDeclineWithoutReauthorization` 1개가 실패했다(신규 전달 복구 23개는 통과). 이 클래스의 FdsEvaluator 대역은 항상 evaluationId=123을 반환하지만 승인 결과 기록은 실제 P2 저장소를 사용했다. 공유 테스트 DB에 같은 ID의 평가가 생기면 다른 평가의 승인 결과를 변경해 `승인 결과는 변경할 수 없습니다`가 발생했다.

평가를 대역으로 사용하는 해당 클래스에 한해 FdsAuthorizationResultRecorder 공개 경계도 대역으로 분리했다. 기존 결제 상태·재승인 방지 단언은 유지하고, DECLINED가 한 번만 기록되는 단언을 추가했다. 고정 ID를 더 큰 숫자로 바꾸거나 운영 P2 코드를 완화하지 않는다. 실제 P2 저장 검증은 별도 통합 테스트에서 유지한다.

### HTTP 연결 후 최종 실행 결과

기준: `b5e0299` 위 미커밋 작업본(Controller·오류 매핑·테스트 격리 수정 포함).

- `cd backend && ./gradlew spotlessApply check mariaDbTest`: BUILD SUCCESSFUL.
- 일반 테스트 630개(기존 건너뜀 1개), 전체 MariaDB 453개, 실패·오류 0.
- 전달 복구 HTTP 테스트는 전역 CSRF false/true 각 15개, 총 30개 통과. 전달 복구 경합·서비스·실제 HTTP MariaDB 클래스는 총 23개 통과.
- 실제 HTTP에서 미전송 보고·FAILED 이벤트 복구, 완료 후 재요청, 전용 잠금 경합 503, 전달 기록 누락 409를 확인했다. 최초 전체 실행에서 실패했던 카드 거절 테스트도 통과했다.
- `python3 .github/scripts/check_md_links.py`, `git diff --check`: 통과.
- 미검증: 실제 브라우저 UI·배포 환경, 프로세스 강제 종료·재시작, 다중 인스턴스 및 잠금을 우회하는 다른 재전달 경로. DB 스키마·P1 보안 정책·P2 공개 계약 변경은 없다. 커밋·PR은 아직 생성하지 않았다.

## 직원 운영 절차

1. SECURITY_STAFF 세션으로 운영 상세 GET의 fdsRecovery를 확인한다. WAITING_ANALYSIS일 때 토큰을 포함한 전달 복구 POST를 호출한다.
2. 200의 WAITING_ANALYSIS는 수신 완료가 아니다. 상세 GET으로 확인하고 READY가 된 경우에만 명시적 재평가 POST를 별도로 호출한다. 조회 GET은 복구·재평가를 실행하지 않는다.
3. 409 RECOVERY_DATA_MISSING은 저장 원본 불일치/누락, DELIVERY_RECORD_UNAVAILABLE은 일치하는 복구 가능 전달 기록의 부재/모호함을 뜻한다. 자동 반복을 멈추고 paymentId·보고 ID·발생 시각으로 P5 원본 및 P2 보고 로그를 대조해 담당에게 확인한다. 원본 ERROR·보고 키를 보존하며 UUID 재발급·DB 상태 수동 변경·임의 이벤트 생성으로 우회하지 않는다.
4. 503·500은 처리되지 않았다는 보장이 아니다. 상세 GET 확인 후 권한 있는 직원이 같은 POST를 명시적으로 재요청한다. 다중 인스턴스·프로세스 중단 기록은 이 API의 자동 복구 대상으로 확대하지 않는다.

## PR 최종 점검 — 2026-10-29

이슈 완료 기준을 대조해 ERROR 저장 fixture → P2 보고 → FAILED 전달 복구 → READY → 실제 P2 명시적 재평가 → 두 POST 반복 호출의 통합 테스트를 추가했다. 새 평가 UUID·retryId가 한 번만 생성되고 평가 행이 원본 포함 2개로 유지되는지 확인한다. 운영 절차에 409 사유별 확인·기록 보존·담당 확인 절차를 명시했다.

보완 후 `spotlessApply check mariaDbTest`에서 일반 검사와 #275 MariaDB 24개는 통과했지만, 전체 검사 중 기존 P3 `PaymentHeldListenerTests.on_sameEventTwice_linksPaymentOnce`가 두 번째 publication 완료 대기 30초 초과로 실패했다. 해당 리스너·테스트 코드는 변경하지 않았으며 코드 변경 없이 전체 MariaDB 검사를 재실행한다. 아래 결과와 이 실패 이력을 함께 PR에 남긴다.

최종 재실행 결과: 동일 코드에서 `./gradlew mariaDbTest` BUILD SUCCESSFUL, 전체 454개 실패·오류 0. 보완 후 일반 검사 630개(기존 건너뜀 1개)와 #275 MariaDB 24개도 통과했다. P3 완료 대기 실패는 재실행에서 재현되지 않았으며 원인 해결을 주장하지 않는다. 문서 링크 검사와 diff 공백 검사를 통과했다. 검증 코드를 담은 커밋은 PR 테스트 결과에 기재한다.
