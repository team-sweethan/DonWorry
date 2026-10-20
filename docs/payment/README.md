# P5 Payment 1차 기술 설계 검토 초안

작성일: 2026-10-20. 분석 기준: `dev` 커밋 `33cb36a`.
**구현 명세 초안이며 Notion 정본을 대체하지 않는다. 업무 코드·마이그레이션은 미구현이다.**
최초 요청의 지정 파일 8개에 후속 요청의 불변식·테스트 시나리오·구현 작업 분해 문서를 추가했다.

## 1. 출처와 상태

| 표기 | 의미 |
|---|---|
| CONFIRMED | 저장소에서 직접 확인한 사실 또는 이번 작업 요청에서 명시적으로 전달된 정책. 출처를 반드시 함께 쓴다 |
| PROPOSED | 구현 가능한 권장안. 이름·타입·인덱스·오류 코드까지 팀 승인 전 제안이다 |
| OPEN | 정책이나 계약을 확인하지 못했거나 아직 결정되지 않음 |
| CONFLICT | 출처 간 불일치. 이 문서가 정본을 무단으로 변경하지 않음 |

`CONFIRMED(요청)`은 요청이 전달한 최신 설계 기준이라는 뜻이며, Notion 동기화·팀 승인까지 검증했다는 뜻이 아니다. 충돌은 별도로 병기한다. 표시 없는 세부 구현 제안은 **PROPOSED**로 읽는다.

**사용자 선택 / 팀 합의 대기**는 사용자가 이번 초안의 방향을 선택했으나 관련 담당자의 승인·Notion 동기화는 아직 확인하지 못했다는 뜻이다. 선택 방향을 팀 전체의 CONFIRMED 정책으로 승격하지 않는다.

