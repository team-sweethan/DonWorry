# #273 FDS 복구 저장 필드·제약 검토

- 기준: `dev e888960`, 2026-10-28. 작업 브랜치: `feature/273-fds-recovery-storage`.
- 범위: 저장 기반 구현. 신규 migration·Entity·Repository 및 저장 테스트를 추가했다. 시각 수집·ERROR 저장·커밋 후 보고를 연결했다. 분석 리스너와 상세 GET fdsRecovery 응답까지 연결했다. 최신 전체 검증 결과는 11절을 따른다. 운영 DB에는 적용하지 않았다.
- 관련: [#273](https://github.com/team-sweethan/DonWorry/issues/273), [기존 저장·API 계약](190-fds-recovery-contract.md), [ADR-0036](../decisions/0036-fds-failure-analysis-and-reevaluation.md).

## 1. 담당 ERD 확인·반영 기록

저장소 정본 목록이 연결한 [P5 담당 Notion ERD](https://www.notion.so/40102d70288a8068af50e5b30fc3efd2)를 직접 조회했다. 반환된 최종 편집 시각은 `2026-10-22T07:24:02.937Z`이며, 본문에는 payments만 있다. 최초 조회 당시 payment_fds_recoveries 및 아래 필드·제약은 반영되어 있지 않았다. 별도 페이지나 댓글의 합의까지 확인한 것은 아니다.

Notion의 “FDS 전용 결제 테이블은 별도로 생성하지 않음”과 신규 복구 테이블의 관계도 반영 시 설명한다. payments가 결제 정본이며 신규 테이블은 결제 복제본이 아닌 오류 복구 기록이라는 기존 합의는 유지한다.

[저장소 ERD](../erd/README.md)는 복구 테이블을 미구현 제안으로 연결한다. 설계 확인 당시 migration에는 해당 테이블이 없었다. 이번 작업에서 신규 migration을 추가했다. 기존 payments·payment_requests의 실제 구조는 CREATE 및 이후 ALTER SQL을 기준으로 확인했다.

AGENTS.md 4절에 따라 마이그레이션 작성 전 담당 페이지에 신규 테이블·필드·제약을 반영하고 소유 담당 확인을 기록해야 한다. 기존 사용자 설계 승인을 취소하거나 P1·P2의 상세 스키마 재승인을 요구하는 뜻은 아니다. 사용자가 담당 ERD 반영과 reason_code 보존 방식을 승인했다. 2026-10-28 아래 필드·제약을 담당 ERD에 추가했으며, 이는 코드·DB 구현이나 P1·P2의 별도 상세 검토 완료를 의미하지 않는다.

## 2. 저장 필드

테이블은 P5 소유 `payment_fds_recoveries` 하나다. UUID는 MariaDB UUID, 시각은 UTC DATETIME(6), 상태는 VARCHAR다. 아래는 기존 합의의 19개 필드를 유지하는 안이며 reason_code는 5절의 사용자 승인 기준을 따른다.

| 필드 | 타입 | NULL | 저장 기준 |
| --- | --- | --- | --- |
| recovery_id | BIGINT AUTO_INCREMENT | 불가 | PK |
| payment_id | BIGINT | 불가 | 대상 P5 결제 |
| original_evaluation_request_id | UUID | 불가 | 최초 ERROR UUID, 불변 |
| original_evaluation_id | BIGINT | 불가 | 최초 P2 평가 ID, 불변 |
| original_error_code | VARCHAR(100) | 불가 | 최초 ERROR 코드, 불변 |
| original_evaluated_at | DATETIME(6) | 불가 | P2 응답의 평가 시각, 불변 |
| evaluation_requested_at | DATETIME(6) | 불가 | 실제 ERROR 응답을 받은 호출 직전 시각 |
| evaluation_responded_at | DATETIME(6) | 불가 | 해당 호출 응답 직후 시각 |
| failure_report_id | UUID | 불가 | 최초 생성 후 재보고에도 유지 |
| analysis_id | BIGINT | 허용 | P2 이벤트의 분석 ID |
| disposition | VARCHAR(40) | 허용 | P2 분석 결과 |
| reason_code | VARCHAR(100) | 허용 | P2 분석 사유 보존, 이후 덮어쓰기 금지 |
| analyzed_at | DATETIME(6) | 허용 | P2 이벤트의 분석 시각 |
| new_evaluation_request_id | UUID | 허용 | #274 최초 접수 시 발급 |
| retry_id | UUID | 허용 | #274 작업 키 |
| requested_by | BIGINT | 허용 | #274 최초 실행 직원 ID |
| requested_at | DATETIME(6) | 허용 | #274 작업 접수 시각 |
| status | VARCHAR(30) | 불가 | 복구 상태 |
| completed_at | DATETIME(6) | 허용 | P5 완료·수동 검토 종료 시각 |

#273에서는 작업 필드 4개를 항상 NULL로 유지한다. 분석 수신 전에는 분석 필드 4개도 NULL이다. 정상 결제와 관측값 없는 과거 ERROR에 행을 소급 생성하지 않는다.

## 3. DB 제약·인덱스

| 종류 | 구현 기준 |
| --- | --- |
| PK | recovery_id |
| UNIQUE 6개 | payment_id, original_evaluation_request_id, failure_report_id, analysis_id, new_evaluation_request_id, retry_id 각각 단독 UNIQUE |
| FK | payment_id → payments.payment_id만 물리 FK. 삭제·갱신 연쇄 없음 |
| 논리 참조 | P2 평가·분석 ID, P1 직원 ID에는 FK 없음 |
| 양수 | payment_id·original_evaluation_id > 0. analysis_id·requested_by는 NULL 또는 > 0 |
| 문자열 | original_error_code는 공백 문자열 불가. reason_code가 있으면 공백 문자열 불가 |
| UUID | 새 UUID는 NULL 또는 원본 UUID와 다름 |
| 분석 묶음 | analysis_id·disposition·reason_code·analyzed_at은 모두 NULL 또는 모두 NOT NULL |
| 작업 묶음 | 새 UUID·retry_id·requested_by·requested_at은 모두 NULL 또는 모두 NOT NULL |
| 상태·분석 enum | status는 아래 5개, disposition은 현재 P2의 5개 값만 허용 |
| 추가 인덱스 | 우선 없음. 결제 단건 조회는 UNIQUE(payment_id) 사용. 배치·상태 검색 도입 시 재검토 |

허용 disposition: REEVALUATION_AVAILABLE, EXISTING_RESULT_AVAILABLE, PAYMENT_FOLLOW_UP_REQUIRED, MANUAL_REVIEW_REQUIRED, EVALUATION_NOT_FOUND.

NULL 가능 필드의 CHECK는 IS NULL / IS NOT NULL을 명시하여 SQL UNKNOWN으로 불완전한 조합이 통과하지 않게 한다. 선택 UNIQUE 컬럼에 여러 NULL 행을 허용하는 동작은 MariaDB 테스트로 확인한다. 업무값을 숨기는 임의 DEFAULT는 두지 않는다.

### 상태별 조합

| 상태 | 분석 4필드 | 작업 4필드 | completed_at |
| --- | --- | --- | --- |
| WAITING_ANALYSIS | 모두 NULL | 모두 NULL | NULL |
| READY | 모두 존재, disposition=REEVALUATION_AVAILABLE | 모두 NULL | NULL |
| REQUESTED | 모두 존재, disposition=REEVALUATION_AVAILABLE | 모두 존재 | NULL |
| COMPLETED | 모두 존재, disposition=REEVALUATION_AVAILABLE | 모두 존재 | NOT NULL |
| MANUAL_REVIEW | 모두 존재 | 모두 NULL 또는 모두 존재 | NOT NULL |

MANUAL_REVIEW에서 작업 필드가 있으면 재평가 접수 후 종료이므로 원래 disposition은 REEVALUATION_AVAILABLE이어야 한다. #273은 비허용 분석 수신 시에만 작업 필드 없는 MANUAL_REVIEW로 전환한다. #274의 안전한 진행 불가 종료 조건은 그 이슈에서 구현한다.

여러 테이블의 현재 평가·결제 상태 일치는 SQL CHECK가 아닌 서비스의 잠금 후 검증으로 보장한다. 원본·분석·작업 키의 불변성, 이벤트 내용 대조, 종료 상태 역행 방지도 Entity/서비스와 테스트에서 보장한다. 시계 보정이나 비동기 수신을 고려해 서로 다른 관측 시각에 일괄적인 DB 대소관계 제약을 추가하지 않는다.

## 4. 입력 재구성과 저장 경계

- payment_requests가 있는 HTTP 결제 흐름만 대상으로 한다. 최초 ERROR에서 originalEvaluationRequestId=evaluationRequestId, evaluationAttemptNo=1이다. 결제 attempt_no와 혼동하지 않는다.
- paymentId·merchantId·customerRef·amount·orderRef·paymentRequestedAt은 기존 불변 payments 값에서, requestId·idempotencyKey는 payment_requests에서 읽는다. paymentRequestedAt은 현재 P2 입력과 같은 payments.created_at이다.
- #273 최초 보고 스냅샷은 paymentStatus=PENDING, processingStage=NEEDS_REVIEW를 기준으로 저장 직후 확인한다. 전달 복구 시에도 현재 상태를 무조건 최초 보고 내용으로 대체하지 않는다.
- 현재 P5가 전달하지 않는 deviceId·cardToken·ipCountryCode, 확보하지 못한 sanitizedErrorMessage·correlationId는 NULL이다. 추가 원문 컬럼은 만들지 않는다.
- ERROR 응답을 반환한 실제 호출의 요청·응답 시각을 수집한다. 내부 재시도 횟수를 evaluationAttemptNo로 쓰지 않으며, 중복 응답으로 최초 저장 시각을 덮어쓰지 않는다.
- ERROR 평가 사본·NEEDS_REVIEW·복구 행을 같은 P5 트랜잭션으로 저장한다. 저장 실패는 함께 롤백한다. 커밋 후 고정 키로 보고하며 보고 실패는 이미 커밋한 ERROR를 되돌리지 않는다.
- 이벤트 수신에서 failureReportId·paymentId·원본/현재 UUID를 대조한다. 같은 analysisId와 같은 내용을 재수신하면 무시하고, 충돌은 덮어쓰지 않고 실패 처리한다. 시각은 저장 정밀도(마이크로초)에 맞춰 비교한다.
- 기존 recordEvaluation의 불변성 검사는 유지한다. 현재 평가 교체 메서드는 #274에서 구현한다.

## 5. reason_code 보존 — 사용자 승인

기존 계약은 reason_code를 분석 사유 보존과 재평가 종료 사유에 함께 사용한다. 재평가 ERROR로 덮어쓰면 지연된 동일 분석 이벤트의 내용 비교 근거가 사라진다.

사용자 승인 기준: DB reason_code는 P2 분석 사유로 고정한다. API reasonCode는 별도 표현으로 취급하여 정상 완료이면 NULL, 재평가 ERROR이면 현재 평가의 errorCode, 분석상 불가이면 보존한 분석 사유를 반환한다. #273에서는 분석 사유만 저장하므로 기존 API와의 실행 충돌은 없다.

별도 수동 종료 사유까지 영속 보존할 필요가 있는지는 #274 전에 결정한다. 필요하면 전용 컬럼과 새 migration을 추가한다. 이 보존 원칙은 기존 저장·API 계약 및 ADR-0036에도 반영한다. 별도 종료 사유 컬럼은 이번에 추가하지 않는다.

## 6. 다음 단계와 검증

1. 담당 ERD와 저장소 문서 반영 후 다음 구현을 진행한다.
2. newMigration으로 신규 테이블 migration을 생성한 뒤 Entity·Repository를 구현한다.
3. ERROR 저장 원자성, 결제당 한 행, 중복 이벤트·충돌·지연 이벤트, NULL 조합·고유 제약을 단위 및 MariaDB 테스트로 검증한다.

초기 설계 단계에서는 문서 상대 링크와 git diff --check만 실행했다. 이후 저장 기반 구현의 검사 결과는 아래에 구분해서 기록한다. 운영 DB에는 적용하지 않았다.

## 7. 저장 기반 구현

- [migration](../../backend/src/main/resources/db/migration/V20261028_155021__create_payment_fds_recoveries_table.sql): 19개 필드, 6개 UNIQUE, P5 결제 FK, 분석·작업 묶음 및 상태별 CHECK. 기존 migration 수정 없음.
- [Entity](../../backend/src/main/java/com/donworry/payment/entity/PaymentFdsRecovery.java): ERROR 원본 생성, 마이크로초 정밀도, 분석 계보 대조·중복 수신·충돌 차단. reason_code 보존. #274 실행 전이 메서드는 아직 없음.
- [Repository](../../backend/src/main/java/com/donworry/payment/repository/PaymentFdsRecoveryRepository.java): 결제별 조회와 PESSIMISTIC_WRITE 조회. 호출 서비스의 payments → payment_requests → recovery 잠금 순서는 후속 연결에서 지킨다.
- 테스트: Entity 단위 테스트와 실제 MariaDB 저장·NULL 고유 키·6개 고유 제약·FK·상태 CHECK·롤백 검사.

이 단계는 저장 기반만 구현한다. ERROR 저장과 복구 행의 업무 원자성, 실제 보고·분석 리스너, 운영 상세 GET 및 HTTP 전체 흐름은 후속 구현·검증 대상이다.

### 실행 결과 — 2026-10-28

기준: `e888960` 위 #273 미커밋 작업본. 테스트 결과를 기준 커밋 자체의 결과로 해석하지 않는다.

- `cd backend && ./gradlew spotlessApply check mariaDbTest`: 통과. 일반 테스트 551개(1개 skipped), MariaDB 368개, 실패·오류 0.
- 이후 선택 고유 키·종료 상태 테스트 추가 및 SQL 줄바꿈 정리 후 `./gradlew spotlessApply check mariaDbTest --tests '*PaymentFdsRecoveryMariaDbTests'`: 통과. 최종 #273 MariaDB 테스트 4개 통과, 전체 check 재통과.
- `python3 .github/scripts/check_md_links.py`, `git diff --check`: 통과.
- 미검증: 운영 DB 적용, 보고 호출·분석 리스너·GET의 실제 업무 연결, #274 재평가 실행, #275 전달 복구. 이번 구현 범위 밖이다.

## 8. 실제 호출 관측과 ERROR 저장·보고 연결

- PaymentEvaluationService.evaluateObserved에서 FdsEvaluator.evaluate 호출 직전·반환 직후 시각을 수집한다. P2 evaluatedAt이나 결제 createdAt을 대신 쓰지 않는다. 응답 검증 실패·호출 예외는 복구 보고로 변환하지 않는다.
- PaymentRequestFlowService의 기존 경합 재시도는 유지하며 성공적으로 반환한 호출의 관측값만 저장한다. HTTP 재전송이나 내부 경합 재시도는 evaluationAttemptNo를 증가시키지 않는다.
- PaymentRequestProcessingService.saveEvaluation은 ERROR 사본·NEEDS_REVIEW·복구 행을 REQUIRES_NEW에서 함께 커밋한다. 최초 RECEIVED 전이에서만 보고 DTO를 반환한다. 동시 중복 응답·이미 처리된 요청은 원본 시각·키를 바꾸거나 보고를 재실행하지 않는다. 과거 ERROR를 자동 편입하지 않는다.
- 저장 메서드의 커밋 성공 후 트랜잭션 없는 흐름에서 FdsFailureReporter.report를 호출한다. 보고 예외는 결제 결과로 전파하지 않으며 WAITING_ANALYSIS와 같은 키를 유지한다. 로그에는 결제 ID·보고 UUID·예외 타입만 남긴다.
- P2 정상 응답과 호출 실패에서는 복구 행·보고가 없다. 분석 이벤트 수신을 아직 연결하지 않았으므로 보고 성공만으로 READY가 되지 않는다. 프로세스 중단·보고 실패·이벤트 미수신 복구는 #275 범위다.
- 검증 대상: 실제 호출 전후 시각, 보고에서 커밋된 ERROR·복구 행 조회, 보고 실패 후 동일 키 유지, 복구 저장 실패 시 평가 롤백, 동시 ERROR에서 보고 한 번, 정상·응답 미수신 제외.

7절은 저장 기반 단계의 실행 기록이다. 이번 연결 단계의 결과는 별도로 기록한다.

### 연결 단계 실행 결과 — 2026-10-28

기준: `e888960` 위 #273 미커밋 작업본.

- `./gradlew spotlessApply check mariaDbTest --tests '*PaymentRequestFlowMariaDbTests'`: 통과.
- 최종 `./gradlew spotlessApply check mariaDbTest`: 통과. 일반 테스트 552개(skipped 1개), MariaDB 372개, 실패·오류 0.
- 문서 링크 검사와 `git diff --check`: 통과.
- 미검증: 실제 P2 분석 이벤트를 P5가 수신하는 전체 흐름, 운영 DB 적용, 재평가·전달 복구 API. 분석 리스너는 다음 구현 단계다.

## 9. 분석 이벤트 리스너 연결

- FdsEvaluationFailureAnalyzedListener가 @ApplicationModuleListener로 P2 커밋 후 이벤트를 받는다. PaymentFdsAnalysisService는 리스너 트랜잭션에 참여한다.
- payments → payment_requests → payment_fds_recoveries 순으로 쓰기 잠금을 잡고 원본 식별자와 분석 내용을 대조한다. 없는 결제·요청·복구 행은 생성하지 않고 실패 처리한다. P2 Mock 전용 가상 결제도 실제 P5 복구 행이 없으면 반영하지 않는다.
- REEVALUATION_AVAILABLE은 READY, 나머지 분석 결과는 MANUAL_REVIEW로 전환한다. 결제 상태·현재 평가·승인·새 UUID·retryId는 변경하지 않는다.
- 같은 분석의 재수신은 무시하고 충돌은 예외로 롤백한다. 예외를 삼키지 않아 실패한 publication이 완료 처리되지 않으며, 기존 publication 재전달로 복구할 수 있다. 운영 전달 복구 POST는 #275에서 구현한다.
- 지연된 동일 분석은 REQUESTED·COMPLETED·MANUAL_REVIEW의 실행 키·최초 직원·완료 시각·분석 사유를 보존한다.
- 검증은 실제 P2 보고 API → Modulith → P5 저장, 발행 롤백, 수신 실패·선택 재전달, 상태 매핑·중복·계보 충돌·지연 이벤트를 포함한다.

8절의 리스너 미연결·WAITING_ANALYSIS 유지 설명은 이전 단계 기록이다. 이번 단계부터 분석 수신 성공 시 READY 또는 MANUAL_REVIEW가 된다. 리스너 연결 전에 P2가 발행한 이벤트에 P5 publication이 없었다면 이번 추가로 과거 이벤트가 자동 생성·재수신되지는 않는다. 운영 상세 fdsRecovery 추가와 #274·#275 API는 아직 구현하지 않았다.

### 리스너 검증 중 보완한 테스트 격리

실제 P2 분석 ID와 가짜 분석 ID의 충돌을 피하도록 테스트용 ID 범위를 분리했다. JDBC 테스트 시각은 저장된 UTC 입력에서 복사한다. 전용 리스너 테스트 컨텍스트는 클래스 종료 시 닫아 전체 검사에서 연결 풀이 누적되지 않게 한다. 운영 조회 테스트는 공유 DB에 논리 참조로 먼저 존재하는 가맹점 ID를 피해서 계정을 만들며 기존 조회 건수 검증은 유지한다. 업무 코드의 입력·권한·고유 제약을 완화하지 않았다.

### 리스너 연결 최종 실행 결과 — 2026-10-28

기준: `e888960` 위 #273 미커밋 작업본. 초기 테스트 데이터 충돌·시각 차이와 연결 풀 누적 실패를 보완한 뒤 최종 재실행한 결과다.

- `cd backend && ./gradlew spotlessApply check mariaDbTest`: 통과. 일반 테스트 554개(skipped 1개), MariaDB 385개, 실패·오류 0.
- 분석 리스너 MariaDB 테스트 13개 통과: 실제 P2 보고, 모든 disposition, 발행 롤백, 실패 재전달, 계보 충돌, 지연·중복 이벤트.
- 문서 링크 검사·`git diff --check`: 통과.
- 미검증·후속: 운영 DB 적용, 상세 GET fdsRecovery 응답, #274 재평가 API, #275 전달 복구 API.

## 10. 운영 상세 GET 복구 요약

- PaymentOperationDetailResponse에 nullable fdsRecovery를 추가했다. 결제·요청·복구 행은 기존 읽기 전용 REPEATABLE_READ 트랜잭션에서 함께 조회한다.
- PaymentFdsRecoveryResponse는 계약의 7개 필드만 제공한다. 복구 기록이 없으면 명시적인 null이며 과거 ERROR로 새 기록을 생성하지 않는다.
- 새 평가 UUID와 현재 평가 UUID가 일치할 때만 재평가 결과를 표시한다. 원본 ERROR는 재평가 결과로 노출하지 않는다.
- API reasonCode는 COMPLETED이면 null, 일치하는 재평가 ERROR이면 현재 errorCode, 그 외에는 저장된 P2 분석 사유다. WAITING_ANALYSIS는 null이다. DB reason_code는 그대로 보존한다.
- 조회는 보고·재평가·상태 변경을 실행하지 않는다. 목록 응답, SECURITY_STAFF/ADMIN 조회 권한, no-store 정책은 유지한다.
- #274 실행 경로가 없는 REQUESTED·종료 작업은 HTTP 테스트에서 DB 제약을 지키는 fixture로 구성해 응답만 검증한다. 이것은 재평가 API 구현·검증을 뜻하지 않는다.

9절의 상세 GET 미구현 표시는 이전 단계 기록이다. #274 재평가 API와 #275 전달 복구 API는 후속 작업이다.

### 상세 GET 검증 결과 — 2026-10-28

기준: `e888960` 위 #273 미커밋 작업본. 최초 실행에서 종료 상태 fixture의 처리 단계·점수가 기존 DB CHECK와 어긋난 부분을 보완한 뒤 재실행했다.

- `cd backend && ./gradlew spotlessApply check mariaDbTest --tests '*PaymentOperationsHttpMariaDbTests'`: 통과. 운영 HTTP MariaDB 34개(이번 복구 응답 9개 포함), 실패·오류 0.
- 기존 인증·인가·no-store 검사와 함께 복구 부재, 모든 복구 상태, 최초 ERROR 제외, 새 UUID 결과·사유, 반복 조회의 저장값·이벤트 불변을 검증했다.
- 문서 링크 검사·`git diff --check`: 통과.
- 미검증: 이번 GET 변경 후 MariaDB 전체 회귀 검사는 재실행하지 않았다. 운영 DB 적용, #274·#275 실행 API는 이번 범위 밖이다.
- 추가 담당 합의: 이번 GET은 기존 §5 계약을 적용하며 새로운 외부 계약 결정은 없다.

## 11. #273 전체 최종 점검 — 2026-10-28

대상: `feature/273-fds-recovery-storage`, 기준 커밋 `e888960` 위 미커밋 변경 전체(신규 파일 포함). GitHub #273의 할 일·완료 기준을 현재 코드와 대조했다. 기준 커밋 자체나 최신 원격 dev의 검증 결과를 뜻하지 않는다.

| 완료 기준 | 구현·검증 근거 |
| --- | --- |
| 실제 ERROR만 저장·보고 | 최초 RECEIVED → ERROR 전이에서 평가·NEEDS_REVIEW·복구 행을 함께 저장한다. 정상 결과·호출 예외는 복구 행/보고가 없으며 호출 직전·직후 시각을 보존한다. |
| 원본·보고 키 보존 | recordEvaluation 불변성 검사를 유지한다. 동일 요청·동시 ERROR는 복구 한 행과 최초 보고 한 번으로 수렴한다. 보고 예외는 이미 커밋된 ERROR를 되돌리지 않는다. |
| 저장 제약 | 19개 필드·6개 UNIQUE·P5 payments FK·분석/작업 NULL 묶음·상태 CHECK를 migration, Entity, ERD와 대조했다. P2·P1은 논리 참조다. |
| 분석 계보·멱등 | 보고 키·결제 ID·원본/현재 평가 UUID를 대조한다. 같은 분석은 무시하고 충돌은 거부한다. 지연 이벤트가 작업·분석 사유·종료 상태를 덮어쓰지 않는다. |
| 커밋 후 수신·재전달 | 실제 P2 보고 API부터 P5 분석 반영을 검증한다. 발행 롤백·수신 실패·동일 publication 재전달을 검사한다. |
| READY/MANUAL_REVIEW 조회 | 상태 매핑과 7개 fdsRecovery 응답, 복구 부재 null, 새 UUID 결과만 표시, 권한·no-store·기존 상세 필드 유지, 반복 GET의 저장값/이벤트 불변을 검사한다. |
| 자동 실행 금지 | 분석 수신은 복구 행만 변경하며 새 평가·승인을 호출하지 않는다. GET도 보고·평가·상태 변경을 실행하지 않는다. |

최종 점검에서 추가 코드 수정이 필요한 결함은 발견하지 못했다. 문서 요약의 상세 GET 미구현 표시 등 단계별로 남은 오래된 설명을 현 구현과 맞췄으며 과거 검사 기록은 보존했다.

### 전체 회귀 검사 결과

- `cd backend && ./gradlew spotlessApply check mariaDbTest`: **BUILD SUCCESSFUL**, 3분 50초.
- MariaDB 전체 **394개**, skipped 0, 실패·오류 0. 신규 migration을 포함한 Flyway/JPA 검사 및 기존 모듈 회귀 검사를 실행했다.
- 일반 검사 `check`는 동일 소스의 Gradle UP-TO-DATE 결과를 재사용했다. 해당 XML 결과는 총 554개, skipped 1, 실패·오류 0이다. 기존 skipped 항목은 LocalSettlementDemoTests의 로컬 샘플 결제 검사다.
- `python3 .github/scripts/check_md_links.py`, `git diff --check`: 통과.
- 이번 점검에서는 문서 상태와 최종 검증 기록을 갱신했다. 추가 Java·SQL 수정은 없으며 커밋·PR 생성·운영 DB 적용은 하지 않았다.

### 미검증·후속 및 담당 확인

- 운영 DB 배포·실제 운영 환경 검증은 미실행이다.
- #274 명시적 재평가와 #275 전달 복구 POST(권한·CSRF 포함), 기존 UUID 결과 복원 설계는 후속 범위다.
- #273 범위에서 새로 필요한 P1·P2 계약 합의는 발견하지 못했다. #274의 별도 수동 종료 사유 영속화와 실행 시 잠금 대기 설정 등 남은 결정은 기존 후속 계약을 따른다.
- GitHub 체크리스트·종료 상태는 변경하지 않았다. 이 결과는 작업 브랜치의 구현·검증 결과이며 병합·배포 완료를 뜻하지 않는다.
