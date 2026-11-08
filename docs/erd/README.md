# ERD

테이블 소유와 저장 계약을 찾는 안내입니다. 구조 정리 기준 커밋은 `ea5294e9ece7feb1e1f454fd00c103372a1aecdb`입니다.
소유 표의 테이블 목록과 [물리 FK 관계](relationships.md)는 `f7db9cd307067dcaa6a71673f0ad02b3115a8754`의 migration SQL과 대조했습니다. [저장 계약](contracts.md)의 대조 범위와 미검증 사항은 해당 문서와 [작업 계획](plan.md)에 기록했습니다. 공통 규칙의 모든 예외·전체 논리 참조·담당별 상세 문서 전체를 검증한 것은 아닙니다.

## 목차

1. [범위와 정본](#1-범위와-정본)
2. [테이블 소유](#2-테이블-소유)
3. [ERD 그림](#3-erd-그림)
4. [테이블별 컬럼](#4-테이블별-컬럼)
5. [모듈 간 참조 규칙](#5-모듈-간-참조-규칙)
6. [변경 절차](#6-변경-절차)

## 1. 범위와 정본

- 현재 스키마는 [migration SQL](../../backend/src/main/resources/db/migration/)의 CREATE와 이후 ALTER를 버전 순으로 합쳐 확인합니다. 실제 DB에 적용됐는지는 해당 DB의 적용 이력을 별도로 확인합니다.
- 설계 합의와 담당자 확인 절차는 [AGENTS.md 1·4절](../../AGENTS.md#1-정본-위치)을 따릅니다.
- 저장 계약은 [담당별 계약](contracts.md)과 아래의 기존 상세 문서, 관계는 [ERD 그림](relationships.md)에서 확인합니다.
- 최초 설계·승인·당시 검증과 미검증은 [변경 기록](history.md)에 보존했습니다. 기록의 "현재"나 "후속"을 오늘의 구현 상태로 읽지 않습니다.

### 공통 규칙

| 대상 | 규칙 | 예 |
| --- | --- | --- |
| 테이블 이름 | 복수 · snake_case | `payments`, `security_cases` |
| PK | `BIGINT AUTO_INCREMENT` 단일 컬럼 | `payment_id` |
| 다른 테이블 ID | `BIGINT` (가리키는 PK와 같은 타입) | `merchant_id` |
| 상태 · 유형 | `VARCHAR` + JPA `EnumType.STRING` (enum을 이름 문자열로 저장) | `status VARCHAR(30)` |
| 시각 | `DATETIME(6)`, UTC로 저장 (`hibernate.jdbc.time_zone: UTC`) | `created_at` |
| 금액 | `BIGINT`, 원(KRW) 정수 | `amount` |
| 비율 | `DECIMAL(9,6)`, 0~1 | `pg_fee_rate` |
| 구조가 자유로운 값 | `JSON` (MariaDB가 형식을 검사) | `report_body` |
| 중복 방지 키 | MariaDB `UUID` | `idempotency_key` |
| 제약 · 인덱스 이름 | `uk_<테이블>_<컬럼>` · `fk_<테이블>_<대상>` · `idx_<테이블>_<용도>` | `uk_ai_reports_case_version` |

## 2. 테이블 소유

기준 커밋의 migration SQL 85개에서 생성하는 영구 테이블 67개를 아래 목록에 포함했습니다. 공통 `EVENT_PUBLICATION`은 포함하며, 전환용 임시 테이블과 Flyway가 관리하는 `flyway_schema_history`는 제외합니다. 실제 DB 적용 여부를 확인한 결과는 아닙니다.

migration 열은 생성·관련 변경 SQL을 찾는 색인입니다. 한 파일이 여러 테이블을 만들거나 한 테이블이 여러 파일에서 바뀔 수 있으며, 모든 후속 변경을 나열하지는 않습니다. 최종 컬럼·제약은 [전체 migration](../../backend/src/main/resources/db/migration/)에서 해당 테이블의 CREATE와 이후 ALTER를 함께 확인합니다. 상세 문서의 제안·승인·당시 검증 상태는 그대로 유지합니다.

| 모듈 | 담당 | 테이블 | migration (생성·관련 변경) |
| --- | --- | --- | --- |
| `identity` | P1 주환 | `merchants`, `platform_users`, `user_permissions` | [20261022_145055](../../backend/src/main/resources/db/migration/V20261022_145055__create_merchants_table.sql) · [20261022_145057](../../backend/src/main/resources/db/migration/V20261022_145057__create_platform_users_table.sql) · [20261022_145059](../../backend/src/main/resources/db/migration/V20261022_145059__create_user_permissions_table.sql) |
| `identity` (#129) | P1 주환 | `platform_users` 강제 변경·역할 CHECK, `admin_change_histories` | [강제 변경](../../backend/src/main/resources/db/migration/V20261025_121650__add_password_change_required.sql) · [역할 CHECK](../../backend/src/main/resources/db/migration/V20261025_121656__add_user_role_merchant_check.sql) · [관리 이력](../../backend/src/main/resources/db/migration/V20261025_121657__create_admin_change_histories.sql) |
| `identity` (#127) | P1 주환 | `platform_users` 연속 로그인 실패 횟수·잠금 종료 시각 | [로그인 잠금](../../backend/src/main/resources/db/migration/V20261025_165712__add_login_attempt_lock.sql) |
| `identity` (#219) | P1 주환 | `merchant_request_nonces` | [HMAC nonce](../../backend/src/main/resources/db/migration/V20261027_144924__create_merchant_request_nonces.sql) |
| `identity` (#147) | P1 주환 | `login_histories` | [로그인 이력](../../backend/src/main/resources/db/migration/V20261025_201559__create_login_histories.sql) |
| `identity` (#525) | P1 주환 | `operator_access_denials` | [403 원본](../../backend/src/main/resources/db/migration/V20261104_153346__create_operator_access_denials.sql) |
| `identity` (#604) | P1 주환 | `staff_access_events` (보안 직원 · ADMIN 접근 기록, ADR-0082) | [접근 기록](../../backend/src/main/resources/db/migration/V20261106_215350__create_staff_access_events.sql) |
| `identity` (#647) | P1 주환 | `staff_account_locks` (보안 직원 계정 보안 정지 기록, ADR-0084) | [보안 정지](../../backend/src/main/resources/db/migration/V20261107_233256__create_staff_account_locks.sql) |
| `identity` ([신청](../identity/315-application-design.md)) | P1 주환 | `merchant_applications` | [생성](../../backend/src/main/resources/db/migration/V20261029_140200__create_merchant_applications.sql) · [신청 로그인 ID](../../backend/src/main/resources/db/migration/V20261029_164047__add_application_requested_login_id.sql) · [쇼핑몰 주소](../../backend/src/main/resources/db/migration/V20261030_105049__add_application_shop_url.sql) · [이메일 확인 상태 전환](../../backend/src/main/resources/db/migration/V20261101_160844__rename_application_email_verification_status.sql) |
| `identity` ([신청 지원](../identity/315-application-design.md)) | P1 주환 | `application_tokens`, `application_mail_deliveries`, `application_rate_limits` | [토큰](../../backend/src/main/resources/db/migration/V20261029_140208__create_application_tokens.sql) · [메일 전송 이력](../../backend/src/main/resources/db/migration/V20261029_140209__create_application_mail_deliveries.sql) · [요청 제한](../../backend/src/main/resources/db/migration/V20261029_140210__create_application_rate_limits.sql) |
| `identity` ([키·연동](../identity/354-pr-b-key-design.md)) | P1 주환 | `merchant_integrations`, `merchant_key_histories` | [연동 상태](../../backend/src/main/resources/db/migration/V20261031_151333__create_merchant_integrations.sql) · [키 이력](../../backend/src/main/resources/db/migration/V20261031_151342__create_merchant_key_histories.sql) |
| `payment` | P5 시은 | `payments` | [20261022_145100](../../backend/src/main/resources/db/migration/V20261022_145100__create_payments_table.sql) |
| `payment` (#141) | P5 시은 | `payment_actions` | [조치 저장](../../backend/src/main/resources/db/migration/V20261025_160810__create_payment_actions_table.sql) |
| `payment` (#381) | P5 시은 | `payment_cancellation_requests`, `payment_cancellations` | [취소 요청](../../backend/src/main/resources/db/migration/V20261031_183409__create_payment_cancellation_requests.sql) · [금융 작업](../../backend/src/main/resources/db/migration/V20261031_183416__create_payment_cancellations.sql) |
| `payment` (#402) | P5 | `payment_sessions` (호스팅 결제창 세션, ADR-0064) | [결제 세션](../../backend/src/main/resources/db/migration/V20261102_142818__create_payment_sessions.sql) · [웹훅 재시도 eventId #455](../../backend/src/main/resources/db/migration/V20261103_093050__add_payment_session_notify_event_id.sql) |
| `payment` (#602) | P5 | `payment_order_contacts` (주문자 연락처 암호문, ADR-0082) | [주문자 연락처](../../backend/src/main/resources/db/migration/V20261106_222538__create_payment_order_contacts.sql) |
| `payment` ([주문·시도](contracts.md#149-p5-cakeshop-결제-저장-제안)) | P5 시은 | `payment_orders`, `payment_requests` | [주문](../../backend/src/main/resources/db/migration/V20261025_210955__create_payment_orders_table.sql) · [요청](../../backend/src/main/resources/db/migration/V20261025_210956__create_payment_requests_table.sql) · [요청 위험 신호](../../backend/src/main/resources/db/migration/V20261029_141011__add_payment_request_risk_signals.sql) |
| `payment` ([FDS 복구](../payment/273-fds-recovery-storage.md)) | P5 시은 | `payment_fds_recoveries` | [복구 저장](../../backend/src/main/resources/db/migration/V20261028_155021__create_payment_fds_recoveries_table.sql) |
| `fds` | P2 정후 | `fds_evaluations`, `fds_rule_hits`, `fds_failure_analyses`, `fds_high_risk_countries`, `fds_blocklist_entries`, `fds_rule_policies`, `fds_rule_change_requests`, `fds_rule_policy_change_histories`, `fds_operator_access_events`, `fds_operator_access_detections`, `fds_operator_access_detection_evidences` | [20261022_145102](../../backend/src/main/resources/db/migration/V20261022_145102__create_fds_evaluations_table.sql) · [20261022_145104](../../backend/src/main/resources/db/migration/V20261022_145104__create_fds_rule_hits_table.sql) · [20261022_174140](../../backend/src/main/resources/db/migration/V20261022_174140__add_fds_evaluation_request_fields.sql) · [20261023_135302](../../backend/src/main/resources/db/migration/V20261023_135302__add_fds_requested_amount.sql) · [20261026_174910](../../backend/src/main/resources/db/migration/V20261026_174910__add_fds_evaluation_merchant_id.sql) · [20261027_101722](../../backend/src/main/resources/db/migration/V20261027_101722__create_fds_failure_analyses.sql) · [20261027_101804](../../backend/src/main/resources/db/migration/V20261027_101804__add_fds_reevaluation_lineage.sql) · [FDS v0.2](../../backend/src/main/resources/db/migration/V20261028_092416__add_fds_rule_inputs_and_policy_tables.sql) · [룰 관리](../../backend/src/main/resources/db/migration/V20261028_155511__create_fds_rule_management_tables.sql) · [접근 탐지·룰 정책·변경 이력](../../backend/src/main/resources/db/migration/V20261104_152441__create_operator_access_detection_tables.sql) · [내부자 규칙 정책](../../backend/src/main/resources/db/migration/V20261108_212551__add_insider_rule_policies.sql) · [차단 식별값](../../backend/src/main/resources/db/migration/V20261028_201821__add_fds_blocklist_identifiers.sql) · [차단 해제 이력](../../backend/src/main/resources/db/migration/V20261030_114028__add_fds_blocklist_deactivation.sql) |
| `fds` (#516) | P2 정후 | `insider_alerts`, `insider_alert_evidences`, `insider_detection_states` (보안 직원 내부자 경보, ADR-0082) | [내부자 경보](../../backend/src/main/resources/db/migration/V20261107_082223__create_insider_alerts.sql) |
| `investigation` | P3 수민 | `security_cases`, `case_payments`, `payment_decisions`, `action_handoffs`, `case_histories` | [20261022_145106](../../backend/src/main/resources/db/migration/V20261022_145106__create_security_cases_table.sql) · [20261022_145108](../../backend/src/main/resources/db/migration/V20261022_145108__create_case_payments_table.sql) · [20261022_145111](../../backend/src/main/resources/db/migration/V20261022_145111__create_payment_decisions_table.sql) · [20261022_145112](../../backend/src/main/resources/db/migration/V20261022_145112__create_action_handoffs_table.sql) · [20261022_145115](../../backend/src/main/resources/db/migration/V20261022_145115__create_case_histories_table.sql) |
| `investigation` (#608) | P3 수민 | `security_cases` 담당 칸, `case_assignment_locks`, `case_assignment_requests` (사건 자동 배정 · 담당 변경, ADR-0082) | [담당 배정](../../backend/src/main/resources/db/migration/V20261106_225206__add_security_case_assignment.sql) · [담당 변경 요청](../../backend/src/main/resources/db/migration/V20261106_225208__create_case_assignment_requests.sql) |
| `investigation` (#619) | P3 수민 | `raw_view_reviews` (주문자 전화 원문 열람 사후 검토, ADR-0082) | [원문 열람 검토](../../backend/src/main/resources/db/migration/V20261107_024052__create_raw_view_reviews.sql) |
| `investigation` (#670) | P3 수민 | `insider_cases`, `insider_case_alerts` (내부자 사건 · 관리자 판정 · 징계 사건 정지 해제, ADR-0082) | [내부자 사건](../../backend/src/main/resources/db/migration/V20261108_123006__create_insider_cases.sql) |
| `investigation/report` | P4 현규 | `ai_reports` | [20261022_145116](../../backend/src/main/resources/db/migration/V20261022_145116__create_ai_reports_table.sql) |
| `investigation/report` (#675) | P4 현규 | `ai_insider_reports` (내부자 사건 해제 검토 보고서, ADR-0082) | [해제 검토 보고서](../../backend/src/main/resources/db/migration/V20261108_134720__create_ai_insider_reports.sql) |
| `investigation/report` (#412) | P4 현규 | `ml_shadow_scores` (FDS ML Shadow 채점 기록) | [ML Shadow](../../backend/src/main/resources/db/migration/V20261102_125730__create_ml_shadow_scores.sql) |
| `investigation/report` (#626) | P4 현규 | `ai_switch_histories` (AI 조사 긴급정지 스위치 변경 기록, ADR-0083) | [긴급정지](../../backend/src/main/resources/db/migration/V20261107_101635__create_ai_switch_histories.sql) |
| `settlement` | P6 민정 | `captures`, `settlements` | [20261022_145118](../../backend/src/main/resources/db/migration/V20261022_145118__create_captures_table.sql) · [20261022_145120](../../backend/src/main/resources/db/migration/V20261022_145120__create_settlements_table.sql) |
| `settlement` ([픽업·상품](../settlement/pickup-schema.md)) | P6 민정 | `order_fulfillments`, `order_fulfillment_items` | [픽업](../../backend/src/main/resources/db/migration/V20261027_092731__create_order_fulfillments.sql) · [상품](../../backend/src/main/resources/db/migration/V20261029_143636__create_order_fulfillment_items.sql) · [가맹점별 주문 유일성](../../backend/src/main/resources/db/migration/V20261029_155634__scope_order_fulfillments_by_merchant.sql) |
| `settlement` ([최초 잔액·지출](../settlement/balance-expenses-api.md)) | P6 민정 | `cash_flow_settings`, `planned_expenses` | [최초 잔액](../../backend/src/main/resources/db/migration/V20261030_125121__create_cash_flow_settings.sql) · [일회성 지출](../../backend/src/main/resources/db/migration/V20261030_125123__create_planned_expenses.sql) |
| `settlement` ([정기 지출](../settlement/recurring-expenses-api.md#erd)) | P6 민정 | `recurring_expenses`, `recurring_expense_payments` | [규칙·회차별 완료 기록](../../backend/src/main/resources/db/migration/V20261030_181043__create_recurring_expenses_with_payments.sql) |
| `settlement` ([D+3 달력](../settlement/d3-policy-and-erd.md)) | P6 민정 | `settlement_business_days` | [달력 생성·기존 정산 일정 전환](../../backend/src/main/resources/db/migration/V20261103_133338__capture_business_day_settlement_calendar.sql) |
| `settlement` ([지급 원장](../settlement/daily-payouts.md)) | P6 민정 | `merchant_payouts`, `merchant_payout_items` | [원장·상세](../../backend/src/main/resources/db/migration/V20261103_144056__create_merchant_daily_payouts.sql) |
| `settlement` ([환불 수신](../settlement/refund-receiving.md)) | P6 민정 | `settlement_refund_adjustments` | [환불 조정 원장](../../backend/src/main/resources/db/migration/V20261103_154941__create_settlement_refund_adjustments.sql) |
| `settlement` ([가맹점 잠금](../settlement/unpaid-refund-exclusion.md)) | P6 민정 | `merchant_settlement_locks` | [공통 잠금](../../backend/src/main/resources/db/migration/V20261103_164349__create_merchant_settlement_locks.sql) |
| `settlement` ([환불 회수](../settlement/refund-recovery.md)) | P6 민정 | `payout_refund_allocations` | [후속 지급별 회수 배분](../../backend/src/main/resources/db/migration/V20261103_174335__create_payout_refund_allocations.sql) |
| (공통) | — | `EVENT_PUBLICATION` (Modulith 이벤트 기록) | [create_event_publication_table](../../backend/src/main/resources/db/migration/V20261021_215412__create_event_publication_table.sql) |

### 담당별 상세 문서

| 담당 | 저장 계약·기존 상세 문서 |
| --- | --- |
| P1 identity | [저장 계약](contracts.md#p1-identity) · [관리 계정](../identity/129-admin-account-design.md) · [로그인 잠금](../identity/127-login-attempt-design.md) · [로그인 이력](../identity/147-login-history-design.md) · [신청](../identity/315-application-design.md) · [승인](../identity/315-approval-design.md) · [주소](../identity/354-pr1-application-design.md) · [키·연동](../identity/354-pr-b-key-design.md) · [상태 전환](../identity/131-status-transition.md) · [CakeShop 시드](../identity/246-cakeshop-provisioning.md) |
| P2 fds | [룰 변경·차단 해제 저장 설명](contracts.md#p2-fds) · 위 소유 표의 생성·변경 SQL |
| P3 investigation | [배정·원문 열람 검토·내부자 사건 계약](contracts.md#p3-investigation) |
| P4 investigation/report | [내부자 사건 보고서 계약](contracts.md#p4-investigationreport) · 위 소유 표의 보고서·ML Shadow·스위치 SQL |
| P5 payment | [조치·결제 주문·시도·연락처 계약](contracts.md#p5-payment) · [FDS 복구 계약](../payment/190-fds-recovery-contract.md) · [복구 저장 검토](../payment/273-fds-recovery-storage.md) · [고객 취소 저장](../payment/381-cancellation-storage.md) |
| P6 settlement | [픽업](../settlement/pickup-schema.md) · [최초 잔액·지출](../settlement/balance-expenses-api.md) · [정기 지출](../settlement/recurring-expenses-api.md#erd) · [D+3](../settlement/d3-policy-and-erd.md) · [지급 원장](../settlement/daily-payouts.md) · [환불 수신](../settlement/refund-receiving.md) · [미지급 제외](../settlement/unpaid-refund-exclusion.md) · [환불 회수](../settlement/refund-recovery.md) |

## 3. ERD 그림

[관계 그림과 논리 참조 목록](relationships.md)으로 옮겼습니다.

## 4. 테이블별 컬럼

컬럼·타입·제약은 관련 테이블의 CREATE와 이후 ALTER를 함께 봅니다. 확인 방법은 [Flyway 가이드 5절](../flyway/05-guide.md#5-기존-스키마-확인하는-법)을 따릅니다.
업무 의미와 저장 계약은 [담당별 계약](contracts.md)과 2절의 상세 문서를 봅니다. 기존 설계의 제안 상태와 운영 미검증 여부는 이동 후에도 유지합니다.

## 5. 모듈 간 참조 규칙

[AGENTS.md 4절](../../AGENTS.md#4-backend-규칙)의 규칙을 그대로 따릅니다.

| 관계 | 참조 방식 | 예시 |
| --- | --- | --- |
| 같은 소유 담당의 테이블끼리 | 물리 FK(외래 키 제약을 DB에 건다) | `security_cases` ↔ `case_payments` (둘 다 P3) |
| 소유 담당이 다른 테이블 | 논리 참조(ID 값만 저장, FK 없음) | `fds_evaluations.payment_id` (P2 → P5), `fds_evaluations.merchant_id` (P2 → P1) |
| 같은 `investigation` 모듈 안의 P3 ↔ P4 | 논리 참조 (예외) | `ai_reports` ↔ `security_cases` · `payment_decisions`, `ai_insider_reports` ↔ `insider_cases` |

- 논리 참조 대상의 존재 확인은 DB가 아니라 소유 담당 모듈의 공개 `api/`로 합니다
- 참조되는 금융·조사 기록(결제·평가·Case·판정·인계·보고서)은 물리 삭제하지 않습니다. 그래서 FK에 `ON DELETE` 옵션을 두지 않습니다 (참조 중이면 삭제 거부)

## 6. 변경 절차

DB 변경 작업 순서와 소유 담당 확인은 [AGENTS.md 4절](../../AGENTS.md#4-backend-규칙), 파일 작성·검사는 [Flyway 작업 가이드](../flyway/05-guide.md)를 따릅니다.
문서를 갱신할 때는 소유 색인, 관계 그림, 해당 저장 계약·상세 문서의 영향을 함께 확인합니다.
이전의 Notion 선행 반영 안내와 이슈별 승인·검증 경위는 [변경 기록](history.md)에 보존했습니다.

## 기존 링크 안내

기존 문서와 ADR에서 참조하는 아래 제목은 유지합니다. 본문은 각 새 위치에서 확인합니다.

### #141 P5 직원 조치 저장 설계 초안

[저장 계약](contracts.md#141-p5-직원-조치-저장-설계-초안) · [승인·도입 기록](history.md#141-p5-직원-조치-저장-설계-초안)

### #149 P5 CakeShop 결제 저장 제안

[저장 계약](contracts.md#149-p5-cakeshop-결제-저장-제안) · [승인·도입 기록](history.md#149-p5-cakeshop-결제-저장-제안)

### #525 P1 사장님 403 원본 기록 (2026-11-04)

[저장 계약](contracts.md#525-p1-사장님-403-원본-기록-2026-11-04) · [승인·도입 기록](history.md#525-p1-사장님-403-원본-기록-2026-11-04)

### #602 P5 주문자 연락처 (2026-11-06)

[저장 계약](contracts.md#602-p5-주문자-연락처-2026-11-06) · [승인·도입 기록](history.md#602-p5-주문자-연락처-2026-11-06)
