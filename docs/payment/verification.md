# P5 1차 시연 검증 기준

**W1 순수 규칙 테스트 42건의 과거 실행 기록은 아래에 보존한다.** #71의 Entity·Repository·최초 PENDING 저장 서비스 검증은 아래 단계별 실행 기록을 따른다. 아래 T01~T15는 전체 시나리오 기준이며, 평가·승인·모듈 연동까지 구현·검증됐다는 뜻은 아니다.
현재 업무 테이블 생성 SQL의 존재·스키마 검사는 실제 결제 저장·인계 동작의 검증과 구분한다.
정책과 계약은 [설계](design.md), 작업 W번호·미결 C번호는 [README](README.md)를 따른다.

## 필수 불변식

| ID | 항상 지킬 조건 | 테스트 |
| --- | --- | --- |
| I01 | FDS 전에 PENDING이 영속 저장된다. ERROR·타임아웃은 자동 ALLOW·DENIED·DECLINED로 처리하지 않고 승인하지 않는다 | T01·T02 |
| I02 | DENY는 승인 없이 DENIED, 승인 업무 거절은 DECLINED. 두 결과 모두 Case 생성 없음 | T02·T04 |
| I03 | REVIEW 승인 성공만 HELD로 통지한다. 1건도 대상이며 직원 ACCEPT 전 CAPTURE 금지 | T03·T04·T09 |
| I04 | 같은 업무 요청 재전송으로 승인·접수·실행·결과 반영 효과가 중복되지 않는다. 같은 키의 다른 내용은 충돌 | T08 |
| I05 | ALLOW는 승인 후 CAPTURE 요청, 직원 ACCEPT는 재승인 없이 CAPTURE 요청, REJECT는 VOID 요청이다 | T01·T05·T06 |
| I06 | ACCEPT는 P6 작업, REJECT는 P5 VOID 작업의 영속 접수가 확인되어야 최종 접수 완료로 통지한다 | T05·T06·T09 |
| I07 | 실제 매입 성공 전 CAPTURED, 실제 VOID 성공 전 VOIDED를 기록하지 않는다. Case CLOSED와 금융 실행 완료는 별개다 | T01·T07·T11 |
| I08 | 동일 승인에 대한 매입·VOID가 중복 성공하지 않는다. 중복·역순 결과가 최종 상태를 되돌리지 않는다 | T08·T10 |
| I09 | 커밋되지 않은 결과를 발행하지 않고, 커밋 후 유실·중복·응답 유실을 영속 기록으로 복구할 수 있다 | T08·T09·T11 |
| I10 | 가맹점·권한 경계와 공개 api 의존을 지킨다. P5는 P3/P6 내부 데이터·Case 상태를 직접 변경하지 않는다 | T12 |

## 최소 테스트 시나리오

U는 통제된 입력의 P5 단위 검증, D는 실제 MariaDB의 P5 영속 검증, X는 실제 참여 모듈/외부 연계 검증이다.
U의 성공은 D/X의 원자성·전달 보장을 증명하지 못한다. 모의 카드사는 정상적인 MVP 실행 대상이지만 P2/P3/P6 대역을 실제 연동으로 기록하지 않는다.

