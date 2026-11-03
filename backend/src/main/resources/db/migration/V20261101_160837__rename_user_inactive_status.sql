-- rename_user_inactive_status
-- 생성: 2026-11-01 16:08:37 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- 계정 상태 이름만 바꾼다. 시연/활성 계정과 과거 감사 JSON·유형은 보존한다.
UPDATE platform_users SET status = 'INACTIVE' WHERE status = 'DISABLED';
