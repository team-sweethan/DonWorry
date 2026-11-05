-- create_operator_access_detection_tables
-- 생성: 2026-11-04 15:24:41 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

CREATE TABLE fds_operator_access_events
(
    operator_access_event_id BIGINT       NOT NULL AUTO_INCREMENT,
    identity_event_id        BIGINT       NOT NULL,
    user_id                  BIGINT       NOT NULL,
    login_id                 VARCHAR(100) NOT NULL,
    merchant_id              BIGINT       NOT NULL,
    http_method              VARCHAR(10)  NOT NULL,
    path                     VARCHAR(512) NOT NULL,
    denied_type              VARCHAR(50)  NOT NULL,
    occurred_at              DATETIME(6)  NOT NULL,
    client_ip                VARCHAR(45)  NULL,
    received_at              DATETIME(6)  NOT NULL,
    created_at               DATETIME(6)  NOT NULL,
    PRIMARY KEY (operator_access_event_id),
    CONSTRAINT uk_fds_operator_access_events_identity_event UNIQUE (identity_event_id),
    INDEX idx_fds_operator_access_events_user_type_occurred (user_id, denied_type, occurred_at),
    INDEX idx_fds_operator_access_events_ip_retention (occurred_at, operator_access_event_id)
);
CREATE TABLE fds_operator_access_detections
(
    operator_access_detection_id BIGINT       NOT NULL AUTO_INCREMENT,
    user_id                      BIGINT       NOT NULL,
    login_id                     VARCHAR(100) NOT NULL,
    merchant_id                  BIGINT       NOT NULL,
    rule_code                    VARCHAR(100) NOT NULL,
    denied_type                  VARCHAR(50)  NOT NULL,
    window_seconds               INT          NOT NULL,
    threshold_count              INT          NOT NULL,
    event_count                  INT          NOT NULL,
    window_started_at            DATETIME(6)  NOT NULL,
    window_ended_at              DATETIME(6)  NOT NULL,
    last_activated_at            DATETIME(6)  NULL,
    action_status                VARCHAR(50)  NOT NULL,
    action_completed_at          DATETIME(6)  NULL,
    failure_code                 VARCHAR(100) NULL,
    detected_at                  DATETIME(6)  NOT NULL,
    created_at                   DATETIME(6)  NOT NULL,
    updated_at                   DATETIME(6)  NOT NULL,
    PRIMARY KEY (operator_access_detection_id),
    INDEX idx_fds_operator_access_detections_detected (detected_at),
    INDEX idx_fds_operator_access_detections_user_detected (user_id, detected_at),
    INDEX idx_fds_operator_access_detections_action_detected (action_status, detected_at)
);
CREATE TABLE fds_operator_access_detection_evidences
(
    operator_access_detection_evidence_id BIGINT      NOT NULL AUTO_INCREMENT,
    detection_id                          BIGINT      NOT NULL,
    operator_access_event_id              BIGINT      NOT NULL,
    identity_event_id                     BIGINT      NOT NULL,
    occurred_at                           DATETIME(6) NOT NULL,
    created_at                            DATETIME(6) NOT NULL,
    PRIMARY KEY (operator_access_detection_evidence_id),
    CONSTRAINT uk_fds_operator_access_detection_evidence UNIQUE (detection_id, operator_access_event_id),
    CONSTRAINT fk_fds_operator_access_detection_evidences_detection
        FOREIGN KEY (detection_id) REFERENCES fds_operator_access_detections (operator_access_detection_id),
    CONSTRAINT fk_fds_operator_access_detection_evidences_event
        FOREIGN KEY (operator_access_event_id) REFERENCES fds_operator_access_events (operator_access_event_id)
);

ALTER TABLE fds_rule_policies
    MODIFY rule_code VARCHAR(100) NOT NULL;

ALTER TABLE fds_rule_change_requests
    MODIFY rule_code VARCHAR(100) NOT NULL;

INSERT INTO fds_rule_policies (rule_code, active, updated_by_user_id, updated_at)
VALUES ('SEC_OPERATOR_OUT_OF_ROLE_REPEAT', TRUE, NULL, UTC_TIMESTAMP(6)),
       ('SEC_OPERATOR_PERMISSION_MISSING_REPEAT', TRUE, NULL, UTC_TIMESTAMP(6));

CREATE TABLE fds_rule_policy_change_histories
(
    rule_policy_change_history_id BIGINT       NOT NULL AUTO_INCREMENT,
    rule_code                     VARCHAR(100) NOT NULL,
    action                        VARCHAR(20)  NOT NULL,
    source                        VARCHAR(50)  NOT NULL,
    requested_by_user_id          BIGINT       NULL,
    requested_by_name             VARCHAR(100) NULL,
    request_reason                VARCHAR(500) NULL,
    changed_by_user_id            BIGINT       NOT NULL,
    changed_by_name               VARCHAR(100) NOT NULL,
    changed_at                    DATETIME(6)  NOT NULL,
    PRIMARY KEY (rule_policy_change_history_id),
    INDEX idx_fds_rule_policy_change_histories_rule_changed (rule_code, changed_at),
    INDEX idx_fds_rule_policy_change_histories_changed (changed_at)
);