| ID·수준 | 입력·상황 | 예상 결과·관찰할 증거 | 선행 계약 |
| --- | --- | --- | --- |
| T01 · U/D/X | ALLOW·모의 승인 성공 | 최초 PENDING 커밋, 승인 참조 기록, CAPTURE 요청. 승인·P6 접수만으로 CAPTURED 아님. P6 실제 성공 후 CAPTURED와 CakeShop 결과 | C1·C2·C4~C7 |
| T02 · U/D/X | DENY, ERROR, FDS 타임아웃 각각 | DENY만 DENIED. 나머지는 승인 없음·PENDING 및 오류/복구 추적. P2의 확인된 평가 기록 유지 | C1·C2·C6 |
| T03 · U/D/X | REVIEW·승인 성공 1건 | HELD·승인 참조 및 커밋 후 통지, Case 1건 연결. 직원 판정 전 CAPTURE 없음 | C1~C3·C5·C6 |
| T04 · U/D/X | ALLOW/REVIEW·모의 승인 업무 거절 | DECLINED, CAPTURE·HELD 이벤트 없음, Case 미생성. FDS 평가 이력 유지 | C1·C2·C6 |
| T05 · U/D/X | HELD 결제 ACCEPT, P6 수신 지연·재기동 | 재승인 없음. P5 접수만으로 P3 최종 ACCEPTED 아님. P6 작업 커밋·작업 ID 확인 후에만 접수 완료 통지. 실제 매입 성공은 별도 확인 | C3~C6 |
| T06 · U/D/X | HELD 결제 REJECT, VOID 실행 지연 | P5 VOID 요청 커밋 후 동기 응답/결과 이벤트로 접수 확인. VOID 실행 전에도 P3는 인계 완료 가능. 실제 성공 전 VOIDED 아님 | C3·C5·C6 |
| T07 · D/X | 영속 접수 후 CAPTURE/VOID 실행 실패·재시도 | 작업·오류가 남고 성공 상태를 미리 기록하지 않음. Case CLOSED·직원 판정은 보존. 복구 성공 후에만 최종 상태·통지 반영 | C4~C7 |
| T08 · U/D/X | 최초 결제·판정 조치·P6 확인·실행 결과 각각 동일/충돌 요청, 커밋 후 응답 유실 | 동일 요청은 기존 식별자·결과 재생, 다른 본문/반대 조치는 계약상 충돌. 실제 승인·작업·실행 효과 1회. 재기동 후에도 확인 가능 | C1~C6 |
| T09 · D/X | 상태/접수 저장 도중 실패, 커밋 후 HELD·CAPTURE·접수 결과 통지 유실·중복 | 롤백 데이터가 발행되지 않음. 커밋된 데이터는 재전달/조회·대조로 복구. 고아 HELD 방치·중복 Case 연결 없음 | C3~C6 |
| T10 · D/X | ACCEPT/CAPTURE와 VOID 동시 요청, 실행 결과가 접수 확인보다 먼저 도착 | 승인별 조치 경합 통제, 이중 금융 성공 없음. 요청·작업 ID 검증과 순서 역전 복구, 기존 최종 상태 보존 | C3·C4·C6 |
| T11 · D/X | 금융 성공 후 CakeShop 통지 실패·중복·역순 도착 | 금융 성공 기록 유지. 같은 결과 재통지/조회로 복구하고 주문을 과거 상태로 되돌리지 않음 | C7 |
| T12 · U/구조/X | 다른 가맹점 결제 조회·금액/주체 변조, AI 조회, 모듈 경계 검사 | 무권한 조회·변경 거부. 신뢰된 금액 사용. 조사 모듈이 Case 권한을 검증하고 P5는 자기 범위를 검증. 타 모듈 내부 접근·변경 없음 | C1·C3·C7 |
| T13 · D | 최초 payment/request 저장 중 실패, 키/작업/판정/이력 제약 경합 | 최초 2행+생성 이력은 함께 커밋/롤백. 같은 키의 같은 본문은 원 payment 재생. 다른 가맹점은 독립 키, 같은 가맹점의 다른 본문은 충돌. 같은 AUTH/조치의 중복 행 없음 | C1·C3·C6 |
| T14 · U/D/X | 복구 가능한 접수 거절 뒤 동일 키 재접수, P3 통지·P6 접수 확인의 중복/역순 | 원 P5 작업 ID 유지, 접수 버전 증가. 오래된 REJECTED가 최신 ACCEPTED를 덮어쓰지 않음. 같은 소스 버전의 다른 작업 ID/본문은 충돌·대조 | C3~C6 |
| T15 · D/X | 실행 중 프로세스 종료, UNKNOWN 결과, 늦은 이전 시도 성공, 모의 성공 반영 중 DB 실패 | 실행 불명에서는 자동 재실행 금지·대조. 모순 성공은 무시하지 않고 추가 실행 중지. 실패·확인 이력 보존. 내부 모의 AUTH/VOID의 업무 효과·작업 성공·공개 결과는 같은 커밋 | C4·C6 |

