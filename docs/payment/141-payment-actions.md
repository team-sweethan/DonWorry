# #141 직원 금융 조치 저장·처리 설계

- 기준: `dev 078a5c8`, 2026-10-25. 설계 기준 커밋. 4번 단계에서 migration·공개 계약·내부 요청 저장을 구현했다. 실행·결과 발행 연결은 후속이다.
- 사용자 승인: P5 소유 `payment_actions` 하나 추가, 완료 기반 P3 통지, VOID 식별자로 actionId 사용 방향.
- P3 공유 완료: 요청 4필드, 완료 시점, 정상 대기 구분, 결과 처리 기본 기준. 별도 P3 회신은 확인하지 않았다.
- 사용자 추가 승인(2026-10-25): 아래 상세 스키마를 저장소 ERD·ADR 기준으로 구현하고 Notion은 후속 반영한다. VOID actionId 및 구체 DTO의 P3 추가 확인은 아직 확인하지 않았다.
- 결정 이유: [ADR-0027](../decisions/0027-payment-actions.md). 저장 초안: [ERD](../erd/README.md#141-p5-직원-조치-저장-설계-초안).

## 1. 범위와 책임

P3는 직원 권한·Case 범위·판정 유효성을 확인하고 판정과 인계를 저장한다. 커밋 후 P5 공개 Java API를 호출한다. P5는 세션이나 P3 DB를 조회하지 않고 결제 상태·승인 참조·요청 일치 여부를 검증한다. `decisionId` 존재 및 결제 연결의 진위는 호출자 P3 책임이다. 이 API를 무인가 HTTP로 노출하지 않는다.

P5는 직원 조치 요청과 처리 결과만 저장한다. 기존 최초 결제 생성·모의 승인·ALLOW 자동 매입에는 조치 행을 만들지 않는다. 한 결제의 직원 최종 판정은 한 번이며 판정 정정·매입 후 취소·환불은 이번 범위 밖이다.

`investigation → payment.api → settlement.api` 의존을 유지한다. P3 Gateway 교체·운영 결과 리스너·Case 종료 로직 변경은 P3 담당이며 이번 P5 구현에 포함하지 않는다. P6 이벤트 4필드 요청·3필드 완료 계약은 그대로 쓴다.

## 2. 공개 계약 초안

다음 공개 타입은 4번 단계에서 Java 파일로 정의했다. PaymentActionRequester의 실행 구현 Bean은 5·6번 단계에서 연결한다. 공개 record에는 내부 Entity·enum을 노출하지 않고, 4필드 이상 record에는 `@Builder`를 적용한다.

| 타입 (`payment.api`) | 계약 |
| --- | --- |
| PaymentActionRequester | `PaymentActionReceipt request(PaymentActionCommand command)` |
| PaymentActionCommand | Long paymentId, Long decisionId, String actionType, UUID idempotencyKey |
| PaymentActionReceipt | String status, Long targetOperationId, String errorCode |
| PaymentActionResult | Long paymentId, Long decisionId, String actionType, UUID idempotencyKey, String status, Long targetOperationId, String errorCode, Instant completedAt |

요청은 전부 필수, ID는 양수, actionType은 대소문자 변환 없이 `CAPTURE` 또는 `VOID`만 허용한다. null·형식 오류는 IllegalArgumentException이다. DB 오류·잠금 실패·기술 장애는 예외로 전달하고 업무 거절이나 UNKNOWN으로 바꾸지 않는다.

| 응답 status | targetOperationId | errorCode | 뜻 |
| --- | --- | --- | --- |
| PENDING | null | null | 요청·매입 발행이 커밋됐고 완료 대기 중 |
| ACCEPTED | 양수 | null | 실제 성공 확인. CAPTURE는 captureId, VOID는 actionId |
| REJECTED | null | 필수 | 확정된 요청 거절 또는 저장된 최종 업무 실패 |

P5는 UNKNOWN 응답을 생성하지 않는다. 응답을 받지 못한 경우 P3가 기존 UNKNOWN/예외 처리로 같은 키를 재전달한다. 정상 PENDING은 요청 전달 성공이므로 예외를 던지지 않고 P3 인계 REQUESTED를 유지한다. 이후 완료 통지를 기다린다. 최초 응답은 반환 직후 이미 바뀌었을 수도 있는 처리 스냅샷이다.

결과 이벤트 status는 ACCEPTED 또는 REJECTED만 허용한다. PENDING 이벤트는 없다. 이벤트의 completedAt은 P5가 보존한 결과 시각(UTC 마이크로초)이며 P3 acceptedAt을 덮어쓰는 값이 아니다. P3는 기존처럼 자기 결과 반영 시각으로 acceptedAt을 저장한다. CAPTURE의 completedAt은 P6 capturedAt, VOID 성공·업무 실패는 P5 처리 시각이다.

P3는 idempotencyKey로 인계를 찾고 paymentId·decisionId·actionType을 함께 대조한다. 성공 결과가 먼저 도착하고 늦게 PENDING 응답이 와도 ACCEPTED를 REQUESTED로 되돌리지 않는다. 동일 성공 결과는 한 번만 반영하고 다른 targetOperationId의 성공 통지는 충돌로 취급한다. 실패 또는 성공 결과의 반대 순서 처리도 P3가 완료 상태를 임의로 덮어쓰지 않도록 검증해야 한다.

### 업무 오류 코드 초안

| 코드 | 조건 |
| --- | --- |
| PAYMENT_NOT_FOUND | 요청한 결제가 없음 |
| PAYMENT_NOT_HELD | 신규 직원 조치인데 HELD가 아님 |
| PAYMENT_NOT_AUTHORIZED | HELD지만 유효한 승인 참조가 없음 |
| IDEMPOTENCY_KEY_CONFLICT | 같은 키에 다른 paymentId·decisionId·actionType |
| PAYMENT_ACTION_CONFLICT | 같은 결제에 다른 키·판정·조치가 이미 저장됨 |
| DECISION_ACTION_CONFLICT | 같은 decisionId가 다른 결제/요청에 사용됨 |
| VOID_DECLINED | 모의 취소기가 명시적으로 업무 거절을 반환 |

형식 오류·신규 요청 검증 거절은 조치 행이나 결과 이벤트를 만들지 않는다. 따라서 거절된 모든 잘못된 요청의 영구 응답 캐시를 보장하지 않는다. 멱등 보장 대상은 접수되어 저장된 조치다. 키 충돌 거절은 기존 정상 조치의 실패로 저장하거나 실패 이벤트로 발행하지 않는다.

## 3. 저장·상태 전이

상세 컬럼·제약은 [ERD 초안](../erd/README.md#141-p5-직원-조치-저장-설계-초안)을 따른다. payments의 현재 결제 상태와 payment_actions의 조치 진행 상태를 구분한다.

| 조치 상태 | 결제 상태 | 재요청 응답 |
| --- | --- | --- |
| PENDING (CAPTURE) | HELD | PENDING |
| SUCCEEDED (CAPTURE) | CAPTURED | ACCEPTED(captureId) |
| SUCCEEDED (VOID) | VOIDED | ACCEPTED(actionId) |
| FAILED (명시적 업무 실패) | HELD 유지 | REJECTED(errorCode) |

신규 유효 요청은 PENDING으로 시작한다. VOID 모의 실행은 같은 로컬 트랜잭션에서 SUCCEEDED 또는 FAILED로 끝내므로 정상 커밋된 VOID PENDING은 만들지 않는다. 기본 모의 취소기는 유효 입력에 성공하며 외부 카드사 호출은 없다. 대역으로 명시적 거절과 기술 예외를 검증한다.

PENDING → SUCCEEDED 또는 FAILED만 허용한다. 종료 상태는 재요청으로 초기화하지 않는다. 확정 실패의 동일 요청은 기존 거절을 반환한다. 실패한 판정을 새 키/반대 조치로 바꾸는 기능은 제공하지 않는다. 일시 장애는 FAILED로 저장하지 않는다.

P6에는 현재 CaptureFailed 계약이 없다. P6 처리 예외만으로 CAPTURE FAILED를 만들지 않고, PENDING과 미완료 이벤트를 보존한다. 운영자가 확정 실패로 전환하는 API나 자동 재시도 스케줄러는 이번 범위가 아니다.

## 4. 요청 트랜잭션과 경합

공개 요청 서비스는 REQUIRES_NEW로 처리하여 반환 시 P5 결과가 커밋되어 있도록 한다. P3는 원칙적으로 판정 커밋 후 호출한다. 이후 P3 결과 반영 트랜잭션이 실패해도 P5 실행을 롤백하지 않는다.

1. 요청 형식 검증 후 해당 payments 행을 쓰기 잠금으로 읽는다.
2. 저장된 키·결제·판정을 확인한다. 같은 키와 같은 내용이면 결제의 현재 HELD 여부를 검사하기 전에 기존 결과를 반환한다. 완료된 재요청이 PAYMENT_NOT_HELD로 거절되면 안 된다.
3. 다른 키의 동일 결제, 동일 키의 다른 내용, 다른 결제의 동일 판정은 거절한다. 신규 요청만 HELD·승인 참조를 검사한다.
4. 결제당 하나의 조치 행을 저장한다. CAPTURE는 같은 트랜잭션에서 저장 결제의 merchantId·orderRef·amount로 CaptureRequested를 발행하고 PENDING을 반환한다.
5. VOID는 같은 트랜잭션에서 모의 취소 → VOIDED·SUCCEEDED 저장 → PaymentActionResult 발행 후 ACCEPTED를 반환한다. 명시적 거절은 FAILED·오류·완료 시각과 REJECTED 이벤트를 저장하며 결제는 HELD를 유지한다. 기술 예외는 신규 조치와 결제 변경·발행을 모두 롤백한다.

모든 요청·완료 경로의 잠금 순서는 payments → payment_actions다. 같은 결제의 CAPTURE/VOID는 결제 잠금과 UNIQUE(payment_id)로 직렬화한다. 비동기 매입 대기 동안 잠금을 계속 잡지 않으며, 커밋된 조치 행이 반대 조치를 차단한다.

서로 다른 결제에 같은 키/판정을 동시에 쓰는 충돌은 UNIQUE로 막는다. 제약 실패가 난 트랜잭션 안에서 계속 조회하지 않는다. 별도 비트랜잭션 조정 서비스가 실패 트랜잭션 종료 후 새 읽기 트랜잭션으로 저장된 요청을 비교한다. 지정한 UNIQUE 충돌만 업무 충돌로 변환하고 다른 DB 오류는 전파한다. 재조회에서 근거를 찾지 못하면 기술 실패로 남긴다.

## 5. 매입 완료와 결과 전달

기존 CaptureCompletedListener의 새 트랜잭션에 PaymentCaptureService가 참여한다. 결제를 먼저 잠근 뒤 조치를 확인한다.

- 직원 CAPTURE PENDING: 결제 HELD·승인·조치 유형을 검증한 뒤 CAPTURED, SUCCEEDED, captureId·capturedAt을 저장하고 PaymentActionResult를 같은 트랜잭션에서 발행한다.
- 직원 CAPTURE SUCCEEDED: 같은 captureId·정규화한 capturedAt과 CAPTURED이면 중복으로 종료한다. 다른 ID·시각이나 불일치 상태는 예외로 남긴다. 결과 이벤트를 무조건 다시 발행하지 않는다.
- VOID·FAILED 조치 또는 유효 조치 없는 HELD: 완료를 거절한다. HELD를 조건 없이 매입 완료로 허용하지 않는다.
- 조치가 없는 기존 ALLOW: 기존 승인된 PENDING/CAPTURED 처리를 유지하고 P3 결과를 발행하지 않는다. ALLOW 결과 ID 보존까지 확대하지 않는다.

P5는 P6 저장소를 직접 읽지 않는다. 결제별 조치 1개와 P6의 결제별 매입 1개 계약을 이용해 paymentId로 요청을 찾는다. 첫 CaptureCompleted는 신뢰된 P6 생산자의 완료 증거로 취급한다. P5에 없는 금액·판정 필드를 이벤트에 추가하거나 추정하지 않는다.

P3 운영 리스너는 payment.api.PaymentActionResult를 커밋 후 수신해야 한다. 실제 구독자가 없는 상태에서는 P3 전달용 EVENT_PUBLICATION 기록이 자동으로 남는다고 보장할 수 없다. P5 테스트 수신자 검증과 운영 연결을 구분한다.

## 6. 재시도·복구

- 요청 응답 유실: P3가 같은 키로 요청한다. 저장 전 기술 실패면 다시 접수하고, 저장된 CAPTURE는 PENDING, 종료된 조치는 동일 결과를 반환한다. 중복 요청 자체로 매입/취소를 다시 실행하지 않는다.
- P6 실패: 요청의 미완료 EVENT_PUBLICATION을 명시적으로 재전달한다. 정상 PENDING을 반환하는 공개 API를 재호출하는 것만으로 P6 재발행을 대신하지 않는다.
- P5 완료 반영 실패: P6 완료 이벤트의 미완료 기록을 재전달한다.
- P3 결과 수신 실패: P5 결과 이벤트의 미완료 기록을 재전달한다. P5 조치 결과는 유지한다.
- P3 구독자 설치 전 발생한 결과는 자동 소급 전달되지 않는다. 저장 결과를 동일 요청으로 재조회해 동기 결과를 반영하는 복구 경로와 배포 순서를 P3와 확인한다.
- 완료 이벤트가 응답보다 먼저 올 수 있다. P3는 종료 상태를 역행시키지 않으며, 응답과 이벤트가 모두 최종 결과여도 한 번만 반영한다.

프로세스 메모리 캐시·EVENT_PUBLICATION payload를 업무 조회 정본으로 사용하지 않는다. 재시작 후 DB의 같은 조치 행에서 결과를 재구성한다. 운영 자동 재시도·관리 UI는 별도다.

## 7. 구현 순서와 검증 계획

1. 상세 ERD 반영·합의 및 추가 P3 계약 확인 → newMigration 생성 → Entity·Repository·공개 DTO/API.
2. 요청 중복·반대 조치 제어 → ACCEPT 요청·완료 연결 → 모의 취소·VOID 완료 연결.
3. 단위 검사: 필수값·필드 매핑·오류·상태 전이·이벤트 조건.
4. MariaDB: UNIQUE·CHECK·FK·UTC 정밀도, 동일 요청·키/판정 충돌·CAPTURE/VOID 경합, 완료와 재요청 경합, DB 재조회로 복구, 요청·완료 롤백.
5. Modulith: 커밋 후 전달, 미완료 요청·완료·결과 재전달, 중복 결과 및 기존 ALLOW 회귀. P3 운영 연결과 테스트 수신자 검증을 구분한다.
6. spotlessApply check mariaDbTest, 문서 링크·diff 검사 후 실행 커밋·미검증을 PR에 기록한다.

### 4번 단계 구현 범위

- 새 migration·PaymentAction Entity·Repository·공개 요청/응답/결과 타입을 추가했다. Entity는 종료 상태 변경 차단과 CAPTURE 동일 결과 재반영 방지를 제공한다.
- PaymentActionAdmissionService는 MANDATORY 트랜잭션에서 결제를 잠그고 요청을 검증·저장한다. 신규 여부와 기존 결과를 내부 실행 서비스에 반환한다. 단독으로 금융 실행을 시작하거나 커밋하지 않는다.
- 실제 공개 PaymentActionRequester Bean은 아직 없다. 요청 저장만으로 PENDING을 반환하고 이벤트가 없는 상태를 외부에 노출하지 않는다. 후속 단계에서 REQUIRES_NEW 실행 트랜잭션·UNIQUE 경쟁 실패 뒤 새 트랜잭션 재조회·P6 발행 또는 VOID 실행을 연결한다.
- 이번 DB 테스트의 PENDING 행과 직접 설정한 CAPTURED는 저장 계층의 검증 fixture다. 직원 매입·취소가 실제 연결됐다는 증거가 아니다.
- 검사 실행 결과는 [검증 기록](verification.md)을 본다. P3 운영 연동·Notion 동기화는 미검증/후속이다.
