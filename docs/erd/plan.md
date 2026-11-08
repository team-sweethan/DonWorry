# ERD 문서 정리 작업 계획

이 문서는 ERD 문서 정리의 범위, 진행 상태, 검증 근거를 이어서 관리하는 작업 기록입니다. 스키마나 업무 정책의 정본은 아니며, 기준은 [AGENTS.md](../../AGENTS.md)와 migration SQL, 채택된 ADR 및 담당자 합의가 기록된 업무 문서입니다.

## 1. 현재 위치와 기준

- 기록일: 2026-11-08.
- 현재 위치: **T5 관계도 보완과 T6 저장 계약 대조·수정을 완료해 한 로컬 커밋으로 기록합니다. T3~T6의 승인 범위는 여기까지이며, 다음 단계는 푸시·PR 범위 협의입니다. T7~T9는 승인 대기입니다.** 아래 완료는 명시한 문서 대조·수정 범위이며 앱·DB 실행 검증 완료가 아닙니다.
- 구조 분리 커밋: `fc5caa477565affe20a713a5578ccc5d7c141259`.
- 최초 계획 커밋: `782fc52c74756aa5bbef93c423d1485ff1b9d7ce` (`docs: ERD 문서 정리 계획과 진행 상태 기록`).
- T4 커밋: `694098da1ba2f4322aa7cb93acfb93997da20074` (`docs: ERD 소유 표의 누락 테이블과 근거 보완`).
- T5 사전 검증 기록 커밋: `112c3e528239b70a2daa25f08830dde47ff0710b` (`docs: ERD 관계도 검증 결과와 다음 작업 정리`).
- T5·T6 수정 커밋: 이 갱신을 포함하는 `docs: ERD 관계도와 저장 계약을 현재 구현에 맞춰 정리`. 커밋 ID는 `git log --oneline -- docs/erd/plan.md`에서 확인합니다.
- 현재 브랜치: `feature/681-flyway-guide`. 문서 정리 커밋은 로컬에만 있으며, 아직 푸시하지 않았습니다.
- 이전 ERD 조사 및 구조 정리의 내용 기준: `ea5294e9ece7feb1e1f454fd00c103372a1aecdb`.
- T4 검증 기준: `git fetch origin` 후 `origin/dev`의 `f7db9cd307067dcaa6a71673f0ad02b3115a8754`. 이전 기준 이후 변경은 보안 탐지 표시 코드 관련 PR #687이며, migration·ERD·이번 소유 근거로 사용한 담당별 문서는 바뀌지 않았습니다.
- T5 사전 검증 당시 HEAD `694098da`와 `origin/dev`는 각각 4개·2개 고유 커밋으로 갈라져 있었습니다. 브랜치 전환·병합·rebase 없이 원격 참조와 파일을 직접 대조했습니다. 사전 검증 당시 SQL 85개는 이 원격 기준과 바이트 단위로 같았습니다.
- T5·T6 수정 시작 시 HEAD는 `112c3e52`였고, 다시 `git fetch origin`한 결과 기준은 같은 `f7db9cd307067dcaa6a71673f0ad02b3115a8754`였습니다. SQL 85개를 원격 기준과 다시 비교했습니다. 구현 차이는 보안 표시 코드 관련 `InsiderRule`이며, 해당 설명은 원격 파일을 직접 확인했습니다. 분기 상태를 임의로 정리하지 않았습니다.
- 후속 작업에서는 기준 커밋과 분기 상태를 다시 확인합니다. 로컬 커밋을 임의로 재배치하거나 충돌을 정리하지 않습니다.

## 2. 전체 작업에서의 위치

| Task | 작업 | 상태 |
| --- | --- | --- |
| T1 | AGENTS.md 직접 연결 문서의 링크·구현 근거·작성 이력 조사 | 1차 조사 완료. 모든 내용의 최신성 검증 완료를 뜻하지 않음 |
| T2 | Flyway 작업 안내를 저장소 합의 절차와 일치시킴 | PR #686 병합 완료 |
| T3 | ERD README를 안내·저장 계약·관계도·역사 기록으로 분리 | 로컬 커밋 완료, 미푸시 |
| T4 | ERD README 테이블 소유 표 보완 | 로컬 커밋 완료 (`694098da`), 미푸시 |
| T5 | ERD 관계도와 참조 설명 대조·수정 | T5-A·B 반영, 물리 FK 54개 대조 완료. T6와 함께 로컬 커밋, 미푸시 |
| T6 | ERD 저장 계약과 CREATE·후속 ALTER 대조·수정 | contracts.md의 P1~P5 설명 대조·수정 완료. T5와 함께 로컬 커밋, 미푸시. 상세 문서 전체·운영 검증은 제외 |
| T7 | 모듈 구조·이벤트 계약·재전달 설명 정리 | 후보 조사만 완료, 별도 범위 협의 필요 |
| T8 | 실제 워크플로·검사 단계에 맞춰 CI 문서 정리 | 후보 조사만 완료, 별도 범위 협의 필요 |
| T9 | 문서 인덱스·ADR 안내의 링크, 정본 안내, 과거 검증 기록 정리 | 후보 조사만 완료, 별도 범위 협의 필요 |

