# ERD 승인·변경 기록

[← ERD 안내](README.md) · [저장 계약](contracts.md) · [관계 그림](relationships.md)

`ea5294e9ece7feb1e1f454fd00c103372a1aecdb`의 ERD README에서 승인 경위와 당시 구현·검증 기록을 옮겼습니다.
아래의 "현재", "이번 PR", "후속", Notion 관련 표현은 각 기록 작성 시점의 설명입니다. 현재의 구현·운영·Notion 상태를 확인한 결과가 아닙니다.
현재 작업 절차와 합의 기록 위치는 [AGENTS.md](../../AGENTS.md)를 따릅니다. 기존 계약과 담당자 승인 요건은 유지합니다.
기존 README에서 사용하던 링크와 명시된 날짜·기준 커밋은 그대로 보존했습니다.
본문의 표 위치·절 번호 표현은 이동 전 README를 기준으로 한 기록입니다.

## 목차

- [최초 생성과 문서 관리 안내](#최초-생성과-문서-관리-안내)
- [P1 identity 기록](#p1-identity-기록)
- [P5 payment 기록](#p5-payment-기록)
- [P6 settlement 기록](#p6-settlement-기록)
- [P3 investigation 기록](#p3-investigation-기록)
- [P4 investigation/report 기록](#p4-investigationreport-기록)

## 최초 생성과 문서 관리 안내

> **상태: 1차 MVP 반영** — Notion 팀페이지 / ERD(P1~P6 담당별 페이지) 기준 업무 테이블 14개를 Flyway로 생성했습니다 (Issue #56).

### 1차 생성 때 Notion 양식과 다르게 적용한 것

| 대상 | 양식 | migration | 이유 |
| --- | --- | --- | --- |
| P1 테이블 이름 | `merchant` · `platform_user` · `user_permission` | `merchants` · `platform_users` · `user_permissions` | 테이블 이름은 복수 |
| `user_permissions` PK | `(user_id, permission_code)` 복합 PK | `permission_id` 단일 PK + `UNIQUE(user_id, permission_code)` | PK는 `BIGINT` |
| `fds_rule_hits.applied_score` | 기본값 0 검토 | `DEFAULT 0` | 미적중이면 0 |
| `security_cases.evidence_version` | 초기값 1 검토 | `DEFAULT 1` | 양식 제안값 |

위 표는 최초 생성 당시 차이를 기록합니다. 현재 제공된 P1 ERD 본문에는 `permission_id` 단일 PK가 반영되어 있으나 아래 표에는 이전 복합 PK 표시가 남아 있습니다. 실제 구조는 SQL을 따릅니다.

### 이전 범위와 정본 안내

- 합의 정본(무엇을 만들지)은 Notion **팀페이지 / ERD**의 담당별 페이지(P1~P6)입니다
- 실제 DB의 기준(지금 무엇이 있는지)은 `backend/src/main/resources/db/migration/`의 SQL입니다. 이 문서는 소유 · 관계만 요약하고 컬럼은 복사하지 않습니다
- DB 원칙과 INSERT/UPDATE 예시는 Notion fds 정본 **04 DB 설계 및 데이터 매핑**에 있습니다 (구현 가이드)
- 2026-10-22 제공된 P1~P6 담당 ERD를 대조했습니다. [담당별 원문 링크와 차이](../README.md)를 함께 봅니다. 특히 P6의 픽업·정산 완료용 테이블·컬럼은 2차 범위이며 현재 SQL에 없습니다

### 이전 일반 절차

1. Notion 팀페이지 / ERD의 담당 페이지에서 먼저 합의·수정합니다. 페이지에 없는 테이블·컬럼은 추가하지 않습니다
2. 소유 담당이 새 Flyway 마이그레이션(`ALTER` 등)을 추가합니다. 병합된 파일은 고치지 않습니다 → [Flyway 작업 가이드 1절](../flyway/05-guide.md#1-테이블-추가-순서)
3. 테이블이 늘거나 관계가 바뀌면 같은 PR에서 이 문서의 2·3절을 갱신합니다

## P1 identity 기록

### #246 P1 CakeShop 시드 (2026-10-27)

기존 P1 `merchants`·`platform_users`·`user_permissions`에 [새 시드](../../backend/src/main/resources/db/migration/V20261027_221311__seed_cakeshop_merchant_and_owner.sql)로 `CAKESHOP`과 사장님 계정을 등록한다.
테이블·컬럼·타입·관계 변경은 없다. 수수료율 2%는 P6 확인 전 임시값이며, 키는 환경변수 이름만 저장한다. [결정과 실행 절차](../identity/246-cakeshop-provisioning.md)를 따른다.

### #129 P1 변경 계약 (2026-10-25)

사용자는 이번 작업에 한해 Notion 선행 반영 대신 저장소 ERD·ADR에 기록하고 구현하도록 승인했다. Notion 최신 내용은 확인하지 못했으며 동기화는 남아 있다. 소유는 모두 P1이고 다른 담당 테이블은 변경하지 않는다.

[저장 계약](contracts.md#129-p1-변경-계약-2026-10-25). 수행자 타입의 후속 변경은 [#517](#517-p1-시스템-수행자-2026-11-04)을 함께 봅니다.

### #517 P1 시스템 수행자 (2026-11-04)

사용자가 P1 Notion ERD 기록 완료와 구현 진행을 확인했다. [설계·검증](../identity/517-operator-suspension.md), [ADR-0078](../decisions/0078-operator-account-suspension.md)을 따른다.

[저장 계약](contracts.md#517-p1-시스템-수행자-2026-11-04).

### #127 P1 로그인 잠금 스키마 계약 (2026-10-25)

사용자가 고정 15분 잠금 정책과 아래 컬럼 이름을 승인했으며, 이번 작업은 저장소 ERD·ADR을 기준으로 구현하고 Notion은 후속 반영하도록 승인했다. Notion 연결 도구가 없고 Chrome 사용이 자동 승인 검토에서 거부되어 최신 Notion P1 ERD 확인·동기화는 미수행이다.

- 소유: P1 `platform_users`만 변경. 다른 담당 테이블·공개 Java 계약·FK·인덱스 변경 없음.
- `login_failure_count`: INT NOT NULL DEFAULT 0. 앱 저장 범위 0~5, 성공 시 0.
- `login_locked_until`: UTC DATETIME(6) NULL DEFAULT NULL. 다섯 번째 실패 시 현재 시각 +15분, 성공 시 NULL.
- 잠금 중 재시도로 값을 바꾸지 않는다. 만료 후 오답은 1회부터 다시 센다. `status`와 분리하며 비밀번호 변경·초기화·재활성화는 잠금을 해제하지 않는다.
- 기존 계정은 0·NULL로 보존하며 [새 migration](../../backend/src/main/resources/db/migration/V20261025_165712__add_login_attempt_lock.sql) 하나로 추가한다. 세부 정책·대안·검증 계획은 [#127 설계](../identity/127-login-attempt-design.md)와 [ADR-0029](../decisions/0029-login-attempt-lock.md)를 따른다.

### #147 P1 로그인 이력 계약 (2026-10-25)

사용자는 #147 상세 설계 초안을 승인하고 이번 작업은 저장소 ERD·ADR부터 반영해 구현한 뒤
Notion P1 ERD를 후속 반영하도록 승인했다. Notion 최신 내용 확인·반영은 미수행이다.
정확한 컬럼·NULL·제약·인덱스는 [승인한 스키마](../identity/147-login-history-design.md#2-p1-스키마-계약)를 따른다.
P1 `login_histories`만 추가하고 같은 P1 `platform_users.user_id`를 물리 FK로 참조한다.
기존 계정·관리자 변경 이력·다른 담당 테이블은 바꾸지 않는다.
이력은 #528 개정에 따라 1년 후 물리 삭제하며 금융·조사 기록의 삭제 금지와는 별개다. 목록은 최근 90일을 유지한다.
상세 정책·대안·영향은 [ADR-0033](../decisions/0033-login-history.md)에 기록한다.

### #219 P1 HMAC nonce 계약 (2026-10-27)

사용자는 P6를 제외한 HMAC 구현과 아래 신규 P1 테이블을 승인했다. 저장소 ERD·ADR에 먼저 반영하고 Notion은 후속 반영한다.

[저장 계약](contracts.md#219-p1-hmac-nonce-계약-2026-10-27).

### #131 PR A P1 상태 이름 전환 (2026-11-01)

P1 사용자 승인(#131 최신 실행 계획)으로 계정 `DISABLED → INACTIVE`, 신청 `EMAIL_PENDING → EMAIL_VERIFICATION_PENDING`을 데이터 초기화 없이 전환한다. [계정 migration](../../backend/src/main/resources/db/migration/V20261101_160837__rename_user_inactive_status.sql)은 기존 정지 행만 변환하고 ACTIVE 시드와 감사 JSON·유형은 유지한다. [신청 migration](../../backend/src/main/resources/db/migration/V20261101_160844__rename_application_email_verification_status.sql)은 CHECK와 `pending_email`·`pending_business_number`·`pending_login_id`의 조건을 함께 변경한다. 기존 UNIQUE 3개는 유지하며 임시로 양쪽 이름을 예약 대상으로 포함한 뒤 데이터 UPDATE와 최종 조건 축소를 수행한다. 컬럼 타입·소유(P1)·FK·보관 정책은 유지한다. 메일 발송 PENDING은 별도 개념이다.

구버전 앱·예약 작업 전부 종료 및 백엔드·프론트 점검 배포가 필요하다. [ADR-0060](../decisions/0060-identity-status-names.md)과 [복구 절차](../identity/131-status-transition.md)를 따른다. Notion 동기화와 실제 공용/운영 DB 적용은 미수행이다. 시연 계정 정지는 운영 시작 코드 PR B에서 처리하며 이 migration에 포함하지 않는다.

### #315 PR 2 P1 이용 신청 계약 (2026-10-29)

P1 담당자가 설계·구현 진행을 승인했다. [상세 컬럼·FK·보관 계약](../identity/315-application-design.md#저장-계약-모두-p1), [ADR-0043](../decisions/0043-application-storage.md)을 따른다. Notion 선행 대조·동기화는 미수행이다.
`merchant_applications`·`application_tokens`·`application_mail_deliveries`·`application_rate_limits`를 추가하고 `merchants.business_registration_number VARCHAR(10) NULL`을 추가한다. 모두 P1 소유, 다른 담당 FK 없음. 연락처는 신청에만 보관한다. 승인·반려와 첫 설정 토큰 컬럼은 PR 2에 준비하고 실제 동작은 PR 3에서 연결한다. 개인정보 자동 삭제는 PR 4이며 이번 PR에서 완료됐다고 표현하지 않는다.

### #315 PR 3 신청자 로그인 아이디 선택 (2026-10-29)

P1 담당자가 이 PR에서 아이디를 신청자가 선택하도록 변경하는 것을 승인했다. `merchant_applications.requested_login_id VARCHAR(30) COLLATE utf8mb4_bin NULL`을 새 migration으로 추가한다. 신규 신청 API는 필수·영문 소문자/숫자/밑줄 3~30자, trim만 적용한다. 기존 행은 NULL로 보존하고 임의로 채우지 않는다.
진행 중 신청의 `pending_login_id` SHA-256 생성 컬럼과 UNIQUE로 같은 아이디 예약을 막는다. P1 계정 생성·신청·승인은 같은 아이디 잠금을 공유하며, 수동 계정 생성도 예약을 존중한다. 승인 시 자기 신청의 예약만 사용할 수 있다. 반려/삭제 후 예약을 해제하며 승인 후에는 platform_users의 기존 UNIQUE가 아이디를 보호한다.
기존 아이디 없는 신청은 임의 발급하지 않고 반려·새 양식 재신청으로 안내한다. 비밀번호는 계속 승인 후 본인이 설정한다. 다른 소유 테이블·공개 Java 계약 변경 없음. 상세는 [PR 3 설계](../identity/315-approval-design.md).

### #354 PR A 쇼핑몰 주소 (2026-10-30)

P1 사용자가 주소만 수집하는 설계·구현 진행을 승인했다. `merchant_applications.shop_url VARCHAR(2048) NULL`을 새 migration으로 추가한다. 신규 신청은 HTTP(S) 절대 URL 필수이며 기존 행은 NULL로 보존한다. 신청 검토 자료로만 보관하고 merchants·webhook으로 복사하지 않는다. 미확인 7일·반려 30일 정리와 승인 신청 보존 정책을 그대로 따른다. Notion 선행 대조·동기화는 미수행이다. 상세는 [PR A 설계](../identity/354-pr1-application-design.md)를 따른다.

### #354 PR B 키·연동 관리 (2026-10-31)

P1 사용자가 신규 테이블 2개를 포함한 설계·구현을 승인했다. `merchant_integrations`는 가맹점별 현재 키 암호문·IV·마스터 키 ID·키 버전·연동 상태·발급/연결/갱신 시각을 저장한다. `merchant_key_histories`는 발급·재발급·폐기·시스템 이전의 실행 계정·버전·사유·시각을 보존하며 원문·암호문은 넣지 않는다. 모두 P1 소유이며 merchants·platform_users로만 물리 FK를 건다.

컬럼·타입·NULL·상태 조합은 [승인 설계](../identity/354-pr-b-key-design.md#3-p1-확인을-받을-저장-구조-제안), 실제 제약은 [현재 설정 SQL](../../backend/src/main/resources/db/migration/V20261031_151333__create_merchant_integrations.sql)·[키 이력 SQL](../../backend/src/main/resources/db/migration/V20261031_151342__create_merchant_key_histories.sql)을 따른다. [ADR-0052](../decisions/0052-merchant-key-management.md)에 저장·복구·이전·B/C 경계를 기록한다. 기존 secret_reference는 이전 대상 식별용으로 유지하되 런타임 키 fallback에 쓰지 않는다. Notion 선행 대조·동기화와 운영 DB 적용은 미수행이다.

### #525 P1 사장님 403 원본 기록 (2026-11-04)

P1 사용자가 #525 초안의 저장 구조와 저장소 ERD·ADR 선행 구현을 승인했다. Notion 반영은 후속이다.

[저장 계약](contracts.md#525-p1-사장님-403-원본-기록-2026-11-04).

### #604 P1 보안 직원 접근 기록 (2026-11-06)

사용자가 저장소 ERD · [ADR-0082](../decisions/0082-insider-access-security.md) 선행 구현을 승인했다. Notion 반영은 후속이다.

[저장 계약](contracts.md#604-p1-보안-직원-접근-기록-2026-11-06).

### #647 P1 보안 직원 계정 보안 정지 (2026-11-07)

사용자가 저장소 ERD · [ADR-0084](../decisions/0084-staff-account-security-suspension.md) 선행 구현을 승인했다(ADR-0082 2절 표의 P1 소유 표). Notion 반영은 후속이다.

[저장 계약](contracts.md#647-p1-보안-직원-계정-보안-정지-2026-11-07).

## P5 payment 기록

### #141 P5 직원 조치 저장 설계 초안

2026-10-25 사용자는 아래 상세 스키마를 저장소 ERD·ADR 기준으로 구현하고 Notion은 후속 반영하도록 승인했다. 4번 단계에서 migration·Entity를 추가했다. Notion 동기화는 아직 하지 않았다. [ADR-0027](../decisions/0027-payment-actions.md), [처리 설계](../payment/141-payment-actions.md)를 함께 본다.

현재 SQL의 payments·action_handoffs·captures는 변경하지 않는 안이다. P3 결과의 targetOperationId는 actionType=CAPTURE일 때 capture_id, VOID일 때 action_id로 해석한다. VOID 식별자 추가 공유·확인은 남아 있다.

[저장 계약](contracts.md#141-p5-직원-조치-저장-설계-초안).

### #149 P5 CakeShop 결제 저장 제안

**저장 구조 방향 사용자 승인·저장 기반 구현.** 기준 dev 84698bb, 2026-10-25. 사용자는 payment_orders·payment_requests 신규와 payments.status_changed_at·decline_code 추가 방향에 동의했다. 작업 브랜치에 migration·Entity·Repository를 추가했다. 운영 DB 적용·Notion 동기화·HTTP 연동은 미수행이다. [처리 설계](../payment/149-cakeshop-payment-api.md), [ADR-0030](../decisions/0030-cakeshop-payment-attempts.md)을 함께 확인한다.

추가 migration: `V20261025_210954` (결제 메타데이터·주문 조회 인덱스), `V20261025_210955` (주문), `V20261025_210956` (시도). 같은 PR에 포함하며 기존 migration은 변경하지 않는다. 아래 입력 일치·동시 요청 제어 중 Entity/DB 제약 외 서비스 정책은 후속 구현이다.

모두 P5 소유로 제안한다. 새 결제 시도마다 기존 payments 한 행을 만들고 P2/P3/P6는 그 paymentId를 계속 사용한다. PK는 BIGINT AUTO_INCREMENT, 시각은 UTC DATETIME(6), 금액 BIGINT, 상태 VARCHAR다. 참조되는 행은 물리 삭제하지 않는다.

현재 SQL은 변경하지 않았다. 물리 스키마 적용 전 타입·collation·제약·순번 상한·기존 데이터 호환 및 후속 migration 배포 순서를 구현 단계에서 검증한다.

[저장 계약](contracts.md#149-p5-cakeshop-결제-저장-제안).

### #190·#273 P5 FDS 복구 저장 기반

#273 구현 준비의 [저장 필드·제약 검토](../payment/273-fds-recovery-storage.md)에 담당 Notion ERD 조회 결과와 상태별 CHECK·키·인덱스 기준을 정리했다. 2026-10-28 최초 조회 시 담당 ERD에는 복구 테이블이 없었으며, 이후 사용자 요청으로 필드·제약과 P2 분석 사유 보존 원칙을 담당 ERD에 반영했다. 문서 반영과 DB 구현은 구분한다.

2026-10-28, `dev ceb4544` 기준으로 사용자가 단일 복구 테이블 설계안을 선택하고 문서화를 요청했다. 상세 필드·상태·제약은 [#190 저장·API 계약](../payment/190-fds-recovery-contract.md)에 기록한다.

- P5 소유 신규 `payment_fds_recoveries` 제안: 결제당 원본 ERROR·오류 보고·P2 분석·직원 재탐지 작업 1행.
- 기존 `payments`는 결제 정본, `payment_requests`는 현재 적용 평가·처리 단계다. 기존 ERROR는 복구 행에 보존하고 제한된 새 결과 적용 전이를 추가할 계획이다.
- 같은 P5 payments 참조에만 물리 FK를 사용하고, P2 평가·분석 ID와 P1 직원 ID는 논리 참조한다. 원본/작업 키 불변·물리 삭제 금지·중복 방지는 상세 계약을 따른다.
- #273 작업 브랜치에 신규 migration·Entity·Repository를 추가했다. 사용자 요청에 따른 Notion 담당 ERD 필드·제약 반영을 마쳤으며 보고·분석 리스너와 상세 GET 응답 확장까지 연결했다. 운영 DB 적용은 후속이다. 이번 사용자 설계 승인을 이미 운영 적용·다른 담당 상세 검토까지 마친 것으로 해석하지 않는다.

### #381 고객 전액 취소 저장 기반

#381은 설계 PR #385 병합 후 사용자의 다음 단계 진행 요청에 따라 [저장 명세](../payment/381-cancellation-storage.md)를 기준으로 P5 두 테이블의 migration·Entity·Repository를 구현한다. 최초 범위에 삭제·키 만료는 두지 않는다. Notion 담당 페이지 동기화·장기 보존 정책·운영 DB 적용은 미완료이며 API·실행 연결은 후속이다.

### #602 P5 주문자 연락처 (2026-11-06)

사용자가 저장소 ERD · [ADR-0082](../decisions/0082-insider-access-security.md) 선행 구현을 승인했다. Notion 반영은 후속이다.

[저장 계약](contracts.md#602-p5-주문자-연락처-2026-11-06).

## P6 settlement 기록

### 픽업·정산 시각 확장

P6 사용자 승인 확장(#226·#227·#333, PR 리뷰 대상): `order_fulfillments`와
`order_fulfillment_items`를 추가하고 `settlements`에 nullable 정산 시각 3개를 추가한다.
상품은 같은 P6 주문만 물리 FK로 참조하며 다른 모듈과는 논리 참조를 유지한다.
상세 컬럼·제약·가맹점별 주문번호 범위는 [픽업 ERD](../settlement/pickup-schema.md)를 따른다.
실제 Notion 최신 동기화·운영 DB 적용 완료를 뜻하지 않는다.

### P6 최초 잔액·일회성 지출 (#229, 2026-10-30)

사용자 구현 승인에 따라 P6 소유 `cash_flow_settings`와 `planned_expenses`를 신규 migration으로 추가한다.
가맹점별 최초 잔액·기준일은 불변이고, 실제 지출 완료 시각은 nullable `paid_at` UTC DATETIME(6)로 보관한다.
가맹점은 논리 참조이며 다른 담당 FK를 추가하지 않는다. 금액·PK·인덱스·API·미검증 범위는 [P6 설계/ERD](../settlement/balance-expenses-api.md)를 따른다.
Notion 최신 동기화·팀 전체 ERD 합의·개인 DB 적용 완료를 뜻하지 않는다.

### 정기 지출 확장

P6 정기 지출 확장: [규칙·회차 완료 기록 ERD](../settlement/recurring-expenses-api.md#erd).
P6 소유 신규 테이블만 추가하며, 다른 모듈 테이블과 물리 FK를 만들지 않는다.

### #230 조회 인덱스

P6 #230 조회 인덱스: [20261102_112639](../../backend/src/main/resources/db/migration/V20261102_112639__index_settlement_cash_flow_queries.sql)은 settlements의 가맹점·상태·완료 시각/예정 시각 복합 인덱스 2개만 추가한다. 테이블·컬럼·데이터 변경은 없다. 상세 목적은 [예측 API](../settlement/cash-flow-forecast-api.md#조회-인덱스)를 따른다.

### #465 영업일 D+3

P6 #465: [영업일 D+3 정책·ERD](../settlement/d3-policy-and-erd.md). 이번 PR은 P6 달력 테이블 추가와 미완료 일정 전환이며, 지급/환불 테이블은 후속 제안이다. Notion·운영 DB 적용 완료를 뜻하지 않는다.

### #474·#478·#488·#493 지급·환불 단계

P6 2단계 #474: [가맹점 지급 원장·상세 ERD](../settlement/daily-payouts.md). P6 소유 테이블만 추가하며, 기존 완료 원본을 유지한 이력 전환을 포함한다.

P6 3단계 #478: [환불 수신 조정 원장 ERD](../settlement/refund-receiving.md#erd-settlement_refund_adjustments). 전액 환불의 원 수수료·순정산액을 PENDING으로 보존한다. 실제 제외·회수·이월과 운영 활성화는 후속이다.

P6 4단계 #488: [가맹점 공통 잠금 ERD·미지급 제외](../settlement/unpaid-refund-exclusion.md). 신규 P6 잠금 테이블만 추가하며 이미 지급한 환불의 회수·이월은 후속이다.

P6 5단계 #493: [회수 배분 원장 ERD](../settlement/refund-recovery.md#erd-payout_refund_allocations). payout_refund_allocations만 추가하고 별도 merchant_settlement_balances 캐시는 생성하지 않는다. 개별 remaining_amount를 잔액 기준으로 유지한다.

## P3 investigation 기록

### #608 P3 FDS 사건 자동 배정과 담당 변경 (2026-11-06)

사용자가 저장소 ERD · [ADR-0082](../decisions/0082-insider-access-security.md) 2절 선행 구현을 승인했다. Notion 반영은 후속이다.

[저장 계약](contracts.md#608-p3-fds-사건-자동-배정과-담당-변경-2026-11-06).

### #670 P3 내부자 사건 · 관리자 판정 · 징계 사건 정지 해제 (2026-11-08)

사용자가 저장소 ERD · [ADR-0082](../decisions/0082-insider-access-security.md) 2절 선행 구현을 승인했다(조각 8). Notion 반영은 후속이다.

[저장 계약](contracts.md#670-p3-내부자-사건--관리자-판정--징계-사건-정지-해제-2026-11-08).

## P4 investigation/report 기록

### #675 P4 내부자 사건 해제 검토 보고서 (2026-11-08)

사용자가 저장소 ERD · [ADR-0082](../decisions/0082-insider-access-security.md) 2절 선행 구현을 승인했다(조각 9). Notion 반영은 후속이다.

[저장 계약](contracts.md#675-p4-내부자-사건-해제-검토-보고서-2026-11-08).
