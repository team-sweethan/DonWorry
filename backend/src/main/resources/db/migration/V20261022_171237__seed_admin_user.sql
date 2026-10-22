-- seed_admin_user
-- 생성: 2026-10-22 17:12:37 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- ADMIN 조회 권한은 role로 계산하므로 user_permissions에는 추가하지 않는다 (Issue #70)
INSERT INTO platform_users
    (login_id, password_hash, display_name, status, role, merchant_id, created_at)
VALUES
    ('admin', '$2a$10$unYsZBJkGB8GI44HV21JVeuI8uCbwgcjzFru3Os2P03iO8UTbSHw2',
     'demo_shop', 'ACTIVE', 'ADMIN', NULL, UTC_TIMESTAMP(6));