이 계획의 실행 범위는 ERD 관련 T3~T6입니다. T7~T9는 전체 작업의 맥락이며 일괄 수정 대상으로 승인된 것이 아닙니다.

## 3. T3 — 구조 분리 (로컬 커밋 완료)

| 파일 | 역할과 반영 내용 |
| --- | --- |
| [README.md](README.md) | 공통 규칙, 테이블 소유 목록, 상세 문서 진입점과 기존 링크 앵커 유지 |
| [contracts.md](contracts.md) | 담당별 저장 계약과 제안 설명 분리 |
| [relationships.md](relationships.md) | 기존 관계도와 논리 참조 설명 이동 |
| [history.md](history.md) | 당시 설계·승인·구현·검증 기록과 이전 절차 보존 |

- 사용자가 팀원의 수정 허락을 받았다고 알렸고, 이동 계획에 따라 구조 분리를 진행했습니다.
- 기존 소유 표와 Mermaid 관계도는 그대로 보존했습니다. 내용까지 최신화됐다고 해석하지 않습니다.
- 공동 작성 문서입니다. 후속 수정에서도 최초 작성자뿐 아니라 수정할 부분의 Git 이력을 확인합니다.
- 이전 작업에서 상대 링크 203개(앵커 포함 71개), 외부 문서에서 들어오는 앵커 링크 17개, 원문 보존 및 `git diff --cached --check`를 확인했습니다. 이는 구조 분리 당시 결과입니다.
- 애플리케이션 실행, Gradle·MariaDB 테스트, 운영 DB 및 Notion 상태는 검증하지 않았습니다.

## 4. T4 — README 테이블 소유 표 보완 (로컬 커밋 완료)

### 무엇을 바꾸는가

“소유 표 보완”은 이미 migration SQL과 기존 업무 문서에 존재하는 테이블을 README의 담당별 목록에서도 찾을 수 있게 하는 문서 수정입니다. 새 테이블 생성, 담당자 신규 지정 또는 담당 이관을 뜻하지 않습니다.

- 대상 파일: 우선 [README.md](README.md) 한 파일.
- 변경 내용: 누락 후보를 재확인해 테이블 이름·기존 소유 담당·관련 migration 및 상세 문서 링크를 추가합니다.
- 표의 migration 안내가 CREATE와 ALTER의 실제 링크 구성을 정확히 설명하도록 정리합니다. 단순히 링크 하나만으로 최종 스키마를 확인할 수 있다고 안내하지 않습니다.
- 이유: 표에 없는 테이블을 미구현으로 오해하거나 담당·상세 설명을 놓치는 문제를 줄입니다.
- 영향 범위: 문서의 탐색과 소유 확인. 코드·SQL·설정·DB 데이터는 변경하지 않습니다.

### 이전 조사에서 나온 누락 후보

아래 21개는 이전 기준 커밋에서 **README 소유 표에 없었던 후보**이며, T4에서 `f7db9cd3` 기준으로 누락과 기존 담당 근거를 재확인해 표에 추가했습니다. 전체 문서에 설명이 없었다는 뜻은 아닙니다.

| 기존 조사상 담당 | 후보 테이블 |
| --- | --- |
| P1 (6개) | `merchant_applications`, `application_tokens`, `application_mail_deliveries`, `application_rate_limits`, `merchant_integrations`, `merchant_key_histories` |
| P5 (3개) | `payment_orders`, `payment_requests`, `payment_fds_recoveries` |
| P6 (12개) | `order_fulfillments`, `order_fulfillment_items`, `cash_flow_settings`, `planned_expenses`, `recurring_expenses`, `recurring_expense_payments`, `settlement_business_days`, `merchant_payouts`, `merchant_payout_items`, `settlement_refund_adjustments`, `merchant_settlement_locks`, `payout_refund_allocations` |

### 검증·완료 기준

1. 기준 커밋을 기록하고 migration 파일을 버전 순으로 확인합니다. 각 대상의 CREATE와 이후 ALTER·이름 변경·삭제 여부를 함께 봅니다.
2. 기존 업무 문서·ADR·관련 Git 이력에서 소유 담당과 저장 목적의 근거를 확인합니다. 파일 작성자만으로 테이블 소유를 결정하지 않습니다.
3. 추가할 행과 근거를 정리합니다. 소유가 불명확하거나 문서끼리 충돌하는 경우 임의로 배정하지 않고 담당자 확인 대상으로 남깁니다.
4. 수정 후 소유 목록을 SQL 테이블 목록과 다시 대조하고, 남은 누락·예외를 명시합니다.
5. 상대 경로·앵커 및 `git diff --check`를 검사합니다. 상세 컬럼·제약의 전체 검증은 T6 범위로 구분합니다.

