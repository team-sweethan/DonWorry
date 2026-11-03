-- create_merchant_request_nonces
-- 생성: 2026-10-27 14:49:24 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P1 #219: 사용자 승인으로 저장소 ERD·ADR 선행 반영, Notion 후속.
CREATE TABLE merchant_request_nonces
(
    request_nonce_id BIGINT NOT NULL AUTO_INCREMENT,
    merchant_id BIGINT NOT NULL,
    nonce UUID NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (request_nonce_id),
    CONSTRAINT uk_merchant_request_nonces_merchant_nonce UNIQUE (merchant_id, nonce),
    CONSTRAINT fk_merchant_request_nonces_merchant FOREIGN KEY (merchant_id) REFERENCES merchants (merchant_id),
    INDEX idx_merchant_request_nonces_expiry (expires_at, request_nonce_id)
);
