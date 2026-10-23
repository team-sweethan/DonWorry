# #122 매입 이벤트 연결 설계

2026-10-23 · 기준 코드 `c680eb9` · 2번 단계 설계, 3·4번 단계 구현 및 5번 단계 통합 검증. P5 요청 발행과 완료 수신·CAPTURED 반영을 추가했다. 단계별 실행 결과는 아래에 구분해 기록한다.
[ADR-0012](../decisions/0012-payment-settlement-dependency.md)의 의존 방향과 채택된 [요청 계약](../decisions/0016-capture-request-event-contract.md)·[완료 계약](../decisions/0017-capture-completed-event-contract.md)을 구현하는 범위다. 공개 이벤트·SQL·의존 방향을 변경하지 않는다.

## 범위와 호출 경계

이번에는 ALLOW 승인 성공 건의 자동 매입만 연결한다. REVIEW 승인 성공은 HELD로 남기며 직원 ACCEPT·P3 인계·VOID는 후속이다. 기존 createAndEvaluate는 평가 전용으로 유지한다.

- PaymentAuthorizationService: 현재 잠금·승인·상태 저장 트랜잭션에서 REQUEST_CAPTURE일 때만 요청 발행을 추가한다.
- P6 CaptureRequestedListener·CaptureService·CaptureTransactionService: 기존 요청 수신·매입·정산 저장·완료 발행을 사용한다. 운영 P5 코드가 P6 Entity·Repository를 직접 참조하지 않는다.
- P5 CaptureCompletedListener: @ApplicationModuleListener로 완료를 수신하고 별도 PaymentCaptureService에 위임한다.
- PaymentCaptureService: 리스너가 연 새 트랜잭션에 참여하여 검증·잠금·상태 변경을 수행한다. 서비스에는 MANDATORY를 적용해 트랜잭션 없는 직접 호출을 거절한다. 리스너 내부에서 다시 REQUIRES_NEW를 열어 업무 반영을 분리하지 않는다.
- Payment Entity: completeCapture() 메서드로 상태를 바꾼다. 이벤트 DTO나 service 타입을 Entity에 넘기지 않는다.

## 요청 발행 조건과 값

승인 전 PENDING·참조 없음 검증, 평가 문맥 대조, 모의 승인 성공, 상태·참조 반영까지 완료한 뒤 PaymentRules의 최종 nextStep이 REQUEST_CAPTURE인 경우만 발행한다. PENDING이라는 상태만 보고 요청하지 않는다.

| 분기 | 승인 트랜잭션에서 반영하는 값 | 매입 요청 |
| --- | --- | --- |
| ALLOW + 성공 | PENDING + 승인 참조 | 1건 발행 |
| REVIEW + 성공 | HELD + 승인 참조 | 발행 안 함 |
| DENY / 업무 거절 | DENIED / DECLINED | 발행 안 함 |
| ERROR | 최초 PENDING 유지 | 발행 안 함 |
| 평가·승인 실패 | 예외 전달, 해당 트랜잭션 롤백 | 커밋된 요청 없음 |
| 이미 처리된 결제 재승인 | 기존 상태 검사에서 충돌 예외 | 추가 발행 없음 |

CaptureRequested의 paymentId·merchantId·amount는 잠금 조회한 Payment의 값이고 orderId는 그 Payment.orderRef다. 외부 입력을 다시 사용하거나 P6 수수료를 P5에서 계산하지 않는다.
ApplicationEventPublisher.publishEvent를 승인 트랜잭션 안에서 호출한다. 승인 서비스 반환 뒤 또는 별도 afterCommit 콜백에서 요청을 처음 발행하지 않는다. 등록된 P6 리스너에 대한 Modulith 발행 기록과 승인 상태·참조가 함께 커밋되어야 한다. 발행 기록 저장 실패도 승인 트랜잭션 실패로 전파한다.

## 트랜잭션과 반환 시점

| 경계 | 업무와 기록 | 이후 처리 |
| --- | --- | --- |
| 기존 최초 저장 | PENDING 먼저 커밋 | P2 평가 |
| 기존 P2 평가 | P2 자체 평가 커밋 | P5 승인 |
| P5 승인 REQUIRES_NEW | 승인 상태·참조 + CaptureRequested 발행 기록 | 커밋 후 P6 리스너 |
| P6 기존 매입 트랜잭션 | captures COMPLETED + settlements SCHEDULED + CaptureCompleted 발행 기록 | 커밋 후 P5 리스너 |
| P5 완료 리스너 트랜잭션 | 잠금 조회 + CAPTURED 반영 | 정상 처리 후 발행 기록 완료 추적 |