### T4 반영·검증 기록 — 2026-11-08

- 승인: 사용자가 “승인할게. 커밋하고 다음 작업 진행”으로 최초 계획 파일 커밋과 T4 진행을 승인했습니다. 계획 파일을 먼저 커밋한 뒤 README를 수정했습니다.
- 변경: README에 21개 테이블을 기존 담당별 13개 행으로 추가했습니다. 생성 SQL과 관련 후속 ALTER, 기존 상세 문서를 연결하고 “테이블당 migration 1개”라는 잘못된 열 설명을 변경했습니다.
- 담당 근거: P1 신청 문서의 “저장 계약 (모두 P1)” 및 키 관리 문서의 P1 소유 설명, P5 계약·복구 저장 문서, P6 픽업·지출·달력·지급·환불 상세 문서와 현재 코드의 소속을 대조했습니다. 담당을 새로 지정하거나 제안 상태를 변경하지 않았습니다.
- 코드 근거: 19개는 해당 모듈 Entity의 `@Table` 매핑으로 확인했습니다. `application_rate_limits`와 `settlement_business_days`는 각각 identity의 `ApplicationRateLimitRepository`, settlement의 `SettlementCalendarRepository`에서 JDBC로 사용하는 것을 확인했습니다.
- Git 이력: README 최초 작성 및 소유 표 해당 줄의 `git log`·`git blame`을 확인했습니다. 기존 표는 HyunGyu-Cho, Hong Ju Hwan, 시은, ssume98, hoo0070 등의 공동 수정 내용이며, P6 상세 문서는 minjung0413의 작성·수정 이력을 확인했습니다. 참고한 담당별 상세 문서는 수정하지 않았습니다.
- SQL 대조: migration 85개에서 영구 테이블 67개를 추출하고 표의 테이블 집합과 일치하는지 검사했습니다. 누락·추가 이름은 0개입니다. `EVENT_PUBLICATION`은 대소문자를 정규화해 대조했고, 전환용 임시 테이블과 Flyway 자체 이력 테이블은 범위에서 제외했습니다.
- 후속 변경 확인: 대상 21개의 CREATE와 후속 ALTER·테이블 삭제·이름 변경 여부를 확인했습니다. `merchant_applications`의 로그인 ID·쇼핑몰 주소·이메일 확인 상태, `payment_requests`의 위험 신호, `order_fulfillments`의 가맹점별 UNIQUE 변경을 함께 확인·연결했습니다. 영구 테이블 DROP·RENAME은 발견되지 않았습니다.
- 실행한 검사: 인메모리 Python으로 SQL 원격 기준 동일성·테이블 집합·ERD 5개 문서의 상대 링크 250개(앵커 73개 포함)·외부 문서에서 README로 들어오는 앵커 링크 17개·후행 공백을 검사했습니다. `git diff --check`도 통과했습니다. 검사 스크립트 파일은 추가하지 않았습니다.
- 미검증: DB migration 실행, 애플리케이션 실행, Gradle·MariaDB 테스트, 운영 DB·Notion 상태. 모든 기존 테이블의 컬럼·제약이나 문서 내용 전체의 최신성을 검증한 것은 아닙니다.
- 남은 범위: T5 관계도와 T6 저장 계약의 대조·수정. README의 migration 링크는 전체 변경 이력 목록이 아니므로 최종 스키마 확인 시 SQL 전체 검색이 필요합니다.
- 추가 담당 확인: 이번 21개 소유 표 추가에서 소유가 불명확한 항목은 없었습니다. 후속 관계·계약 조사에서 드러나는 정책 충돌은 별도 확인합니다.
- Git 상태: 사용자 요청 “T4 커밋 -> T5 검증 시작”에 따라 README와 당시 진행 기록을 `694098da`로 커밋했습니다. 커밋 전 `git diff --cached --check` 통과. 푸시·PR 생성은 하지 않았습니다.

## 5. T5 — 관계도 검증·수정

- 주 대상: [relationships.md](relationships.md).
- 변경 방향: 실제 FK와 논리 참조를 구분하고, 필요한 누락 관계를 보완합니다. 모든 테이블을 한 그림에 넣는 것을 완료 기준으로 삼지 않습니다.
- 재확인 후보: `fds_rule_policies`와 `fds_rule_change_requests` 사이의 물리 관계 표기. 이전 조사에서는 SQL에 FK가 없었으므로 최신 CREATE·ALTER와 코드의 참조 사용을 다시 확인합니다.
- 검증: FK 생성·변경·삭제 SQL, 관련 코드와 업무 문서, 카디널리티를 뒷받침하는 UNIQUE·NULL 제약, Mermaid 문법 및 링크를 대조합니다.
- 영향: 관계 설명만 변경합니다. DB 제약이나 모듈 간 계약을 새로 정하지 않습니다. 설계 합의가 필요한 충돌은 별도로 보고합니다.
- 착수 조건: 조사 결과에 따라 정확한 변경 위치·범위를 제시하고 승인된 범위만 수정합니다.

