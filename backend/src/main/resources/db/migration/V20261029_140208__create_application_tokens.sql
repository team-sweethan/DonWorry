-- create_application_tokens
-- 생성: 2026-10-29 14:02:08 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P1 #315: 담당자 승인, 저장소 ERD·ADR-0043 선행 기록.
CREATE TABLE application_tokens (
    token_id BIGINT NOT NULL AUTO_INCREMENT,
    application_id BIGINT NOT NULL,
    purpose VARCHAR(30) NOT NULL,
    user_id BIGINT NULL,
    token_hash VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    used_at DATETIME(6) NULL,
    revoked_at DATETIME(6) NULL,
    PRIMARY KEY (token_id),
    CONSTRAINT uk_application_tokens_hash UNIQUE (token_hash),
    CONSTRAINT fk_application_tokens_application FOREIGN KEY (application_id) REFERENCES merchant_applications (application_id),
    CONSTRAINT fk_application_tokens_user FOREIGN KEY (user_id) REFERENCES platform_users (user_id),
    CONSTRAINT ck_application_tokens_purpose CHECK (
        (purpose = 'EMAIL_VERIFICATION' AND user_id IS NULL)
        OR (purpose = 'INITIAL_PASSWORD_SETUP' AND user_id IS NOT NULL)),
    CONSTRAINT ck_application_tokens_expiry CHECK (expires_at > created_at),
    INDEX idx_application_tokens_application_purpose (application_id, purpose)
);
