# P5 1차 MVP 검증 계획과 실행 기록

2026-10-23 · #104. 아래 M 항목은 [ADR-0018](../decisions/0018-payment-mvp-capture-completion.md) 제안을 채택한 뒤 구현할 검증 계획이며 실행 결과가 아니다. 기존 I/T 번호에 대한 과거 기록은 당시 의미로 보존한다. 이전 계획은 Git `ae28a7c`에 남아 있다.

## 필수 검증 계획

| ID | 상황 | 관찰할 결과 |
| --- | --- | --- |
| M01 | 최초 저장·평가 실패 | FDS 전에 PENDING 커밋. ERROR·타임아웃을 자동 승인/거절로 바꾸지 않음 |
| M02 | ALLOW/REVIEW/DENY·승인 거절 | ALLOW 승인 후 매입 요청, REVIEW 승인 후 HELD, DENY는 승인 없이 DENIED, 승인 업무 거절은 DECLINED |
| M03 | 직원 ACCEPT/REJECT | 재승인 없이 CAPTURE/VOID 선택. 같은 요청 재전송과 반대 조치를 구분 |
| M04 | P6 정상 저장·완료 수신 | 매입 COMPLETED·정산 SCHEDULED·발행 기록 동시 커밋 후 P5 CAPTURED. 승인/요청만으로 CAPTURED 금지 |
| M05 | P6 저장 실패 | 매입·정산·완료 발행 기록 함께 롤백. 완료 통지 없음. 합의된 실패 기록·재시도 확인 |
| M06 | 저장 성공 후 완료 통지 유실·중복 | 기존 결과 재전달, 매입·정산 각 1건 유지, 기존 captureId·capturedAt 유지 |
| M07 | P5 상태 반영 또는 P3 통지 기록 실패 | P5 소비 트랜잭션 원자성. 재처리 후 상태와 후속 통지가 모두 반영됨 |
| M08 | 직원 ACCEPT 완료 통지 | P3가 원 판정·인계를 대조해 ACCEPTED와 합의된 ID·시각 저장. ALLOW에는 P3 인계 생성 없음 |
| M09 | 완료 지연·실패·P3 통지 유실 | 성공 확인 전 인계 미완료·Case 미종료. 합의된 REQUESTED/FAILED 기준과 재전달·재시도 검증 |
| M10 | 잘못된 ID·상태·동시 CAPTURE/VOID | 없는 결제·모순 매입·VOIDED를 무조건 CAPTURED로 변경하지 않음. 이중 성공 차단 |
| M11 | VOID 및 혼합 판정 | 실제 성공 전 VOIDED 금지. VOID 접수 증거·시점 합의 후 모든 판정·인계 완료 시에만 Case CLOSED |
| M12 | CakeShop·권한·모듈 경계 | 실제 결과 통지, 유실·중복·역순 복구, 가맹점 격리, 공개 api 의존 준수 |

단위 테스트는 분기 판단을, MariaDB 테스트는 새 트랜잭션 재조회로 커밋·롤백·유니크·동시성을, 실제 연동 테스트는 P3/P6/CakeShop의 수신·상태 반영을 확인한다. 대역 테스트를 실제 연동으로 기록하지 않는다.
DB/발행 기록/전달 실패를 구분해 주입한다. 이벤트 재발행 설정과 운영 재시도 경로를 구현·확인해야 한다. 기본 오류 이력·멱등 요구는 유지하며, 미합의 보조 테이블이나 시도별 UNKNOWN 프로토콜을 검사 전제로 삼지 않는다.

## 통합 시연 계획

1. REVIEW·승인 성공 결제 B/C의 HELD와 P3 Case 연결을 확인한다.
2. P3/P4 조사 후 B ACCEPT·C REJECT를 판정·인계 REQUESTED와 함께 저장한다.
3. B의 P6 매입·정산 동시 저장과 완료 전달, P5 CAPTURED, P3 ACCEPTED 순서를 확인한다.
4. C의 VOID 처리와 접수 확인은 별도 합의한 계약으로 검증한다. 모든 판정·인계 완료 후 Case CLOSED를 확인한다.
5. B 저장 실패·통지 지연을 주입하면 인계가 미완료로 남아 Case가 조기 종료되지 않는지 확인한다. 실패 분류·재시도 후 복구는 C3~C6 합의에 따른다.