점수 경계 테스트는 C2 합의 후 P2/P5 계약 검증으로 추가한다. 50/80을 확정값으로 고정하지 않는다.
EXPIRED 자동 전이·만료 스케줄러·카드 한도·토큰 재발급은 현재 완료 조건에 넣지 않는다. 해당 범위를 추가하면 별도 정책·데이터·검증이 필요하다.

## 통합 시연 D1 — 혼합 판정

기획 §2.14·DB 가이드 §4를 따른다. 금액과 시간은 가상 시연값이다.

1. 같은 가맹점·고객의 B(300,000), C(500,000)를 차례로 접수한다. 각각 PENDING → REVIEW·승인 성공 → HELD를 확인한다.
2. 최초 Case 생성 후 30분 미만·직원 판정 전에 C를 병합한다. P3/P4의 증거 버전·보고서 v1 보존·v2 생성을 확인한다.
3. 직원이 최신 보고서를 보고 B ACCEPT, C REJECT를 확정한다. P3 판정·인계 REQUESTED 커밋 이후 P5 호출을 확인한다.
4. B는 P6 CAPTURE 작업 ID, C는 P5 VOID 작업 ID로 최종 영속 접수를 확인한다. 모든 인계 ACCEPTED 후 Case CLOSED가 된다.
5. B CAPTURED·C VOIDED 및 CakeShop 반영을 확인한다. P6 1차는 이벤트 수신 후 즉시 매입하므로, Case 종료 뒤 매입 실행을 필수 순서로 요구하지 않는다.
6. 접수·실행 분리와 장애 복구 검증은 C4·C5 확정 후 지연·실패를 주입해 수행한다. 금융 처리가 CLOSED 전후에 끝나도 종료 조건은 영속 접수 확인이며, 이후 실행 실패만으로 Case를 재개하지 않는다.

Case 최초 생성 후 정확히 30분 이상이거나 직원 판정 확정 후인 추가 REVIEW 결제는 새 Case로 간다.
병합·AI·판정·Case 종료 자체는 P3/P4 검증이며, P5 단독 완료로 표시하지 않는다.

## 저장·DTO 초안의 검토 기준

- 현재 `payments` 8개 컬럼·BIGINT PK와 미합의 보조 저장 3개를 구분한다. `auth_expires_at`·`rule_set_version`은 현재 SQL에 없으며 담당 ERD 합의 없이 추가하지 않는다.
- 필수/NULL 조건: 직원 조치 decisionId, 최종 접수 targetOperationId·acceptedAt, 실행 성공 resultRef, 실패 errorCode를 검증한다.
- DTO의 paymentId·merchantId·operationId·승인 참조·금액 불일치는 부작용 없이 거절하고 기존 정본을 유지한다.
- 접수 receiptVersion/sourceVersion과 실행 attemptNo를 구분한다. 타임스탬프만으로 덮어쓰기 순서를 정하지 않는다.
- P3/P6의 키·버전·조회·재전달 계약과 P5 저장안은 합의 전 제안으로 남긴다. 예시 타입·제약을 확정 DDL로 간주하지 않는다.
- P5/P6의 이벤트 타입은 `settlement.api`에 두고, P6가 P5에 동기 접수 확인·실행 허가·결과 ACK를 요청하는 이전 초안을 사용하지 않는다.
- P6의 즉시 매입·PROCESSING 선행 커밋 없음 조건에서 영속 접수 근거와 실행 실패 기록·접수 결과 발행이 성립하는지 C4에서 확인한다.

## 완료 증거