위 표는 업무 트랜잭션 경계다. Modulith가 완료 기록을 갱신하는 정확한 트랜잭션·인터셉터 순서는 통합 테스트에서 확인한다. CAPTURED 커밋과 발행 기록 완료 표시가 항상 한 원자적 커밋이라고 가정하지 않는다. 상태 반영 후 완료 표시 전 실패도 중복 수신의 상태 멱등으로 처리한다.

애플리케이션 소스에는 @EnableAsync가 없지만, 설치된 Modulith 2.1.1의 EventPublicationAutoConfiguration은 AsyncEnablingConfiguration(@EnableAsync)을 import한다. 5번 단계에서 완료 리스너가 호출자와 다른 task-* 스레드에서 실행되는 것을 관찰했다. 이 작업에서 전역 비동기 설정을 추가하지 않는다. 서비스 반환 시점만으로 수신 완료를 판단하지 않는다.

PaymentAuthorizationResult는 승인 트랜잭션 안에서 만든 불변 스냅샷이다. ALLOW의 status=PENDING과 nextStep=REQUEST_CAPTURE는 승인 단계의 결과이며, 반환 직후 DB가 반드시 PENDING이라는 뜻이 아니다. 완료 리스너가 이미 실행됐다면 DB는 CAPTURED일 수 있다. nextStep은 호출자가 요청을 다시 발행하라는 지시가 아니다. 최종 상태는 별도 조회로 확인하며 이번에 HTTP 조회 API를 추가하지 않는다.

수신 실패로 이미 커밋된 발행자 업무까지 되돌아간다고 표현하지 않는다. 커밋 이후 콜백 오류의 실제 전파 여부를 검증하고, 호출 결과만으로 승인 롤백이나 매입 실패를 단정하지 않는다. createAndAuthorize 전체 재호출은 새 결제를 생성하므로 복구 수단으로 쓰지 않는다.

## 완료 입력과 상태 전이

CaptureCompleted 자체는 P6 공개 record 그대로 사용한다. P5 수신 서비스 진입 시 이벤트 non-null, paymentId·captureId 양수, capturedAt non-null을 검사한다. 과도한 미래 시각 제한이나 생성 시각과의 대소 비교는 계약에 없으므로 추가하지 않는다.
이후 findByIdForUpdate로 결제 행을 잠근다. 유효한 승인 참조는 공백이 아니고 기존 컬럼 길이 제한을 충족하는 값이다.

| 저장 상태 | 처리 |
| --- | --- |
| PENDING + 유효 승인 참조 | CAPTURED로 변경. 승인 참조·금액 등 기존 값 유지 |
| CAPTURED + 유효 승인 참조 | 중복으로 간주해 변경 없이 정상 종료 |
| PENDING 또는 CAPTURED + 승인 참조 누락/부적합 | 불일치로 거절 |
| HELD | 직원 ACCEPT 연결 전이므로 거절 |
| DENIED·DECLINED·VOIDED·EXPIRED | 허용하지 않은 상태로 거절 |
| 결제 없음 | 거절 |

입력 오류는 IllegalArgumentException, 없는 결제·상태 충돌은 IllegalStateException으로 명시한다. 공통 오류 응답 체계나 외부 HTTP 계약은 새로 만들지 않는다. 리스너는 이 예외를 잡아 성공으로 바꾸지 않는다.

같은 결제의 동시 완료는 행 잠금으로 직렬화한다. 첫 커밋 뒤 두 번째는 CAPTURED를 보고 정상 종료한다. 첫 롤백이면 두 번째가 다시 반영할 수 있다. 기존 승인도 같은 행 잠금을 사용하므로 완료 처리와 승인 처리 사이에 상태·참조를 덮어쓰지 않는다. 잠금 대기 실패·교착은 전달하고 자체 반복하지 않는다.

## 결과 식별의 한계와 신뢰 경계

현재 payments에는 captureId·capturedAt·매입 요청 ID 저장 컬럼이 없다. 이번 멱등 보장은 동일 paymentId의 상태를 한 번 CAPTURED로 전이하는 범위다. 서로 다른 captureId가 같은 paymentId로 들어온 경우 원래 결과와의 일치를 증명하지 못한다. 입력 형식 검증을 결과 진위 검증이라고 부르지 않는다.

신뢰 근거는 앱 내부의 P6 발행 경로와 P6의 captures.payment_id UNIQUE 및 중복 시 기존 매입 반환 정책이다. 이벤트는 외부 사용자가 임의로 제출하는 승인 API가 아니다. P5에는 이벤트 수신 외부 HTTP 진입점을 만들지 않는다. 승인 참조가 있는 PENDING만으로 영속 매입 요청의 존재까지 입증하는 것은 아니며, 이전 버전의 승인 후 미발행 결제에 대한 자동 보정도 하지 않는다.
추가 결과 대조가 필요하면 P6 공개 조회 계약 또는 저장 구조의 담당 합의를 먼저 진행한다. 이를 위해 P6 내부 저장소를 우회 조회하거나 임의 컬럼·이벤트 필드를 추가하지 않는다.