Case 병합·AI·판정 자체는 P3/P4 책임이다. 실제 점수 생성은 P2 계약에 따르며 가상 금액만으로 REVIEW 발생을 보장하지 않는다. 기존 정본의 실행 전 Case 종료 시연과 달라지는 CAPTURE 순서는 담당 합의가 필요하다.

## 완료 증거

- 구현 PR은 실행 커밋·명령·환경·실제 결과·대역 사용·미검증을 남긴다.
- 코드 변경 시 해당 검사와 `cd backend && ./gradlew check`, DB 변경 시 `mariaDbTest`를 실행한다.
- 문서 작업 #104는 상대 링크·공백을 검사한다. 코드·DB·업무 연동 테스트 결과로 해석하지 않는다.

## #104 문서 축소 검사 기록

2026-10-23, `feature/104-payment-mvp-docs`, 기준 `ae28a7c` 위 문서 작업본에서 수행했다.

- `python3 .github/scripts/check_md_links.py` → Markdown 상대 링크 이상 없음.
- `git diff --check` → 공백 오류 없음.
- Python으로 Git 기준본과 대조: 아래 과거 실행 기록과 설계의 P2 계약 절이 그대로 보존됐음을 확인했다. 코드·migration·채택된 ADR-0012 변경 없음도 확인했다.
- 미검증: Backend·MariaDB·Frontend·AI 테스트, Mermaid/GitHub 렌더링, 실제 P3/P6/CakeShop 연동. 문서만 변경하여 업무 검사를 재실행하지 않았다.
- P3·P6 계약 합의와 Notion 동기화는 완료하지 않았다. #100의 구현·테스트는 별도 브랜치에 남겨 두었다.

최신 dev `d9e4251` 병합 후 ADR 목록 충돌을 해결하고 이 제안 번호를 0016으로 옮겼다. P3의 현재 목 접수·시각 처리와 제안의 차이를 코드로 대조했다. 같은 문서 검사 명령을 다시 실행하며, dev에서 들어온 P2/P3 코드의 업무 테스트는 이 문서 PR에서 재실행하지 않는다.

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

### #100 문서 정리 후 최종 점검

2026-10-23, `4bf6c04` + 문서 수정 작업본에서 `cd backend && ./gradlew spotlessApply check mariaDbTest`를 실행해 BUILD SUCCESSFUL을 확인했다. 모든 태스크는 UP-TO-DATE로 기존 결과를 재사용했다. 새 테스트 실행으로 기록하지 않는다. 현재 backend 코드·테스트는 `4bf6c04`와 동일하며, 남아 있는 XML 결과는 일반 검사 159건·MariaDB 30건, 실패·오류·건너뜀 0이다.

README의 연결 미구현 설명과 설계의 통합 검증 예정 설명을 현재 구현·검증 범위로 갱신했다. `python3 .github/scripts/check_md_links.py`와 `git diff --check`도 통과했다. 위 미검증·후속 범위와 P2 후속 합의는 유지한다.


## #118 승인 연결·트랜잭션 검증 (6번 단계)

2026-10-23, `feature/118-payment-authorization`, 기준 커밋 `4a41a3f` + 2~6번 단계 미커밋 작업본에서 실행했다.