- 단위 테스트는 분기·호출 여부, DB 테스트는 실제 저장·재기동·유니크·커밋/롤백, 연동 테스트는 실제 수신 모듈의 접수·실행을 관찰한다.
- DB 검증은 커밋/롤백 이후 새 트랜잭션에서 수행한다. 동시 테스트는 별도 연결과 실행 순서 제어를 사용한다.
- 실패 주입은 DB flush/commit, 이벤트 저장·전달, 접수 응답·결과 통지 유실, 실행 결과 불명까지 구분한다.
- 변경 영역 테스트와 `cd backend && ./gradlew check`를 실행한다. DB·재기동·연동 검사는 별도 명령이 필요하면 함께 기록한다.
- 실행 커밋·미커밋 변경·환경·명령·실제 결과·증거 위치·대역 범위·미검증 항목을 남긴다. 과거 커밋 결과를 현재 검증으로 쓰지 않는다.

아래 기록은 W1 도입 당시 작업본의 결과다. 이후 #56에서 추가된 업무 테이블 SQL과 현재 모듈 의존 방향 검사를 대신하지 않는다.
업무 DB 저장·모듈 연동·실제 금융 처리는 별도 검증이 필요하다.

## 문서 자체 검사 기록

2026-10-22, Issue #62: 코드 기준 `ba9427f` 위 문서 작업본에서 수행했다.

- `python3 .github/scripts/check_md_links.py` → Markdown 상대 링크 이상 없음.
- `git diff --check` → 공백 오류 없음.
- `cd frontend && npm run format:check` → 통과.
- 문서만 변경했다. Backend·DB·Frontend 빌드·AI 테스트는 이번 작업에서 재실행하지 않았으며, 아래 과거 실행 결과를 현재 검증으로 간주하지 않는다.

2026-10-21, 코드 기준 `475bbaa` 위 문서 작업본에서 수행했다. 업무 테스트 결과와 구분한다.

- 저장소 Markdown 상대 링크 검사: 이상 없음.
- 이번 저장·DTO 초안: P5 문서 3개·ADR-0010·ADR 목록의 markdownlint 오류 0(MD013·MD060 제외).
- 새 결제 흐름 Mermaid 1개: Mermaid 12.2.0 문법 파싱 통과.
- 필드·ID·NULL 조건·DTO 상관관계의 문서 대조 및 변경 문서 공백 검사 통과. GitHub 실제 렌더링·업무 코드·DB·모듈 연동은 미검증.

## #71 Entity 단계 실행 기록

2026-10-22, `feature/71-payment-persistence`, 기준 커밋 `e9ab2eb` + Entity·테스트 미커밋 작업본에서 실행했다.

- 실행: `cd backend && ./gradlew spotlessApply check mariaDbTest` → BUILD SUCCESSFUL.
- `check`: PaymentTests 10건, 기존 PaymentRulesTests 42건, 구조 검사 5건으로 총 57건 통과. 실패·오류·건너뜀 0.
- `mariaDbTest`: 기존 Flyway 검사 3건과 [PaymentMappingTests](../../backend/src/test/java/com/donworry/payment/entity/PaymentMappingTests.java) 1건으로 총 4건 통과. 실패·오류·건너뜀 0.
- 생성 시 ID·승인 참조가 NULL이고 상태가 PENDING임을 확인. 필수값 누락·참조 길이 초과 거절과 DATETIME(6) 정밀도 처리 검증.
- MariaDB 11.8에서 기존 Flyway 전체 적용·JPA validate, ID 자동 생성, 커밋 후 새 트랜잭션의 전체 필드 재조회, 상태 문자열·UTC 시각의 실제 저장값을 확인.
- Repository·저장 서비스·입력 인증·가맹점 존재 확인·요청 멱등성·P2 호출은 아직 구현·검증하지 않았다. Entity 매핑 테스트를 결제 접수 전체 검증으로 해석하지 않는다.

## #71 Repository 단계 실행 기록

2026-10-22, `feature/71-payment-persistence`, 기준 커밋 `41c75d1` + Repository·테스트 미커밋 작업본에서 실행했다.

