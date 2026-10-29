# #315 PR 2 — 이용 신청과 이메일 확인

기준: dev `8a243c5` (#323 / PR 1). 2026-10-29 P1 담당자가 설계와 구현 진행을 승인했다.
새 P1 저장 구조를 저장소 ERD·ADR에 선행 기록한다. Notion 최신 대조·동기화는 미수행이다.

## 범위와 흐름

랜딩 → `/apply` → 가맹점·담당자 입력 → 확인·동의 → 실제 접수 → 확인 메일 → `/apply/verify#token=...` → 확인 POST → 검토 대기.
GET·페이지 마운트만으로 토큰을 사용하지 않는다. 승인·반려·계정 발급·첫 비밀번호 설정 동작은 PR 3, 관리자 메일 복구 UI·개인정보 자동 삭제는 PR 4다.
신청 버튼은 활성화하되 계정 발급·결제 연동 완료로 표현하지 않는다.

## 저장 계약 (모두 P1)

- `merchant_applications`: 가맹점명(100), 사업자등록번호(10자리), 담당자명(100), 정규화 이메일(254), 연락처(9~15자리), 문의(1000), 동의 버전(2026-10-29)·시각, 상태·이메일 확인 시각·검토 시각·관리자·반려 사유(1000)·발급 가맹점/계정 참조·생성 시각.
- 상태는 EMAIL_PENDING → REVIEW_PENDING → APPROVED / REJECTED. PR 2에서는 앞 두 상태만 전이한다. 이메일 확인은 사업자 자격 검증이 아니다.
- 진행 중(EMAIL_PENDING, REVIEW_PENDING) 이메일·사업자번호는 SHA-256 생성 컬럼과 UNIQUE로 중복 방지한다. 종료 상태는 NULL로 바뀌어 재신청 가능하다. 이메일은 trim·소문자, 사업자번호·전화는 하이픈·공백 제거. 점·플러스 주소를 합치지 않는다. 사업자번호는 형식만 확인하고 실재 여부를 확인했다고 표현하지 않는다.
- `application_tokens`: 신청 FK, 용도 EMAIL_VERIFICATION / INITIAL_PASSWORD_SETUP, 계정 FK(nullable, 첫 설정용), SHA-256 토큰 해시(64), 생성·만료·사용·폐기 시각. 원문은 SecureRandom 32바이트 URL-safe Base64(43자). 첫 설정 토큰은 계정 필수 CHECK. 확인·재발송은 READ_COMMITTED 격리로 실행한다. 기본 REPEATABLE_READ에서 옛 토큰을 읽은 후 잠금을 승격하면 MariaDB가 1020 스냅샷 충돌을 내므로 현재 읽기를 사용한다. 신청 잠금을 먼저 얻은 뒤 해당 용도 토큰 전체 폐기·발급/사용한다.
- `application_mail_deliveries`: 신청 FK, 토큰 FK(nullable), 종류 EMAIL_VERIFICATION / APPROVAL / REJECTION, PENDING / SENT / FAILED / UNKNOWN, 시작·종료 시각·실패 코드(고정 문자열). 본문·수신 주소·원문 토큰·SMTP 예외를 복제하지 않는다. 지연된 결과는 해당 시도 행만 갱신한다.
- `application_rate_limits`: BIGINT PK, scope·대상 SHA-256 UNIQUE, 최근 허용 요청 epoch millis 목록(최대 5개), 만료 시각. 원문 IP·이메일은 저장하지 않으나 해시는 익명 정보로 보지 않는다. 제한의 최대 창(24시간) 이후 정리한다. 요청별 INSERT/행 잠금/판정/갱신을 독립 트랜잭션으로 수행해 재시작·경쟁·업무 롤백으로 횟수가 초기화되지 않는다.
- `merchants.business_registration_number VARCHAR(10) NULL`: PR 3 승인 때 복사한다. 기존 가맹점은 모르면 NULL, UNIQUE 없음. 기존 가맹점 중복은 관리자 경고용이다. 담당자 연락처는 신청에만 남기고 platform_users에는 복제하지 않는다.
- PK BIGINT AUTO_INCREMENT, 상태 VARCHAR, 시각 UTC DATETIME(6). 같은 P1 테이블 FK만 사용한다. 삭제 연쇄 없음. 삭제 시 발송 → 토큰 → 신청 순서이며 금융 기록·가맹점·계정은 삭제하지 않는다.

## HTTP·오류·보안

- POST `/api/merchant-applications`: 201 `{mailStatus}`. 입력 400 APPLICATION_001, 진행 중 중복 409 APPLICATION_002, 제한 429 APPLICATION_003, 서비스/저장 오류 503 APPLICATION_008. 저장 후 메일 실패는 201과 FAILED/UNKNOWN으로 접수를 유지한다.
- POST `/api/merchant-applications/email-verifications`: `{token}`, 200 `{status:REVIEW_PENDING}`. 잘못됨 400 APPLICATION_004, 만료 410 APPLICATION_005, 사용됨 409 APPLICATION_006, 재발송으로 폐기됨 410 APPLICATION_007.
- POST `/api/merchant-applications/email-verification-resends`: `{email}`, 202. 대상이 없거나 이미 확인된 경우에도 같은 일반 안내를 반환한다. 잘못된 이메일 형식은 400, 대상 존재와 무관하게 같은 이메일 제한(429)을 적용한다. 발송 성공을 약속하지 않는다.
- 신청 IP 최근 1시간 5회. 재발송 이메일 최근 1분 1회·최근 24시간 5회. SMTP 실패도 횟수에 포함한다. 최초 발송은 1분 쿨다운에만 포함해 제출 직후 중복 발송을 막고, 24시간 5회는 재발송만 센다 (#315 "재발송 하루 5회"). 최초 발송 기록은 신청 저장과 같은 트랜잭션에서 남겨 신청 1건이 DB 연결을 하나만 쓴다.
- 기존 LoginHistoryRequestMetadata의 DIRECT/신뢰 프록시 정책 재사용. IP 확인 불가는 503, 전달 헤더를 임의 신뢰하지 않는다.
- 세 공개 POST는 전역 CSRF false에서도 검사한다. 기존 쿠키·헤더 교환을 재사용한다. 공개 경로만 강제 비밀번호 변경 필터에서 제외하며 업무 API 제한은 그대로다.
- 모든 응답 no-store. 토큰은 URL fragment로 전달하고 확인 화면에서 읽은 뒤 제거한다. 본문 POST에만 넣고 재방문은 다시 메일을 열도록 안내한다. 공개 신청 상세·목록 조회 없음. 브라우저 저장소에 개인정보·토큰 저장 없음.

## 메일과 트랜잭션

신청/토큰/PENDING 저장 커밋 → 트랜잭션 밖 SMTP → 별도 트랜잭션으로 해당 발송 시도 결과 기록.
원문 토큰은 호출 메모리에서만 전달한다. 자동 재전송 큐는 만들지 않고 공개 재발송 시 새 토큰을 발급한다.
SMTP 수락은 SENT이며 실제 도착을 뜻하지 않는다. 예외·결과 저장 실패·프로세스 중단은 성공으로 보장하지 않는다. 오래된 PENDING은 UNKNOWN으로 해석/복구하며 PR 4 관리 UI에서 노출한다.
Spring Boot mail starter + JavaMailSender를 사용한다. 로컬 Mailpit SMTP 1025/UI 8025는 loopback에만 바인딩한다. Gmail은 설정만 교체하고 발신 계정·앱 비밀번호를 환경변수로 주입한다. 연결/읽기/쓰기 시간 제한을 지정한다.
링크는 요청 Host 대신 설정된 public-base-url에서 생성한다. prod는 기능 기본 꺼짐이며 명시 활성화 시 HTTPS 공개 URL과 SMTP 발신 설정을 검증한다.

## 개인정보와 후속 경계

동의 항목·목적·기간을 제출 전에 표시하고 버전과 시각을 기록한다. 미확인 7일·반려 30일 자동 삭제는 PR 4이며 PR 2만으로 보관 정책 전체를 구현했다고 하지 않는다. 미확인 7일이 지난 토큰은 재발송/확인하지 않는다. 물리 삭제·중복 키 해제는 PR 4에 연결한다.
승인 신청은 가맹점 이용 기간 보관한다. INACTIVE는 일시 정지일 수 있어 이용 종료로 간주하지 않는다. 종료 기준·처리는 아직 외부 계약이 없어 후속 결정 사항이다.
첫 설정 토큰의 초기화 시 폐기·비밀번호 플래그 해제·승인 원자성은 PR 3에서 기존 계정 잠금/생성 로직과 연결한다.

## 검증 계획

MariaDB: 실제 Flyway/JPA, 공개 CSRF와 입력 오류, 중복/동시 제출, 메일 실패에도 접수 보존, 재발송 구 토큰 폐기, 만료·사용·용도 구별, 확인/재발송 경쟁, IP/이메일 제한과 시간 경계, 강제 변경 세션 공개 진입.
프론트: 정규화·동의·재발송 API 계약·토큰 fragment 제거·결과 안내, 기존 인증 회귀 검사. Mailpit 실제 SMTP/본문 링크 확인과 Gmail 실제 수신은 별도 기록한다.
실행 결과는 identity README와 PR의 테스트 결과·미검증에 기록한다.
