-- create_operator_access_denials
-- 생성: 2026-11-04 15:33:46 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P1 #525: 사용자 승인으로 저장소 ERD·ADR-0080 선행 반영, Notion 후속.
CREATE TABLE operator_access_denials
(
    denial_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    merchant_id BIGINT NOT NULL,
    http_method VARCHAR(10) NOT NULL,
    path VARCHAR(512) NOT NULL,
    denied_type VARCHAR(30) NOT NULL,
    client_ip VARCHAR(45) NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (denial_id),
    CONSTRAINT fk_operator_access_denials_user FOREIGN KEY (user_id) REFERENCES platform_users (user_id),
    CONSTRAINT fk_operator_access_denials_merchant FOREIGN KEY (merchant_id) REFERENCES merchants (merchant_id),
    CONSTRAINT ck_operator_access_denials_type CHECK (denied_type IN ('OUT_OF_ROLE', 'PERMISSION_MISSING')),
    INDEX idx_operator_access_denials_user_time (user_id, occurred_at)
);