- 실행: `cd backend && ./gradlew spotlessApply check mariaDbTest` → BUILD SUCCESSFUL.
- `check`: 기존 단위·구조 검사 총 57건 통과. 실패·오류·건너뜀 0.
- `mariaDbTest`: [PaymentRepositoryTests](../../backend/src/test/java/com/donworry/payment/repository/PaymentRepositoryTests.java) 3건과 기존 Flyway·Entity 매핑 검사 4건으로 총 7건 통과. 실패·오류·건너뜀 0.
- 실제 MariaDB 11.8에서 Repository Bean 생성, 저장 커밋 후 새 트랜잭션의 ID 조회·전체 필드 보존, 롤백된 결제 미저장, 없는 ID의 빈 조회 결과를 확인.
- Repository는 저장·ID 조회만 노출한다. 트랜잭션 경계는 테스트에서 지정했으며 업무 저장 서비스는 후속 단계다.
- 업무 입력 인증·가맹점 존재 확인·요청 멱등성·P2 호출·실제 결제 접수 흐름은 미검증이다. 다른 담당 계약 변경은 없다.

## #71 최초 저장 서비스 단계 실행 기록

2026-10-22, `feature/71-payment-persistence`, 기준 커밋 `874b1b0` + 저장 서비스·테스트 미커밋 작업본에서 실행했다.

- 실행: `cd backend && ./gradlew spotlessApply check mariaDbTest` → BUILD SUCCESSFUL.
- `check`: 기존 단위·구조 검사 총 57건 통과. 실패·오류·건너뜀 0.
- `mariaDbTest`: [PaymentCreationServiceTests](../../backend/src/test/java/com/donworry/payment/service/PaymentCreationServiceTests.java) 3건과 기존 검사 7건으로 총 10건 통과. 실패·오류·건너뜀 0.
- 실제 MariaDB 11.8에서 서비스 반환 ID로 새 트랜잭션 조회, 입력값·PENDING·승인 참조 NULL·서비스 생성 시각 보존을 확인.
- 호출자 트랜잭션을 롤백해도 별도 커밋한 PENDING이 남는 것을 확인. 필수값 누락 시 결제 행이 추가되지 않음을 확인.
- 기존 스키마를 사용하며 migration·다른 모듈 계약은 변경하지 않았다. 생성 시각은 서비스에서 정하고, 외부 입력으로 ID·상태·승인 참조를 받지 않는다.
- 가맹점 존재·권한·신뢰할 금액 검증, 요청 멱등성, HTTP·CakeShop·P2 호출·승인·이벤트는 미구현·미검증이다. 이 단계는 내부 최초 저장 기반만 검증하며 W2 전체 완료가 아니다.

## #94 평가 결과 분기 실행 기록

2026-10-23, `feature/94-fds-result-handling`, 기준 커밋 `49a6967` + 이후 `b13c6e2`로 커밋한 코드·테스트 작업본에서 실행했다.

- `cd backend && ./gradlew spotlessApply check` → BUILD SUCCESSFUL. 전체 122건 통과, 실패·오류·건너뜀 0.
- [PaymentEvaluationDecisionTests](../../backend/src/test/java/com/donworry/payment/service/PaymentEvaluationDecisionTests.java) 16건: 결과 4종, 부적합 상태 8종, 입력 누락·null 응답·요청 ID 불일치·호출 예외를 검증했다.
- P2 테스트 대역으로 요청 전달과 응답·판단 보존, 호출 전 상태 차단, 예외 전달·자동 재시도 없음을 확인했다.
- ALLOW·REVIEW는 승인 전 PENDING과 승인 요청 판단, DENY는 DENIED, ERROR는 오류 기록 필요 판단을 반환한다. REVIEW만으로 HELD가 되지 않는다.
- 실제 P2·DB 통합, Spring Bean 연결, 판단 결과의 DB 반영, 오류 기록·승인·이벤트 실행은 미검증이다. `mariaDbTest`는 재실행하지 않았다. 이번 변경은 DB 스키마·저장 로직을 바꾸지 않는다.
- State에는 결제 ID가 없으므로, State와 평가 입력이 같은 결제라는 보장은 후속 결제 흐름에서 같은 저장 데이터로 구성해 확보해야 한다.