## 실패·재전달

| 실패 지점 | 남아야 하는 업무 상태 | 복구 기준 |
| --- | --- | --- |
| P5 승인·요청 저장 롤백 | 최초 PENDING·기커밋 P2 평가. 요청 발행 기록 없음 | #118의 확실한 롤백 후 동일 내부 문맥 재처리 범위 |
| P6 처리 롤백 | P5 승인된 PENDING 유지. 매입·정산·완료 발행 기록 없음 | 미완료 요청 발행 기록 재전달 대상 |
| P5 완료 반영 실패 | P6 매입·정산과 완료 발행 기록 유지 | 미완료 완료 발행 기록 재전달 대상 |
| P5 CAPTURED 반영 후 완료 표시 전 실패 | CAPTURED 유지 가능 | 완료 재전달을 변경 없이 성공 처리 |

P6 중복 요청은 새 CaptureCompleted를 발행하지 않는다. 따라서 완료 수신 실패를 CaptureRequested 재전송으로 복구하지 않는다. 원래 미완료 CaptureCompleted의 해당 리스너 발행 기록을 재전달한다.
테스트는 설치된 Modulith 버전의 공개 재전달 API를 확인해 대상 기록만 선택해 사용한다. 이벤트 원문을 고치거나 DB 완료 표시를 직접 수정하여 복구된 것처럼 만들지 않는다. 잘못된 입력·상태 충돌은 같은 이벤트를 반복해도 해결되지 않으므로 원인 확인 대상이며, 이번에 자동 재시도 스케줄러·운영 복구 API·영구 실패 판정을 추가하지 않는다.

## 검증 계획과 기존 테스트 영향

- 단위: 요청 필드 매핑, 분기별 발행 여부, 기존 중복 승인 차단, 완료 입력·상태 표와 참조 보존을 확인한다.
- 실제 P5·P6·MariaDB: 승인 저장과 요청 발행 기록 원자성, 커밋 전 미처리·커밋 후 매입·정산 및 최종 CAPTURED를 확인한다. 조회는 새 트랜잭션이나 JDBC로 수행해 1차 캐시의 오래된 상태를 피한다.
- 실패 주입: P5 요청 기록 저장·커밋 실패, P6 저장 롤백, P5 완료 저장 실패를 구분한다. 리스너 실패 시 업무 상태와 해당 이벤트·리스너의 발행 기록을 함께 검사한다. 다른 테스트의 전체 발행 건수만으로 성공을 판정하지 않는다.
- 재전달: 실제 미완료 완료 기록을 재전달해 CAPTURED와 완료 추적을 확인한다. 단순 리스너 직접 호출 테스트로 대체하지 않는다.
- 동시성: latch로 같은 결제의 요청·완료 경합을 통제하고 P6 매입·정산 각 1건과 최종 상태·참조를 확인한다.
- 기존 #118 승인 단위·독립 트랜잭션 테스트는 필요한 경우 P6 처리 경계를 대역으로 분리해 승인 PENDING 검증을 유지한다. 별도의 실제 통합 테스트는 CAPTURED까지 기다린다. 기존 assertion을 지우거나 PENDING/CAPTURED 중 아무 값이나 허용하는 방식으로 약화하지 않는다.
- 기존 P6 테스트의 임의 paymentId는 P5 결제 없이 발행되고 있다. 새 P5 리스너를 추가하면 결제 없음으로 실패할 수 있으므로 P6 단독 테스트의 소비자 격리와 실제 P5·P6 테스트의 유효 결제 생성 범위를 명시적으로 나눈다. 테스트를 맞추기 위해 운영 리스너가 없는 결제를 무시하게 하지 않는다.

3~5번 단계에서 코드·테스트를 작성하고 `./gradlew spotlessApply check mariaDbTest`, 문서 링크 검사와 diff 검사를 통과했다. 단계별 기준과 실제 검증 범위는 아래 실행 기록과 [검증 문서](verification.md)에 기록한다.

## 대안과 영향

- 승인 서비스 반환 뒤 요청 발행: 승인만 커밋되고 요청이 유실될 간격이 있어 제외한다.
- 완료 전에 CAPTURED 반영: 실제 매입 실패와 불일치하므로 제외한다.
- 완료 중복을 예외 처리: 이미 반영한 정상 재전달도 계속 실패하게 되므로 CAPTURED는 정상 종료한다.
- 완료 이벤트의 captureId를 무조건 새 컬럼에 저장: 현재 합의된 SQL 범위를 넘으므로 별도 합의가 필요하다.
- 전역 비동기 활성화·자동 재발행: 다른 모듈 실행 방식까지 바꾸므로 이번 설계에서 자동 채택하지 않는다.

