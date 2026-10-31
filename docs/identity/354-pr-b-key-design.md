# #354 PR B — 시크릿 키 관리 설계안

상태: **P1 사용자 설계·구현 승인, PR 리뷰 대상**. 2026-10-31 사용자가 아래 신규 테이블 2개를 포함한 구현 진행을 승인했다. 상세 결정·최종 계약은 [ADR-0052](../decisions/0052-merchant-key-management.md)를 따른다. 운영 이전·배포 완료를 뜻하지 않는다.

관련: [Issue #354](https://github.com/team-sweethan/DonWorry/issues/354), [기존 HMAC 계약](219-merchant-hmac.md), [기존 키 준비 절차](246-cakeshop-provisioning.md).
기준: 2026-10-31 `dev 54a33fa` (`origin/dev`와 일치 확인), `feature/354-secret-key-management`.

## 1. 범위

- B: 저장 방식 변경 ADR, DB 암호화 저장, 사장님 발급·보기·재발급, 관리자 폐기, 기존 CAKESHOP 키 이전, 해당 화면·문서·테스트.
- C: 연결 확인 요청, 연동 상태에 따른 신규 결제 차단, 사장님 홈·로그인 첫 화면 변경, 개발자 연동 문서·가짜 쇼핑몰 스크립트.
- B의 dev 병합은 이슈에 적힌 대로 시연 이후다. 운영 환경의 키 이전·배포 실행은 별도다.

## 2. 현재 코드와 단계 사이의 차이

`MerchantSecretService.resolve(reference)`는 환경변수에서 Base64 키를 읽는다. `MerchantHmacService`는 이 키로 서명을 확인하고 nonce를 소비한 뒤 INACTIVE 가맹점의 POST를 거절한다. 연동 상태는 아직 없다.

B에서 DB 키 인증으로 바꾸면 발급받은 키로 서명할 수 있지만, **연동 전 신규 결제 차단은 C 전까지 적용되지 않는다.** 화면에서 연결 확인 기능과 결제 차단이 이미 동작한다고 안내하면 안 된다. B 단독 운영 활성화 여부는 이 차이를 고려해야 한다.

## 3. P1 확인을 받을 저장 구조 제안

### `merchant_integrations` — 가맹점별 현재 키·연동 상태

| 컬럼 | 타입·제약 | 용도 |
| --- | --- | --- |
| integration_id | BIGINT AUTO_INCREMENT PK | 내부 식별자 |
| merchant_id | BIGINT NOT NULL UNIQUE, merchants FK | 가맹점당 현재 설정 1개 |
| status | VARCHAR(30) NOT NULL | NO_KEY / PENDING_CONNECTION / CONNECTED |
| encrypted_secret | VARBINARY(1024) NULL | AES-256-GCM 암호문·인증 태그 |
| encryption_nonce | VARBINARY(12) NULL | 암호화마다 새 무작위 IV |
| master_key_id | VARCHAR(40) NULL | 복호화할 마스터 키 식별자 |
| key_version | BIGINT NOT NULL | 발급·재발급·폐기 시 증가, 늦은 요청 구분 |
| key_issued_at | DATETIME(6) NULL | 현재 키 발급 시각 |
| connected_at | DATETIME(6) NULL | 현재 키 연결 확인 시각 |
| updated_at | DATETIME(6) NOT NULL | 마지막 변경 시각 |

행이 없으면 NO_KEY로 읽는다. 첫 변경은 기존 merchants 행을 잠근 뒤 행을 생성해 중복을 막는다. NO_KEY는 암호문·IV·마스터 키 ID·발급/연결 시각이 모두 NULL이고, 다른 상태는 키 필드·발급 시각이 필수다. CONNECTED에서만 connected_at이 존재하도록 CHECK를 둔다.

### `merchant_key_histories` — 키 관리 이력

| 컬럼 | 타입·제약 | 용도 |
| --- | --- | --- |
| history_id | BIGINT AUTO_INCREMENT PK | 이력 식별자 |
| merchant_id | BIGINT NOT NULL, merchants FK | 대상 가맹점 |
| actor_user_id | BIGINT NULL, platform_users FK | 실행 계정, 시스템 이전만 NULL |
| action | VARCHAR(30) NOT NULL | ISSUE / REISSUE / REVOKE / MIGRATE |
| key_version | BIGINT NOT NULL | 작업 후 버전 |
| reason | VARCHAR(500) NULL | 관리자 폐기 사유 필수 |
| created_at | DATETIME(6) NOT NULL | 실행 시각 |

키 변경과 이력 INSERT는 같은 트랜잭션이다. 키 원문·암호문을 이력에 넣지 않는다. 이력은 보존한다. 두 테이블 모두 P1 소유이며 P1 테이블로만 물리 FK를 건다. `(merchant_id, history_id)` 조회 인덱스를 둔다.

`merchants.secret_reference`는 이번에 삭제하지 않고 이전 대상 확인에만 쓴다. 이전 후 런타임 인증은 DB만 사용한다. 폐기 후 환경변수 키가 다시 살아나는 fallback을 만들지 않는다.

## 4. 키 저장·이전 제안

- 새 HMAC 키는 SecureRandom 32바이트, 화면·쇼핑몰 설정에는 표준 Base64를 사용한다. 미리보기의 `dw_sk_` 예시 접두사는 실제 형식으로 사용하지 않는다.
- AES-256-GCM, 매 암호화마다 새 12바이트 IV와 128비트 태그를 쓴다. AAD에는 형식 버전·merchant_id·key_version을 묶어 다른 행으로 암호문을 옮기는 것을 거절한다.
- 마스터 키는 환경변수에만 두고 DB에는 식별자만 저장한다. 키 ID별 복호화를 지원하고 신규 암호화에는 활성 키 ID를 쓴다. DB 백업과 별도로 마스터 키를 안전하게 백업하며 복원 검증을 운영 절차에 넣는다. 누락·잘못된 키·변조는 503으로 차단한다.
- CAKESHOP은 명시적으로 켜는 이전 실행 경로에서 기존 환경변수 값을 읽어 **동일한 바이트**를 암호화하고 CONNECTED로 기록한다. SQL·인자·로그에 원문을 넣지 않는다. 기존 시드 SQL은 수정하지 않는다.
- 이전은 merchants 잠금 안에서 멱등하게 실행하고 이미 설정·폐기한 키를 덮어쓰지 않는다. 실패하면 DB 인증 전환을 완료하지 않는다. 이전 완료 전 업무 요청을 받지 않는 기동 순서를 구현·검증한다.
- DEMOSHOP은 NO_KEY로 남는다. CAKESHOP 외 기존 secret_reference가 있는 환경은 사전 목록 확인 후 이전 대상을 정하며 임의로 버리지 않는다.

## 5. API·인가·동시성 제안

| 메서드·경로 | 내용 |
| --- | --- |
| GET /api/owner/integration | 자기 merchantCode·상태·버전·시각·페이지 단위 이력, 키 제외 |
| POST /api/owner/integration/keys | 최초 발급, currentPassword 필요 |
| POST /api/owner/integration/keys/reveal | 연동 전 다시 보기, currentPassword 필요 |
| POST /api/owner/integration/keys/reissue | 재발급, currentPassword·expectedKeyVersion 필요 |
| POST /api/admin/merchants/{merchantId}/integration/revoke | 관리자 폐기, reason·expectedKeyVersion 필요 |

모든 변경은 기존 CSRF 보호를 적용한다. 사장님 API는 ACTIVE OPERATOR의 세션에서 가맹점을 결정하며 요청으로 merchantId를 받지 않는다. FDS_VIEW·FINANCE_VIEW는 요구하지 않는다. 강제 비밀번호 변경 전에는 기존 제한을 유지한다. 정지 가맹점의 발급·보기·재발급도 거절하고 관리자의 사고 대응 폐기는 허용하는 안이다.

키를 반환하는 발급·보기·재발급은 매번 비밀번호를 확인한다. 기존 로그인과 같은 계정별 실패 5회·15분 잠금 기준을 공유하는 안이며, 실패 기록은 별도 커밋한다. 계정 잠금 안에서 현재 세션·계정 상태·최신 비밀번호를 다시 확인한다. 성공 시 실패 횟수 초기화 정책도 로그인과 맞춘다.

키 변경은 merchants 행 잠금으로 직렬화한다. 상태·버전은 잠금 후 재확인하고 충돌은 409로 반환한다. 네트워크 오류가 나면 자동 재발급하지 않고 상태를 재조회한다. 키 응답은 Cache-Control: no-store이며 원문은 컴포넌트 메모리에서만 잠시 보관한다. 화면 이동·닫기·작업 전환·세션 만료 시 지운다.

기존 nonce 저장도 merchants를 잠그므로, 키 검증 후 nonce 소비 사이에 재발급·폐기가 완료된 요청을 방지해야 한다. 검증한 key_version을 nonce 소비 트랜잭션에서 다시 확인하고 불일치 시 거절하는 방향으로 구현한다. 이미 인증을 끝내 업무 실행 중인 요청을 소급 취소하는 의미는 아니다.

C의 연결 확인도 같은 키 버전·가맹점 잠금으로 상태를 바꿔 이전 키의 늦은 확인이 새 키를 CONNECTED로 만들지 않게 한다. 연결 확인 경로·오류 코드 상세는 구현 시 기존 보안 체인과 함께 확정해 ADR에 기록한다.

## 6. ADR·화면·검증 계획

새 ADR에서 ADR-0037의 키 보관 부분을 변경한다고 명시하고 기존 제안 ADR을 대체됨으로 바꾸지 않는다. 환경변수 유지·외부 비밀 저장소·일회 표시·이전 키 유예·PENDING_SETUP 추가를 버린 이유, B/C 경계와 마스터 키 복구·배포 절차를 기록한다.

사장님 화면은 기존 미리보기 배치를 사용해 실제 코드·상태·이력을 붙이고 비밀번호 확인창과 재발급 경고를 추가한다. B에서는 연결 확인 준비 중임을 표시한다. 관리자 상세에는 상태와 폐기 사유 입력·확인창을 추가하고 키를 반환하지 않는다.

검증 대상: 암호화 왕복·변조·다른 행 AAD·키 누락, 가맹점 격리·무권한 OPERATOR·정지/강제변경·CSRF, 비밀번호 실패 잠금과 롤백 독립성, 연동 후 보기 차단, 동시 발급·폐기/재발급·이전/재발급·서명/폐기 경합, 구 키 거절·no-store·이력 비밀 미노출, CAKESHOP 동일 서명 이전·멱등 재기동·이전 실패 차단, 전체 Flyway·JPA validate 및 프론트 검사.

실행 예정: backend `./gradlew spotlessApply check mariaDbTest`, frontend `npm run lint`, `npm run format:check`, `npm test`, `npm run build`.

## 7. 구현과 검증 기록

2026-10-31 사용자 승인 후 전체 기존 migration을 버전 순으로 조회하고 `newMigration`으로 두 파일을 생성했다. 저장소 ERD와 ADR-0052에 승인 범위·실제 구조를 기록했다. 키 암호화 저장·발급·보기·재발급·관리자 폐기·명시적 기동 이전·nonce 소비 시 키 버전 재검사와 실제 화면을 구현했다.

작업 중 검증에서 기존 결제·픽업 HTTP 테스트의 대역 키 조회만으로는 새 DB 버전 검사를 통과하지 못함을 확인했다. 검사를 우회하지 않고 실제 암호화 키·버전 데이터를 테스트에 준비했으며, 해당 결제·픽업·신규 키 HTTP 회귀 테스트 묶음이 통과했다. 기존 환경변수 참조·Base64·길이 검사 테스트는 이전 전용 경계의 테스트로 옮겨 유지한다.

최신 dev 통합 후 최종 명령·테스트 수치·결과는 아래에 추가한다. **브라우저 수동 확인·실제 환경 키 이전/프로세스 기동·마스터 키 백업 복원·운영 배포·Notion 대조/동기화는 미수행**이다. 연결 확인·연동 상태에 따른 신규 결제 차단·새 가맹점 전체 흐름 검증은 PR C 범위다.


### 최신 dev 통합 후 최종 검사

`origin/dev 0b1d75d`(#373)을 통합한 **`58a720d`**에서 실행했다. 이후 애플리케이션·테스트 코드는 바꾸지 않고 이 검증 기록만 추가했다. 프론트는 통합 전후 동일한 소스이며 아래 검사를 통과한 내용 그대로 커밋했다.

- backend `./gradlew spotlessApply check mariaDbTest --console=plain`: BUILD SUCCESSFUL, 4분 3초. 일반 1,172개 중 1,171개 성공·기존 1개 skip, MariaDB 598개 모두 성공, 실패/오류 0. 전체 Flyway/JPA validate·ModularityTests·ArchitectureTests 포함.
- 신규 키 관리 HTTP/MariaDB 14개: 무권한 OPERATOR 발급·암호화·재조회, 재발급/폐기 후 옛 키 거절, CONNECTED 보기 차단, 실패 잠금/해제, 전역 off CSRF, 역할·계정/가맹점 정지·강제 변경, 동시 발급·재발급/폐기 경합, 서명 검증 직후 폐기, 이력 실패 롤백, 입력 버전·사유, 대상 ID 주입, 동일 키 이전·재실행·폐기 보존·이전 실패 롤백을 확인했다.
- 암호화 단위 검사: 변조·다른 가맹점/버전 AAD·마스터 키 누락/형식·다른 활성 마스터 키에서도 저장된 ID 사용. 기존 레거시 참조/키 형식 검사는 이전 전용 경계로 옮겨 32/48/64바이트 원문 보존을 확인했다.
- frontend `npm run lint`, `npm run format:check`, `npm test`, `npm run build`: 모두 종료 코드 0, 테스트 252개 성공. 기존 Fast Refresh 경고 1개·번들 크기 경고 유지. 새 API 4개 테스트는 POST/CSRF/no-store·대상·응답 유실/충돌 시 자동 반복 없음·세션 만료·다른 로그인/취소 뒤 늦은 키 응답 폐기를 확인했다.
- `git diff origin/dev --check`, 설계/ADR의 상대 파일 링크 검사: 통과.

실행 보고서: `backend/build/reports/tests/test/index.html`, `backend/build/reports/tests/mariaDbTest/index.html`. 위 미검증 항목과 PR C 범위는 유지한다. 원격 CI는 PR의 해당 커밋 결과로 별도 확인한다.