- `cd backend && ./gradlew spotlessApply check mariaDbTest` → BUILD SUCCESSFUL. 일반 검사 234건 중 233건 통과·1건 건너뜀, MariaDB 검사 68건 통과. 실패·오류 0. 건너뛴 기존 LocalSettlementDemoTests는 개발 DB용 수동 실행 환경 변수가 필요한 테스트다.
- [PaymentAuthorizationPersistenceTests](../../backend/src/test/java/com/donworry/payment/service/PaymentAuthorizationPersistenceTests.java) 신규 9건 통과. Spring 프록시·실제 MariaDB·P2 평가를 사용하고 승인기는 테스트 대역으로 제어한다. 정상 성공 대역은 MockPaymentAuthorizer에 위임한다.
- 실제 P2 ALLOW·REVIEW에 따른 PENDING/HELD 및 승인 참조 커밋, 호출자 트랜잭션 롤백 이후 승인 결과와 평가 기록 보존을 확인했다.
- DENY·ERROR는 평가 문맥 대역으로 상태 반영과 승인기 미호출을 확인했다. 실제 P2에서 해당 판정을 생성·저장한 검증은 아니다.
- 업무 거절 커밋과 중복 처리 차단, 모의 기술 실패 뒤 PENDING·평가 보존 및 동일 문맥의 명시적 재처리를 확인했다.
- REVIEW 성공의 HELD·승인 참조를 flush하여 SQL 반영을 확인한 후 beforeCommit 예외를 주입했다. 호출자에게 성공을 반환하지 않고 두 값이 모두 최초 PENDING·null로 롤백되며 P2 평가는 남는 것을 확인했다. 실제 DB 장애·커밋 응답 유실을 재현한 검사는 아니다.
- 두 스레드에서 동일 결제를 처리했다. latch로 첫 승인을 멈춘 동안 두 번째 요청이 완료되지 않음을 확인하고, 첫 커밋 뒤 두 번째 충돌·승인 호출 1회, 첫 롤백 뒤 두 번째 성공·총 호출 2회를 확인했다.
- 기존 평가 전용 흐름 테스트도 유지·통과했다. 모듈 경계·계층·포맷 검사 포함.
- 미검증·후속: 외부 카드사, 실제 DB 연결 장애·응답 유실, 프로세스 재시작 복구, 주문 단위 중복 접수, P3/P6 이벤트·HTTP·CakeShop. 거절 코드·오류 이력의 영속 저장은 구현하지 않았다. 스키마·다른 담당 공개 계약 변경은 없다.


## #122 5번 단계: 실패·재전달·동시 수신 검증

2026-10-23, `feature/122-payment-capture-events`, `c680eb9` + 2~5번 단계 미커밋 작업본에서 실행했다.

- `cd backend && ./gradlew spotlessApply check mariaDbTest` → BUILD SUCCESSFUL. 일반 255건 중 254건 통과·기존 LocalSettlementDemoTests 1건 건너뜀, MariaDB 79건 통과. 실패·오류 0. 문서 링크 검사와 git diff --check도 통과했다.
- PaymentCaptureRecoveryTests 신규 3건은 실제 P5·P6·P2·MariaDB·Modulith를 사용하고 실패 지점과 잠금 대기를 spy로 통제한다. 이번 단계에서 운영 코드는 변경하지 않았다.
- P5 완료 트랜잭션에서 CAPTURED를 flush한 뒤 커밋 직전 예외를 주입했다. P5는 승인 참조가 있는 PENDING으로 롤백되고 P6 매입·정산 각 1건 및 원래 완료 발행 기록은 보존됐다. 실패한 완료 발행 기록 ID를 IncompleteEventPublications 공개 API로 선택해 재전달하고 같은 기록의 완료 및 CAPTURED를 확인했다. 새 완료 이벤트 발행이나 DB 기록 직접 수정으로 대체하지 않았다.
- P6 정산 저장 실패 시 매입·정산·완료 발행 기록은 남지 않고 P5 승인과 미완료 요청 기록은 유지됐다. 원래 요청 기록을 재전달하여 재승인·재평가 없이 매입·정산·CAPTURED까지 이어짐을 확인했다.
- 완료 실패로 PENDING을 유지한 실제 매입에 대해 동일 값의 독립 완료 이벤트 두 건을 서로 다른 트랜잭션에서 발행했다. 첫 리스너가 행 잠금을 잡은 상태에서 둘째 리스너의 잠금 시도와 대기를 latch로 확인했다. PENDING을 관찰한 처리는 1회이며 두 새 발행 기록은 모두 완료됐고, 원래 실패 기록도 재전달 후 정상 완료됐다. 상태·승인 참조 보존과 매입·정산 각 1건을 확인했다.
- 실행 관찰: 완료 리스너는 Test worker가 아닌 task-* 스레드에서 실행됐다. 설치된 Modulith 2.1.1 EventPublicationAutoConfiguration의 AsyncEnablingConfiguration(@EnableAsync)도 소스로 확인했다. 서비스 반환만으로 후속 완료를 판단하지 않고 발행 기록과 DB 상태를 기다린다.
- 테스트 작성 중 프록시를 통한 대역 설정, 동기 실행 가정, 같은 이벤트 객체의 동시 재발행을 수정했다. Modulith의 진행 중 발행 추적은 객체 identity를 사용하므로 독립 전달 테스트는 값이 같은 별도 객체를 사용한다. 마지막 전체 검사 결과는 위 집계다.
- 미검증·후속: 실제 DB 네트워크 장애·프로세스 중단·응답 유실, 자동 재시도 스케줄러·운영 복구 API, 같은 paymentId의 상이한 captureId 진위 대조, 직원 ACCEPT·P3 통지·VOID·외부 카드사·HTTP·CakeShop. 테스트의 실패 주입과 명시적 재전달 통과를 운영 자동 복구 구현으로 표현하지 않는다.


