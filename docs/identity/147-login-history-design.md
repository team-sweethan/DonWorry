# #147 로그인 이력 설계 초안

- 상태: 사용자 승인으로 구현 반영. 저장소 ERD·ADR 우선 반영, Notion 후속 동기화. 팀 리뷰 대상.
- 기준: `origin/dev 84698bb`, 작업 브랜치 `feature/147-login-history`.
- 관련: [Issue #147](https://github.com/team-sweethan/DonWorry/issues/147), [ADR-0031](../decisions/0031-login-history.md).
- #146의 운영 초기화 코드는 포함하지 않는다. 병합 후 통합 검증한다.

## 1. 확정 정책과 구현 범위

이슈의 확정 정책을 따른다. 성공·실패·명시적 로그아웃을 90일 보관하고,
본인·ADMIN 계정별 조회와 최근 성공 로그인 표시를 제공한다.
이력 INSERT·커밋 실패는 기존 로그인·인증 거절·로그아웃 결과를 바꾸지 않는다.
기존 잠금 횟수 저장과 성공 초기화는 필수 처리이며 AUTH_005 정책을 유지한다.

자동·강제 세션 종료 추적, 기기별 세션 관리, 없는 아이디 이력 검색은 범위 밖이다.
일반 로그아웃은 익명·반복 요청도 204, 전체 로그아웃은 인증 필수를 유지한다.

## 2. P1 스키마 계약

신규 `login_histories`만 추가한다. `platform_users`와 `admin_change_histories`는 변경하지 않는다.

| 컬럼 | SQL 타입 | NULL | 의미 |
| --- | --- | --- | --- |
| history_id | BIGINT AUTO_INCREMENT | 불가 | PK |
| user_id | BIGINT | 허용 | 실제 계정 FK, 없는 계정 실패만 NULL |
| event_type | VARCHAR(20) | 불가 | LOGIN_SUCCESS / LOGIN_FAILURE / LOGOUT |
| occurred_at | DATETIME(6) | 불가 | 발생 시각 UTC |
| client_ip | VARCHAR(45) | 허용 | IPv4/IPv6, 신뢰할 수 없으면 NULL |
| user_agent | VARCHAR(512) | 허용 | 제어 문자 제거·512 코드 포인트 제한 |
| login_reference | UUID | 허용 | 인증 권한 없는 로그인별 연결 ID |
| logout_scope | VARCHAR(10) | 허용 | LOGOUT만 CURRENT / ALL |

- FK `fk_login_histories_user`: user_id → platform_users.user_id. 같은 P1 소유이며 삭제 연쇄 없음.
- 입력 아이디 자체·실패 상세 원인은 보관하지 않는다. 없는 계정 시도는 user_id=NULL로 고정하고 나중에 연결하지 않는다.
- CHECK: 유형 허용값, 성공·로그아웃의 user_id 필수, 실패의 login_reference·logout_scope NULL,
  성공의 login_reference 필수·logout_scope NULL, 로그아웃의 login_reference·logout_scope 필수.
- `uk_login_histories_reference_event` UNIQUE(login_reference, event_type).
  성공과 로그아웃은 같은 연결 ID를 사용한다. 실패의 NULL은 여러 행을 허용한다.
  동시 일반/전체 로그아웃은 DB UNIQUE로 최대 한 건만 남고 먼저 커밋한 scope를 유지한다.
- `idx_login_histories_user_time` (user_id, occurred_at, history_id): 계정별 최신순.
- `idx_login_histories_user_event_time` (user_id, event_type, occurred_at, history_id): 최근 성공.
- `idx_login_histories_retention` (occurred_at, history_id): 기간 지난 행 삭제.
- 연결 ID는 세션 principal에 보관하되 HTTP 응답·화면에는 노출하지 않는다.
  원본 세션 ID·쿠키·토큰은 DB에 보관하지 않으며 연결 ID로 인증하지 않는다.
- 성공 저장이 실패해도 연결 ID는 세션에 남으므로 나중에 로그아웃을 저장할 수 있다.

## 3. 인증·저장 경계

가맹점 → 계정 → 짧은 세션 모니터 순서와 단일 세션 정책을 유지한다.
세션 모니터 안에 DB 저장을 추가하지 않는다.

성공은 BCrypt·상태·권한 조회·잠금 초기화 뒤 UUID를 발급하고 세션 인증 저장까지 완료한 후 기록한다.
실패는 인증 거절(BadCredentialsException / DisabledException)만 기록한다.
입력 400, DB 조회·잠금 갱신·세션 생성 장애를 비밀번호 실패로 기록하지 않는다.
실패 계정 ID는 기존 실제 계정 조회 결과를 사용하고, 없는 입력 아이디로 계정을 새로 만들거나 이력을 연결하지 않는다.

저장 서비스는 REQUIRES_NEW 트랜잭션 전체를 감싼다.
INSERT뿐 아니라 커밋 오류까지 격리하고 서버에는 원문 IP·User-Agent·로그인 입력·비밀값 없이
종류·userId와 안전한 장애 식별 정보를 남긴다.
로그아웃 UNIQUE 충돌은 기존 행의 동일 이벤트 중복인지 확인하고, 다른 제약 오류를 조용히 성공 처리하지 않는다.
확인 조회까지 실패하면 저장 실패로 기록하되 로그아웃 결과는 유지한다.

일반 로그아웃은 Spring Security LogoutHandler에서 인증 principal과 연결 ID를 먼저 확보한다.
전체 로그아웃도 같은 이력 서비스를 사용하되 expireAll·쿠키/컨텍스트 정리를 마친 뒤 DB 저장을 시도한다.
인증 전제 없는 요청이나 연결 ID 없는 도입 전 세션은 잘못된 이력을 만들지 않는다.

세션 수립 도중 예외가 나면 부분 생성된 새 세션·registry·SecurityContext를 정리하고 성공 이력을 만들지 않는다.
그 전에 만료된 이전 세션은 자동 복구하지 않는다. 기존 오류 응답 흐름을 유지하며 회귀 검사한다.

## 4. 보관·삭제

주입 Clock의 UTC 현재 시각을 마이크로초로 맞추고 요청마다 한 번 기준을 잡는다.
조회 범위는 `occurred_at > now - 90일 AND occurred_at <= now`다.
정확히 90일 지난 행은 조회에서 제외하고 `occurred_at <= cutoff`로 삭제한다.

native 삭제의 UTC 기준은 문자열을 DATETIME(6)으로 명시 변환해 JVM 시간대의 이중 적용을 피한다.
인증 잠금과 같은 `identityClock` UTC 빈(이전 이름 `loginAttemptClock`)을 주입해 기준 시각을 검사한다.

삭제 작업은 기동 후 및 매시간 실행하고, 1,000행씩 독립 커밋한다.
없는 아이디의 실패는 잠금 없이 요청마다 쌓일 수 있어 배치 수로 막지 않고, 만료 행이 1,000건 미만이 될 때까지 반복하되 한 실행을 5분으로 제한한다.
(이전 안의 "최대 10배치"는 하루 24만 건이 상한이라 초당 약 2.8건 이상의 실패가 계속되면 테이블이 계속 커졌다.)
한 배치가 실패하면 오류를 기록하고 실행을 끝내며 다음 주기에 다시 시도한다.
조회 제한은 스케줄러의 성공·지연과 무관하게 적용한다.
실행 시간 상한 도달 경고가 계속되면 운영에서 실행 주기·처리량을 조정할 관측 항목으로 남긴다.
IP 차단·CAPTCHA 같은 입력 측 제한은 #147 범위 밖이다.

`@EnableScheduling`은 앱 전체 설정이라 루트 패키지의 `SchedulingConfiguration`에 둔다.
`donworry.scheduling.enabled`(기본 true)로 끌 수 있으며, 테스트는 `build.gradle`에서 끄고 삭제 작업을 직접 호출한다.

## 5. IP·User-Agent

수집 방식은 명시적인 설정으로 나눈다. 기본 DIRECT는 Servlet 접속 주소를 사용하고 전달 헤더는 무시한다.
PROXY는 실제 peer IP가 설정된 신뢰 프록시 목록과 일치할 때만,
관리 프록시가 덮어쓴 단일 사용자 IP 헤더를 읽는다.
신뢰 목록 밖의 peer, 누락·다중 값·잘못된 IP는 NULL로 기록한다. 헤더 이름은 `X-DonWorry-Client-IP`로 고정한다.
프록시 peer 주소를 사용자 주소의 대체값으로 쓰지 않는다.

IP는 숫자 주소만 허용하고 DNS 조회를 하지 않는다. 애플리케이션 앞에서 주소를 임의 변환하는
`server.forward-headers-strategy`가 NONE이 아니거나 Tomcat remote-ip-header가 설정된 조합은 기동을 거절한다.
값을 비워 두면 Spring Boot가 클라우드 환경(Kubernetes 등)을 감지해 NATIVE를 켜고 이 검사를 지나칠 수 있으므로 `application.yml`에 `none`을 명시한다.
이 제약은 앱 전체에 적용된다. TLS를 앞단 프록시에서 끊는 배포에서는 `request.isSecure()`가 false가 되어 #146의 HTTPS 의존 쿠키 동작에 영향을 줄 수 있으므로 배포 구조를 정할 때 함께 결정한다.
배포 담당이 proxy 종류·주소·헤더 덮어쓰기·직접 접근 차단을 확정하기 전에는 운영 IP 신뢰 설정을 추정하지 않는다.
개발 기본 DIRECT와 별개로 실제 프록시 배포 검증은 미검증으로 남긴다.

User-Agent는 문자열로 보관하고 HTML로 렌더링하지 않는다.
화면에서는 브라우저·OS만 추출하고 알 수 없는 값은 '알 수 없는 기기'로 표시한다.

## 6. 조회 API 제안

| 메서드·경로 | 인가 | 응답 |
| --- | --- | --- |
| GET /api/auth/login-histories?page=0&size=20 | 본인 세션 | 페이지 |
| GET /api/auth/login-histories/latest-success | 본인 세션 | latestSuccess 객체 또는 null을 담은 응답 |
| GET /api/admin/users/{userId}/login-histories?page=0&size=20 | ADMIN | 페이지 |

- 본인 조회는 파라미터로 계정 ID를 받지 않는다. ADMIN은 기존 계정 상세 존재 확인·404 계약을 재사용한다.
- page는 0 이상, size는 1~100. 잘못된 값은 안전한 400이다.
- 페이지: items, page, size, totalElements, totalPages.
- 항목: historyId, eventType, occurredAt(UTC ISO 8601, Z 포함), clientIp, userAgent, logoutScope.
- 최근 성공: `{latestSuccess: 항목 또는 null}`. 행이 없어도 200 JSON 객체를 반환해 빈 HTTP 본문과 구별한다.
- 정렬은 occurred_at DESC, history_id DESC. user_id=NULL은 계정별 조회에서 제외한다.
- 익명 401, ADMIN 조회에 타 역할 403. 강제 비밀번호 변경 중에는 새 조회도 기존 필터로 차단한다.
- 기존 로그인 응답은 유지하고 최신 성공 조회를 별도로 호출한다.

## 7. 화면

- 실제 사용자 헤더(AuthRoutes.tsx)에 작은 회색 마지막 로그인 보조 정보를 표시한다.
- 이름·역할을 사용자 메뉴로 묶고 비밀번호 변경·내 로그인 이력 링크를 메뉴 안에 둔다. 로그아웃은 별도 버튼으로 유지한다.
- /account/login-history는 역할 공통 경로다. 업무 권한 없는 계정도 접근한다.
- 강제 비밀번호 변경 중에는 비밀번호 변경 화면으로 이동하고 최신 이력 조회를 시도하지 않는다.
- ADMIN 계정 상세에 로그인 이력 탭. 본인 화면과 목록 컴포넌트를 공유한다.
- 로딩·빈 목록·오류 재시도·페이지 이동을 제공한다.
- 시각은 Asia/Seoul로 표시한다. 최근 성공이 없으면 '최근 90일 내 로그인 기록 없음', IP NULL은 '확인 불가'.
- API 오류를 빈 이력으로 표시하지 않는다. 다른 사용자로 전환할 때 이전 결과를 남기지 않는다.
- HTTP는 src/api/에만 두고 기존 인증 만료 처리와 연결한다.

## 8. 검증 계획·현재 상태

DB·저장·조회 API·이력 화면을 구현했다. 최종 전체 검사·실제 화면 검증 결과는
[identity README](README.md)의 #147 검증 기록을 따른다.
공통 경로 회귀는 구현 전 실패를 확인한 뒤 접근 처리를 반영해 통과했다.

- 단위: Clock의 90일 경계, IP DIRECT/PROXY/위조·누락·오류, User-Agent 제한, 세션 부분 실패 정리.
- MariaDB·HTTP: 이슈의 성공·실패 유형, AUTH_004/005 유지, 독립 커밋·외부 롤백,
  INSERT/커밋 장애, UNIQUE 중복 및 일반/전체 동시 로그아웃, 기간 삭제·실패 후 재실행.
- 조회: 본인/ADMIN 인가·강제 변경, 계정 분리·NULL 계정, 동일 시각 정렬·페이지,
  최근 성공과 기간 경계·기록 없음.
- 회귀: 단일 세션 교체·계정/가맹점 정지·비밀번호 변경과 로그인 동시성.
- 프론트: API 계약, 공통 경로·무권한 계정·강제 변경, 한국 시각·기기 표시와 상태 처리.
- 최종: backend spotlessApply check mariaDbTest, frontend lint format:check test build,
  문서 링크 검사, git diff --check, 실제 브라우저 확인.
- 실제 프록시·HTTPS와 Notion 반영은 별도 확인하며 수행 여부를 명시한다.

## 9. 합의·Notion 반영 절차

[AGENTS.md 4절](../../AGENTS.md#4-backend-규칙), [ERD 변경 절차](../erd/README.md#6-변경-절차)는
Notion P1 담당 ERD 선행 합의를 요구한다. 사용자가 이번 #147에 한해 위 스키마 초안을 승인하고,
저장소 ERD·ADR부터 반영해 구현한 뒤 Notion을 후속 반영하도록 승인했다.
Notion 최신 내용 확인·수정은 수행하지 않았으며 합의·동기화가 완료된 것으로 표시하지 않는다.
다른 담당 테이블·공개 Java API·이벤트·모듈 의존 방향은 변경하지 않는다.
