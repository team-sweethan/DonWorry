-- create_merchant_applications
-- 생성: 2026-10-29 14:02:00 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P1 #315: 담당자 승인, 저장소 ERD·ADR-0043 선행 기록.
CREATE TABLE merchant_applications (
    application_id BIGINT NOT NULL AUTO_INCREMENT,
    merchant_name VARCHAR(100) NOT NULL,
    business_registration_number VARCHAR(10) COLLATE utf8mb4_bin NOT NULL,
    contact_name VARCHAR(100) NOT NULL,
    email VARCHAR(254) COLLATE utf8mb4_bin NOT NULL,
    phone VARCHAR(15) NOT NULL,
    inquiry VARCHAR(1000) NULL,
    consent_version VARCHAR(20) NOT NULL,
    consented_at DATETIME(6) NOT NULL,
    status VARCHAR(30) NOT NULL,
    email_verified_at DATETIME(6) NULL,
    reviewed_at DATETIME(6) NULL,
    reviewer_user_id BIGINT NULL,
    rejection_reason VARCHAR(1000) NULL,
    merchant_id BIGINT NULL,
    user_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    pending_email VARCHAR(64) COLLATE utf8mb4_bin GENERATED ALWAYS AS
        (CASE WHEN status IN ('EMAIL_PENDING', 'REVIEW_PENDING') THEN SHA2(email, 256) ELSE NULL END) PERSISTENT,
    pending_business_number VARCHAR(64) COLLATE utf8mb4_bin GENERATED ALWAYS AS
        (CASE WHEN status IN ('EMAIL_PENDING', 'REVIEW_PENDING') THEN SHA2(business_registration_number, 256) ELSE NULL END) PERSISTENT,
    PRIMARY KEY (application_id),
    CONSTRAINT uk_merchant_applications_pending_email UNIQUE (pending_email),
    CONSTRAINT uk_merchant_applications_pending_business UNIQUE (pending_business_number),
    CONSTRAINT fk_merchant_applications_reviewer FOREIGN KEY (reviewer_user_id) REFERENCES platform_users (user_id),
    CONSTRAINT fk_merchant_applications_merchant FOREIGN KEY (merchant_id) REFERENCES merchants (merchant_id),
    CONSTRAINT fk_merchant_applications_user FOREIGN KEY (user_id) REFERENCES platform_users (user_id),
    CONSTRAINT ck_merchant_applications_status CHECK (status IN ('EMAIL_PENDING', 'REVIEW_PENDING', 'APPROVED', 'REJECTED')),
    INDEX idx_merchant_applications_status_created (status, created_at, application_id)
);
