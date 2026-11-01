-- rename_application_email_verification_status
-- 생성: 2026-11-01 16:08:44 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- 모든 구버전 앱·예약 작업을 중단한 점검 배포에서 실행한다 (docs/identity/131-status-transition.md).
-- 양쪽 이름을 예약 대상으로 먼저 포함해 UPDATE 중에도 기존 UNIQUE 3개를 유지한다.
ALTER TABLE merchant_applications
    DROP CONSTRAINT ck_merchant_applications_status,
    ADD CONSTRAINT ck_merchant_applications_status CHECK
        (status IN ('EMAIL_PENDING', 'EMAIL_VERIFICATION_PENDING', 'REVIEW_PENDING', 'APPROVED', 'REJECTED')),
    MODIFY COLUMN pending_email VARCHAR(64) COLLATE utf8mb4_bin GENERATED ALWAYS AS
        (CASE WHEN status IN ('EMAIL_PENDING', 'EMAIL_VERIFICATION_PENDING', 'REVIEW_PENDING') THEN SHA2(email, 256) ELSE NULL END) PERSISTENT,
    MODIFY COLUMN pending_business_number VARCHAR(64) COLLATE utf8mb4_bin GENERATED ALWAYS AS
        (CASE WHEN status IN ('EMAIL_PENDING', 'EMAIL_VERIFICATION_PENDING', 'REVIEW_PENDING') THEN SHA2(business_registration_number, 256) ELSE NULL END) PERSISTENT,
    MODIFY COLUMN pending_login_id VARCHAR(64) COLLATE utf8mb4_bin GENERATED ALWAYS AS
        (CASE WHEN status IN ('EMAIL_PENDING', 'EMAIL_VERIFICATION_PENDING', 'REVIEW_PENDING') THEN SHA2(requested_login_id, 256) ELSE NULL END) PERSISTENT;

UPDATE merchant_applications SET status = 'EMAIL_VERIFICATION_PENDING' WHERE status = 'EMAIL_PENDING';

-- 데이터 변환 후에는 옛 이름을 거절한다. UNIQUE 인덱스는 제거하지 않는다.
ALTER TABLE merchant_applications
    DROP CONSTRAINT ck_merchant_applications_status,
    ADD CONSTRAINT ck_merchant_applications_status CHECK
        (status IN ('EMAIL_VERIFICATION_PENDING', 'REVIEW_PENDING', 'APPROVED', 'REJECTED')),
    MODIFY COLUMN pending_email VARCHAR(64) COLLATE utf8mb4_bin GENERATED ALWAYS AS
        (CASE WHEN status IN ('EMAIL_VERIFICATION_PENDING', 'REVIEW_PENDING') THEN SHA2(email, 256) ELSE NULL END) PERSISTENT,
    MODIFY COLUMN pending_business_number VARCHAR(64) COLLATE utf8mb4_bin GENERATED ALWAYS AS
        (CASE WHEN status IN ('EMAIL_VERIFICATION_PENDING', 'REVIEW_PENDING') THEN SHA2(business_registration_number, 256) ELSE NULL END) PERSISTENT,
    MODIFY COLUMN pending_login_id VARCHAR(64) COLLATE utf8mb4_bin GENERATED ALWAYS AS
        (CASE WHEN status IN ('EMAIL_VERIFICATION_PENDING', 'REVIEW_PENDING') THEN SHA2(requested_login_id, 256) ELSE NULL END) PERSISTENT;