## W1 실행 기록

2026-10-21, `feature/p5-state-rules`, 기준 커밋 `b3e5799` + W1 미커밋 작업본에서 실행했다.

- 실행: `cd backend && ./gradlew check` → BUILD SUCCESSFUL.
- [PaymentRulesTests](../../backend/src/test/java/com/donworry/payment/service/PaymentRulesTests.java): 42건 통과, 실패·오류·건너뜀 0.
- 기존 ArchitectureTests 3건·ModularityTests 2건 통과. 전체 47건 통과.
- T01~T04: FDS별 승인 필요 여부, 승인 성공/업무 거절/기술 실패의 상태·후속 단계 판단만 검증.
- T05~T06: HELD의 ACCEPT/REJECT가 재승인 없이 CAPTURE/VOID 요청 종류를 반환하는 부분만 검증.
- 승인 성공/불명·종료 결제의 재처리 차단, 잘못된 상태/승인 조합·누락 입력 거절 검증.
- 반환된 NOTIFY_HELD·REQUEST_CAPTURE·REQUEST_VOID·RECORD_FDS_ERROR 등은 판단 결과이며 실제 호출·이벤트·기록을 실행하지 않았다.
- DB 선저장·트랜잭션·원장·요청 키 멱등성·직원 권한·실제 FDS/Case/P6/CakeShop 연동은 미검증. W1 통과를 T01~T15 전체 통과로 해석하지 않는다.

## #100 최초 저장·평가 흐름 통합 실행 기록

2026-10-23, `feature/100-payment-evaluation-flow`, 기준 커밋 `a74c83b` + 3~6번 단계 미커밋 작업본에서 실행했다.

- `cd backend && ./gradlew spotlessApply check mariaDbTest` → BUILD SUCCESSFUL. 일반 검사 159건, MariaDB 검사 30건 통과. 실패·오류·건너뜀 모두 0.
- [PaymentEvaluationFlowPersistenceTests](../../backend/src/test/java/com/donworry/payment/service/PaymentEvaluationFlowPersistenceTests.java) 3건은 실제 P2 Bean·MariaDB를 사용한다. ALLOW, 동일 고객 세 번째 고액 결제의 REVIEW, 호출자 롤백 후 결제와 평가의 별도 커밋 보존을 확인했다.
- 저장된 결제와 평가의 paymentId·customerRef·요청 시각을 DB에서 비교하고, P2 HIGH_AMOUNT 근거의 금액이 결제 저장 금액과 일치함을 확인했다. 결제마다 평가 한 건, 새 결제들의 요청 UUID가 서로 다름을 확인했다.
- [PaymentEvaluationFlowFailureTests](../../backend/src/test/java/com/donworry/payment/service/PaymentEvaluationFlowFailureTests.java) 3건은 실제 P5·MariaDB에 `FdsEvaluator` 대역을 주입한다. 평가 시작 시 호출자 트랜잭션이 중단되어 있고 별도 트랜잭션에서 최초 결제를 조회할 수 있음을 확인했다. 호출 예외·호출자 롤백 후 PENDING 보존과 평가 단일 호출을 확인했다.
- 대역 DENY·ERROR 응답에서는 반환 판단만 달라지고 DB 결제는 승인 참조 없는 PENDING으로 유지된다. 이는 실제 P2의 DENY·ERROR 생성·저장 검증이 아니다. 현재 실제 P2 규칙의 최대 점수는 60점, DENY 기준은 80점이다.
- 미검증·후속: 실제 승인·상태 변경·오류 이력·이벤트, HTTP·CakeShop, 중복 접수·재시작 후 복구. 동일 요청 UUID에 다른 입력을 넣는 경우의 검증은 #95에서 합의한 P2 후속 과제다. 스키마·P2 공개 계약 변경은 없다.
