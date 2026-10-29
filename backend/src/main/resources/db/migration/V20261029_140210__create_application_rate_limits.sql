-- create_application_rate_limits
-- 생성: 2026-10-29 14:02:10 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P1 #315: 담당자 승인, 저장소 ERD·ADR-0043 선행 기록.
CREATE TABLE application_rate_limits (
    rate_limit_id BIGINT NOT NULL AUTO_INCREMENT,
    scope VARCHAR(20) NOT NULL,
    subject_hash VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    attempt_times VARCHAR(255) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (rate_limit_id),
    CONSTRAINT uk_application_rate_limits_scope_subject UNIQUE (scope, subject_hash),
    INDEX idx_application_rate_limits_expiry (expires_at, rate_limit_id)
);
