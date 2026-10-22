-- create_identity_tables
-- 생성: 2026-10-22 14:30:19 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P1 identity · 기준: Notion 팀페이지/ERD P1 (1차 MVP 일괄 생성, Issue #56)
-- ERD 양식과 다른 점: 테이블명 복수화, user_permissions는 BIGINT 단일 PK + UNIQUE(user_id, permission_code)

-- 가맹점 (시드: CAKESHOP)
CREATE TABLE merchants
(
    merchant_id      BIGINT        NOT NULL AUTO_INCREMENT,
    merchant_code    VARCHAR(40)   NOT NULL,
    display_name     VARCHAR(100)  NOT NULL,
    status           VARCHAR(20)   NOT NULL,              -- ACTIVE / INACTIVE
    pg_fee_rate      DECIMAL(9, 6) NOT NULL,
    webhook_url      VARCHAR(300)  NULL,                  -- CakeShop 결과 수신 URL
    secret_reference VARCHAR(200)  NULL,                  -- HMAC 키 참조, 원문은 환경변수
    created_at       DATETIME(6)   NOT NULL,              -- UTC
    PRIMARY KEY (merchant_id),
    CONSTRAINT uk_merchants_merchant_code UNIQUE (merchant_code)
);

-- 돈워리 계정 (보안 직원 · 가맹점 운영자 · 관리자)
CREATE TABLE platform_users
(
    user_id       BIGINT       NOT NULL AUTO_INCREMENT,
    login_id      VARCHAR(100) NOT NULL,                  -- trim · 소문자 정규화
    password_hash VARCHAR(255) NOT NULL,                  -- BCrypt
    display_name  VARCHAR(100) NOT NULL,
    status        VARCHAR(20)  NOT NULL,                  -- ACTIVE / DISABLED
    role          VARCHAR(30)  NOT NULL,                  -- SECURITY_STAFF / OPERATOR / ADMIN
    merchant_id   BIGINT       NULL,                      -- OPERATOR는 필수, 직원 · 관리자는 NULL
    created_at    DATETIME(6)  NOT NULL,                  -- UTC
    PRIMARY KEY (user_id),
    CONSTRAINT uk_platform_users_login_id UNIQUE (login_id),
    CONSTRAINT fk_platform_users_merchant FOREIGN KEY (merchant_id) REFERENCES merchants (merchant_id)
);

-- 계정별 추가 권한
CREATE TABLE user_permissions
(
    permission_id   BIGINT      NOT NULL AUTO_INCREMENT,
    user_id         BIGINT      NOT NULL,
    permission_code VARCHAR(40) NOT NULL,                 -- FDS_VIEW / FINANCE_VIEW
    PRIMARY KEY (permission_id),
    CONSTRAINT uk_user_permissions_user_code UNIQUE (user_id, permission_code),
    CONSTRAINT fk_user_permissions_user FOREIGN KEY (user_id) REFERENCES platform_users (user_id)
);
