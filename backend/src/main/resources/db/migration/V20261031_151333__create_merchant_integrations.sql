-- create_merchant_integrations
-- 생성: 2026-10-31 15:13:33 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P1 사용자 승인: #354 PR B, docs/identity/354-pr-b-key-design.md
CREATE TABLE merchant_integrations (
 integration_id BIGINT NOT NULL AUTO_INCREMENT,
 merchant_id BIGINT NOT NULL,
 status VARCHAR(30) NOT NULL,
 encrypted_secret VARBINARY(1024) NULL,
 encryption_nonce VARBINARY(12) NULL,
 master_key_id VARCHAR(40) NULL,
 key_version BIGINT NOT NULL,
 key_issued_at DATETIME(6) NULL,
 connected_at DATETIME(6) NULL,
 updated_at DATETIME(6) NOT NULL,
 PRIMARY KEY (integration_id),
 CONSTRAINT uk_merchant_integrations_merchant UNIQUE (merchant_id),
 CONSTRAINT fk_merchant_integrations_merchant FOREIGN KEY (merchant_id) REFERENCES merchants (merchant_id),
 CONSTRAINT ck_merchant_integrations_version CHECK (key_version > 0),
 CONSTRAINT ck_merchant_integrations_state CHECK (
 (status = 'NO_KEY' AND encrypted_secret IS NULL AND encryption_nonce IS NULL AND master_key_id IS NULL AND key_issued_at IS NULL AND connected_at IS NULL)
 OR (status IN ('PENDING_CONNECTION','CONNECTED') AND encrypted_secret IS NOT NULL AND OCTET_LENGTH(encryption_nonce) = 12 AND encryption_nonce IS NOT NULL AND master_key_id IS NOT NULL AND key_issued_at IS NOT NULL
 AND ((status = 'PENDING_CONNECTION' AND connected_at IS NULL) OR (status = 'CONNECTED' AND connected_at IS NOT NULL))))
);
