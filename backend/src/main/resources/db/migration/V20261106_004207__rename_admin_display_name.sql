-- rename_admin_display_name
-- 생성: 2026-11-06 00:42:07 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- 시드 관리자 계정의 표시 이름만 바꾼다. 비밀번호·로그인 ID는 건드리지 않는다
-- 서버에서 이름을 이미 바꿨다면 덮어쓰지 않는다
UPDATE platform_users
SET display_name = '관리자'
WHERE login_id = 'admin'
  AND role = 'ADMIN'
  AND display_name = 'demo_shop';
