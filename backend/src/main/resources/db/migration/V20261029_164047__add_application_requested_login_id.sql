-- add_application_requested_login_id
-- 생성: 2026-10-29 16:40:47 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다


-- P1 #315 PR 3: 신청자가 선택한 아이디를 저장하고 진행 중 신청끼리 예약한다.
-- 기존 신청은 선택한 아이디를 알 수 없으므로 NULL로 보존한다.
ALTER TABLE merchant_applications
    ADD COLUMN requested_login_id VARCHAR(30) COLLATE utf8mb4_bin NULL,
    ADD COLUMN pending_login_id VARCHAR(64) COLLATE utf8mb4_bin GENERATED ALWAYS AS
        (CASE WHEN status IN ('EMAIL_PENDING', 'REVIEW_PENDING') THEN SHA2(requested_login_id, 256) ELSE NULL END) PERSISTENT,
    ADD CONSTRAINT uk_merchant_applications_pending_login UNIQUE (pending_login_id),
    ADD CONSTRAINT ck_merchant_applications_requested_login CHECK
        (requested_login_id IS NULL OR requested_login_id REGEXP '^[a-z0-9_]{3,30}$');
