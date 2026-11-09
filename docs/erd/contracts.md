# ERD 저장 계약

[← ERD 안내](README.md) · [관계 그림](relationships.md) · [승인·변경 기록](history.md)

`ea5294e9ece7feb1e1f454fd00c103372a1aecdb`의 ERD README에서 옮긴 계약을 `f7db9cd307067dcaa6a71673f0ad02b3115a8754`의 SQL·관련 코드와 대조했습니다. 범위는 아래 P1~P5 절에 기술된 컬럼·제약·주요 저장 동작이며, 전체 67개 테이블의 모든 업무 경로를 검증한 문서는 아닙니다.

이슈별 제목·날짜는 기존 링크와 도입 맥락을 유지합니다. 특히 제목의 “초안”·“제안”은 작성 당시 이름이며, 본문에서 확인한 현재 구현과 설계 채택 여부를 구분합니다. ADR 상태나 담당자 승인 요건은 바꾸지 않습니다. 당시 승인·검증·Notion 기록은 각 절의 이력 링크에 보존합니다.

현재 컬럼·타입·제약은 CREATE와 이후 ALTER를 합쳐 확인합니다. SQL 제약과 서비스가 적용하는 업무 규칙을 구분하며, 이번 대조는 DB 적용·테스트 실행·운영 검증 결과가 아닙니다. P6의 상세 계약은 [README 담당별 문서](README.md#담당별-상세-문서)를 따릅니다.

## 목차

- [P1 identity](#p1-identity)
- [P2 fds](#p2-fds)
- [P3 investigation](#p3-investigation)
- [P4 investigation/report](#p4-investigationreport)
- [P5 payment](#p5-payment)

## P1 identity

### #129 P1 변경 계약 (2026-10-25)

[승인·도입 기록](history.md#129-p1-변경-계약-2026-10-25). 아래는 최초 계약이며, 수행자 타입과 NULL 허용의 후속 변경은 [#517](#517-p1-시스템-수행자-2026-11-04)을 함께 봅니다.

SQL 근거: [비밀번호 변경 요구](../../backend/src/main/resources/db/migration/V20261025_121650__add_password_change_required.sql) · [역할 CHECK](../../backend/src/main/resources/db/migration/V20261025_121656__add_user_role_merchant_check.sql) · [감사 이력 생성](../../backend/src/main/resources/db/migration/V20261025_121657__create_admin_change_histories.sql).

- `platform_users.password_change_required`: BOOLEAN NOT NULL DEFAULT FALSE. 신규·초기화 계정 TRUE, 본인 비밀번호 변경 후 FALSE.
- `platform_users` CHECK: OPERATOR는 merchant_id 필수, ADMIN·SECURITY_STAFF는 NULL.
- 신규 `admin_change_histories`: history_id BIGINT AUTO_INCREMENT PK, actor_user_id BIGINT NOT NULL FK platform_users, target_user_id BIGINT NULL FK platform_users, target_merchant_id BIGINT NULL FK merchants, change_type VARCHAR(40), before_data·after_data JSON NULL, created_at DATETIME(6) UTC. 대상 계정·가맹점 중 하나만 지정한다. INSERT 전용이며 비밀번호·해시는 저장하지 않는다.
- 관계: platform_users → admin_change_histories(수행 관리자·대상 계정), merchants → admin_change_histories(대상 가맹점). 모두 P1 물리 FK이며 삭제 연쇄는 없다.
- 상세 계약: [#129 설계](../identity/129-admin-account-design.md), [ADR-0024](../decisions/0024-admin-account-management.md).

### #517 P1 시스템 수행자 (2026-11-04)

[승인·도입 기록](history.md#517-p1-시스템-수행자-2026-11-04).

[후속 migration](../../backend/src/main/resources/db/migration/V20261104_134638__add_admin_change_history_actor_type.sql)은 `admin_change_histories.actor_type VARCHAR(20) NOT NULL DEFAULT 'STAFF'`를 추가해 기존 행을 STAFF로 채우고 `actor_user_id BIGINT NULL`로 전환한다. 기존 FK는 유지한다. 현재 스키마를 확인할 때 #129의 최초 NOT NULL 설명만 적용하지 않는다.
`ck_admin_change_histories_actor`는 STAFF+ID 있음 또는 SYSTEM+ID 없음만 허용한다. 기존 대상 CHECK·FK·인덱스·JSON·보존 정책은 유지한다.
기존 계정·가맹점·신청 경로는 STAFF를 명시하고, FDS 정지는 SYSTEM/NULL과 `USER_SUSPENDED_BY_FDS`로 구분한다. 운영 DB 적용 완료를 뜻하지 않는다.

### #219 P1 HMAC nonce 계약 (2026-10-27)

[승인·도입 기록](history.md#219-p1-hmac-nonce-계약-2026-10-27).

현재 저장 근거: [생성 SQL](../../backend/src/main/resources/db/migration/V20261027_144924__create_merchant_request_nonces.sql), [인증 순서·만료 시각](../../backend/src/main/java/com/donworry/identity/service/MerchantHmacService.java), [독립 트랜잭션 저장](../../backend/src/main/java/com/donworry/identity/service/DatabaseMerchantNonceStore.java).

| 컬럼 | 타입·조건 | 의미 |
| --- | --- | --- |
| request_nonce_id | BIGINT AUTO_INCREMENT PK | 내부 기록 ID |
| merchant_id | BIGINT NOT NULL FK merchants | 인증한 P1 가맹점 |
| nonce | UUID NOT NULL | HTTP 전송별 UUID v4 |
| expires_at | DATETIME(6) NOT NULL | 서명 시각+301초, UTC |

`UNIQUE(merchant_id, nonce)`로 동시 재전송을 한 번만 소비한다. `INDEX(expires_at, request_nonce_id)`로 만료 행만 배치 삭제한다.
가맹점 FK는 같은 P1 소유이고 삭제 연쇄는 없다. 원문 키·서명·요청 본문을 저장하지 않는다.
서명 확인 후 업무 실행 전에 독립 커밋하므로 업무 롤백·서버 재시작으로 nonce가 복구되지 않는다. 만료 행은 금융·조사 이력이 아닌 단기 보안 기록으로 물리 삭제한다.
`merchants.secret_reference`의 Entity 매핑은 #219 당시 변경이다. 현재 키 조회는 [MerchantSecretService](../../backend/src/main/java/com/donworry/identity/service/MerchantSecretService.java)가 `merchant_integrations`의 암호문을 사용하며, `secret_reference`를 인증 키 fallback으로 쓰지 않는다. 후속 키 관리 계약은 [#354 PR B](../identity/354-pr-b-key-design.md)를 본다.
세부 계약과 검증은 [#219 설계](../identity/219-merchant-hmac.md)와 [ADR-0037](../decisions/0037-merchant-hmac-authentication.md)을 따른다.

### #525 P1 사장님 403 원본 기록 (2026-11-04)

[승인·도입 기록](history.md#525-p1-사장님-403-원본-기록-2026-11-04).

SQL 근거: [생성](../../backend/src/main/resources/db/migration/V20261104_153346__create_operator_access_denials.sql)과 아래 #528 인덱스 추가. IP 보관 구현은 [정리 서비스](../../backend/src/main/java/com/donworry/identity/service/OperatorAccessDenialRetentionService.java)를 대조했다.

신규 `operator_access_denials`는 P1 소유이며 사용자·가맹점 모두 같은 P1 FK로 연결하고 삭제 연쇄는 두지 않는다.

| 컬럼 | 타입·NULL | 의미 |
| --- | --- | --- |
| denial_id | BIGINT AUTO_INCREMENT PK | OperatorAccessDenied.eventId |
| user_id | BIGINT NOT NULL FK platform_users | 사장님 계정 |
| merchant_id | BIGINT NOT NULL FK merchants | 세션이 아닌 DB 계정의 가맹점 |
| http_method | VARCHAR(10) NOT NULL | 거절된 요청 HTTP token의 앞 최대 10자, 원본·이벤트 동일 |
| path | VARCHAR(512) NOT NULL | servletPath, 쿼리·컨텍스트 경로 제외, 최대 512자 |
| denied_type | VARCHAR(30) NOT NULL | OUT_OF_ROLE / PERMISSION_MISSING |
| client_ip | VARCHAR(45) NULL | 공통 ClientIpResolver 결과 |
| occurred_at | DATETIME(6) NOT NULL | UTC, 이벤트와 동일한 마이크로초 |

`idx_operator_access_denials_user_time(user_id, occurred_at)`와 거절 유형 CHECK를 둔다.
#528은 [새 인덱스 migration](../../backend/src/main/resources/db/migration/V20261104_195051__index_operator_access_denial_retention.sql)으로 `idx_operator_access_denials_retention(client_ip, occurred_at)`를 추가한다. 사용자 조건 없이 IP가 남은 만료 행만 찾는 IP 정리 배치를 지원한다. 기존 테이블 생성 migration·컬럼·행 데이터는 변경하지 않는다.
login_id는 복제 저장하지 않고 같은 트랜잭션에서 읽은 DB 계정 값으로 이벤트에만 전달한다.
`client_ip`는 발생 후 1년 보관하고 NULL 처리한다. 원본 행은 조사 근거이므로 IP 정리 작업에서 삭제하지 않는다.
행 보관 기간은 조사 기록 정책으로 따로 정하며, 기존 90일 행 삭제 제안은 사용자 결정으로 대체했다.

제8조 적용 범위·최소 2년 보관 대상 예외는 ADR-0080을 따른다. #525의 수집·발행에 더해 #528에서 원본 IP 정리를 구현한다. [실행 근거·미검증](../identity/528-access-record-retention.md)을 따른다.
[ADR-0080](../decisions/0080-operator-access-denials.md), [구현·검증](../identity/525-operator-access-denials.md)을 따른다.

### #604 P1 보안 직원 접근 기록 (2026-11-06)

[승인·도입 기록](history.md#604-p1-보안-직원-접근-기록-2026-11-06).

근거: [생성 SQL](../../backend/src/main/resources/db/migration/V20261106_215350__create_staff_access_events.sql), [허용·거부 기록의 트랜잭션](../../backend/src/main/java/com/donworry/identity/service/StaffAccessService.java).

신규 `staff_access_events`는 P1 소유다. 직원 · 가맹점은 같은 P1 FK로 연결하고, `case_id`(P3) · `payment_id`(P5)는 논리 참조다.
이름 · 전화 같은 원문 칸은 없다. 자유 글 칸을 두지 않는다.

| 컬럼 | 타입·NULL | 의미 |
| --- | --- | --- |
| event_id | BIGINT AUTO_INCREMENT PK | StaffAccessRecorded.eventId |
| staff_id | BIGINT NOT NULL FK platform_users | 로그인 세션의 직원 (요청 값이 아님) |
| actor_role | VARCHAR(20) NOT NULL | SECURITY_STAFF / ADMIN (CHECK) |
| access_type | VARCHAR(40) NOT NULL | StaffAccessType. CHECK 없음 — 값은 기록을 부르는 조각에서 더한다 |
| result | VARCHAR(10) NOT NULL | ALLOWED / DENIED |
| deny_reason | VARCHAR(40) NULL | DENIED일 때만 필수 (CHECK) |
| case_id | BIGINT NULL | P3 사건 논리 참조 |
| merchant_id | BIGINT NULL FK merchants | 대상 가맹점 |
| payment_id | BIGINT NULL | P5 결제 논리 참조 |
| customer_ref | VARCHAR(100) NULL | 숫자 고객 번호만 (`가맹점ID:번호` 포함). 검색어 원문은 넣지 않는다 |
| field_type | VARCHAR(30) NULL | StaffAccessFieldType 코드 (예: ORDERER_PHONE) |
| item_count | INT NULL | 목록 · 내보내기 건수, 0 이상 (CHECK) |
| login_reference | UUID NULL | login_histories.login_reference와 같은 값 (FK 없음). IP는 복사하지 않는다 |
| occurred_at | DATETIME(6) NOT NULL | UTC, 이벤트와 동일한 마이크로초 |

인덱스는 `idx_staff_access_events_staff_time(staff_id, occurred_at)` · `idx_staff_access_events_staff_result_time(staff_id, result, occurred_at)`.
허용 기록은 호출 쪽과 같은 트랜잭션, 거부 기록은 독립 트랜잭션으로 저장한다 (ADR-0082 4절).
행은 지우거나 고치지 않는다. 보관 기간 정리(법정 접속기록 1~2년)는 후속이다.

### #647 P1 보안 직원 계정 보안 정지 (2026-11-07)

[승인·도입 기록](history.md#647-p1-보안-직원-계정-보안-정지-2026-11-07).

근거: [생성 SQL](../../backend/src/main/resources/db/migration/V20261107_233256__create_staff_account_locks.sql), [정지·해제 서비스](../../backend/src/main/java/com/donworry/identity/service/StaffAccountLockService.java).

정지하면 `platform_users.status`를 `SUSPENDED`로 바꾸고(이미 `INACTIVE`면 유지), 이 표에 한 줄을 남긴다. 판정으로 풀 때 같은 줄에 해제를 적는다.
`platform_users.status`는 CHECK 없는 `VARCHAR(20)`이라 `SUSPENDED` 추가에 migration이 없다. 원문(이름 · 전화) 칸은 없다.

| 컬럼 | 타입·NULL | 의미 |
| --- | --- | --- |
| lock_id | BIGINT AUTO_INCREMENT PK | 정지 번호 |
| staff_id | BIGINT NOT NULL FK platform_users | 정지된 보안 직원 |
| reason_code | VARCHAR(40) NOT NULL | `StaffLockReason` (예: INSIDER_EXPORT_BLOCKED). CHECK 없음 — 조각마다 값을 더한다 |
| source_alert_id | BIGINT NULL | 근거 경보, P2 `insider_alerts` 논리 참조 |
| locked_by_type | VARCHAR(20) NOT NULL | SYSTEM(자동 정지) / STAFF(관리자 정지). `admin_change_histories.actor_type`과 같은 방식 (CHECK) |
| locked_by | BIGINT NULL FK platform_users | 정지한 관리자. SYSTEM이면 NULL (CHECK) |
| status | VARCHAR(20) NOT NULL | OPEN / RELEASED (CHECK) |
| locked_at | DATETIME(6) NOT NULL | UTC |
| released_at | DATETIME(6) NULL | UTC. RELEASED일 때만 필수 (CHECK) |
| released_by | BIGINT NULL FK platform_users | 해제한 관리자. RELEASED일 때만 필수 (CHECK) |
| release_case_id | BIGINT NULL | 해제 근거 사건, P3 내부자 사건 논리 참조 |
| release_decision | VARCHAR(40) NULL | P3 판정 코드. identity는 해석하지 않는다 |
| release_outcome | VARCHAR(20) NULL | RESTORE / DEACTIVATE. RELEASED의 결과 값(CHECK와 Entity 규칙). 계정 상태 적용 조건은 아래 설명 참조 |
| open_key | BIGINT 생성 칼럼 | OPEN일 때만 staff_id. UNIQUE로 사람당 열린 정지 1개 (엔티티에 매핑하지 않음) |

인덱스는 `idx_staff_account_locks_staff_time(staff_id, locked_at)`.
정지는 독립 트랜잭션으로 커밋한 뒤 세션을 만료한다(부른 쪽 롤백과 무관). 해제는 부른 쪽 판정 트랜잭션에 참여한다 (ADR-0084).
해제 당시 계정이 `SUSPENDED`인 경우에만 RESTORE는 ACTIVE, DEACTIVATE는 INACTIVE로 바꾼다. 이미 INACTIVE인 계정은 RESTORE 결과라도 되살리지 않는다. 결과 코드만으로 계정이 반드시 ACTIVE가 된다고 해석하지 않는다.
행은 지우지 않는다(조사 기록).

## P2 fds

### 룰 변경 요청과 차단 해제 기록

근거: [룰 관리 생성](../../backend/src/main/resources/db/migration/V20261028_155511__create_fds_rule_management_tables.sql) · [rule_code 길이 ALTER](../../backend/src/main/resources/db/migration/V20261104_152441__create_operator_access_detection_tables.sql) · [차단 해제 컬럼 ALTER](../../backend/src/main/resources/db/migration/V20261030_114028__add_fds_blocklist_deactivation.sql) · [차단 상태 전이](../../backend/src/main/java/com/donworry/fds/entity/FdsBlocklistEntry.java).

현재 `fds_rule_change_requests.rule_code`는 `VARCHAR(100) NOT NULL`이다. 최초 생성의 VARCHAR(10)만 보고 길이를 정하지 않는다. 정책과의 참조는 FK 없는 논리 참조다.

`fds_rule_change_requests`는 요청·처리 사용자 ID와 당시 표시 이름, 룰 끄기 요청 사유를 함께 보존한다. 사용자 이름은 Identity 소유 `platform_users`를 직접 조인하지 않고 FDS 저장 스냅샷으로 조회하며, 요청 IP는 저장하지 않는다.

`fds_blocklist_entries`는 차단 등록 사유와 함께 해제 사유(`deactivation_reason`)·해제 시각(`deactivated_at`)을 보존한다. 재등록하면 기존 해제 이력은 현재 활성 차단 상태와 혼동되지 않도록 NULL로 되돌린다.

## P3 investigation

### #608 P3 FDS 사건 자동 배정과 담당 변경 (2026-11-06)

[승인·도입 기록](history.md#608-p3-fds-사건-자동-배정과-담당-변경-2026-11-06).

SQL 근거: [사건 담당 ALTER·배정 잠금](../../backend/src/main/resources/db/migration/V20261106_225206__add_security_case_assignment.sql) · [담당 변경 요청](../../backend/src/main/resources/db/migration/V20261106_225208__create_case_assignment_requests.sql) · [원문 열람 검토](../../backend/src/main/resources/db/migration/V20261107_024052__create_raw_view_reviews.sql).

세 가지 모두 P3 소유다. 직원 · ADMIN ID는 P1 `platform_users`의 논리 참조(FK 없음)이고, 요청의 `case_id`만 같은 P3 FK다.

`security_cases` 추가 칸

| 컬럼 | 타입·NULL | 의미 |
| --- | --- | --- |
| assignee_staff_id | BIGINT NULL | 담당 보안 직원. NULL = 미배정 (직원 없을 때 만든 사건 · 도입 전 사건 → ADMIN [미배정 배정]) |
| assigned_at | DATETIME(6) NULL | 마지막 배정 · 변경 시각 (UTC) |

인덱스 (assignee_staff_id, status) — 직원별 미종결 담당 수.

`case_assignment_locks` — 배정을 한 줄로 세우는 잠금 행 1개(`FDS_CASE`). 배정 · 담당 변경 트랜잭션이 첫 문장에서 `SELECT … FOR UPDATE`로 잡는다.

`case_assignment_requests` — 담당 직원의 담당 변경 요청. 물리 삭제하지 않는다.

| 컬럼 | 타입·NULL | 의미 |
| --- | --- | --- |
| request_id | BIGINT AUTO_INCREMENT PK | |
| case_id | BIGINT NOT NULL FK security_cases | |
| requested_by_staff_id | BIGINT NOT NULL | 요청한 담당 직원 (로그인 세션) |
| reason | VARCHAR(30) NOT NULL | CONFLICT_OF_INTEREST(이해충돌) / WORKLOAD(업무 과다) / PERSONAL(개인 사유) / OTHER(기타). CHECK 없음. 현재 Enum 값은 아래 설명 참조 |
| memo | VARCHAR(200) NULL | 업무 메모, OTHER면 필수 (서비스). 개인정보 금지 안내 |
| status | VARCHAR(20) NOT NULL | PENDING / APPROVED / REJECTED (CHECK) |
| requested_at · reviewed_at | DATETIME(6) NOT NULL · NULL | UTC. 요청 시각 필수, 처리 시각은 처리 전 NULL |
| reviewed_by_admin_id · reviewed_by_admin_name | BIGINT · VARCHAR(100) NULL | 처리 ADMIN과 그때의 표시 이름 |
| review_memo | VARCHAR(200) NULL | 거절이면 필수 (서비스) |
| new_assignee_staff_id | BIGINT NULL | 승인일 때 새 담당 |
| pending_case_id | 생성 칼럼 UNIQUE | PENDING일 때만 case_id — 사건당 대기 1건 |

[AssignmentChangeReason](../../backend/src/main/java/com/donworry/investigation/entity/AssignmentChangeReason.java)의 현재 값은 위 네 가지다. 원문의 “조각 8에서 ADMIN 전용 사유 추가”는 예정 설명이며 현재 Enum에 추가됐다는 뜻이 아니다. 여기서 새 사유나 정책을 정하지 않는다.

`raw_view_reviews` (#619) — 담당 보안 직원이 열린 사건 결제의 주문자 전화 원문을 볼 때마다 1행. ADMIN 사후 검토용이고 원문은 담지 않는다. 물리 삭제하지 않는다.

| 컬럼 | 타입·NULL | 의미 |
| --- | --- | --- |
| review_id | BIGINT AUTO_INCREMENT PK | |
| case_id | BIGINT NOT NULL FK security_cases | |
| payment_id | BIGINT NOT NULL | 사건에 속한 결제 (P5 논리 참조) |
| staff_id | BIGINT NOT NULL | 원문을 본 담당 직원 (로그인 세션, P1 논리 참조) |
| dispute_ref | VARCHAR(100) NOT NULL | 이의제기 참조 번호 (업무 메모) |
| merchant_contact_attempt | VARCHAR(200) NOT NULL | 가맹점 연락 시도 내역 (업무 메모) |
| reason | VARCHAR(500) NOT NULL | 사유 (업무 메모). 화면에서 고객 이름 · 번호를 적지 않도록 안내 |
| viewed_at · reviewed_at | DATETIME(6) NOT NULL · NULL | UTC. 열람 시각 필수, 검토 시각은 검토 전 NULL |
| status | VARCHAR(30) NOT NULL | PENDING / APPROPRIATE(적절) / EXPLANATION_REQUESTED(소명 요청) (CHECK) |
| reviewed_by_admin_id · reviewed_by_admin_name | BIGINT · VARCHAR(100) NULL | 검토 ADMIN과 그때의 표시 이름 |
| review_memo | VARCHAR(200) NULL | 검토 메모 |

인덱스 (status, viewed_at, review_id) — 검토 목록, (staff_id, viewed_at) — 직원별 열람 이력.

### #670 P3 내부자 사건 · 관리자 판정 · 징계 사건 정지 해제 (2026-11-08)

[승인·도입 기록](history.md#670-p3-내부자-사건--관리자-판정--징계-사건-정지-해제-2026-11-08).

근거: [생성 SQL](../../backend/src/main/resources/db/migration/V20261108_123006__create_insider_cases.sql), [사건 저장·판정·해제](../../backend/src/main/java/com/donworry/investigation/service/InsiderCaseService.java), [경보 사본 Entity](../../backend/src/main/java/com/donworry/investigation/entity/InsiderCaseAlert.java).

두 표 모두 P3 소유다. 직원 · ADMIN ID는 P1 `platform_users`, `alert_id`는 P2 `insider_alerts`의 논리 참조(FK 없음)이고, `insider_case_alerts.insider_case_id`만 같은 P3 FK다. 고객 이름·전화 원문 칸은 없다. 처리 ADMIN 표시 이름은 아래 컬럼에 당시 값으로 보존한다.
사건 열기 · 판정 · 정지 해제는 `case_assignment_locks`의 `FDS_CASE` 행을 첫 문장에서 잡아 FDS 사건 배정과 한 줄로 선다 — 사건이 열린 직원은 배정 · 담당 변경 후보에서 빠진다.

`insider_cases` — 높음 이상 내부자 경보를 직원 한 명당 열린 사건 하나로 묶는다. 물리 삭제하지 않는다.

| 컬럼 | 타입·NULL | 의미 |
| --- | --- | --- |
| insider_case_id | BIGINT AUTO_INCREMENT PK | |
| staff_id | BIGINT NOT NULL | 조사 대상 보안 직원 (P1 논리 참조) |
| status | VARCHAR(20) NOT NULL | OPEN / CLOSED (CHECK) |
| severity | VARCHAR(20) NOT NULL | HIGH / CRITICAL — 묶인 경보 중 가장 높은 등급, 올라가기만 한다 (CHECK) |
| opened_at · updated_at | DATETIME(6) NOT NULL | UTC |
| decision | VARCHAR(30) NULL | NORMAL_DUTY(정상 업무) / CAUTION_NOTICE(주의 조치) / DISCIPLINE_REQUEST(징계 요청). CLOSED 전이 시 Entity가 필수 검사, SQL CHECK는 상태·허용값 조합 검사 |
| decision_memo | VARCHAR(200) NULL | 판정 메모. 개인정보 금지 안내 |
| decided_by_admin_id · decided_by_admin_name | BIGINT · VARCHAR(100) NULL | 판정 ADMIN과 그때의 표시 이름. CLOSED일 때 ID 필수 (CHECK) |
| decided_at | DATETIME(6) NULL | UTC. CLOSED일 때 필수 (CHECK) |
| lock_released_at | DATETIME(6) NULL | 징계 사건의 나중 정지 해제 시각. DISCIPLINE_REQUEST일 때만 (CHECK) |
| lock_released_by_admin_id · lock_released_by_admin_name | BIGINT · VARCHAR(100) NULL | 해제 ADMIN |
| lock_release_reason | VARCHAR(200) NULL | 해제 사유 (필수, 개인정보 금지 안내) |
| lock_release_outcome | VARCHAR(20) NULL | RESTORE / DEACTIVATE (CHECK). 해제 기록 시 Entity가 필수 검사. 실제 계정 전이는 P1의 현재 상태 조건을 따름 |
| open_staff_id | BIGINT 생성 칼럼 | OPEN일 때만 staff_id. UNIQUE로 사람당 열린 사건 1개 (엔티티에 매핑하지 않음) |

인덱스 (status, updated_at, insider_case_id) — 목록, (staff_id, opened_at) — 직원별 지난 사건.

판정·해제 결과의 필수 값과 전이 조건은 [InsiderCase](../../backend/src/main/java/com/donworry/investigation/entity/InsiderCase.java)의 `decide`·`recordLockRelease`도 함께 적용한다. NULL 허용 컬럼의 `IN (...)` CHECK만으로 NOT NULL과 같은 보장을 한다고 해석하지 않는다. P1 해제 결과의 계정 적용 조건은 [#647](#647-p1-보안-직원-계정-보안-정지-2026-11-07)을 본다.

`insider_case_alerts` — 사건에 묶인 경보 한 줄. 같은 경보가 다시 와도(재전달 · 등급 상승) 한 줄이다. 물리 삭제하지 않는다.

| 컬럼 | 타입·NULL | 의미 |
| --- | --- | --- |
| alert_id | BIGINT PK | P2 `insider_alerts` 논리 참조 (자동 생성 아님) |
| insider_case_id | BIGINT NOT NULL FK insider_cases | |
| rule_code | VARCHAR(20) NOT NULL | `InsiderRule`의 Enum 이름(예: INS_01)을 저장하는 사본. 화면 표시 코드 SEC-INS-01과 구분 |
| severity | VARCHAR(20) NOT NULL | HIGH / CRITICAL (CHECK) — 올라가기만 한다 |
| summary | VARCHAR(300) NOT NULL | P2 요약 사본 (건수 · 규칙만, 원문 · IP 없음) |
| raised_at · linked_at | DATETIME(6) NOT NULL | UTC |

인덱스 (insider_case_id, linked_at).

## P4 investigation/report

### #675 P4 내부자 사건 해제 검토 보고서 (2026-11-08)

[승인·도입 기록](history.md#675-p4-내부자-사건-해제-검토-보고서-2026-11-08).

근거: [생성 SQL](../../backend/src/main/resources/db/migration/V20261108_134720__create_ai_insider_reports.sql), [커밋 후 수신](../../backend/src/main/java/com/donworry/investigation/report/service/InsiderReportListener.java), [사실 집계](../../backend/src/main/java/com/donworry/investigation/report/service/InsiderReportService.java), [실패 다시 보내기](../../backend/src/main/java/com/donworry/investigation/report/service/InsiderReportRedeliveryScheduler.java).

P4 소유다. `insider_case_id`는 P3 `insider_cases`, `trigger_alert_id`는 P2 `insider_alerts`의 논리 참조(FK 없음, [5절](README.md#5-모듈-간-참조-규칙) P3 ↔ P4 예외와 같은 방식)다.
사건이 열리거나 내보내기 차단(`InsiderRule.INS_05`, 기준 커밋의 표시 코드 `SEC-INS-05`) 경보가 붙은 것이 커밋된 뒤 코드가 사실을 세어 판을 쌓는다. 보고서 실패는 사건 · 정지 상태를 바꾸지 않는다.
사실 읽기가 실패하면 판을 남기지 않는다(#702). 알림이 `event_publication`에 실패로 남아 5분마다 최대 3번 다시 보내고, 그래도 없으면 다음 서버 시작 때 빠진 판을 채운다.

`ai_insider_reports` — 해제 검토 보고서 한 판. 판별로 쌓고 고치거나 지우지 않는다. FDS 사건 보고서 `ai_reports`와 대상 · 만드는 방식이 달라 따로 둔다.

| 컬럼 | 타입·NULL | 의미 |
| --- | --- | --- |
| report_id | BIGINT AUTO_INCREMENT PK | |
| insider_case_id | BIGINT NOT NULL | P3 내부자 사건 (논리 참조) |
| report_version | INT NOT NULL | 사건별 1부터 (CHECK ≥ 1). UNIQUE(insider_case_id, report_version) |
| trigger_type | VARCHAR(30) NOT NULL | CASE_OPENED(사건 생성) / EXPORT_BLOCKED(내보내기 차단) (CHECK). UNIQUE(insider_case_id, trigger_type) — 같은 계기는 한 판 |
| trigger_alert_id | BIGINT NOT NULL | 이 판을 만든 경보 (P2 논리 참조) |
| status | VARCHAR(20) NOT NULL | SUCCEEDED / FAILED (CHECK) — 만들 때 끝난 상태로 저장. #702부터 새 판은 SUCCEEDED만, FAILED는 그 전에 저장된 판 |
| report_body | JSON NOT NULL | 사실(facts) · 고정 문장 설명(narrative) · 한계(limits). FAILED면 오류 코드만. 고객 번호 · IP · 원문 없음 |
| created_at | DATETIME(6) NOT NULL | UTC |

## P5 payment

### #141 P5 직원 조치 저장 설계 초안

[승인·도입 기록](history.md#141-p5-직원-조치-저장-설계-초안).

현재 저장 근거: [생성 SQL](../../backend/src/main/resources/db/migration/V20261025_160810__create_payment_actions_table.sql), [PaymentAction](../../backend/src/main/java/com/donworry/payment/entity/PaymentAction.java), [결과 ID 매핑](../../backend/src/main/java/com/donworry/payment/service/PaymentActionMapping.java). 제목의 “초안”은 기존 문서 이름이며 아래 구조는 SQL에 존재한다. 설계 승인 경위는 위 이력과 [ADR-0027](../decisions/0027-payment-actions.md)을 따른다.

추가 테이블: `payment_actions`, 소유: P5. 직원 판정 조치만 저장하며 ALLOW 자동 매입·최초 승인에는 행을 만들지 않는다.

| 컬럼 | 타입 | NULL | 의미 |
| --- | --- | --- | --- |
| action_id | BIGINT AUTO_INCREMENT | 불가 | PK, VOID 성공 결과의 targetOperationId |
| payment_id | BIGINT | 불가 | P5 payments FK, 결제별 조치 최대 한 건 |
| decision_id | BIGINT | 불가 | P3 판정 ID 논리 참조, FK 없음 |
| action_type | VARCHAR(20) | 불가 | CAPTURE / VOID |
| idempotency_key | UUID | 불가 | 최초 요청 키, 재시도 시 유지 |
| status | VARCHAR(20) | 불가 | PENDING / SUCCEEDED / FAILED |
| capture_id | BIGINT | 허용 | CAPTURE 성공 때 P6 ID 논리 참조, FK 없음 |
| error_code | VARCHAR(100) | 허용 | 확정 업무 실패 코드, 기술 장애는 저장하지 않음 |
| requested_at | DATETIME(6) | 불가 | P5 최초 요청 저장 시각, UTC |
| completed_at | DATETIME(6) | 허용 | CAPTURE 성공은 capturedAt, VOID 성공/업무 실패는 P5 결과 시각, UTC |

구현한 DB 제약 및 서비스 규칙:

- PK(action_id), `uk_payment_actions_payment` UNIQUE(payment_id), `uk_payment_actions_decision` UNIQUE(decision_id), `uk_payment_actions_idempotency_key` UNIQUE(idempotency_key).
- `fk_payment_actions_payment`: payment_id → payments.payment_id. 같은 P5 소유의 물리 FK이며 삭제 연쇄 없음. 업무 행을 물리 삭제하지 않는다.
- capture_id는 양수 또는 NULL, decision_id는 양수. action_type·status는 위 허용값만 CHECK로 제한한다.
- `chk_payment_actions_result`: PENDING은 capture_id·error_code·completed_at 모두 NULL. SUCCEEDED는 completed_at 필수·error_code NULL이며 CAPTURE이면 capture_id 필수, VOID이면 capture_id NULL. FAILED는 completed_at·error_code 필수, capture_id NULL.
- 완료 상태 불변·원 요청 4필드 불변·requested_at 유지 및 결제 상태와의 일치는 서비스/Entity 전이로 보장하고 테스트한다. 단일 행 CHECK만으로 다른 테이블 상태까지 보장한다고 보지 않는다.
- 상태별 운영 목록·자동 복구 스케줄러는 범위 밖이므로 추가 조회 인덱스는 우선 만들지 않는다. 결제·키·판정 조회는 UNIQUE 인덱스를 쓴다.

### #149 P5 CakeShop 결제 저장 제안

[승인·도입 기록](history.md#149-p5-cakeshop-결제-저장-제안). 제목의 “제안”은 기존 링크와 도입 맥락을 유지합니다. 아래는 기준 커밋의 현재 SQL·코드 설명이며 새로운 설계 채택 선언이 아닙니다.

SQL 근거: [주문 생성](../../backend/src/main/resources/db/migration/V20261025_210955__create_payment_orders_table.sql) · [요청 생성](../../backend/src/main/resources/db/migration/V20261025_210956__create_payment_requests_table.sql) · [FDS 입력 ALTER](../../backend/src/main/resources/db/migration/V20261029_141011__add_payment_request_risk_signals.sql) · [결제 상태 정보 ALTER](../../backend/src/main/resources/db/migration/V20261025_210954__add_payment_status_metadata.sql).

#### payment_orders — 주문 단위 접수·직렬화 기준

| 컬럼 | 타입·NULL | 의미 |
| --- | --- | --- |
| payment_order_id | BIGINT AUTO_INCREMENT PK | P5 내부 주문 접수 ID |
| merchant_id | BIGINT NOT NULL | 인증된 P1 가맹점 논리 참조 |
| order_ref | VARCHAR(100) COLLATE utf8mb4_bin NOT NULL | CakeShop 주문번호, 대소문자 구분 |
| customer_ref | VARCHAR(100) COLLATE utf8mb4_bin NOT NULL | 내부 고객 참조, 대소문자 구분 |
| amount | BIGINT NOT NULL | 주문의 확정 결제 금액, 양수 |
| order_created_at | DATETIME(6) NOT NULL | CakeShop 최초 주문 생성 시각 |
| payment_deadline_at | DATETIME(6) NOT NULL | 최초 주문 생성 +10분, 새 시도 접수 제한 |
| last_attempt_no | BIGINT NOT NULL DEFAULT 0 | 마지막으로 발급한 시도 순번 |
| created_at | DATETIME(6) NOT NULL | P5 주문 접수 기준행 생성 시각 |

UNIQUE(merchant_id, order_ref), 요청행 복합 FK를 위한 UNIQUE(payment_order_id, merchant_id). CHECK: merchant_id/amount >0, last_attempt_no >=0, payment_deadline_at > order_created_at. 정확한 +10분과 주문 불변 입력은 서비스에서 검사한다. 다른 담당 merchants로 FK를 걸지 않는다. PK 행 잠금으로 같은 주문의 요청들을 직렬화한다. 최신 결제 포인터/상태를 복제하지 않고 요청행의 최대 attempt_no로 찾는다.

주문번호·고객 참조의 공백뿐인 값도 SQL CHECK가 막는다. +10분 생성·시도 순번은 [PaymentOrder](../../backend/src/main/java/com/donworry/payment/entity/PaymentOrder.java), 주문 잠금·재요청 불변 입력·기존 주문 충돌은 [접수 트랜잭션](../../backend/src/main/java/com/donworry/payment/service/PaymentRequestTransactionService.java)을 따른다.

#### payment_requests — 결제 시도별 식별·재개 정보

| 컬럼 | 타입·NULL | 의미 |
| --- | --- | --- |
| request_id | BIGINT AUTO_INCREMENT PK | 요청 기록 ID |
| payment_order_id | BIGINT NOT NULL | P5 payment_orders 참조 |
| merchant_id | BIGINT NOT NULL | 가맹점별 키 UNIQUE를 위한 값, 주문과 일치 |
| payment_id | BIGINT NOT NULL | P5 payments FK, 시도당 하나 |
| attempt_no | BIGINT NOT NULL | 주문별 1부터 증가하는 순번 |
| idempotency_key | UUID NOT NULL | CakeShop이 시도별로 보존하는 키 |
| evaluation_request_id | UUID NOT NULL | P5가 최초 생성·고정하는 P2 요청 키 |
| device_id | VARCHAR(100) NULL | 가맹점이 보낸 기기 식별자 (#325) |
| card_token | VARCHAR(255) NULL | 카드 토큰 · 해시, 카드 원문 아님 (#325) |
| card_display_label | VARCHAR(50) NULL | 마스킹한 카드 표시값 (#325) |
| ip_country_code | CHAR(2) NULL | 접속 IP 국가 코드, 대문자 (#325) |
| ip_address | VARCHAR(45) NULL | 접속 IP 주소 (#325) |
| buyer_email | VARCHAR(254) NULL | 구매자 이메일, 소문자 (#325) |
| processing_stage | VARCHAR(30) NOT NULL | RECEIVED / EVALUATED / AUTHORIZED / NEEDS_REVIEW |
| evaluation_id | BIGINT NULL | P2 응답 논리 참조, FK 없음 |
| evaluation_result | VARCHAR(20) NULL | P2 응답 ALLOW/REVIEW/DENY/ERROR |
| evaluation_risk_score | INT NULL | P2 응답 점수, ERROR이면 NULL |
| evaluation_error_code | VARCHAR(100) NULL | P2 ERROR 코드, 외부 거절 코드와 별개 |
| evaluated_at | DATETIME(6) NULL | P2 응답 시각 |
| created_at | DATETIME(6) NOT NULL | 시도 저장 시각, payments.created_at과 동일 |

- UNIQUE(merchant_id, idempotency_key), UNIQUE(payment_order_id, attempt_no), UNIQUE(payment_id), UNIQUE(evaluation_request_id).
- 복합 FK(payment_order_id, merchant_id) → payment_orders(payment_order_id, merchant_id); FK(payment_id) → payments(payment_id). 같은 P5 소유, 삭제 연쇄 없음.
- CHECK: attempt_no >0, processing_stage 허용값. RECEIVED는 평가 필드 전부 NULL. EVALUATED/AUTHORIZED는 양수 evaluation_id·evaluated_at·ALLOW/REVIEW/DENY·0 이상 점수 필수, 오류 NULL. NEEDS_REVIEW는 양수 evaluation_id·evaluated_at·ERROR·비어 있지 않은 오류 필수, 점수 NULL.
- AUTHORIZED는 초기 승인 판단이 끝났다는 내부 단계다. 결제 최종 성공이 아니며 DENIED/DECLINED도 포함한다. 실제 상태는 payments에서 읽는다.
- payment와 주문의 가맹점·주문번호·금액·고객 일치는 같은 트랜잭션의 저장 서비스와 테스트로 보장한다. 행 CHECK로 타 테이블 일치까지 보장한다고 주장하지 않는다.
- 요청 재전송 내용은 주문 불변 필드 · FDS 식별자와 키로 대조한다. 요청 raw JSON·API Key·카드 원문은 저장하지 않는다. FDS 식별자 6개(`V20261029_141011`, #325)는 P2 평가 · 재평가 입력을 다시 만들기 위해 시도 단위로 저장하며 모두 NULL 허용이다. 카드는 토큰과 마스킹 표시값만 저장한다. 평가 응답 사본은 재개에 필요한 최소 6필드이며 룰·보고서 사본은 두지 않는다.

#### payments 추가 제안

기존 제목을 유지한 현재 구현 설명이다. `status_changed_at`·`decline_code`·아래 CHECK와 비고유 인덱스는 위 상태 정보 ALTER에 존재한다.

| 컬럼 | 타입·NULL | 의미 |
| --- | --- | --- |
| status_changed_at | DATETIME(6) NULL | 현재 상태로 전이한 P5 시각. 과거 행은 알 수 없으면 NULL |
| decline_code | VARCHAR(100) NULL | 외부용 최종 DENIED/DECLINED 사유 |

신규 결제는 생성 시 status_changed_at=created_at. [Payment](../../backend/src/main/java/com/donworry/payment/entity/Payment.java)의 상태 전이는 ALLOW/직원 CAPTURE·VOID 완료를 포함해 시각을 갱신하며, 동일 상태 재전달은 기존 시각을 보존한다. decline_code는 DENIED에 FDS_REJECTED, DECLINED에 AUTHORIZATION_DECLINED를 기록하고 그 외 상태는 NULL이다. SQL CHECK는 NULL 또는 이 두 상태/코드 조합을 허용한다. 기존 행의 사유를 추정 백필하지 않으므로 거절 상태에서도 NULL을 허용한다.

기존 조회·거절·완료 API의 원래 시각을 대체하지 않는다. 주문·키 구조는 과거 데이터 자동 편입 없이 도입됐으며, 접수 트랜잭션은 같은 가맹점/주문번호의 미편입 과거 결제를 찾으면 `LEGACY_ORDER_EXISTS`로 막는다. `idx_payments_merchant_order(merchant_id, order_ref)`는 비고유 인덱스다. 이 ALTER는 기존 결제의 주문번호 중복 데이터를 삭제하거나 UNIQUE로 강제하지 않는다.

### #602 P5 주문자 연락처 (2026-11-06)

[승인·도입 기록](history.md#602-p5-주문자-연락처-2026-11-06).

현재 스키마 근거: [생성 SQL](../../backend/src/main/resources/db/migration/V20261106_222538__create_payment_order_contacts.sql). 아래 보관·파기 계획을 실제 배치 실행 완료로 해석하지 않는다.

신규 `payment_order_contacts`는 P5 소유다. `session_id`는 같은 P5 `payment_sessions`의 FK이자 PK(세션당 최대 1행), `merchant_id`는 P1 `merchants` 논리 참조다.
이름 · 전화 원문 칸은 없다. 받기 규칙과 키는 [#602 문서](../payment/602-order-contacts.md).

| 컬럼 | 타입·NULL | 의미 |
| --- | --- | --- |
| session_id | BIGINT PK FK payment_sessions | 결제 준비 세션 |
| merchant_id | BIGINT NOT NULL | 세션의 가맹점. 암호화 AAD에 쓴다 |
| orderer_name_enc | VARCHAR(512) NULL | 주문자 이름 AES-GCM 암호문 (nonce 포함 Base64) |
| orderer_phone_enc | VARCHAR(512) NULL | 주문자 전화(숫자만) 암호문 |
| orderer_phone_hmac | VARCHAR(64) NULL | 주문자 전화 HMAC-SHA256 지문. 전화 암호문과 함께 있거나 함께 없다 (CHECK) |
| pickup_differs | BOOLEAN NULL | 픽업자가 주문자와 다른지. 픽업자 원문은 저장하지 않는다 |
| request_digest | VARCHAR(64) NOT NULL | 같은 요청 키 재요청의 연락처 비교용 HMAC |
| key_id | VARCHAR(10) NOT NULL | 암호화 키 판 (`V1`) |
| created_at | DATETIME(6) NOT NULL | UTC, 세션 생성 시각과 같다 |

주문자 이름 · 전화 중 하나는 있어야 한다 (CHECK). 인덱스는 `idx_payment_order_contacts_phone(orderer_phone_hmac)` — 조각 5의 번호 대조용.
행은 고치지 않는다. 암호문 파기(`created_at`부터 540일, 열린 사건이면 종료까지 연장, ADR-0082 7절)는 2차다. 지문만 남기려면 CHECK 변경 migration이 필요하다.
