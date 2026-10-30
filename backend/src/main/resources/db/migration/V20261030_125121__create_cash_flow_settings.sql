-- create_cash_flow_settings
-- 생성: 2026-10-30 12:51:21 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다


CREATE TABLE cash_flow_settings (
    setting_id BIGINT NOT NULL AUTO_INCREMENT,
    merchant_id BIGINT NOT NULL,
    opening_balance BIGINT NOT NULL,
    base_date DATE NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (setting_id),
    CONSTRAINT uk_cash_flow_settings_merchant UNIQUE (merchant_id),
    CONSTRAINT chk_cash_flow_settings_balance CHECK (opening_balance >= 0 AND opening_balance <= 9007199254740991),
    CONSTRAINT chk_cash_flow_settings_merchant CHECK (merchant_id > 0)
);
