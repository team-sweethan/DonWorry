-- add_login_attempt_lock
-- 생성: 2026-10-25 16:57:12 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P1 #127: 사용자 승인으로 저장소 ERD·ADR-0029 기준 구현, Notion 후속 반영.
ALTER TABLE platform_users
    ADD COLUMN login_failure_count INT NOT NULL DEFAULT 0,
    ADD COLUMN login_locked_until DATETIME(6) NULL DEFAULT NULL;

