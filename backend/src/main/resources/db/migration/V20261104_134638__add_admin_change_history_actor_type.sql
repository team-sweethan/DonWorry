-- add_admin_change_history_actor_type
-- 생성: 2026-11-04 13:46:38 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

ALTER TABLE admin_change_histories
    ADD COLUMN actor_type VARCHAR(20) NOT NULL DEFAULT 'STAFF',
    MODIFY COLUMN actor_user_id BIGINT NULL,
    ADD CONSTRAINT ck_admin_change_histories_actor CHECK (
        (actor_type = 'STAFF' AND actor_user_id IS NOT NULL)
        OR (actor_type = 'SYSTEM' AND actor_user_id IS NULL)
    );