### T5 사전 검증 범위와 결과 — 2026-11-08

- 아래는 “T4 커밋 -> T5 검증 시작” 승인 당시 기록입니다. 당시 `relationships.md`는 수정하지 않았습니다. 이후 일괄 진행 승인과 반영 결과는 아래 별도 절에 기록합니다.
- 기준: 구현 `f7db9cd3`, 문서 로컬 HEAD `694098da`. SQL 85개에서 CREATE·ALTER 및 FK·UNIQUE·NULL 관련 구문을 추출해 대조했습니다. FK 추가·삭제 ALTER나 영구 테이블 DROP·RENAME은 발견되지 않았습니다.
- 현재 그림: 37개 테이블, 관계선 49개(문서 범례상 실선 31개·점선 18개). 영구 테이블 67개 중 그림에 없는 테이블은 30개입니다. 독립 테이블도 있으므로 30개 모두를 누락 오류로 판단하지 않습니다.
- 실제 FK: 제약 54개 중 그림에 표현된 제약은 31개, 그림에 없는 제약은 23개입니다. `locked_by`·`released_by` 두 FK가 한 선으로 묶여 있고, FK가 없는 실선도 하나 있어 관계선 수와 제약 수를 구분했습니다.
- 기존 물리 관계: FK 31개의 참조 컬럼 및 NULL·UNIQUE/PK에 따른 수량 표기를 대조했고, 결제 세션의 부모 선택성 1건이 맞지 않았습니다. `admin_change_histories.actor_user_id`는 이후 ALTER로 NULL 허용이 되어 기존 선택 관계 표기가 맞습니다.
- 논리 관계: 기존 점선의 관련 컬럼·키를 확인하고, 오류 분석·룰 변경 요청·조건부 수행자·작업 ID 매핑은 코드까지 확인했습니다. 전체 시스템의 논리 참조나 서비스의 존재 확인 경로를 모두 검증한 결과는 아닙니다.

### T5-A — 기존 표기 오류 수정안 (사전 제안, 이후 승인·반영)

사전 제안의 대상은 `relationships.md` 한 파일과 이 계획의 진행 기록입니다. 아래 줄 번호는 수정 전 파일 기준입니다. 이후 T5-B와 함께 진행하면서 그림의 배치를 나누되 아래 세 관계선과 범례 수정 방향을 유지했습니다. 코드·SQL·업무 계약·ADR 상태는 바꾸지 않습니다.

| 위치 (현재 파일 줄) | 현재 설명과 영향 | 확인 근거 | 수정 방향 |
| --- | --- | --- | --- |
| 31: 룰 정책 → 변경 요청 | `fds_rule_policies`에서 `fds_rule_change_requests`로 실선을 그려 DB가 `rule_code` 관계를 강제하는 것으로 읽힘 | [생성 SQL](../../backend/src/main/resources/db/migration/V20261028_155511__create_fds_rule_management_tables.sql)에 FK 없음. [후속 SQL](../../backend/src/main/resources/db/migration/V20261104_152441__create_operator_access_detection_tables.sql)은 `rule_code` 길이만 변경. [서비스](../../backend/src/main/java/com/donworry/fds/service/FdsRuleManagementService.java)의 `requestDisable`·`policyForUpdate`에서 정책 조회 후 요청 저장 | 프로젝트 범례에 맞춰 `\|\|--o{`를 `\|\|..o{`로 변경. 정책 존재는 정상 서비스 경로에서 확인하며 DB FK는 없음을 설명 |
| 52: 결제 → 결제 세션 | `payments \|\|--o\| payment_sessions`로 세션에 결제가 반드시 있다고 표시 | [생성 SQL](../../backend/src/main/resources/db/migration/V20261102_142818__create_payment_sessions.sql): `payment_id` NULL 허용·UNIQUE·FK, OPEN은 NULL이고 SUBMITTED는 NOT NULL인 CHECK. 후속 `notify_event_id` ALTER는 이 관계를 바꾸지 않음 | `payments \|o--o\| payment_sessions`로 변경. 결제 전 OPEN 세션과 제출 후 상태를 설명 |
| 30: 평가 → 오류 분석 | `fds_evaluations \|\|..o{ fds_failure_analyses`로 분석마다 실제 평가가 하나 있다고 표시 | [분석 SQL](../../backend/src/main/resources/db/migration/V20261027_101722__create_fds_failure_analyses.sql)에 FK 없음. [서비스](../../backend/src/main/java/com/donworry/fds/service/FdsFailureAnalysisService.java)의 `report`·`analyze`는 평가 미발견에도 분석 저장·`EVALUATION_NOT_FOUND` 발행. [기존 테스트](../../backend/src/test/java/com/donworry/fds/service/FdsFailureAnalysisServiceTests.java)의 `report_whenEvaluationIsMissing_publishesNotFound`도 해당 분기를 다룸(이번에 실행하지 않음) | `fds_evaluations \|o..o{ fds_failure_analyses`로 변경. 보고된 요청 UUID는 NOT NULL이어도 실제 평가 행이 없을 수 있음을 설명 |

