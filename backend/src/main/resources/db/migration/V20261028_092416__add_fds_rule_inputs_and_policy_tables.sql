-- add_fds_rule_inputs_and_policy_tables
-- 생성: 2026-10-28 09:24:16 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P2 FDS v0.2 룰 입력 스냅샷과 P2 소유 공통 정책 목록
ALTER TABLE fds_evaluations
    ADD COLUMN device_id VARCHAR(100) NULL AFTER customer_ref,
    ADD COLUMN card_token VARCHAR(255) NULL AFTER device_id,
    ADD COLUMN ip_country_code CHAR(2) NULL AFTER card_token,
    ADD COLUMN authorization_result VARCHAR(20) NULL AFTER result,
    ADD INDEX idx_fds_evaluations_merchant_customer_authorization (merchant_id, customer_ref, authorization_result),
    ADD INDEX idx_fds_evaluations_merchant_customer_device (merchant_id, customer_ref, device_id),
    ADD INDEX idx_fds_evaluations_merchant_customer_card (merchant_id, customer_ref, card_token);

CREATE TABLE fds_high_risk_countries
(
    country_id BIGINT      NOT NULL AUTO_INCREMENT,
    country_code CHAR(2)   NOT NULL,
    active     BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (country_id),
    CONSTRAINT uk_fds_high_risk_countries_code UNIQUE (country_code)
);

CREATE TABLE fds_blocklist_entries
(
    blocklist_entry_id BIGINT       NOT NULL AUTO_INCREMENT,
    identifier_type    VARCHAR(30)  NOT NULL,
    identifier_value   VARCHAR(255) NOT NULL,
    reason             VARCHAR(500) NOT NULL,
    active             BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at         DATETIME(6)  NOT NULL,
    expires_at         DATETIME(6)  NULL,
    PRIMARY KEY (blocklist_entry_id),
    CONSTRAINT uk_fds_blocklist_entries_identifier UNIQUE (identifier_type, identifier_value),
    INDEX idx_fds_blocklist_entries_active_expiry (active, expires_at)
);
