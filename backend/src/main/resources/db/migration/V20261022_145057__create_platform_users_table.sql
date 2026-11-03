-- create_platform_users_table
-- 생성: 2026-10-22 14:50:57 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P1 identity · 기준: Notion 팀페이지/ERD (1차 MVP 일괄 생성, Issue #56)
-- ERD 양식과 다른 점: 테이블명 복수화 (platform_user → platform_users)

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