## #136 4번 단계: HELD 발행 연결

2026-10-25, `feature/136-payment-held-event`, `c19f94f` + 3·4번 단계 작업본 기준이다.

- PaymentAuthorizationService의 NOTIFY_HELD 분기에서 저장 결제 필드·성공 승인 참조·해당 REVIEW 평가 ID와 HELD 전환 시각으로 PaymentHeld를 발행한다. 기존 REQUIRES_NEW 트랜잭션과 행 잠금·반복 승인 차단을 유지했다.
- 단위·흐름 테스트에서 저장값 매핑(외부 입력과 다른 고객·금액 포함), 전환 시각 범위, 반복 승인 후 추가 발행 없음, 다른 분기·승인 실패의 미발행 및 발행 예외 전파를 확인했다. 기존 ALLOW CaptureRequested 검사도 유지했다.
- `cd backend && ./gradlew spotlessApply check` → BUILD SUCCESSFUL. 모듈 경계·계층·기존 일반 검사 통과.
- `python3 .github/scripts/check_md_links.py`, `git diff --check` → 통과.
- 미검증: 이번 단계에서 MariaDB 검사를 실행하지 않았다. 실제 HELD 저장과 발행 기록의 동시 커밋·롤백, 커밋 후 전달·재전달·동시 승인 검증은 5번 단계다. 단위 테스트의 예외 전파를 DB 롤백 검증으로 표현하지 않는다. P3 운영 리스너·Case 연결은 별도 담당 작업이며 현재 운영 수신자가 없는 상태에서 전달 기록 보장을 주장하지 않는다.


## #136 5번 단계: HELD 전달·롤백·재전달 통합 검증

2026-10-25, `feature/136-payment-held-event`, `d90d584` + 5번 단계 작업본 기준이다. 운영 코드는 변경하지 않았다.

