-- create_login_histories
-- 생성: 2026-10-25 20:15:59 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P1 #147: 사용자 승인으로 저장소 ERD·ADR-0033 우선 반영, Notion 후속.
CREATE TABLE login_histories
(
    history_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NULL,
    event_type VARCHAR(20) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    client_ip VARCHAR(45) NULL,
    user_agent VARCHAR(512) NULL,
    login_reference UUID NULL,
    logout_scope VARCHAR(10) NULL,
    PRIMARY KEY (history_id),
    CONSTRAINT fk_login_histories_user FOREIGN KEY (user_id) REFERENCES platform_users (user_id),
    CONSTRAINT uk_login_histories_reference_event UNIQUE (login_reference, event_type),
    CONSTRAINT ck_login_histories_event CHECK (
        (event_type = 'LOGIN_FAILURE' AND login_reference IS NULL AND logout_scope IS NULL)
        OR (event_type = 'LOGIN_SUCCESS' AND user_id IS NOT NULL AND login_reference IS NOT NULL AND logout_scope IS NULL)
        OR (event_type = 'LOGOUT' AND user_id IS NOT NULL AND login_reference IS NOT NULL
            AND logout_scope IS NOT NULL AND logout_scope IN ('CURRENT', 'ALL'))
    ),
    INDEX idx_login_histories_user_time (user_id, occurred_at, history_id),
    INDEX idx_login_histories_user_event_time (user_id, event_type, occurred_at, history_id),
    INDEX idx_login_histories_retention (occurred_at, history_id)
);
