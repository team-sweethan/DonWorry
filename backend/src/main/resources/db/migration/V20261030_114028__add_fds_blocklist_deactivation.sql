-- add_fds_blocklist_deactivation
-- 생성: 2026-10-30 11:40:28 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

ALTER TABLE fds_blocklist_entries
    ADD COLUMN deactivation_reason VARCHAR(500) NULL AFTER reason,
    ADD COLUMN deactivated_at DATETIME(6) NULL AFTER active;
