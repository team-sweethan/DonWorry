# ERD

돈워리 DB의 표(테이블)가 **누구 담당이고, 무엇을 담고, 서로 어떻게 이어지는지** 안내합니다.

## 무엇을 찾나요?

| 알고 싶은 것 | 볼 곳 |
| --- | --- |
| 이 표는 누구 담당이고 무엇을 담나 | [2절 테이블 소유](#2-테이블-소유) |
| 표끼리 어떻게 이어지나 (그림) | [관계 그림](relationships.md) |
| 칸 하나하나의 뜻 · 제약 · 저장 규칙 | [저장 계약](contracts.md) · 각 담당 문서 |
| 실제 칸 이름 · 타입 | [migration 폴더](../../backend/src/main/resources/db/migration/)에서 표 이름으로 검색 ([Flyway 가이드 5절](../flyway/05-guide.md#5-기존-스키마-확인하는-법)) |
| 표를 새로 만들거나 바꾸려면 | [6절 변경 절차](#6-변경-절차) |
| 왜 이렇게 정했나 (승인 경위) | [승인 · 변경 기록](history.md) |

## 1. 범위와 정본

- 실제 표 · 칸 · 제약의 정답은 **migration SQL**입니다. 한 표의 현재 모양은 그 표를 만든 `CREATE`와 이후 `ALTER`를 버전 순으로 합쳐 봅니다.
- 이 폴더의 문서는 그 SQL을 사람이 찾고 이해하기 쉽게 정리한 안내입니다. 문서와 SQL이 다르면 SQL을 따르고 문서를 고칩니다.
- 설계 합의와 담당자 확인 절차는 [AGENTS.md 1 · 4절](../../AGENTS.md#1-정본-위치)을 따릅니다.

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

표 68개를 담당별로 나눴습니다. "계약" 칸의 링크는 칸 단위 설명이 있는 곳입니다. 비어 있으면 아래 "더 읽을 문서"나 SQL을 봅니다.

> 🔒 표시는 업무 데이터가 아니라 **작업을 한 줄로 세우기 위한 잠금 행**입니다.

### P1 주환 — 계정 · 가맹점 (`identity`, 15개)

| 표 | 무엇을 담나 | 계약 |
| --- | --- | --- |
| `merchants` | 가맹점 기본 정보 (코드 · 이름 · 상태 · PG 수수료율 · 웹훅 주소) | |
| `platform_users` | 로그인 계정 — 관리자 · 보안 직원 · 사장님. 역할 · 상태 · 소속 가맹점 | [#129](contracts.md#129-p1-변경-계약-2026-10-25) |
| `user_permissions` | 계정별 추가 권한 (`FDS_VIEW` · `FINANCE_VIEW` 등) | |
| `admin_change_histories` | 관리자(또는 시스템)가 계정 · 가맹점을 바꾼 기록, 바뀌기 전후 값 | [#129](contracts.md#129-p1-변경-계약-2026-10-25) · [#517](contracts.md#517-p1-시스템-수행자-2026-11-04) |
| `login_histories` | 로그인 · 로그아웃 이력 (IP · 브라우저) | |
| `merchant_request_nonces` | 가맹점 서명 요청의 1회용 번호 — 같은 요청 재전송을 막고, 만료되면 지움 | [#219](contracts.md#219-p1-hmac-nonce-계약-2026-10-27) |
| `operator_access_denials` | 사장님이 권한 밖 화면을 요청해 거절된 원본 기록 | [#525](contracts.md#525-p1-사장님-403-원본-기록-2026-11-04) |
| `staff_access_events` | 보안 직원 · 관리자가 민감 정보를 열람한 기록 (허용 · 거부) | [#604](contracts.md#604-p1-보안-직원-접근-기록-2026-11-06) |
| `staff_account_locks` | 보안 직원 계정의 보안 정지 · 해제 기록 | [#647](contracts.md#647-p1-보안-직원-계정-보안-정지-2026-11-07) |
| `merchant_applications` | 가맹점 이용 신청서 (사업자 · 연락처 · 심사 상태) | |
| `application_tokens` | 신청 이메일 확인 등에 쓰는 1회용 토큰 (해시만 저장) | |
| `application_mail_deliveries` | 신청 관련 메일 발송 이력 | |
| `application_rate_limits` | 신청 요청 횟수 제한 (IP · 이메일은 해시로) | |
| `merchant_integrations` | 가맹점 연동 상태와 서명 키 (암호문) | |
| `merchant_key_histories` | 서명 키 발급 · 교체 이력 | |

더 읽을 문서: [관리 계정](../identity/129-admin-account-design.md) · [로그인 잠금](../identity/127-login-attempt-design.md) · [로그인 이력](../identity/147-login-history-design.md) · [신청](../identity/315-application-design.md) · [승인](../identity/315-approval-design.md) · [주소](../identity/354-pr1-application-design.md) · [키 · 연동](../identity/354-pr-b-key-design.md) · [상태 전환](../identity/131-status-transition.md) · [CakeShop 시드](../identity/246-cakeshop-provisioning.md)

### P2 정후 — 이상거래 탐지 (`fds`, 14개)

| 표 | 무엇을 담나 | 계약 |
| --- | --- | --- |
| `fds_evaluations` | 결제 한 건의 FDS 평가 결과 (위험 점수 · 판정) | |
| `fds_rule_hits` | 그 평가에서 걸린 규칙과 근거 값 | |
| `fds_failure_analyses` | FDS 평가가 오류로 끝났을 때 받은 보고와 분석 결과 | |
| `fds_high_risk_countries` | 고위험 국가 목록 (규칙 입력값) | |
| `fds_blocklist_entries` | 차단 목록 (식별값 · 사유 · 만료 · 해제) | [룰 변경 · 차단 해제](contracts.md#룰-변경-요청과-차단-해제-기록) |
| `fds_rule_policies` | 규칙별 켜기 · 끄기 상태 (결제 규칙과 내부자 규칙 `INS_xx`) | |
| `fds_rule_change_requests` | 규칙 켜기 · 끄기 변경 요청과 처리 결과 | [룰 변경 · 차단 해제](contracts.md#룰-변경-요청과-차단-해제-기록) |
| `fds_rule_policy_change_histories` | 규칙 정책이 실제로 바뀐 이력 | |
| `fds_operator_access_events` | P1에서 받은 사장님 접근 거절 기록의 사본 (탐지 입력) | |
| `fds_operator_access_detections` | 사장님 계정의 반복 접근 거절 탐지 결과 · 조치 상태 · 관리자 확인 시각 | [접근 탐지 확인](contracts.md#계정-접근-이상-탐지-확인) |
| `fds_operator_access_detection_evidences` | 위 탐지의 근거가 된 접근 기록 연결 | |
| `insider_alerts` | 보안 직원 내부자 경보 (규칙 · 심각도 · 종결) | |
| `insider_alert_evidences` | 경보의 근거가 된 직원 접근 기록 연결 | |
| `insider_detection_states` | 🔒 같은 직원의 탐지를 하나씩 처리하기 위한 직원별 행 | |

### P3 수민 — 사건 · 판정 (`investigation`, 10개)

| 표 | 무엇을 담나 | 계약 |
| --- | --- | --- |
| `security_cases` | FDS 사건 (가맹점 · 고객 · 상태 · 담당 직원) | [#608](contracts.md#608-p3-fds-사건-자동-배정과-담당-변경-2026-11-06) |
| `case_payments` | 사건에 묶인 결제 | |
| `payment_decisions` | 보안 직원의 결제 판정과 사유 | |
| `action_handoffs` | 판정을 P5 결제 조치(매입 · 승인 취소)로 넘긴 인계와 처리 상태 | |
| `case_histories` | 사건에서 일어난 일의 이력 | |
| `case_assignment_locks` | 🔒 사건 자동 배정을 한 줄로 세우는 행 | [#608](contracts.md#608-p3-fds-사건-자동-배정과-담당-변경-2026-11-06) |
| `case_assignment_requests` | 담당 변경 요청과 관리자 처리 | [#608](contracts.md#608-p3-fds-사건-자동-배정과-담당-변경-2026-11-06) |
| `raw_view_reviews` | 주문자 전화번호 원문 열람과 관리자 사후 검토 | [#608](contracts.md#608-p3-fds-사건-자동-배정과-담당-변경-2026-11-06) |
| `insider_cases` | 내부자 사건 — 관리자 판정과 정지 해제 | [#670](contracts.md#670-p3-내부자-사건--관리자-판정--징계-사건-정지-해제-2026-11-08) |
| `insider_case_alerts` | 내부자 사건에 묶인 경보 | [#670](contracts.md#670-p3-내부자-사건--관리자-판정--징계-사건-정지-해제-2026-11-08) |

### P4 현규 — 보고서 (`investigation/report`, 5개)

| 표 | 무엇을 담나 | 계약 |
| --- | --- | --- |
| `ai_reports` | 결제 사건 AI 조사 보고서. 판(`report_version`)마다 쌓고 덮어쓰지 않음 | |
| `ai_report_questions` | 보고서를 본 직원의 질문과 답변 | [#752](contracts.md#752-p4-보고서-뒤-사건-질문--답변-2026-11-10) |
| `ai_insider_reports` | 내부자 사건 해제 검토 보고서 | [#675](contracts.md#675-p4-내부자-사건-해제-검토-보고서-2026-11-08) |
| `ml_shadow_scores` | ML 모델이 결제를 옆에서 채점한 기록 (결제 판정에는 쓰지 않음, 비교용) | |
| `ai_switch_histories` | AI 조사 긴급정지 스위치를 켜고 끈 기록 ([ADR-0083](../decisions/0083-ai-kill-switch.md)) | |

### P5 시은 — 결제 (`payment`, 9개)

| 표 | 무엇을 담나 | 계약 |
| --- | --- | --- |
| `payments` | 결제 정본 (가맹점 · 금액 · 상태 · 승인번호) | |
| `payment_orders` | 주문 단위 접수 — 같은 주문의 결제를 한 줄로 세움 | [#149](contracts.md#149-p5-cakeshop-결제-저장-제안) |
| `payment_requests` | 결제 시도별 기록과 FDS 평가 단계 | [#149](contracts.md#149-p5-cakeshop-결제-저장-제안) |
| `payment_actions` | 판정 뒤 결제 조치(매입 · 승인 취소) 실행 기록 | [#141](contracts.md#141-p5-직원-조치-저장-설계-초안) |
| `payment_fds_recoveries` | FDS 평가가 오류로 끝난 결제의 복구 작업 | |
| `payment_cancellation_requests` | 가맹점이 보낸 고객 취소 요청 | |
| `payment_cancellations` | 취소 금융 작업(승인 취소 · 환불)과 결과 | |
| `payment_sessions` | 결제창 세션 — 주문 정보 · 카드 제출 · 가맹점 알림 재시도 ([ADR-0064](../decisions/0064-hosted-checkout-and-payment-webhook.md)) | |
| `payment_order_contacts` | 주문자 이름 · 전화번호 (암호문) | [#602](contracts.md#602-p5-주문자-연락처-2026-11-06) |

더 읽을 문서: [FDS 복구 계약](../payment/190-fds-recovery-contract.md) · [복구 저장 검토](../payment/273-fds-recovery-storage.md) · [고객 취소 저장](../payment/381-cancellation-storage.md)

### P6 민정 — 정산 (`settlement`, 14개)

| 표 | 무엇을 담나 | 더 읽을 문서 |
| --- | --- | --- |
| `captures` | 매입 (결제 금액 확정) | |
| `settlements` | 매입 건별 정산 금액 (카드 · PG 수수료 차감) | [D+3](../settlement/d3-policy-and-erd.md) |
| `order_fulfillments` | 주문 픽업 예정 · 완료 시각 | [픽업](../settlement/pickup-schema.md) |
| `order_fulfillment_items` | 픽업 주문의 상품 목록 | [픽업](../settlement/pickup-schema.md) |
| `cash_flow_settings` | 가맹점의 최초 잔액 · 기준일 · 최소 운영 자금 (현금흐름 예측의 출발점과 경고 기준) | [최초 잔액 · 지출](../settlement/balance-expenses-api.md) · [ADR-0085](../decisions/0085-p6-minimum-operating-funds.md) |
| `planned_expenses` | 일회성 지출 예정 · 완료 | [최초 잔액 · 지출](../settlement/balance-expenses-api.md) |
| `recurring_expenses` | 정기 지출 규칙 | [정기 지출](../settlement/recurring-expenses-api.md#erd) |
| `recurring_expense_payments` | 정기 지출의 회차별 완료 기록 | [정기 지출](../settlement/recurring-expenses-api.md#erd) |
| `settlement_business_days` | 영업일 달력 (D+3 정산일 계산) | [D+3](../settlement/d3-policy-and-erd.md) |
| `merchant_payouts` | 가맹점 일별 지급 원장 | [지급 원장](../settlement/daily-payouts.md) |
| `merchant_payout_items` | 지급 한 건에 포함된 정산 건 | [지급 원장](../settlement/daily-payouts.md) |
| `settlement_refund_adjustments` | 정산 뒤 환불로 생긴 조정 금액과 남은 회수액 | [환불 수신](../settlement/refund-receiving.md) |
| `merchant_settlement_locks` | 🔒 가맹점별 정산 작업을 한 줄로 세우는 행 | [미지급 제외](../settlement/unpaid-refund-exclusion.md) |
| `payout_refund_allocations` | 환불 회수액을 이후 지급에서 나눠 뺀 기록 | [환불 회수](../settlement/refund-recovery.md) |

### 공통 (1개)

| 표 | 무엇을 담나 |
| --- | --- |
| `EVENT_PUBLICATION` | 모듈 사이 이벤트 전달 기록 (Spring Modulith, 실패하면 다시 전달) |

`flyway_schema_history`(Flyway가 관리)와 migration 안에서 잠깐 쓰고 지우는 임시 표는 목록에서 뺐습니다.

## 3. ERD 그림

[관계 그림](relationships.md)에 담당 사이 전체 지도와 담당별 그림이 있습니다.

## 4. 테이블별 컬럼

칸 이름 · 타입 · 제약은 그 표의 `CREATE`와 이후 `ALTER`를 함께 봅니다. 찾는 법은 [Flyway 가이드 5절](../flyway/05-guide.md#5-기존-스키마-확인하는-법)에 있습니다.
칸의 업무 의미는 [저장 계약](contracts.md)과 2절의 담당 문서를 봅니다.

## 5. 모듈 간 참조 규칙

[AGENTS.md 4절](../../AGENTS.md#4-backend-규칙)의 규칙을 그대로 따릅니다.

| 관계 | 참조 방식 | 예시 |
| --- | --- | --- |
| 같은 소유 담당의 테이블끼리 | 물리 FK(외래 키 제약을 DB에 건다) | `security_cases` ↔ `case_payments` (둘 다 P3) |
| 소유 담당이 다른 테이블 | 논리 참조(ID 값만 저장, FK 없음) | `fds_evaluations.payment_id` (P2 → P5), `fds_evaluations.merchant_id` (P2 → P1) |
| 같은 `investigation` 모듈 안의 P3 ↔ P4 | 논리 참조 (예외) | `ai_reports` ↔ `security_cases` · `payment_decisions`, `ai_insider_reports` ↔ `insider_cases` |

- 논리 참조 대상의 존재 확인은 DB가 아니라 소유 담당 모듈의 공개 `api/`로 합니다
- 참조되는 금융 · 조사 기록(결제 · 평가 · Case · 판정 · 인계 · 보고서)은 물리 삭제하지 않습니다. 그래서 FK에 `ON DELETE` 옵션을 두지 않습니다 (참조 중이면 삭제 거부)

## 6. 변경 절차

DB 변경 작업 순서와 소유 담당 확인은 [AGENTS.md 4절](../../AGENTS.md#4-backend-규칙), 파일 작성 · 검사는 [Flyway 작업 가이드](../flyway/05-guide.md)를 따릅니다.
표를 추가하거나 바꾸면 이 문서의 2절 표 · [관계 그림](relationships.md) · 해당 [저장 계약](contracts.md)을 같은 PR에서 함께 고칩니다.

## 이 문서의 기준

- 2절 표 목록과 관계 그림은 `7e8a1ab1`(2026-11-16 dev)의 migration 91개와 대조했습니다. 표 68개, 물리 FK 55개입니다.
- 이 대조는 SQL 파일을 읽은 결과입니다. 실제 DB 적용 · 앱 실행 결과가 아닙니다.
- 이전 대조 기준과 당시 미검증 사항은 [작업 계획](plan.md)과 [승인 · 변경 기록](history.md)에 남아 있습니다. 기록 속 "현재" · "후속"을 오늘 상태로 읽지 않습니다.

## 기존 링크 안내

다른 문서와 ADR이 아래 제목으로 링크하고 있어 남겨 둡니다. 본문은 각 새 위치에서 봅니다.

### #141 P5 직원 조치 저장 설계 초안

[저장 계약](contracts.md#141-p5-직원-조치-저장-설계-초안) · [승인 · 도입 기록](history.md#141-p5-직원-조치-저장-설계-초안)

### #149 P5 CakeShop 결제 저장 제안

[저장 계약](contracts.md#149-p5-cakeshop-결제-저장-제안) · [승인 · 도입 기록](history.md#149-p5-cakeshop-결제-저장-제안)

### #525 P1 사장님 403 원본 기록 (2026-11-04)

[저장 계약](contracts.md#525-p1-사장님-403-원본-기록-2026-11-04) · [승인 · 도입 기록](history.md#525-p1-사장님-403-원본-기록-2026-11-04)

### #602 P5 주문자 연락처 (2026-11-06)

[저장 계약](contracts.md#602-p5-주문자-연락처-2026-11-06) · [승인 · 도입 기록](history.md#602-p5-주문자-연락처-2026-11-06)
