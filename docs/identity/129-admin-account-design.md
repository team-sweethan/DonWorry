# #129 관리자 계정 관리 설계

상태: #129 구현 계약. 2026-10-25 사용자가 Notion 선행 반영 대신 저장소 ERD·ADR 기록 후 구현하도록 승인했다. Notion 최신 ERD 확인·동기화는 미수행이다.

## 범위

- P1 identity와 frontend만 변경한다. 타 담당 테이블·모듈 공개 Java 계약은 바꾸지 않는다.
- 계정 목록·상세·생성, 사장님 기능 권한 설정, 정지·복구, 임시 비밀번호 초기화를 제공한다.
- 계정 삭제·역할 변경·가맹점 변경·관리자 생성 API는 제공하지 않는다.
- #125 세션 종료와 #128 비밀번호 정책·변경 화면을 재사용한다.
- #130의 가맹점 생성·정지는 후속이며, 계정 생성용 ACTIVE 가맹점 선택 조회만 먼저 제공한다.
- 전체 업무 인가·CSRF 활성화, #127 로그인 실패 잠금 해제는 포함하지 않는다.

## DB 제안

### platform_users

- `password_change_required BOOLEAN NOT NULL DEFAULT FALSE` 추가.
- 신규·초기화 계정은 TRUE, 본인 변경 커밋 성공 시 FALSE.
- CHECK: `(role = 'OPERATOR' AND merchant_id IS NOT NULL) OR (role IN ('ADMIN', 'SECURITY_STAFF') AND merchant_id IS NULL)`.
- SQL 시드의 admin·boss·staff_test는 조건을 충족한다. 각 환경의 수동 데이터는 별도 사전 확인 대상이다.
- 기존 login_id VARCHAR(100)은 유지한다. 신규 생성 요청만 `[a-z0-9_]{3,30}`을 검사한다.

### admin_change_histories (P1 신규)

| 컬럼 | 타입·조건 | 의미 |
| --- | --- | --- |
| history_id | BIGINT AUTO_INCREMENT PK | 이력 ID |
| actor_user_id | BIGINT NOT NULL FK platform_users | 변경한 관리자 |
| target_user_id | BIGINT NULL FK platform_users | 대상 계정 |
| target_merchant_id | BIGINT NULL FK merchants | #130 대상 가맹점 |
| change_type | VARCHAR(40) NOT NULL | USER_CREATED, PERMISSIONS_CHANGED, USER_DISABLED, USER_ENABLED, PASSWORD_RESET 등 |
| before_data | JSON NULL | 허용 필드의 변경 전 값 |
| after_data | JSON NULL | 허용 필드의 변경 후 값 |
| created_at | DATETIME(6) NOT NULL | UTC |

- 대상 계정·가맹점 중 정확히 하나만 값이 있는 CHECK를 둔다.
- 사용자·가맹점별 시간순 이력 조회 인덱스를 둔다.
- 비밀번호·해시·요청 전체·세션 값은 이력 JSON에 넣지 않는다.
- 이력 쓰기는 업무 변경과 동일 트랜잭션에서 INSERT만 한다. 이력 수정·삭제 API와 저장소 메서드는 제공하지 않는다.
- 세 스키마 전환을 각각 newMigration으로 생성한다. 기존 migration은 수정하지 않는다.

## HTTP 제안

모든 `/api/admin/**`는 서버에서 ADMIN만 허용한다. 비로그인 401 AUTH_001, 비관리자 403 AUTH_002.

| 메서드·경로 | 계약 |
| --- | --- |
| GET /api/admin/users | page·size·keyword·role·status, 크기 제한과 안정적인 userId 정렬 |
| GET /api/admin/users/{id} | 공개 계정 정보·소속·권한·강제 변경 상태 |
| POST /api/admin/users | loginId·displayName·role·merchantId, 201 계정 정보와 임시 비밀번호 |
| PUT /api/admin/users/{id}/permissions | permissions 최종 집합, OPERATOR만 허용 |
| PUT /api/admin/users/{id}/status | ACTIVE 또는 DISABLED, 자기 정지 거절 |
| POST /api/admin/users/{id}/password-reset | 새 임시 비밀번호, 강제 변경 TRUE |
| GET /api/admin/merchants/options | ACTIVE 가맹점 ID·이름의 제한된 검색 목록 |

- 중복 아이디는 DB UNIQUE를 최종 기준으로 409, 없는 대상은 404, 업무 입력 오류는 400 ProblemDetail.
- 임시 비밀번호는 서버 SecureRandom 생성 후 PasswordPolicy로 검증하고 BCrypt로만 저장한다.
- 임시 비밀번호 응답은 Cache-Control: no-store. DTO 문자열 표현·로그·영속 브라우저 저장소에서 원문을 제외한다.
- 같은 상태·권한 집합 재요청은 성공하되 실제 변경이 없으므로 이력·세션 종료를 반복하지 않는다.
- 재발급 응답을 잃으면 조회로 복구하지 않고 다시 초기화한다. 자동 재시도하지 않는다.

## 세션과 순서

- 관리자 변경: 대상 실제 loginId 잠금 → 관리자 세션 유효성 재확인 → REQUIRES_NEW 트랜잭션에서 검증·변경·이력 INSERT·커밋 → expireAll(targetUserId) → 잠금 해제.
- 커밋 실패·롤백 시 세션을 종료하지 않는다. 로그인은 동일 AccountLockService 잠금을 사용한다.
- /login·/me와 SessionUser에 passwordChangeRequired를 추가한다.
- 강제 변경 계정은 화면에서 비밀번호 변경·로그아웃만 제공한다.
- 인증된 강제 변경 계정의 업무 API 요청은 403 AUTH_008로 차단한다. 기존 비로그인 업무 API 허용 정책과 서버 간 API는 유지한다. 익명 호출까지 업무 API를 보호하는 것은 #68 5단계이며 이 변경만으로 전체 서버 인가가 완료되지는 않는다.
- 본인 비밀번호 변경 성공은 DB 플래그와 현재 SecurityContext의 플래그를 함께 갱신한다. 세션 재로그인 전략을 재실행하지 않는다. 그 뒤 /me를 갱신해 역할별 첫 화면으로 이동한다.
- 강제 변경 중에는 새 비밀번호가 현재 임시 비밀번호와 같으면 400 AUTH_009로 거절한다. 그 외 비밀번호 정책은 #128과 같다 (일반 본인 변경의 동일 비밀번호는 허용).

## 검증할 것

- 관리자 API 401·403, 생성 규칙·중복·ACTIVE 가맹점·자기 정지 금지.
- 실제 MariaDB 전체 Flyway·JPA validate와 CHECK의 NULL 조건·FK·기존 시드.
- 생성·초기화 후 강제 변경·직접 업무 API 차단·변경 후 세션 유지와 /me 갱신.
- 정지·권한 변경·초기화와 로그인 양방향 경쟁 조건, 계정 별칭, 타 사용자 보호.
- 이력 저장·DB 커밋 실패 시 업무 값·세션 유지, 비밀정보 미노출.
- 목록·생성·상세·비밀값 일회 표시·세션 만료·새로고침 브라우저 동작.
- backend spotlessApply/check/mariaDbTest, frontend lint/format:check/test/build.

## 현재 미검증

Notion P1 ERD 최신 내용은 연결 도구가 없고 Chrome 접근이 허용되지 않아 확인하지 못했다. 사용자 승인에 따라 저장소 ERD·ADR에 기록하고 구현했다. 테스트 결과·현재 미검증은 identity README의 #129 기록을 따른다.
