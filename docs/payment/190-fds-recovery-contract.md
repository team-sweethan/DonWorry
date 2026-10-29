# #190 P5 FDS 오류 복구 저장·API 계약

- 상태: **저장·API 설계안 사용자 승인, 문서화 단계. 코드·DB 미변경, 구현·통합 검증 전.**
- 기준: `dev 72cc5c8`, 2026-10-28. 관련: [#190](https://github.com/team-sweethan/DonWorry/issues/190), [#218](https://github.com/team-sweethan/DonWorry/issues/218), [ADR-0036](../decisions/0036-fds-failure-analysis-and-reevaluation.md).
- 근거: 사용자가 제시된 단순 설계안으로 진행하고 문서에 정리하도록 요청했다. 기존 정책·실행 방식의 P1·P2 동의와 이번 저장·HTTP 상세 설계의 사용자 승인을 구분한다. 상세 스키마까지 P1·P2가 확인했다고 표현하지 않는다.
- 추가 승인: 사용자가 202 제외·직원 전달 복구 POST 추가·두 POST의 선택적 CSRF 강제 적용안을 선택했다. 이번 변경에 대한 P1·P2·공통 이벤트 담당의 별도 확인까지 받은 것으로 기록하지 않는다.
- 이번 변경은 설계 기록이다. migration·Entity·API·보안 설정·DB를 변경하거나 기능을 활성화하지 않는다. Notion 담당 ERD 반영·소유 담당 확인과 후속 구현 이슈 연결은 구현 전에 진행한다.

## 1. 범위와 현재 코드

대상은 P2가 실제 ERROR를 반환하고 P5가 저장한 결제다. 최초 ERROR당 명시적 재탐지 1회, 재탐지도 ERROR이면 추가 실행 없이 수동검토한다. 타임아웃·통신 오류로 최초 응답을 받지 못한 경우와 기존 UUID 결과 복원은 #190의 별도 설계 범위다. 이 문서의 기술 실패 재개는 이미 접수한 동일 재탐지 작업을 이어가는 것이며 새 평가 횟수를 늘리지 않는다.

현재 구현 근거:

- [PaymentRequest](../../backend/src/main/java/com/donworry/payment/entity/PaymentRequest.java): UUID가 고정되고 `recordEvaluation`은 RECEIVED 이후 결과 교체를 거절한다.
- [PaymentRequestProcessingService](../../backend/src/main/java/com/donworry/payment/service/PaymentRequestProcessingService.java): ERROR는 NEEDS_REVIEW이며 기존 승인 경로에서 건너뛴다.
- [FdsReevaluationService](../../backend/src/main/java/com/donworry/fds/service/FdsReevaluationService.java): 같은 앱의 동기 Java 호출·기본 REQUIRED 트랜잭션, 원본 ERROR/분석/입력 대조와 retryId 재전송 검사를 제공한다.
- [PaymentAuthorizationService](../../backend/src/main/java/com/donworry/payment/service/PaymentAuthorizationService.java): `authorizeInTransaction`은 MANDATORY이며 모의 승인·상태·이벤트 등록을 호출자 트랜잭션에 포함한다.
- [현재 요청 스키마](../../backend/src/main/resources/db/migration/V20261025_210956__create_payment_requests_table.sql): 단계별 평가 필드 CHECK와 결제·평가 UUID UNIQUE가 있다.
- [운영 조회 계약](188-payment-operations-api.md): 현재 GET 상세는 평가 사본·요청 단계·결제 상태를 조합해 분류한다.

## 2. 저장 책임 — 기존 2개와 신규 1개

| 저장 위치 | 설계 |
| --- | --- |
| payments | 기존 결제 상태·승인 참조의 정본. 이번 설계로 컬럼을 추가하지 않는다. |
| payment_requests | 현재 적용할 평가 UUID·평가 결과·요청 단계. 재탐지 적용을 위한 제한된 전이를 추가할 계획이다. |
| payment_fds_recoveries | P5 소유 신규 테이블 제안. 원본 ERROR·보고 키·분석 결과·직원 재탐지 작업을 한 행에 보존한다. |

정상 결제에는 복구 행을 만들지 않는다. 기존 평가 저장 메서드의 불변성 검사를 풀지 않고, 해당 복구 작업과 새 UUID가 일치할 때만 적용하는 별도 전이를 둔다. 새 결과 적용 전에 원본 ERROR가 복구 행에 보존되어 있어야 한다.

### 신규 테이블의 필드

아래는 구현 전 검토할 논리 스키마다. PK는 BIGINT AUTO_INCREMENT, UUID는 MariaDB UUID, 시각은 UTC DATETIME(6), 상태는 VARCHAR로 저장한다. 물리 삭제·삭제 연쇄는 사용하지 않는다.

| 필드 | 타입·NULL | 의미 |
| --- | --- | --- |
| recovery_id | BIGINT PK | 복구 기록 ID |
| payment_id | BIGINT NOT NULL | P5 결제 FK, 결제당 복구 행 1개 |
| original_evaluation_request_id | UUID NOT NULL | 최초 ERROR UUID, 이후 변경 금지 |
| original_evaluation_id | BIGINT NOT NULL | 최초 P2 ERROR 평가 ID, 논리 참조 |
| original_error_code | VARCHAR(100) NOT NULL | 최초 ERROR 코드 |
| original_evaluated_at | DATETIME(6) NOT NULL | 최초 P2 평가 완료 시각 |
| evaluation_requested_at | DATETIME(6) NOT NULL | 최초 ERROR 응답을 받은 호출의 실제 호출 시각 |
| evaluation_responded_at | DATETIME(6) NOT NULL | 해당 ERROR 응답의 실제 수신 시각 |
| failure_report_id | UUID NOT NULL | 동일 오류 보고 재전송에 유지하는 키 |
| analysis_id | BIGINT NULL | 수신한 P2 분석 ID, 논리 참조 |
| disposition | VARCHAR(40) NULL | P2 분석 결과 |
| reason_code | VARCHAR(100) NULL | P2 분석 사유 코드, 수신 후 보존·덮어쓰기 금지 |
| analyzed_at | DATETIME(6) NULL | P2 분석 완료 시각 |
| new_evaluation_request_id | UUID NULL | 직원 접수 시 발급, 이후 변경 금지 |
| retry_id | UUID NULL | 재탐지 작업 키, 이후 변경 금지 |
| requested_by | BIGINT NULL | 최초 접수 직원 ID, P1 논리 참조 |
| requested_at | DATETIME(6) NULL | 직원 작업 접수 시각. FDS 호출 시각과 구분 |
| status | VARCHAR(30) NOT NULL | 아래 5개 상태 |
| completed_at | DATETIME(6) NULL | 복구 처리가 완료되거나 수동검토로 종료된 P5 시각 |

제약·보존 규칙:

- `UNIQUE(payment_id)`, `UNIQUE(original_evaluation_request_id)`, `UNIQUE(failure_report_id)`, `UNIQUE(analysis_id)`, `UNIQUE(new_evaluation_request_id)`, `UNIQUE(retry_id)`. 선택 필드는 미접수 시 NULL이다.
- payment_id만 같은 P5 소유 payments로 FK를 둔다. 평가·분석·직원 ID에는 다른 담당 테이블로 FK를 걸지 않는다.
- ID 양수·원본 오류 코드 비어 있지 않음·새 UUID와 원본 UUID 다름·상태 허용값을 검사한다. 분석 4필드는 모두 NULL 또는 모두 존재한다. 작업 키 2개·직원·접수 시각도 함께 존재하거나 함께 비어 있어야 한다.
- 원본 ERROR 결과는 항상 ERROR·점수 NULL이므로 중복 컬럼으로 저장하지 않는다. 최초 보고 횟수는 1, 재탐지는 2로 계산하며 결제 attempt_no를 재사용하지 않는다.
- 결제·주문·고객·금액·결제 요청 시각은 기존 불변 데이터를 사용한다. 원본을 재구성할 수 없는 과거 행은 실행하지 않는다. 당시 없었던 관측 시각을 created_at/evaluated_at으로 채우지 않는다.
- 현재 P5가 보내지 않는 deviceId/cardToken/ipCountryCode는 NULL 입력을 유지한다. 이후 최초 평가에 추가하면 최초 입력 보존·오류 보고·재탐지 계약도 함께 변경해야 한다.
- sanitizedErrorMessage·correlationId는 실제 확보한 값이 없으면 NULL로 보고한다. 임의 값이나 원본 예외를 만들지 않는다.
- 같은 ERROR의 동시 저장·재보고가 원본 관측값을 덮어쓰지 않도록 최초 저장값을 유지한다.

## 3. 상태와 중복 요청

| 상태 | 조건·의미 | 재탐지 POST 처리 |
| --- | --- | --- |
| WAITING_ANALYSIS | 원본 ERROR·보고 키 저장, 분석·작업·완료 시각 없음 | 409, 분석 대기 |
| READY | 일치하는 REEVALUATION_AVAILABLE 분석 존재, 작업·완료 시각 없음 | 최초 작업 접수 |
| REQUESTED | 분석과 작업 키·최초 직원·접수 시각 존재, 완료 시각 없음 | 동일 순서로 잠금 대기 후 기존 결과 반환 또는 같은 작업 재개. 대기 초과·데드락은 503 |
| COMPLETED | 정상 재탐지 결과 및 기존 승인/거절 단계 반영 완료, 작업·완료 시각 존재 | 200, 기존 결과 반환 |
| MANUAL_REVIEW | 분석상 실행 불가, 재탐지 ERROR 또는 안전한 진행 불가로 종료. 완료 시각 존재 | 재탐지 ERROR 결과가 반영된 기존 작업이면 200, 미실행/결과 미반영이면 409 |

분석상 거절로 MANUAL_REVIEW가 되면 작업 필드는 NULL이다. 실행 접수 후 상태 불일치로 종료하면 작업 식별자는 보존한다. 어떤 경우에도 종료 행을 READY로 되돌리거나 새 작업 키를 발급하지 않는다.

COMPLETED는 결제 성공이 아니라 재탐지 후 초기 결제 처리 완료다. ALLOW여도 매입 완료 전에는 PENDING일 수 있고, REVIEW의 모의 승인 거절은 DECLINED일 수 있다. 기존 PaymentRules를 사용하고 이번 설계로 결과별 금융 규칙을 바꾸지 않는다.

이벤트 수신은 failureReportId·paymentId·원본/현재 평가 UUID를 원본 ERROR와 대조한다. 같은 analysisId·같은 내용은 무시하고, 식별자·내용 충돌은 조용히 덮어쓰지 않는다. 지연 이벤트는 REQUESTED·종료 상태를 초기화하지 않는다. REEVALUATION_AVAILABLE 외 분석은 MANUAL_REVIEW로 기록하며 EXISTING_RESULT_AVAILABLE도 이번 흐름에서 임의 복원하지 않는다.

## 4. 트랜잭션과 재개

### A. ERROR 저장·보고

실제 FDS 호출 직전·응답 직후 시각을 관측하고, ERROR 스냅샷·NEEDS_REVIEW·복구 행을 같은 P5 트랜잭션으로 저장한다. 복구 행은 기존 payment_requests가 있는 HTTP 결제 흐름을 대상으로 한다. 요청 행 또는 필수 관측값이 없는 과거·별도 내부 경로는 추정해서 편입하지 않는다.

커밋 후 보존된 입력·failureReportId로 P2 오류 보고를 호출한다. 보고 실패가 앞선 ERROR 저장을 되돌리지 않는다. 실패 시 WAITING_ANALYSIS와 같은 키를 유지한다. P2가 보고를 이미 받았더라도 분석 이벤트 수신 완료까지는 READY로 간주하지 않는다.

보고 누락·재시작 복구는 아래 전달 복구 POST로 명시적으로 실행한다. 같은 failureReportId·원본 값으로만 재전달하며, GET이나 재탐지 POST에서 암묵적으로 재전달하지 않는다. 자동 배치·큐를 추가하지 않고 직원 실행으로 제한한다.

### B. 분석 수신

커밋 후 이벤트 리스너에서 분석 결과를 저장하고 READY 또는 MANUAL_REVIEW로 전환한다. 재탐지를 호출하지 않는다. 수신 트랜잭션이 실패하면 수신 완료로 기록하지 않는다. 별도 범용 이벤트 수신 테이블 대신 복구 행의 분석 ID·내용으로 멱등성을 검사한다.

### C. 명시적 접수 — 짧은 트랜잭션

권한·CSRF 검사 후 기존 P5의 PESSIMISTIC_WRITE를 사용해 payments → payment_requests → 복구 행 순으로 잠근다. 접수·실행 및 복구 상태를 갱신하는 경로는 같은 순서를 지키며 잠근 뒤 최신 상태를 다시 읽는다. 조회 시점의 상태만으로 승인하지 않는다.

두 직원이 동시에 요청하면 뒤 요청은 유한 시간 동안 기다린다. 앞 요청이 완료되면 기존 결과를 반환하고, 롤백됐다면 같은 작업 키로 재개한다. 잠금 대기 초과·데드락은 해당 트랜잭션 롤백 후 503으로 응답한다. 새 작업 생성이나 무제한 자동 재시도를 하지 않는다. 별도 실행 중 플래그·리스·NOWAIT 판별·202 응답은 도입하지 않는다.

유한 잠금 대기 설정의 정확한 적용 지점·값과 예외 매핑은 구현 시 MariaDB로 검증한다. 연결 풀에 설정이 누출되지 않게 하고 기존 주문 잠금을 포함한 관련 쓰기 경로의 순서도 확인한다. 이 방식을 실제 외부 카드사 호출 동안 긴 DB 잠금을 유지하는 방식으로 확대하지 않는다.

1. 기존 REQUESTED·종료 작업을 먼저 확인한다. 재요청 직원 권한도 매번 검사하며, 최초 requested_by/requested_at을 덮어쓰지 않는다.
2. 신규 접수는 READY + PENDING + 승인 참조 없음 + NEEDS_REVIEW + 현재 평가와 원본 ERROR 일치 조건을 확인한다.
3. 새 UUID·retryId·직원·접수 시각을 저장하고 REQUESTED로 커밋한다. UNIQUE와 잠금으로 서로 다른 직원의 동시 요청도 같은 작업으로 수렴시킨다.

### D. 동일 작업 실행 — 별도 트랜잭션

같은 잠금 순서로 작업을 확인하고 P2 공개 재탐지 API를 동기 호출한다. P2 평가 저장 → 응답 UUID·결과 검증 → P5 현재 평가 적용 → 모의 승인·후속 이벤트 등록 → 작업 종료를 같은 트랜잭션으로 묶는 설계다.

- 현재 P2 REQUIRED와 P5 `authorizeInTransaction` MANDATORY를 사용한다. 기존 REQUIRES_NEW 승인 진입점을 잘못 호출해 부분 커밋하지 않는다.
- ALLOW/REVIEW/DENY: 원본 보존을 확인한 별도 적용 메서드로 현재 평가를 새 결과로 바꾸고 EVALUATED → 기존 승인 처리 → AUTHORIZED와 COMPLETED를 함께 저장한다.
- ERROR: 현재 평가를 새 ERROR로 바꾸고 NEEDS_REVIEW·MANUAL_REVIEW를 함께 저장한다. 새 복구 행·보고·추가 재탐지를 자동 생성하지 않는다.
- 실행 전 현재 결제·평가가 달라졌으면 실행하지 않고 수동 확인 대상으로 기록한다. 승인된 결제를 PENDING으로 되돌리지 않는다.
- 기술적 예외이면 D 전체를 롤백하고 C에서 커밋한 REQUESTED·작업 키를 유지한다. 동일 POST가 같은 작업을 재개한다. 정상 FDS ERROR 결과는 기술적 예외와 구분한다.
- 실행 후 HTTP 응답만 유실되면 다음 POST는 종료된 작업을 반환한다. P2와 P5의 부분 커밋이 없다는 전제는 MariaDB 통합 테스트로 검증해야 한다.
- P3/P6의 커밋 후 리스너와 최종 매입 완료는 이 트랜잭션 완료와 별개다. 해당 모듈 완료까지 보장하지 않는다.
- 이 단순화는 현재 같은 DB의 P2와 모의 승인 기준이다. 실제 외부 카드사 호출로 바뀌면 같은 DB 롤백으로 외부 효과를 되돌릴 수 없으므로 실행·결과 불명 계약을 다시 설계한다.

## 5. HTTP 요청·응답

`POST /api/payment-operations/{paymentId}/fds-reevaluations`

- paymentId는 양수 Long. 본문·쿼리 파라미터는 허용하지 않는다. 클라이언트 멱등키는 요구하지 않고 서버가 원본 ERROR 기준으로 작업을 식별한다.
- SECURITY_STAFF 세션·CSRF 검사 필수. ADMIN·OPERATOR 실행 불허. 기존 GET의 SECURITY_STAFF/ADMIN 조회 권한은 유지한다.
- 역할 검사는 입력·존재 확인보다 먼저 수행한다. 비로그인·권한·CSRF 실패 시 업무 데이터를 조회·변경하지 않는다. 복수 보안 실패의 응답 우선순위는 P1 보안 체인 계약에 맞춘다.
- 성공·실패 모두 Cache-Control: no-store. 신규 권한·CSRF 구현은 POST와 함께 연결해야 하며 기본 CSRF 비활성 설정에 의존하지 않는다.

재탐지 완료와 전달 복구의 현재 상태 응답은 같은 DTO로 제공한다. 아래 이름은 후속 구현 기준의 설계명이다.

| 필드 | 타입 | 의미 |
| --- | --- | --- |
| paymentId | Long | 대상 결제 |
| recoveryId | Long | 복구 기록 |
| status | String | 복구 상태 |
| evaluationResult | String 또는 null | 새 UUID와 일치하는 재탐지 결과만 표시. 반영 전 null |
| paymentStatus | String | 응답 시 조회한 실제 결제 상태 |
| reasonCode | String 또는 null | 분석·수동검토 사유. 완료 시 null, 재탐지 ERROR이면 새 errorCode |
| completedAt | Instant 또는 null | P5 복구 종료 시각, 미종료 null |

```json
{
  "paymentId": 101,
  "recoveryId": 12,
  "status": "COMPLETED",
  "evaluationResult": "ALLOW",
  "paymentStatus": "PENDING",
  "reasonCode": null,
  "completedAt": "2026-10-28T06:00:00Z"
}
```

새 UUID·retryId·고객 원문·원본 예외는 응답에 넣지 않는다. DB reason_code는 disposition과 함께 P2 분석 사유를 보존하며 재탐지 결과로 덮어쓰지 않는다. API reasonCode는 별도 표현이다. 정상 완료이면 null, 재탐지 ERROR이면 현재 평가의 errorCode, 분석상 불가이면 보존한 분석 사유를 반환한다. 별도 수동 종료 사유의 영속 보존 필요 여부는 #274에서 결정한다.

기존 `GET /api/payment-operations/{paymentId}`에 같은 모양의 `fdsRecovery` 요약을 추가한다. 복구 행이 없으면 null이다. GET은 보고·재탐지·상태 변경을 수행하지 않는다. 목록의 기존 분류는 현재 payment_requests 평가와 payments 상태를 사용하며, 원본 ERROR를 현재 결과로 오인하지 않는다.

### HTTP·오류 계약

| HTTP | code 또는 응답 | 조건 |
| --- | --- | --- |
| 200 | 위 DTO | 실행 완료·기존 완료 작업. 재탐지가 ERROR여도 정상 저장·종료했다면 200 |
| 400 | FDS_REEVALUATION_INVALID_REQUEST | 잘못된 ID·지원하지 않는 본문/쿼리 |
| 401 | P1 기존 인증 코드 | 인증 실패 |
| 403 | P1 보안 응답 | 역할 불허·CSRF 실패 |
| 404 | PAYMENT_OPERATIONS_NOT_FOUND | 허용된 호출에서 결제 없음 |
| 409 | FDS_REEVALUATION_NOT_ALLOWED | 분석 대기·분석상 불허·상태 불일치·요청/관측 기록 부족 |
| 503 | FDS_REEVALUATION_UNAVAILABLE | DB 장애·일시적 실행 실패 |
| 500 | FDS_REEVALUATION_INTERNAL_ERROR | 예상하지 못한 오류·P2 응답/내부 계약 불일치 |

오류는 기존처럼 ProblemDetail + code를 사용한다. 409에는 안전한 설명과 reasonCode(ANALYSIS_PENDING / ANALYSIS_NOT_ALLOWED / PAYMENT_STATE_CHANGED / RECOVERY_DATA_MISSING)를 제공해 구분한다. 원문 SQL·예외 메시지·입력 개인정보는 반환하지 않는다.

202는 사용하지 않는다. 동시 재탐지 요청은 잠금 해제 후 기존 결과를 반환하거나 같은 작업을 재개한다. 잠금 대기 초과·데드락은 503이며, 조회 GET은 작업을 재개하지 않는다.

503·500 이후에도 새 작업 키나 새 결제를 만들지 않는다. 권한 있는 사용자가 기존 GET으로 확인한 뒤 같은 POST를 재요청할 수 있으나, 무제한 자동 재시도를 뜻하지 않는다. DB 조회조차 실패하면 작업 미접수·금융 실패라고 단정하지 않는다.

### 전달 복구 POST

`POST /api/payment-operations/{paymentId}/fds-recovery-deliveries`

보안 직원이 오류 보고·분석 이벤트 전달만 복구한다. 결제 ID만 업무 입력으로 받고 본문·쿼리·클라이언트 멱등키는 요구하지 않는다. **재탐지·승인 API를 호출하지 않으며**, 새 평가 UUID·retryId·재탐지 횟수·최초 재탐지 실행자 기록을 변경하지 않는다.

| 상황 | 처리·응답 |
| --- | --- |
| 결제 없음 | 404 PAYMENT_OPERATIONS_NOT_FOUND |
| 복구 기록·원본 필수값 없음 | 409 FDS_RECOVERY_DELIVERY_NOT_ALLOWED, reasonCode=RECOVERY_DATA_MISSING |
| WAITING_ANALYSIS | 아래 보고·이벤트 복구 실행 후 현재 요약을 200으로 반환 |
| READY / REQUESTED / COMPLETED / MANUAL_REVIEW | 전달을 새로 실행하지 않고 현재 요약 200 반환 |
| 잘못된 ID·본문·쿼리 | 400 FDS_RECOVERY_DELIVERY_INVALID_REQUEST |
| DB·일시적 전달 실패·잠금 대기 초과 | 503 FDS_RECOVERY_DELIVERY_UNAVAILABLE |
| 계약 불일치·예상하지 못한 실패 | 500 FDS_RECOVERY_DELIVERY_INTERNAL_ERROR |

401/403은 두 POST의 공통 보안 계약을 따른다. 오류는 ProblemDetail + code, 모든 응답은 no-store다. 200은 관측한 현재 상태를 뜻한다. WAITING_ANALYSIS가 반환되면 완료·성공으로 표시하지 않고 GET으로 확인한다. READY가 된 뒤 직원이 재탐지 POST를 별도로 호출한다.

전달 복구 순서:

1. P5의 원본 복구 기록과 상태를 잠금으로 확인하고, 필요한 불변 입력을 읽은 뒤 짧은 트랜잭션을 종료한다. P2 호출·리스너 실행을 기다리면서 P5 잠금을 계속 잡지 않는다. 이후 상태가 진행돼도 수신 멱등 검사로 역행을 막는다.
2. 보존한 failureReportId·원본 입력으로 FdsFailureReporter.report를 호출한다. P2가 이미 분석한 보고면 기존 코드처럼 반환한다. 재전달 실패로 원본 ERROR·보고 키를 지우지 않는다.
3. P5 수신 결과를 다시 확인한다. 미수신이면 해당 보고의 실패·중단된 기존 분석 이벤트 전달만 IncompleteEventPublications 경계로 재전달한다. 테스트에 존재하는 선택 재전달 방식을 참고하되 운영용 선별·실행 코드는 아직 없다.
4. 응답 직전에 현재 P5 요약을 읽는다. 수신 완료가 확인되지 않았다면 READY로 직접 바꾸지 않는다.

현재 P2는 동일 failureReportId를 받으면 이벤트를 다시 발행하지 않는다. 따라서 **보고 재호출과 분석 이벤트 재전달을 분리**한다. 기존 보고로 새 분석·새 이벤트를 무조건 만들지 않는다.

재전달 허용 대상은 FdsEvaluationFailureAnalyzed 타입 + paymentId·failureReportId·원본/현재 평가 UUID 일치 + P5 분석 수신 리스너 + 실패 또는 중단이 확인된 기존 발행 기록이다. 현재 처리 중인 기록·완료 기록·다른 리스너·다른 결제·다른 이벤트는 제외한다. 단순히 완료 시각이 NULL인 것만으로 실패나 중단이라고 판단하지 않는다.

정확한 리스너 식별·기록 상태·중단 판별 방법은 설치된 Modulith API와 실제 발행 기록으로 검증한다. 이를 구분할 수 없다면 무조건 재전달하지 않고 운영 확인 대상으로 남긴다. P2 분석은 존재하지만 P5 결과·복구 가능한 기록이 없는 경우도 새 분석을 만들어 덮지 않는다. 확정할 수 있는 기록 불일치는 409 FDS_RECOVERY_DELIVERY_NOT_ALLOWED(reasonCode=DELIVERY_RECORD_UNAVAILABLE)로 표시한다. 현재 P2 report의 void 반환만으로 분석 존재·전달 상태를 추정하지 않는다.

범용 이벤트 복구(#191)로 확대하지 않는다. P2 내부 Repository·event_publication 테이블 직접 수정으로 우회하지 않으며, 선택 재전달 경계·현재 실행 중 기록 보호는 공통 이벤트 담당과 구현 전에 확인한다. 전달 복구 요청의 직원·대상·결과 관측을 로그에 남기되 원본 입력·비밀값은 제외한다. 별도 전달 감사 테이블은 이번 설계에 추가하지 않는다.

### 두 POST의 CSRF·권한 적용

기존 직원용 SecurityConfig와 CookieCsrfTokenRepository를 재사용한다. 새 보안 체인·토큰 체계·가맹점 HMAC 예외를 만들지 않는다.

```text
CSRF 필요 = 정확한 재탐지 POST 경로
         OR 정확한 전달 복구 POST 경로
         OR (기존 security.csrf.enabled AND 기존 unsafe-method 조건)
```

- 메서드와 경로를 함께 검사한다. 두 POST는 전역 설정이 false여도 CSRF를 검사한다. 다른 경로는 기존 설정을 유지하며 전체 CSRF를 이번 작업에서 일괄 활성화하지 않는다.
- 두 경로 모두 서버에서 SECURITY_STAFF 세션만 허용한다. ADMIN 조회 권한은 실행 권한이 아니며, P5 서비스도 identity.api.CurrentStaff로 역할·실행자를 확인한다.
- 두 경로는 /internal/** 및 가맹점 HMAC CSRF 예외 대상에 포함하지 않는다. 익명·역할·CSRF 검사 실패 시 업무 처리를 시작하지 않는다.
- 직원 로그인 후 GET /api/auth/csrf로 현재 XSRF-TOKEN 쿠키를 확보하고 세션 쿠키와 X-XSRF-TOKEN 헤더로 POST한다. 로그인 시 토큰이 바뀔 수 있으므로 로그인 전 토큰을 계속 사용하지 않는다.
- 기존 JsonAccessDeniedHandler의 403 / CSRF_001, 역할 불허 403 / AUTH_002, 인증 실패 AUTH_001을 재사용한다. 비로그인과 CSRF 누락이 함께 있으면 필터 순서에 따라 403이 먼저 발생할 수 있으며 항상 401이라고 보장하지 않는다.
- 구체 matcher·필터 순서·기존 예외와의 상호작용은 P1 구현 검토와 HTTP 테스트로 검증한다. 이 문서는 설정을 실제 변경하지 않는다.

## 6. 대안과 영향

- 보고·분석 수신·재탐지를 3개 테이블로 분리하는 대신 최초 ERROR당 단일 복구 행을 사용한다. 여러 차례 재평가·임의 이력 수정은 지원하지 않는다.
- 기존 요청 평가만 덮어쓰면 원본이 사라지고, 기존 요청을 영구 ERROR로 두면 운영 분류·승인 경로에 별도 예외가 늘어난다. 원본은 복구 행, 현재 적용 결과는 요청 행으로 구분한다.
- 범용 작업 큐·스케줄러·새 화면 대신 직원 전달 복구 POST를 추가한다. 재탐지 POST에 전달 복구까지 숨겨 넣지 않는다.
- 실행 중을 판별해 202로 즉시 반환하는 대신 기존 행 잠금 대기·재조회와 503을 사용해 상태 분기를 줄인다.
- 전역 CSRF 활성화 대신 두 POST만 항상 보호하여 기존 단계적 활성화 범위를 유지한다.
- 현재 단계별 SQL CHECK와 일치하게 현재 결과·processing_stage를 함께 전이한다. payment_requests UNIQUE UUID는 새 UUID에도 유지한다. 기존 migration은 수정하지 않는다.
- PaymentRequest의 별도 재탐지 적용, PaymentRequestProcessingService, 운영 조회 분류·상세 응답, P1 권한·CSRF, P2 호출 트랜잭션을 함께 확인해야 한다. P2/P1 내부 Repository 직접 참조는 하지 않는다.

## 7. 검증 계획과 남은 작업

- 오류 저장과 복구 행 원자성, 원본 불변, 필수 관측값 없는 과거 건 거절.
- 같은 보고·분석 이벤트 반복, 식별자/내용 불일치, 지연 이벤트로 종료 작업이 초기화되지 않음.
- 두 직원의 동시 POST가 하나의 작업·새 평가·승인·후속 이벤트로 수렴하고 최초 실행자가 보존됨.
- C 커밋 후 중단·D 중 오류·응답 유실 후 같은 키 재개. P2 평가·P5 상태·이벤트·복구 종료의 커밋/롤백을 실제 MariaDB로 검증.
- ALLOW/REVIEW/DENY/ERROR와 모의 승인 거절. 재탐지 ERROR 후 반복 POST는 같은 결과이며 추가 평가 없음.
- 진행된 결제·뒤늦은 이벤트, 기존 CakeShop POST와 재탐지 경합, 운영 조회의 현재 평가 표시 및 GET 무변경.
- 직원 세션·ADMIN/OPERATOR/익명·CSRF 누락/변조, ID·본문·쿼리 오류, HTTP 상태·no-store·원문 미노출.
- 동시 요청의 잠금 대기 후 기존 결과 반환, 대기 초과·데드락 롤백과 503, 계약 불일치 500. 202를 반환하지 않음.
- 보고 유실·보고 성공 후 수신 실패·재시작·중복 전달 복구, 다른 타입/보고/리스너·현재 실행 중·완료 기록 제외. 복구 API가 새 평가·승인·작업 키를 만들지 않음.
- 전역 CSRF false/true 모두에서 두 POST의 누락·변조 토큰 거절 및 정상 토큰 허용, 다른 경로의 기존 동작 유지. P2 Mock 버튼만으로 통합 완료 판정하지 않음.

구현 전 남은 작업: 담당 ERD·Notion 반영, 상세 제약/인덱스, 유한 잠금 대기 설정·값 검증, Modulith 리스너/기록 선별·중단 판별과 #191 경계 확인, P1 matcher·필터 구현 검토, 수동검토 절차와 후속 구현 이슈 분리. 잠금·전달 복구 진입점·CSRF 적용 방향은 이번 사용자 승인으로 정리했으며 실제 구현·동작 검증과 구분한다. 응답 유실·기존 UUID 복원 설계는 #190에서 별도로 유지한다.

문서 검사는 `python3 .github/scripts/check_md_links.py`, `git diff --check`로 수행한다. 이후 코드·DB 구현 시 `cd backend && ./gradlew spotlessApply check mariaDbTest`를 실행한다. 이번 문서화에서는 backend·MariaDB·HTTP·보안·통합 테스트를 실행하지 않았으며 기능 구현·검증 완료로 표시하지 않는다.

## #273 저장 상세 반영

2026-10-28 사용자 요청으로 담당 ERD에 저장 필드·제약을 반영하고, reason_code를 P2 분석 사유로 보존하는 방식으로 정리했다. 상세 기준은 [#273 저장 계약](273-fds-recovery-storage.md)을 따른다. 위 구현 전 기록 이후 #273 작업 브랜치에 migration·Entity·Repository, 실제 호출 시각 수집·ERROR 저장·커밋 후 보고, 분석 이벤트 수신 및 상세 GET fdsRecovery 응답을 구현했다. 최신 검증 결과는 연결한 #273 문서의 최종 점검 기록을 따른다. 운영 DB 적용과 #274·#275 실행 API는 후속이며, 위 문서화 당시의 미검증 기록과 구분한다.

## #275 HTTP 구현 보충 — 2026-10-29

작업 브랜치에서 전달 복구 POST·전용 오류 매핑을 내부 복구 서비스에 연결했다. 기존 두 POST의 직원 권한·CSRF matcher를 그대로 적용한다. 실제 재전달은 단일 인스턴스 공통 잠금 안에서 식별값과 P5 수신자가 일치하는 FAILED publication만 허용한다. PUBLISHED/PROCESSING/RESUBMITTED의 중단 판별·재전달은 포함하지 않는다. 현재 구현·실행 결과·미검증 범위는 [#275 기록](275-fds-delivery-concurrency.md)을 따른다. 이전 절의 구현 전 상태는 당시 기록이다.