| 출처 ID | 자료 | 확인 내용·한계 |
|---|---|---|
| S1 | [AGENTS](../../AGENTS.md), [CLAUDE](../../CLAUDE.md), [루트 README](../../README.md) | 모듈 경계·작업 규칙·과거 HELD 정책 |
| S2 | [ADR 목록](../decisions/README.md), [ADR-0001](../decisions/0001-module-dependency-direction.md), [ADR-0002](../decisions/0002-frontend-common-layout.md) | 둘 다 제안 상태. 0001의 P5–P6 방향은 미확정 |
| S3 | [build.gradle](../../backend/build.gradle), [application.yml](../../backend/src/main/resources/application.yml) | Java 21, Boot 4.1.1, Modulith 2.1.1, JPA, MariaDB. Flyway 꺼짐, ddl-auto update |
| S4 | [payment 패키지](../../backend/src/main/java/com/donworry/payment/package-info.java), [ModularityTests](../../backend/src/test/java/com/donworry/ModularityTests.java), [ArchitectureTests](../../backend/src/test/java/com/donworry/ArchitectureTests.java) | payment는 package-info만 있음. 업무 코드·전용 테스트 없음. allowedDependencies 없음 |
| S5 | [Notion: 2차 프로젝트](https://app.notion.com/p/3d902d70288a802783def0e6f8bc42fa) | 2026-10-20 재조회한 P5 API 9건·7개 테이블·상태표. 초안/협의 중. 첨부 이미지 내용은 미확인 |
| S6 | 사용자 작업 요청 “DonWorry P5 결제 모듈 1차 필수 설계 문서 작성” (2026-10-20) | 승인 후 HELD, FDS 경계, P6 계약, 문서만 작성할 범위 |
| S8 | 사용자 후속 선택 및 문서 수정 요청 (2026-10-20) | 검토 질문 1-A·2-B·3-A·4-A·5-A·7-A 선택, 6·8 미결 유지. 팀 합의와 구분 |
| S7 | [PR #3](https://github.com/team-sweethan/DonWorry/pull/3) | 계층 검사 도입. 당시 결과이지 현재 커밋 실행 결과가 아님 |

Notion 정본 링크 등록 위치: 아래 표. 검색으로 S5만 특정했고, 지정 정본 전체와 P2·P3·P6 원문은 확보하지 못했다. S5를 정본 전체로 간주하지 않는다.

| 정본 | 링크·상태 | 동기화 대상 |
|---|---|---|
| 1 상태값 | OPEN: 팀 링크 확인 필요 | 승인 후 HELD, 자동 만료 미결, 카드 승인 여부 |
| 2 API 명세서 | OPEN: 팀 링크 확인 필요; S5는 관련 초안 | P6 API-09/10 소유·전파, 결정 식별자, 결과 전달 |
| 3 ERD | OPEN: 팀 링크 확인 필요; S5의 7개 테이블 참고 | FK 구분, 타입·인덱스, 멱등 저장 정책 |
| 기능 명세·P2/P3/P6 공통계약 | OPEN: 링크 확인 필요 | FDS 재시도, 직원 결정, 매입 잠금·정산 마감 |

GitHub 접근 결과: 저장소 Issue 검색에서는 frontend #6을 확인했고 P5 전용 Issue는 특정하지 못했다. payment PR 검색에서 #3을 확인했다. 검색 결과가 전체 자료 부재의 증거는 아니다. Issue 번호를 만들지 않고 `feature/p5-payment-design`에서 문서만 준비했다. 정식 작업 등록 시 Issue 연결·브랜치명 보정이 필요하다. commit/push/PR은 수행하지 않는다.

## 사용자 선택 반영표

검토 질문 번호와 Q번호는 서로 다르다. 특히 **질문 6은 Q13, 질문 8은 Q07**에 대응하며 Q06/Q08과 혼동하지 않는다.

| 검토 질문 | 선택 방향 | 상태 | 확인 담당 | 관련 항목 |
|---|---|---|---|---|
| 1-A | P5 소유 포트를 P6가 구현, 같은 취소 트랜잭션 | 사용자 선택 / 팀 합의 대기 | P5·P6·팀 | Q01 |
| 2-B | 미승인 만료를 별도 상태로 분리. 상태명 EXPIRED는 제안 | 사용자 선택 / 팀 합의 대기 | P5·CakeShop·P1 | Q12 |
| 3-A | 인증된 CakeShop 서버의 checkout 토큰 재발급 | 사용자 선택 / 팀 합의 대기 | P5·CakeShop·P1 | Q11 |
| 4-A | 실제 FDS 시도를 집계하고 기록을 P5 롤백과 분리 | 사용자 선택 / 팀 합의 대기 | P2·P5 | Q05 |
| 5-A | P3 결정 저장과 P5 실행을 같은 트랜잭션으로 커밋 | 사용자 선택 / 팀 합의 대기 | P3·P5 | Q03 |
| 6 | 정산 단위·settlementStatus 의미·취소 저장 위치 | **OPEN — 미결** | P5·P6 | Q13·Q04 |
| 7-A | 배치마다 APPROVED 재조회, P6가 기매입 제외 | 사용자 선택 / 팀 합의 대기 | P5·P6 | Q19 |
| 8 | 케이스 생성 기준·이벤트 책임 | **OPEN — 미결** | P2·P3·P5 | Q07 |

EXPIRED는 이 초안에서 REQUESTED의 주문 기한 만료만 표현한다. HELD 자동 만료·고객 자발적 중단 정책은 확정하지 않는다. 새 방향은 미구현이며 기존 ADR-0001과 Notion을 변경하지 않았다.

## 2. 범위·패키지

P5: 세션·결제창의 Mock 카드 선택 계약, 요청 인증·중복 방지, FDS 호출, Mock 승인·취소, 상태·이력, P3 결정 실행, P6 연동, CakeShop 결과 전달, 원자성·동시성.
제외: P2 점수 계산, P3 케이스·최종 판정, P4 AI 보고서 및 요청에서 지정한 조치 업무, P6 매입·수수료·정산 계산, P1 사용자·가맹점 관리.

CONFLICT: S1은 카드·기기 차단 목록을 P2 책임으로 설명하지만 S6은 차단 조치 관리를 P4 제외 범위로 설명한다. P5가 구현하지 않는 점은 일치한다. 조치 요청자와 저장·실행 책임 구분은 Q14에서 확인한다.

현재 실제 폴더는 `backend/src/main/java/com/donworry/payment/{api,controller,service,repository,entity,dto}`이다. 향후 `PaymentReader`, `ReviewExecutor`, `PaymentService`, `MockCardService`, `PaymentRepository`, `PaymentStatus` 등의 클래스명은 제안이며 현재 존재하지 않는다. Controller는 Service와 DTO를 사용하고, 다른 모듈은 공개 api의 값·인터페이스만 사용한다. Mock은 P5 내부 Java 호출이다.

## 3. 문서 안내

| 문서 | 구현에 쓰는 기준 |
|---|---|
| [01 흐름](01-payment-flow.md) | 시나리오별 참여자·커밋·실패 경로 |
| [02 ERD](02-erd.md) | 정확히 7개 테이블, 물리 FK와 UUID 논리 참조 |
| [03 API](03-api-contracts.md) | 외부 HTTP와 내부 Java 계약, DTO·오류·멱등 |
| [04 상태](04-state-machine.md) | 승인 후 HELD, 허용/금지 전이, DB 불변식 |
| [05 Mock](05-mock-card.md) | 한도·카드 상태·승인/취소 원장 |
| [06 트랜잭션](06-transaction-locking.md) | P5–P6 충돌 우선 분석, 잠금·롤백·재시도 |
| [07 불변식](07-invariants.md) | P5 독립 검증 조건과 팀 답변이 필요한 조건 구분 |
| [08 테스트 시나리오](08-test-scenarios.md) | 입력·실행·예상 결과, 검증 수준, 기존 TX 계획 연결. 모두 미구현·미실행 |
| [09 구현 작업 분해](09-implementation-breakdown.md) | 먼저 진행할 내부 작업과 팀 합의 후 작업, 불변식·시나리오·선행 조건·완료 기준 연결 |
| [12 미결](12-open-questions.md) | 승인 전 해결해야 할 결정 목록 |
| [ADR-0004 초안](../decisions/0004-payment-expiry-state.md) | 미승인 결제 만료 상태 분리 — 사용자 선택 / 팀 합의 대기 |
| [ADR-0005 초안](../decisions/0005-checkout-token-reissue.md) | checkout 토큰 재발급 — 사용자 선택 / 팀 합의 대기 |
| [ADR-0006 초안](../decisions/0006-fds-attempt-persistence.md) | FDS 실제 시도 기록의 독립 저장 — 사용자 선택 / 팀 합의 대기 |
| [ADR-0007 초안](../decisions/0007-review-payment-atomicity.md) | P3 결정과 P5 실행의 공동 커밋 — 사용자 선택 / 팀 합의 대기 |
| [ADR-0008 초안](../decisions/0008-capture-candidate-rescan.md) | 매입 후보의 실행별 재조회 — 사용자 선택 / 팀 합의 대기 |
| [ADR-0003 초안](../decisions/0003-payment-settlement-cancellation-contract.md) | 기존 API 보존을 위한 포트 대안. 기존 ADR을 대체·확정하지 않음 |

## 4. 설계 기준과 개발 전 확인

CONFIRMED(S6): ALLOW 0~49, HOLD 50~80은 Mock 승인을 거친다. 성공 시 각각 APPROVED/HELD. REJECT 81 이상은 승인 없이 DECLINED. FDS 기술 오류는 카드 승인 금지이며 DECLINED로 오인하지 않는다. HELD는 이미 승인되어 한도를 점유하고 매입할 수 없다. 유지 시 재승인 금지, 취소 성공 후 CANCELED. 최대 유지 시간은 OPEN이다.

S8의 선택 방향을 반영했으며 Q01(의존/원자성), Q03(직원 결정 키), Q05(FDS 중복), Q08(스키마), Q10(실패 복구)의 관련 담당자 확인과 세부 계약은 팀 합의 대기다. 특히 승인 후 HELD와 과거 문서 차이를 정본에 동기화하기 전 기존 구현이 있다고 가정하지 않는다. 본 문서의 동시성 수용 조건은 테스트 계획이지 통과 결과가 아니다.

## 5. 최초 초안 작성 당시 검증 기록 (후속 수정 전)

2026-10-20, 기준 코드 `33cb36a`, 문서 작업 브랜치 `feature/p5-payment-design`에서 수행했다.

| 검사 | 실행·결과 |
|---|---|
| 저장소 Markdown 링크 | `python3 .github/scripts/check_md_links.py` → 상대 링크 이상 없음 |
| Markdown 문법 | 임시 설치한 `markdownlint-cli2` 0.23.3으로 신규 Markdown 9개 검사 → 오류 0. MD013(줄 길이), MD060(표 열 정렬)만 제외 |
| Mermaid 문법 | 임시 Node 스크립트에서 Mermaid 12.2.0의 `mermaid.parse()`로 모든 코드 블록 파싱 → 시퀀스 8개·ERD 1개·상태 1개, 총 10개 통과 |
| diff 공백 | `git diff --check` 및 각 새 파일의 `git diff --no-index --check /dev/null <파일>` → 공백 오류 없음. no-index의 차이 존재 종료코드 1과 오류 출력을 구분 |
| 구조 대조 | 임시 Python 검사 → API 14개에 필수 명세 11항목씩 존재, ERD 7개 테이블, Q01~Q18 참조 일치 |
| 변경 범위 | `git diff --name-only`, `git diff --cached --name-only` → 기존 추적 파일 변경·스테이징 없음. 새 문서는 이 폴더 8개와 ADR 초안 1개뿐 |

원래 있던 루트 미추적 `package-lock.json`은 작업 대상에서 제외했다. 검증 도구와 스크립트는 `/private/tmp/donworry-doc-validation` 등 임시 위치에만 준비했으며 저장소 의존성을 바꾸지 않았다. commit/push/PR 생성 없음.

미검증: GitHub의 실제 Mermaid 렌더링, Notion 첨부 이미지·정본 전체·팀 합의, Java API 컴파일, DB DDL·트랜잭션·동시성·런타임 동작. 업무 코드가 없으므로 Gradle 업무 테스트를 실행하지 않았으며 수용 테스트는 향후 구현 기준이다.

## 6. 사용자 선택 반영 후 검증 기록

2026-10-20, 기준 코드 `33cb36a` 유지. 위 5절은 최초 초안의 이력이며 아래가 이번 수정 결과다.

- 수정: P5 문서 8개와 미채택 ADR-0003. 추가: 선택 주제별 ADR-0004~0008 초안 5개. 한 ADR에 한 결정을 기록한다. 기존 ADR-0001·0002·목록은 변경하지 않았다.
- `python3 .github/scripts/check_md_links.py` → 저장소 Markdown 상대 링크 이상 없음.
- 임시 `markdownlint-cli2` 검사 → 관련 Markdown 14개 오류 0(MD013·MD060 제외).
- Mermaid 12.2.0 `mermaid.parse()` → 시퀀스 10개·ERD 1개·상태 1개, 총 12개 통과.
- 임시 `check-updated-design.py` → API 15개 필수 항목, P5 테이블 7개 유지, 선택 방향·질문 6/8 미결·Q참조·EXPIRED 범위 검사 통과. 모든 설계 파일의 no-index diff 공백 검사 통과.
- 수정 전 복사본과 diff를 확인했다. SHA-256 대조로 원래 추적된 모든 파일 및 루트 미추적 package-lock.json이 그대로임을 확인했다.
- 구현·마이그레이션·설정·Notion 정본 수정, commit/push/PR 생성은 하지 않았다. 팀 승인·Java 컴파일·DB·동시성·런타임·GitHub 렌더링은 미검증이다.
