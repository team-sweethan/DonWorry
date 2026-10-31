# #315 PR 3 — 관리자 검토·계정 발급·첫 비밀번호 설정

기준: dev `09c2b0c` (#331 / PR 2). P1 담당자가 2026-10-29 설계와 구현을 승인했다.
PR 2의 [신청 저장 계약](315-application-design.md)과 ADR-0043~0045를 이어 쓴다. P1 신청 아이디 컬럼·예약 UNIQUE를 새 migration으로 추가한다. 모듈 의존·다른 담당 API 변경은 없다.

## 화면과 상태

관리자 홈·메뉴 → `/admin/applications` → `/admin/applications/:id` → 승인 또는 반려.
목록 기본값은 REVIEW_PENDING이며 상태·가맹점명/사업자번호 검색·20건 페이지를 URL에 보관해 상세에서 돌아갈 때 유지한다.
EMAIL_PENDING은 조회만 가능하다. 이메일 확인은 주소 사용 확인이며 사업자 자격 확인으로 표시하지 않는다.

상세는 신청 정보와 발급 입력을 나란히 표시하고 좁은 화면에서는 신청 정보부터 세로로 배치한다.
신청자는 신청 폼에서 로그인 아이디를 선택한다. 관리자는 선택된 아이디를 확인하며 수정하지 않는다. 승인은 코드·수수료·권한 입력 → 발급 정보 최종 확인 → ‘승인·계정 발급’ 순서다.
반려는 사유 입력 → 신청자에게 보낼 내용 확인 → 확정 순서다. 오류 시 입력을 유지한다.
동시 처리 충돌·응답 유실 시 최신 상태를 조회하기 전에는 다시 처리하지 않는다. 변경 요청을 자동 재시도하지 않는다.

신청은 EMAIL_PENDING → REVIEW_PENDING → APPROVED / REJECTED만 사용한다.
발급 계정의 passwordChangeRequired와 메일 발송 결과를 신청 상태와 구별한다.
APPROVED 상세의 ‘설정 또는 변경 필요’에는 관리자 초기화도 포함하므로 첫 설정 미완료로 단정하지 않는다.
SMTP 수락은 실제 수신을 보장하지 않으며 발급 결과와 별도로 안내한다. 메일 실패 관리·재발송 화면은 PR 4다.

## HTTP

| 메서드·경로 | 계약 |
| --- | --- |
| GET `/api/admin/merchant-applications` | page=0, size=20(1~100), keyword(100자), status(생략 시 전체). items/page/size/totalElements |
| GET `/api/admin/merchant-applications/{id}` | 신청 정보·신청자 선택 아이디(requestedLoginId)·검토 기록·발급 ID·실제 로그인 아이디·현재 비밀번호 변경 필요 여부·같은 사업자번호의 기존 가맹점 |
| POST `/api/admin/merchant-applications/{id}/approve` | merchantCode, pgFeeRate, permissions → status/merchantId/userId/mailStatus |
| POST `/api/admin/merchant-applications/{id}/reject` | reason(공백 제외 필수, 최대 1000자) → status와 mailStatus, 발급 ID는 null |
| POST `/api/merchant-applications/initial-password-setups` | token, newPassword → 204. 토큰·비밀번호를 응답하지 않는다 |

관리자 경로는 기존 ADMIN 인가·세션 정책을 유지하고 쓰기 직전 수행자 상태·세션을 다시 확인한다.
새 공개 POST는 정확한 경로만 permitAll·강제 변경 필터 예외로 추가하고 전역 false에서도 CSRF를 검사한다.
모든 신규 경로 응답은 no-store. 공개 토큰 조회 API·GET 상태 변경은 만들지 않는다.

오류: 신청 없음 APPLICATION_009(404), 검토 대기 아님 APPLICATION_010(409), 첫 설정 링크 오류 APPLICATION_011(400), 만료 APPLICATION_012(410), 사용됨 APPLICATION_013(409), 폐기됨 APPLICATION_014(410), 계정/가맹점 상태·발급 연결 부적합 APPLICATION_015(409), 검토 입력 APPLICATION_016(400), 첫 설정 입력 APPLICATION_017(400).
코드·아이디 중복과 비밀번호 정책은 기존 ADMIN/AUTH 오류를 재사용한다.

## 발급과 원자성

기존 AdminMerchantService·AdminAccountService의 독립 REQUIRES_NEW 생성 메서드를 연속 호출하지 않는다.
MerchantProvisioningService·AccountProvisioningService로 검증·생성·기존 이력을 추출한다. 두 내부 서비스는 MANDATORY여서 호출자의 트랜잭션에 반드시 참여한다. 기존 관리자 수동 생성도 같은 서비스를 사용한다.

승인: 수행자·신청에 저장된 아이디 AccountLockService → 수행자 세션 확인 → READ_COMMITTED/REQUIRES_NEW → 관리자 재확인 → 신청 PESSIMISTIC_WRITE → REVIEW_PENDING/이메일 확인 검사 → 가맹점·계정·권한·관리 이력 → 신청 승인·검토자·발급 참조 → 첫 설정 토큰·PENDING 발송 이력 → 커밋 → 잠금 해제 → SMTP → 별도 트랜잭션으로 발송 결과 기록.
새 가맹점은 커밋 전 다른 요청에 보이지 않으며 기존 가맹점 잠금을 계정 잠금 안에서 추가 획득하지 않는다.
메일 발송은 DB 트랜잭션·계정 잠금 밖이다. 어느 INSERT/UPDATE/커밋이 실패해도 발급 전체가 롤백된다.
신청 행 잠금으로 승인/승인·승인/반려를 직렬화하고 두 번째 요청은 409로 끝낸다. 다시 승인하거나 메일을 반복 발송하지 않는다.

가맹점명·계정 표시 이름은 신청에서 가져온다. 역할은 OPERATOR 고정, 기능 권한은 기존 두 코드만 허용한다.
아이디는 신청에 저장된 requestedLoginId를 사용한다. 승인 요청에 임의 loginId를 넣어도 채택하지 않는다. 코드·아이디 형식 규칙은 기존 계약 그대로다. 수수료 기본 1% → 0.010000, 0 이상 1 미만·소수 6자리.
사업자번호는 merchants에 복사하고 연락처는 신청에만 둔다. 기존 가맹점의 같은 사업자번호는 경고로 보여주고 승인 차단/기존 계정 연결을 자동 수행하지 않는다.
secret_reference는 설정하지 않는다. #354 PR B부터 승인된 사장님은 로그인 후 시크릿 키를 직접 발급하며 DB 암호화 저장은 [ADR-0052](../decisions/0052-merchant-key-management.md)를 따른다. 연결 확인·신규 결제 차단은 PR C 범위이며 결제 연동은 별도 진행임을 승인 화면·메일에 명시한다.

## 신청 아이디 예약과 기존 신청

2026-10-29 P1 사용자가 아이디를 직접 선택해 신청하도록 변경을 승인했다. 원래 #315 관리자 입력 계약을 대체한다.
requested_login_id VARCHAR(30) NULL과 진행 중 신청의 SHA-256 pending_login_id 생성 컬럼·UNIQUE를 추가한다. 신규 API에서는 필수, NULL은 이전 양식 호환만을 위한 것이다.
신청 제출은 IP 제한 → 선택 아이디의 계정 잠금 → 기존 계정 중복 검사 → 신청/예약/메일 시도 커밋 → 잠금 해제 → SMTP 순서다. 신청 간 예약 충돌은 DB UNIQUE가 판정한다.
수동 계정 생성도 같은 아이디 잠금 안에서 기존 계정과 신청 예약을 검사한다. 승인만 자기 신청의 예약을 사용할 수 있다. 반려/삭제 시 예약이 해제되고 승인 후에는 platform_users UNIQUE가 보호한다. 다중 서버는 기존 세션/잠금 전제 밖이다.
아이디 중복은 APPLICATION_018/409, 기존 신청의 아이디 누락은 APPLICATION_019/409. 기존 계정 충돌이 예외적으로 승인 시 발견되면 전체 롤백하며 관리자가 임의 아이디로 우회하지 않는다.
기존 아이디 없는 신청은 상세에 미입력 사유를 표시하고 승인 입력 폼을 제공하지 않는다. 반려 사유로 새 양식 재신청을 안내한다. 승인된 기존 계정의 아이디와 데이터는 그대로 유지한다.

## 첫 비밀번호와 초기화

계정 생성 시 SecureRandom 비밀값의 BCrypt 해시를 넣고 passwordChangeRequired=true로 둔다. 비밀값 원문을 반환·메일 발송·저장하지 않는다.
토큰은 기존 32바이트 난수·SHA-256 저장을 사용한다. INITIAL_PASSWORD_SETUP, application_id·user_id 필수, 72시간 유효·1회 사용.
메일 링크는 설정된 공개 주소의 `/apply/password#token=...`다. fragment는 라우트 loader에서 읽고 즉시 주소창에서 지운다. 저장소·로그에 넣지 않는다.
페이지 방문만으로 토큰을 소비하지 않는다. 새로고침으로 토큰이 없어지면 메일에서 다시 열도록 안내한다.

설정 POST: 토큰으로 계정·가맹점 잠금 대상을 확인 → 가맹점 잠금 → 계정 잠금 → READ_COMMITTED/REQUIRES_NEW → 신청 잠금 → 토큰 재조회·refresh/PESSIMISTIC_WRITE → 용도·사용·폐기·만료와 승인/계정 연결·ACTIVE/OPERATOR/강제 변경 검사 → PasswordPolicy → 비밀번호 변경·플래그 해제·토큰 사용을 함께 커밋 → 계정 세션 만료.
서버 자동 로그인 없이 로그인 화면으로 이동한다. 기존 계정의 일반 비밀번호 변경 정책은 유지한다.

관리자 초기화는 기존 수행자·대상 계정 잠금 안에서 해시 변경과 대상의 미사용 첫 설정 토큰 폐기를 함께 커밋한다. 첫 설정도 같은 계정 잠금을 사용하므로 초기화가 먼저 끝나면 링크는 폐기되고, 첫 설정이 먼저 끝나면 나중 초기화 값이 최종 비밀번호가 된다.
잠금은 기존 단일 서버 세션 전제다. 다중 서버 보장은 이 PR 범위에 포함하지 않는다.

## 검증과 후속

실행 결과는 identity README와 PR 본문에 기준 커밋·작업 트리를 적는다.
MariaDB: 인가·입력·목록 필터, 원자적 발급·중복 코드/아이디·이력 실패 롤백, 승인 경쟁, 메일/결과 저장 실패, 반려 후 재신청, 설정 CSRF·비밀번호·토큰 용도/만료/폐기/사용·동시 사용·초기화 경쟁·정지 상태, 기존 관리자 생성/초기화 회귀.
프론트: API·CSRF·비밀값 본문 전송·자동 재시도 방지·메일과 발급 문구 분리·UTC 표시, 브라우저 데스크톱/좁은 화면·확인창·입력 유지.
Mailpit의 실제 SMTP 수신과 Gmail 실제 수신은 별도로 기록한다.
PR 4: 메일 실패·UNKNOWN 관리와 새 토큰 재발송, 미확인/반려 자동 삭제, 운영 기능 설정에 맞춘 공개 화면 차단, 전체 흐름 최종 검증.
