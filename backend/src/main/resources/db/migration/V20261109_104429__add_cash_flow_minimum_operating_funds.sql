ALTER TABLE cash_flow_settings
    ADD COLUMN minimum_operating_funds BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_cash_flow_minimum_operating_funds
        CHECK (minimum_operating_funds BETWEEN 0 AND 9007199254740991);
