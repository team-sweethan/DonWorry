-- create_fds_rule_management_tables
-- 생성: 2026-10-28 15:55:11 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

CREATE TABLE fds_rule_policies
(
    rule_policy_id     BIGINT      NOT NULL AUTO_INCREMENT,
    rule_code          VARCHAR(10) NOT NULL,
    active             BOOLEAN     NOT NULL DEFAULT TRUE,
    updated_by_user_id BIGINT      NULL,
    updated_at         DATETIME(6) NOT NULL,
    PRIMARY KEY (rule_policy_id),
    CONSTRAINT uk_fds_rule_policies_rule_code UNIQUE (rule_code)
);

INSERT INTO fds_rule_policies (rule_code, active, updated_by_user_id, updated_at)
VALUES ('R01', TRUE, NULL, UTC_TIMESTAMP(6)),
       ('R02', TRUE, NULL, UTC_TIMESTAMP(6)),
       ('R03', TRUE, NULL, UTC_TIMESTAMP(6)),
       ('R04', TRUE, NULL, UTC_TIMESTAMP(6)),
       ('R05', TRUE, NULL, UTC_TIMESTAMP(6)),
       ('R06', TRUE, NULL, UTC_TIMESTAMP(6)),
       ('R07', TRUE, NULL, UTC_TIMESTAMP(6)),
       ('R08', TRUE, NULL, UTC_TIMESTAMP(6));

CREATE TABLE fds_rule_change_requests
(
    rule_change_request_id BIGINT      NOT NULL AUTO_INCREMENT,
    rule_code              VARCHAR(10) NOT NULL,
    requested_by_user_id   BIGINT      NOT NULL,
    requested_by_name      VARCHAR(100) NOT NULL,
    reason                 VARCHAR(500) NOT NULL,
    requested_at           DATETIME(6) NOT NULL,
    status                 VARCHAR(20) NOT NULL,
    reviewed_by_user_id    BIGINT      NULL,
    reviewed_by_name       VARCHAR(100) NULL,
    reviewed_at            DATETIME(6) NULL,
    PRIMARY KEY (rule_change_request_id)
);

