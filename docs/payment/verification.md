# P5 1차 MVP 검증 계획과 실행 기록

2026-10-23 · #104. 아래 M 항목은 [ADR-0016](../decisions/0016-payment-mvp-capture-completion.md) 제안을 채택한 뒤 구현할 검증 계획이며 실행 결과가 아니다. 기존 I/T 번호에 대한 과거 기록은 당시 의미로 보존한다. 이전 계획은 Git `ae28a7c`에 남아 있다.

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
