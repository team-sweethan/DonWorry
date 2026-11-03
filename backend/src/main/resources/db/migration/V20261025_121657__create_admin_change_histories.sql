-- create_admin_change_histories
-- 생성: 2026-10-25 12:16:57 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

CREATE TABLE admin_change_histories
(
    history_id         BIGINT      NOT NULL AUTO_INCREMENT,
    actor_user_id      BIGINT      NOT NULL,
    target_user_id     BIGINT      NULL,
    target_merchant_id BIGINT      NULL,
    change_type        VARCHAR(40) NOT NULL,
    before_data        JSON        NULL,
    after_data         JSON        NULL,
    created_at         DATETIME(6) NOT NULL,
    PRIMARY KEY (history_id),
    CONSTRAINT fk_admin_change_histories_actor FOREIGN KEY (actor_user_id) REFERENCES platform_users (user_id),
    CONSTRAINT fk_admin_change_histories_user FOREIGN KEY (target_user_id) REFERENCES platform_users (user_id),
    CONSTRAINT fk_admin_change_histories_merchant FOREIGN KEY (target_merchant_id) REFERENCES merchants (merchant_id),
    CONSTRAINT ck_admin_change_histories_target CHECK (
        (target_user_id IS NOT NULL AND target_merchant_id IS NULL)
        OR (target_user_id IS NULL AND target_merchant_id IS NOT NULL)
    ),
    INDEX idx_admin_change_histories_user_created (target_user_id, created_at),
    INDEX idx_admin_change_histories_merchant_created (target_merchant_id, created_at)
);