- [PaymentHeldPersistenceTests](../../backend/src/test/java/com/donworry/payment/service/PaymentHeldPersistenceTests.java) 신규 4건은 실제 P2·P5·MariaDB·Modulith와 테스트 전용 @ApplicationModuleListener를 사용한다. P3 Case 처리 구현을 대체하거나 검증하는 테스트가 아니다. 승인 성공은 제어 가능한 대역에서 기존 MockPaymentAuthorizer에 위임한다.
- 실제 P2의 REVIEW 평가 문맥을 만든 뒤 승인·HELD 전환을 수행했다. 커밋 직전 flush 후 HELD·승인 참조·수신자 발행 기록 1건과 수신 미실행을 확인했다. 정상 커밋 후 별도 스레드·트랜잭션의 수신자가 커밋된 HELD·참조를 읽고 7개 필드가 원본과 일치함을 확인했다.
- 커밋 직전 예외 주입 시 HELD·참조·발행 기록은 함께 롤백되고 최초 PENDING·기커밋 P2 평가는 보존됐다. 발행 기록 없음과 수신 미실행을 확인했다. 실제 DB 네트워크 장애를 재현한 검사는 아니다.
- 수신 실패 시 발행 기록의 FAILED·미완료 상태와 P5 HELD·승인 참조 보존을 확인했다. IncompleteEventPublications 공개 API로 원래 발행 기록 ID만 재전달한 뒤 같은 기록의 완료를 확인했다. 역직렬화된 새 객체의 모든 값(heldAt 포함)이 원래 이벤트와 같고 재승인·재평가·새 발행 기록 생성이 없음을 확인했다.
- 같은 결제의 반복 승인 차단과 동시 승인 두 건을 검증했다. latch로 첫 승인 트랜잭션을 유지하는 동안 두 번째 호출이 대기함을 확인하고, 첫 커밋 후 두 번째 충돌과 승인 1회·발행 기록 1건·수신 1회를 확인했다.
- 첫 실행에서 테스트 관찰용 필드를 Spring 프록시에서 직접 읽어 실패했다. 실제 수신 대상의 관찰 필드를 읽도록 수정했으며, 실제 이벤트 호출의 프록시·트랜잭션·비동기 처리는 유지했다.
- 최종 `cd backend && ./gradlew spotlessApply check mariaDbTest` → BUILD SUCCESSFUL. 일반 311건 중 310건 통과·기존 수동 LocalSettlementDemoTests 1건 건너뜀, MariaDB 98건 통과. 실패·오류 0. 모듈 경계·계층·기존 ALLOW 매입 검사 포함.
- `python3 .github/scripts/check_md_links.py`, `git diff --check` → 통과.
- 미검증·후속: P3 운영 리스너·실제 Case 생성/병합/중복 연결, 실제 DB 장애·프로세스 재시작·응답 유실, 운영 자동 재시도. 실패 수신자의 업무 DB 변경 롤백은 테스트하지 않았다. 운영 리스너 등록 전의 발행은 P3 전달 기록 보장이 없으며, 테스트 전용 수신자의 검증을 운영 P3 연동 완료로 표현하지 않는다.


## #139 3번 단계: 결제 조회 계약·공개 타입

2026-10-25, `feature/139-payment-read-api`, `4c62977` + 3번 단계 작업본 기준이다.

- ADR-0026에 단건 입력·4개 출력·없는 결제·입력 오류·DB 오류·P3 권한 책임·조회 트랜잭션 한계를 기록했다. 사용자 확정안과 P3 전달 사실을 기록하되 P3의 별도 승인 회신을 추정하지 않았다.
- payment.api.PaymentReader 및 PaymentSummary를 추가했다. Entity·내부 enum을 노출하지 않는다. 실제 조회 서비스와 동작 검증은 아직 구현하지 않았다.
- `cd backend && ./gradlew spotlessApply check` → BUILD SUCCESSFUL. 컴파일·포맷·모듈 경계·계층 및 기존 일반 검사 통과.
- `python3 .github/scripts/check_md_links.py`, `git diff --check` → 통과.
- 미검증: MariaDB 검사는 실행하지 않았다. 입력 검증·조회 결과·DB 오류 전파·세션 없는 실제 조회·P3 연동은 후속 단계다. 타입 정의의 검사 통과를 조회 기능 완료로 표현하지 않는다.


## #139 4번 단계: 읽기 전용 조회 구현

2026-10-25, `feature/139-payment-read-api`, `4c62977` + 3·4번 단계 작업본 기준이다.