범례(11줄)는 다음처럼 보완합니다.

- `|o`/`o|`는 관계 대상이 0 또는 1개임을 뜻합니다. NULL 허용 여부와 항상 같은 의미로 설명하지 않습니다. FK 없는 오류 보고는 ID를 저장했어도 대상 행이 없을 수 있습니다.
- 실선=물리 FK·점선=논리 참조는 **이 문서에서 사용하는 표기 약속**으로 명시합니다. [Mermaid 공식 문법](https://mermaid.js.org/syntax/entityRelationshipDiagram.html#identification) 자체의 실선·점선은 식별·비식별 관계 구분이므로 일반 문법의 의미와 혼동하지 않도록 합니다.
- Mermaid 선 모양을 수정했다고 DB에 FK가 생기거나 없어지는 것이 아님을 검증 범위 안내에서 분명히 합니다.

작성 이력: `git blame -C -C -C`로 이동 전 README까지 추적했습니다. 30·31줄은 hoo0070의 `d5a74d42`·`f505dcae`, 52줄은 ssume98의 `8894427b`, 범례는 HyunGyu-Cho의 `b06a31fe`에서 왔습니다. 구조 분리 커밋 작성자만으로 원문 작성자를 판단하지 않았습니다.

승인 후 검증 방법: 수정 전후 관계선 차이가 위 세 곳인지 확인하고 SQL·서비스 근거와 다시 대조합니다. 링크·앵커·`git diff --check`를 검사합니다. Mermaid 파서·렌더러를 실행하지 못하면 문법·화면 렌더링 검증을 완료로 기록하지 않습니다.

### T5-B — 누락 관계와 긴 설명 분할안 (사전 제안, 이후 승인·반영)

사전 조사에서 누락된 관계 23개를 한 그림에 모두 추가하면 읽기 어려워지므로, 담당별 작은 그림과 논리 참조 표로 나누는 안을 제시했습니다. 실제 이동·추가 결과는 아래 반영 기록에 정리합니다. 아래 수는 당시 그림에 없던 FK 제약 수이며, FK 없는 업무 참조는 포함하지 않습니다.

| 담당 | 누락 FK 수 | 자식 테이블과 참조 컬럼 |
| --- | --- | --- |
| P1 | 10 | `merchant_applications`의 reviewer_user_id·merchant_id·user_id(3), `application_tokens`의 application_id·user_id(2), `application_mail_deliveries`의 application_id·token_id(2), `merchant_integrations.merchant_id`(1), `merchant_key_histories`의 merchant_id·actor_user_id(2) |
| P2 | 2 | `fds_operator_access_detection_evidences`의 detection_id·operator_access_event_id |
| P5 | 3 | `payment_requests`의 (payment_order_id, merchant_id) 복합 FK·payment_id, `payment_fds_recoveries.payment_id` |
| P6 | 8 | `order_fulfillment_items.fulfillment_id`(1), `recurring_expense_payments.recurring_expense_id`(1), `merchant_payout_items`의 payout_id·settlement_id(2), `settlement_refund_adjustments`의 settlement_id·capture_id(2), `payout_refund_allocations`의 payout_id·adjustment_id(2) |

- 72줄의 긴 논리 참조 문단은 출발 테이블·컬럼, 대상, 조건, 근거를 나눈 표로 바꾸는 안을 검토합니다.
- `case_histories.actor_id`는 [CaseHistory](../../backend/src/main/java/com/donworry/investigation/entity/CaseHistory.java)의 STAFF 생성 시 직원 ID이고 SYSTEM 생성 시 비어 있습니다. `action_handoffs.target_operation_id`는 [PaymentActionMapping](../../backend/src/main/java/com/donworry/payment/service/PaymentActionMapping.java) 기준 CAPTURE 성공이면 P6 captureId, VOID 성공이면 P5 actionId입니다. 기존 방향은 맞으며 조건을 보완하면 됩니다.
- 새 그림·표에 포함할 논리 참조의 전체 목록과 조건은 아직 확정하지 않았습니다. 다른 담당 계약과 충돌하면 추정해서 추가하지 않고 근거와 함께 확인 대상으로 남깁니다.
- 기존 상세 문서의 그림은 이번 범위에서 고치지 않습니다. 해당 문서까지 수정해야 하면 파일·이유·영향을 별도로 제시합니다.

### T5 사전 검사와 미검증 기록

- 실행: `git fetch origin`, `git log --follow`, `git blame -C -C -C`, SQL 원격 기준 바이트 비교, 인메모리 Python을 통한 FK 목록·관계선 대조 및 FK 31개 수량 표기 검사, 관련 서비스·테스트 소스 읽기.
- 검증 기록 검사: 계획 파일의 상대 링크 18개, Markdown 표 열 수, 후행 공백 및 `git diff --check` 통과. 참고한 서비스 파일이 검증 기준과 동일하고, `relationships.md`가 변경되지 않았음을 확인했습니다.
- Mermaid 49개 관계문은 공식 관계 표기와 정규식으로 형태를 대조했습니다. 실제 Mermaid 파싱·렌더링은 실행하지 않았습니다.
- 미검증: SQL을 실제 DB에 적용한 결과, 앱·Gradle·MariaDB 테스트, 운영 DB·Notion, 전체 논리 참조의 업무 경로. 테스트 소스를 읽은 것을 테스트 통과로 취급하지 않습니다.
- 당시 결론은 현재 구현 설명의 문서 오류 3건과 범위 보완안이었습니다. 새 FK·스키마·업무 정책의 합의 요청은 아니었습니다.
- 사용자 요청 “커밋하고 PLAN 최신화하자”에 따라 T5 검증 기록과 현재 위치·다음 작업을 이 계획의 로컬 커밋에 함께 반영합니다. 관계도·코드·SQL은 변경하지 않았으며 푸시·PR도 하지 않았습니다.

### T5-A·B 반영 기록 — 2026-11-08

- 승인: 사용자가 “T5, T6 한번에 진행하고 커밋까지 하자”로 T5·T6 문서 수정과 로컬 커밋을 승인했습니다. 범위·근거·영향·검사 방법을 공유하고 진행했으며 푸시·PR은 포함하지 않았습니다.
- 변경 파일: `relationships.md`, 진행 기록 `plan.md`. `README.md`는 진행 안내를 갱신하고 기존 참조 예시의 `fds_evaluations.merchant_id` 대상을 P5가 아닌 P1로 바로잡았습니다. 소유 변경은 아닙니다.
- T5-A: 룰 정책→요청을 논리 참조로 변경, 평가 없는 오류 분석과 결제 전 OPEN 세션의 선택성을 수정했습니다. 범례는 문서의 실선·점선 약속과 Mermaid 문법을 구분했습니다.
- T5-B: 아래 이동표대로 한 그림을 8개 그림으로 나눴습니다. 기존 관계의 의미를 유지하면서 누락 FK 23개를 추가했고, 합쳐 있던 `locked_by`·`released_by`를 두 FK로 표시했습니다. 결과는 실선 54개·점선 19개입니다.

| 기존 위치 | 변경 위치 | 내용 |
| --- | --- | --- |
| 기존 그림의 P1 계정·접근 관계 | P1 계정·접근 기록 | FK 14개. 정지·해제 수행자 FK를 분리 |
| 그림에서 누락 | P1 신청·키 관리 | FK 10개 추가 |
| 기존 P2 관계 | P2 평가·탐지 | FK 2개 추가로 총 4개, 논리 참조 오류 2건 수정 |
| 기존 P3·내부자 보고서 관계 | P3 사건·판정과 P4 보고서 | FK 7개와 기존 논리 참조 유지 |
| 기존 P5 관계 | P5 결제·취소 | FK 3개 추가로 총 10개, 세션 선택성 수정 |
| 기존 정산 FK 1개·누락 P6 관계 | P6의 픽업·지출 / 매입·정산·지급·환불 두 그림 | FK 8개 추가로 총 9개 |
| 기존 모듈 간 점선 | 모듈 간 주요 논리 참조 | 기존 관계를 별도 그림으로 이동 |
| 그림 아래 긴 문단 | 그림에 없는 논리 참조 표 | 원래 참조를 컬럼별로 나누고 수행자·작업 결과 조건 및 확인된 접근 기록·보고서·조치·연락처 참조 보완 |

- 작성 이력: 이동 전 README까지 `git blame -C -C -C HEAD`로 추적했습니다. 오류 관계선은 hoo0070(`d5a74d42`, `f505dcae`), ssume98(`8894427b`), 범례는 HyunGyu-Cho(`b06a31fe`)의 작성 부분입니다. README 참조 예시는 시은(`e505a0fe`)의 수정 이력입니다. 공동 작성 문서에 대한 기존 수정 허락과 이번 T5·T6 승인 범위에서 설명을 보완했습니다.
- 검증: SQL 54개 FK와 실선의 자식 컬럼·대상 테이블을 일대일 대조했습니다. 복합 FK, NULL, 직접 UNIQUE/PK에 따른 수량 표기도 54개 모두 검사했습니다. 조건부 생성 컬럼 UNIQUE는 전체 이력을 1건으로 제한하는 것으로 해석하지 않았습니다.
- 한계: 점선 전체의 서비스 존재 확인 경로나 새로 생길 모든 논리 참조를 전수 검증한 것은 아닙니다. Mermaid 8개 블록·73개 관계문은 정규식 형식 검사만 수행했습니다. 로컬 Mermaid 패키지·CLI 캐시가 없어 실제 파서·렌더러는 실행하지 않았습니다.

## 6. T6 — 저장 계약 검증·수정

- 주 대상: [contracts.md](contracts.md). 기존 담당별 상세 문서나 [history.md](history.md)의 추가 수정이 필요하면 범위를 먼저 구체화합니다.
- 변경 방향: 현재 구현을 설명하는 문장의 컬럼·제약·상태 설명을 CREATE와 후속 ALTER, 관련 코드·설정과 대조합니다.
- 당시 제안·합의·검증 기록은 날짜와 기준을 유지합니다. 코드가 존재한다는 이유로 ADR 제안을 채택 상태로 바꾸지 않습니다.
- 검증: 수정 문장마다 SQL·코드·합의 문서의 근거를 남기고, 링크·앵커 및 변경 형식을 검사합니다.
- 영향: 저장 계약 설명과 역사 기록의 구분. 업무 정책·스키마·ADR 상태 변경은 포함하지 않습니다.
- 착수 조건: 담당별로 변경안과 확인이 필요한 사항을 나누어 협의합니다.

### T6 반영·검증 기록 — 2026-11-08

- 승인·기준은 T5-A·B와 같습니다. 주 변경 파일은 `contracts.md`이며, `history.md`·기존 담당별 상세 문서·ADR 본문과 상태는 수정하지 않았습니다.
- 대조 범위: 이 파일의 P1~P5 절에 기술된 저장 컬럼·타입·NULL·CHECK·FK·UNIQUE·인덱스를 해당 CREATE와 후속 ALTER·CREATE INDEX와 비교했습니다. 주요 저장 동작은 아래 코드와 대조했습니다. 문서에 요약하지 않은 67개 테이블 전체의 모든 계약·상태 전이 검증은 아닙니다. P6 상세 계약은 기존 README의 담당별 문서로 안내합니다.

| 담당·대상 | 확인 결과와 수정 | 근거·영향 |
| --- | --- | --- |
| P1 #129·#517 감사 수행자 | 최초 NOT NULL과 후속 SYSTEM/NULL 전환을 구분하고 SQL 링크 추가 | CREATE + `V20261104_134638`. 현재 스키마를 최초 계약만으로 오해하지 않도록 함 |
| P1 #219 HMAC | nonce 계약은 유지. `secret_reference` 매핑 추가는 당시 변경으로 표시하고 현재 `merchant_integrations` 암호문 조회 안내 | `MerchantHmacService`, `DatabaseMerchantNonceStore`, `MerchantSecretService`. 새 키 정책을 정하지 않음 |
| P1 #525·#604 | 생성·인덱스·IP 정리·접근 기록 트랜잭션 근거 연결 | 생성 SQL + retention 인덱스, `OperatorAccessDenialRetentionService`, `StaffAccessService`. 운영 정리 실행 완료를 주장하지 않음 |
| P1 #647 | RESTORE가 항상 ACTIVE로 바뀐다는 오해를 수정. SUSPENDED일 때만 전환, 기존 INACTIVE 유지 | `StaffAccountLockService.release`. 결과 필수 값은 CHECK와 Entity 규칙을 구분 |
| P2 룰 요청·차단 | `rule_code` VARCHAR(10) 생성 후 VARCHAR(100) ALTER, FK 없는 정책 참조를 명시. 차단 재활성화의 해제 값 NULL 초기화 확인 | 룰 관리·접근 탐지·차단 해제 SQL, `FdsBlocklistEntry` |
| P3 배정·원문 검토 | 생성 시각과 후속 처리 시각의 서로 다른 NULL 조건을 표에 구분. ADMIN 전용 사유는 현재 Enum에 없다는 점을 명시 | 배정·요청·검토 SQL와 `AssignmentChangeReason`. 예정 정책을 구현 완료로 바꾸지 않음 |
| P3 내부자 사건 | 고객 원문과 ADMIN 표시 이름 사본을 구분. 저장 Enum 이름 INS_01과 표시 코드 SEC-INS-01 구분. 판정·해제의 Entity 필수 검사를 설명 | 생성 SQL, `InsiderCase`, `InsiderCaseAlert`, `InsiderCaseService`, 원격 `InsiderRule`. 화면 코드 전환을 DB 저장값 전환으로 오해하지 않도록 함 |
| P4 내부자 보고서 | 생성 SQL·커밋 후 수신·실패 판 저장 근거 추가. INS_05 저장 식별자와 SEC-INS-05 표시값 구분 | `InsiderReportListener`, `InsiderReportService`. 보고서 생성이 판정·정지 상태를 바꾸지 않는 경계 유지 |
| P5 #141·#149 | 기존 초안·제안 제목/앵커는 유지하고 현재 구현과 도입 기록을 구분. 주문의 두 참조 값 collation, 상태 시각·거절 CHECK·비고유 인덱스가 SQL에 존재함을 명시 | `PaymentAction`, `PaymentActionMapping`, `PaymentOrder`, `PaymentRequestTransactionService`, `Payment`와 생성·후속 ALTER. ADR 상태 변경 없음 |
| P5 #602 | 생성 SQL·연락처 제약을 확인하고 보관·파기 계획을 실행 결과와 구분 | 생성 SQL. 새 보관 정책·파기 배치 구현 없음 |

- 작성 이력: 수정된 원문 줄은 Hong Ju Hwan(`3de31bec`, `b78f4172`), 시은(`c16adefe`), HyunGyu-Cho(`7834648f`, `8e279583`, `943e29fc`, `a0d3eadc`, `ed937eb1`) 등의 공동 작성입니다. 구조 분리 커밋만을 원문 작성 이력으로 보지 않았습니다.
- 담당 확인·잔여 범위: 이번 문서 정정에 새 소유 지정이나 업무 합의는 필요하지 않았습니다. ADMIN 전용 담당 변경 사유 추가 여부, 연락처 파기 배치 등 기존 후속 정책·구현은 이번에 결정하지 않습니다. README 공통 PK 원칙과 현재 예외 테이블을 일반 규칙으로 어떻게 설명할지는 별도 확인 대상으로 남깁니다.

### T5·T6 최종 검사와 Git 처리

- 인메모리 Python: SQL 85개의 원격 기준 동일성(작업 파일 줄바꿈 정규화), 영구 테이블 67개, FK 54개와 실선의 정확한 대응·NULL·UNIQUE/PK 수량 표기 통과.
- ERD 5개 문서의 상대 링크 347개(앵커 포함 89개), 기존 업무 문서 제목 앵커 보존, Markdown 표 열 수·후행 공백, 외부 문서에서 ERD로 들어오는 링크 37개 검사가 통과했습니다. PLAN의 사전 제안 절 제목은 현재 상태에 맞게 바꿨으며 들어오는 링크가 깨지지 않는지 함께 확인했습니다.
- `git diff --check` 및 커밋 전 staged diff 검사. 코드·SQL·설정·`history.md`가 변경되지 않았는지 확인했습니다. 검사 스크립트 파일은 저장소에 추가하지 않았습니다.
- 미검증: Mermaid 실제 파싱·화면 렌더링, 애플리케이션 실행, Gradle·MariaDB 테스트, 실제 DB migration 적용, 운영·Notion 상태, 상세 문서 전체와 전체 논리 참조 경로. 문서 전용 수정이라 코드 자동 포맷 명령은 실행하지 않았습니다.
- Git 처리: 승인된 ERD 문서 4개(`README.md`, `relationships.md`, `contracts.md`, `plan.md`)만 한 로컬 커밋으로 묶습니다. 기존 미추적 Excel 2개는 제외합니다. 푸시·PR·브랜치 전환·rebase는 수행하지 않습니다.

## 7. 진행·승인·검증 기록 방식

- 다음 작업을 시작할 때 이 계획의 현재 위치와 승인 범위를 먼저 확인합니다.
- 팀원에게 받은 수정 허락과 사용자의 진행 승인을 구분합니다. 최초 계획 파일 작성, T4 수정·커밋, T5 사전 검증을 순서대로 승인받았고, 이후 “T5, T6 한번에 진행하고 커밋까지 하자”로 T5-A·B와 T6의 문서 정정 및 로컬 커밋까지 승인받았습니다. T7~T9나 업무 정책·스키마 수정으로 승인을 확대하지 않습니다.
- T4~T6의 실제 수정 전에는 대상·변경 이유·영향·검증 방법을 구체화하고 사용자가 승인한 범위를 기록합니다.
- 작업이 진행되면 해당 Task의 상태, 기준 커밋, 변경 파일, 실행한 검사, 미검증, 담당자 확인 사항을 이 파일에 갱신합니다.
- 커밋·푸시·PR은 각각 실제 진행 상태를 기록합니다. T3의 로컬 커밋 완료를 푸시·PR 승인 또는 완료로 간주하지 않습니다.
- 문서 검사와 애플리케이션·DB 실행 검증을 구분합니다. 실행하지 않은 테스트와 확인하지 않은 운영·Notion 상태를 검증 완료로 쓰지 않습니다.
- 기존 미추적 `프로젝트 주제.xlsx`, `~$프로젝트 주제.xlsx`는 건드리지 않습니다.
- 커밋 메시지 및 GitHub 작성물에 AI 작성자 표기를 추가하지 않습니다.
