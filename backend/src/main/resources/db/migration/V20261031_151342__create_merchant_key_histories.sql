-- create_merchant_key_histories
-- 생성: 2026-10-31 15:13:42 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P1 사용자 승인: #354 PR B, 키 원문·암호문을 이력에 넣지 않는다.
CREATE TABLE merchant_key_histories (
 history_id BIGINT NOT NULL AUTO_INCREMENT,
 merchant_id BIGINT NOT NULL,
 actor_user_id BIGINT NULL,
 action VARCHAR(30) NOT NULL,
 key_version BIGINT NOT NULL,
 reason VARCHAR(500) NULL,
 created_at DATETIME(6) NOT NULL,
 PRIMARY KEY (history_id),
 CONSTRAINT fk_merchant_key_histories_merchant FOREIGN KEY (merchant_id) REFERENCES merchants (merchant_id),
 CONSTRAINT fk_merchant_key_histories_actor FOREIGN KEY (actor_user_id) REFERENCES platform_users (user_id),
 CONSTRAINT ck_merchant_key_histories_action CHECK (
 (action = 'MIGRATE' AND actor_user_id IS NULL AND reason IS NULL)
 OR (action IN ('ISSUE','REISSUE') AND actor_user_id IS NOT NULL AND reason IS NULL)
 OR (action = 'REVOKE' AND actor_user_id IS NOT NULL AND reason IS NOT NULL AND CHAR_LENGTH(TRIM(reason)) > 0)),
 INDEX idx_merchant_key_histories_merchant_history (merchant_id, history_id)
);