- PaymentReaderService가 PaymentReader를 구현한다. 기본 REQUIRED의 읽기 전용 트랜잭션으로 기존 findById를 호출하고 4개 저장 필드를 반환한다. 세션 조회·상태 변경·쓰기 잠금·이벤트 발행은 추가하지 않았다.
- PaymentReaderServiceTests 13건 통과: 상태 7종의 저장값 매핑·상태/참조 보존, null·0·음수 입력 거절, 없는 결제 empty, DB 오류 원본 전파를 검사했다. Repository 대역 기반 단위 검사이며 실제 DB 증거와 구분한다.
- `cd backend && ./gradlew spotlessApply check` → BUILD SUCCESSFUL. 기존 일반 검사와 모듈 경계·계층 검사 통과.
- `python3 .github/scripts/check_md_links.py`, `git diff --check` → 통과.
- 미검증: 이번 단계에서 MariaDB 검사는 실행하지 않았다. 실제 Spring 프록시의 읽기 전용 트랜잭션, DB 왕복 시각 정밀도, 상태 변경 커밋 후 조회, 세션 없는 실제 호출은 5번 단계에서 검증한다. P3 목업 교체·접근 제어·화면·보고서 연동은 P3 후속 작업이다.


## #139 5번 단계: 결제 조회 MariaDB 통합 검증

2026-10-25, `feature/139-payment-read-api`, `4c62977` + 3~5번 단계 작업본 기준이다.

- [PaymentReaderMariaDbTests](../../backend/src/test/java/com/donworry/payment/service/PaymentReaderMariaDbTests.java) 4건 통과. 실제 Spring Bean을 PaymentReader 인터페이스로 주입받아 MariaDB에 저장된 결제를 조회했다.
- SecurityContext를 비운 상태에서 호출했다. Repository spy로 트랜잭션 활성·readOnly를 관찰하는 테스트는 실제 EntityManager 조회에 위임했고, 나머지 조회는 실제 Repository를 사용했다. 호출 전후 트랜잭션 종료와 DB 전체 행 불변을 확인했다.
- 나노초 입력이 기존 Entity 규칙대로 마이크로초로 저장되고, API의 UTC Instant와 DB DATETIME(6) 원본이 일치함을 확인했다.
- 승인 참조 저장 후 조회가 최초 시각·금액을 유지하고, CAPTURED 커밋 후 새 트랜잭션 조회에는 변경 상태가 반영됨을 확인했다. 이전 반환 DTO는 PENDING 스냅샷으로 유지된다.
- 호출자 트랜잭션에서 HELD를 flush한 뒤 조회하면 해당 변경이 보이며, 호출자 롤백 뒤 새 조회는 PENDING·null 승인 참조를 읽는다. 조회가 호출자의 변경을 별도 커밋하지 않음을 확인했다.
- 실제 Spring 프록시에서도 null·0·음수는 입력 오류, 없는 양수 ID는 empty로 구분했다.
- 첫 실행에서 테스트의 JDBC Timestamp 변환에 로컬 시간대가 적용돼 DB 원본 비교 1건이 실패했다. DATETIME 원본을 DATE_FORMAT으로 확인하도록 수정했다. API UTC 반환 검증은 유지했으며 운영 코드는 수정하지 않았다.
- 최종 `cd backend && ./gradlew spotlessApply check mariaDbTest` → BUILD SUCCESSFUL. 일반 336건 중 335건 통과·기존 수동 LocalSettlementDemoTests 1건 건너뜀, MariaDB 118건 통과. 실패·오류 0. 기존 결제·모듈 경계·계층 검사 포함.
- `python3 .github/scripts/check_md_links.py`, `git diff --check` → 통과.
- 미검증: P3 목업 교체·권한 검증·화면·보고서 실제 연결, 실제 DB 네트워크 장애. DB 오류 전파는 단위 대역 검증이다. 장기 트랜잭션의 반복 조회가 항상 외부 최신 커밋을 보장하지 않는다는 계약을 유지한다.


## #141 4번 단계 — 직원 조치 저장 기반

2026-10-25, `19e70b2` + 4번 단계 미커밋 작업본에서 실행했다. 사용자 승인에 따라 저장소 상세 ERD·ADR을 기준으로 구현했으며 Notion 동기화는 후속이다.