API 이벤트·DB·화면 변경은 없다. P5 내부 서비스·Entity·리스너와 관련 테스트·문서가 변경 대상이다. 새로운 공개 계약·저장 구조·기술 선택이 필요해지면 별도 ADR과 담당 합의로 분리한다.


## 3번 단계 실행 기록

2026-10-23, `c680eb9` + 2·3번 단계 미커밋 작업본 기준이다.

- PaymentAuthorizationService에 ApplicationEventPublisher를 주입하고 REQUEST_CAPTURE 분기에서 저장 결제 값으로 발행하도록 구현했다. 완료 수신·CAPTURED 반영은 아직 구현하지 않았다.
- 승인 서비스·흐름 단위 테스트에서 ALLOW 성공 필드 매핑·단일 발행, REVIEW·DENY·ERROR·거절·기술 실패의 미발행, 중복 승인 시 추가 미발행, 발행 예외 전파를 확인했다.
- PaymentCaptureRequestPersistenceTests 신규 2건에서 실제 MariaDB·Modulith·P6를 사용했다. 승인 참조와 요청 발행 기록이 커밋 전에 같은 트랜잭션에서 보이며 P6는 아직 처리하지 않았음을 확인했다. 커밋 후 P6의 매입·정산 각 1건 및 요청 처리 완료 기록을 확인했고, 커밋 직전 실패 주입 시 승인 참조·요청 기록 롤백과 P6 미처리를 확인했다. 최초 PENDING·기커밋 P2 평가는 남는다.
- 첫 실행에서 새 테스트 조회문의 금액 컬럼명을 수정한 뒤 `cd backend && ./gradlew spotlessApply check mariaDbTest`를 재실행해 BUILD SUCCESSFUL을 확인했다. 운영 SQL·P6 코드는 변경하지 않았다.
- 미검증·후속: P5 완료 리스너, CAPTURED 전이, 완료 중복·동시 수신, 수신 실패·실제 미완료 기록 재전달, 비동기 실행 여부는 4~5번 단계다. 이번 요청 발행 검증을 전체 매입 연동 완료로 보지 않는다.


## 4번 단계 실행 기록

2026-10-23, `c680eb9` + 2~4번 단계 미커밋 작업본 기준이다.

- CaptureCompletedListener·PaymentCaptureService·Payment.completeCapture를 추가했다. 리스너 트랜잭션에 MANDATORY 서비스가 참여하고 행 잠금 조회 후 상태를 반영한다. 이벤트 입력 오류, 없는 결제, 허용하지 않은 상태와 잘못된 승인 참조는 거절하며 이미 CAPTURED인 정상 재전달은 참조·금액을 유지한다.
- 단위 테스트에서 허용 상태·중복·입력·참조 검증과 리스너 예외 전파를 확인했다. 실제 Spring 프록시의 트랜잭션 없는 서비스 호출 거절도 검증했다.
- 기존 요청 통합 테스트에 실제 P5 완료 수신을 연결하여 CAPTURED와 완료 발행 기록의 처리 완료를 확인했다. 같은 완료 이벤트 재발행 시 상태·승인 참조 보존과 두 발행 기록의 완료를 확인했다.
- 기존 승인 독립 트랜잭션 테스트는 P6 CaptureService를 대역으로 분리하여 승인 상태 검증을 유지했다. P6 단독 통합 테스트 2개 클래스는 가상 paymentId 사용 범위를 유지하기 위해 P5 PaymentCaptureService를 대역으로 분리했다. 실제 양 모듈 연결은 PaymentCaptureRequestPersistenceTests에서 검증한다. 운영 P6 코드는 변경하지 않았다.
- `cd backend && ./gradlew spotlessApply check mariaDbTest` → BUILD SUCCESSFUL. 일반 검사 255건 중 254건 통과·기존 수동 테스트 1건 건너뜀, MariaDB 76건 통과. 실패·오류 없음.
- 미검증·후속: 완료 동시 수신, P5 완료 저장 실패와 미완료 기록의 실제 재전달, P6 실패 경계 및 실행 스레드 관찰은 5번 단계에서 보강한다. 이번 재발행 검증은 실제 미완료 기록 재전달 검증과 구분한다.


## 5번 단계 실행 기록

실제 완료 저장 실패·P6 저장 실패·동시 완료 수신 및 원래 미완료 기록 재전달 검증을 추가했다. `c680eb9` + 2~5번 단계 작업본에서 일반 254건 통과·1건 건너뜀, MariaDB 79건 통과했다. 실패·오류는 없다. 실패 주입 범위·실행 스레드 관찰·미검증은 [검증 기록](verification.md)의 #122 5번 단계에 구분했다.
운영 자동 재전달 정책은 추가하지 않았으며 다음 단계는 문서 최종 정리·커밋·PR이다.
