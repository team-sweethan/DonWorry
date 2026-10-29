-- create_application_mail_deliveries
-- 생성: 2026-10-29 14:02:09 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P1 #315: 담당자 승인, 저장소 ERD·ADR-0043 선행 기록.
CREATE TABLE application_mail_deliveries (
    delivery_id BIGINT NOT NULL AUTO_INCREMENT,
    application_id BIGINT NOT NULL,
    token_id BIGINT NULL,
    kind VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL,
    started_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    failure_code VARCHAR(40) NULL,
    PRIMARY KEY (delivery_id),
    CONSTRAINT fk_application_mail_deliveries_application FOREIGN KEY (application_id) REFERENCES merchant_applications (application_id),
    CONSTRAINT fk_application_mail_deliveries_token FOREIGN KEY (token_id) REFERENCES application_tokens (token_id),
    CONSTRAINT ck_application_mail_deliveries_kind CHECK (kind IN ('EMAIL_VERIFICATION', 'APPROVAL', 'REJECTION')),
    CONSTRAINT ck_application_mail_deliveries_status CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'UNKNOWN')),
    INDEX idx_application_mail_deliveries_application (application_id, started_at, delivery_id)
);