- `cd backend && ./gradlew newMigration -Pdesc=create_payment_actions_table` 성공. V20261025_160810 생성 후 SQL 작성.
- `cd backend && ./gradlew spotlessApply check mariaDbTest` → BUILD SUCCESSFUL (1m31s). 일반 342건 중 341건 성공·기존 수동 시연 1건 제외, MariaDB 125건 성공. 실패·오류 0.
- 신규 PaymentActionTests 6건: 종료 상태 불변·CAPTURE 중복/충돌·요청 내용 일치·공개 계약 검증.
- 신규 PaymentActionAdmissionMariaDbTests 7건: 트랜잭션 필수, 별도 트랜잭션 재조회·결과 복원, 키/판정/결제 충돌, 롤백, CAPTURE/VOID 동시 요청 직렬화, UNIQUE/FK/CHECK, UTC 마이크로초 저장. 새 migration 전체 적용과 JPA validate 포함.
- 문서 상대 링크 및 diff 검사를 통과했다.
- 미구현·미검증: 공개 실행 Bean, 서로 다른 결제의 UNIQUE 경쟁 후 업무 응답 복구, 직원 CAPTURE 발행·완료 전이·모의 VOID 실행·P3 결과 발행/재전달은 5·6·7번 단계에서 연결·검증한다. 현재 내부 admission은 반드시 호출자 트랜잭션에 참여하며 저장만으로 외부 API를 성공 응답하지 않는다.
- P3 운영 Gateway·리스너·권한 연결은 별도 담당 범위다. VOID actionId와 구체 DTO의 P3 추가 확인, Notion 동기화는 아직 확인하지 않았다.


## #141 5번 단계 — 직원 ACCEPT 매입 연결

2026-10-25, `68290cd` + 5번 단계 미커밋 작업본 기준. 코드·테스트 최종 수정 후 `cd backend && ./gradlew spotlessApply check mariaDbTest` → BUILD SUCCESSFUL (1m22s). 일반 342건 중 341건 성공·기존 수동 시연 1건 제외, MariaDB 133건 성공. 실패·오류 0. 이후에는 문서 실행 기록만 추가했다.

- 신규 PaymentActionCaptureMariaDbTests 8건: 실제 P6 매입·정산과 P5 CAPTURED·조치 완료·테스트 수신자 결과, 원 요청 필드·금액/주문 매핑, 완료 후 결과 재조회, 중복 완료 및 다른 captureId 거절.
- 요청 커밋 실패 시 조치·CaptureRequested 기록 동시 롤백, 완료 커밋 실패 시 HELD/PENDING 유지·P3 결과 기록 롤백 후 원 CaptureCompleted 재전달 복구를 검증했다.
- 결과 수신 실패·원 결과 재전달, 같은 요청의 동시 실행, 호출자 롤백과 P5 커밋 분리, 다른 결제의 동일 키/동일 판정 UNIQUE 경쟁 후 실패 트랜잭션 밖에서 업무 충돌 판정을 검증했다.
- 기존 ALLOW 자동 매입·P6 중복 처리 검사를 포함한 전체 검사 통과. VOID 미연결 요청은 저장·발행 없이 기술 예외를 전달함을 확인했다.
- 첫 실행의 신규 테스트 3건 실패는 Spring Data 인터페이스 spy에서 callRealMethod를 호출한 테스트 구성 오류였다. 실패 주입을 서비스 spy로 옮기고 경쟁 제어는 실제 조회와 INSERT를 유지하도록 수정했다. 첫/두 번째 실행의 기존 P6 테스트 시간 초과는 테스트 로그의 MariaDB `Too many connections`로 원인을 확인했다. 새 테스트 전용 컨텍스트에 AFTER_CLASS 정리를 추가해 연결 풀을 반환했으며, 기존 테스트의 제한 시간·단언·운영 DB 설정은 바꾸지 않았다. 최종 전체 재실행은 모두 통과했다.
- 문서 링크 검사와 git diff --check 통과.
- 미구현·미검증: VOID 실행·VOIDED 전이(6번 단계), P3 실제 Gateway·결과 수신·권한·Case 종료 및 응답/이벤트 순서 역전 처리, 운영 자동 재시도, Notion 동기화. P3 결과는 테스트 전용 리스너로만 전달 검증했다. 해당 운영 구독자가 없으면 P3용 발행 기록이 자동 보존된다고 보장하지 않는다.
