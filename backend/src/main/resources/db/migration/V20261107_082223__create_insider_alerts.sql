-- create_insider_alerts
-- 생성: 2026-11-07 08:22:23 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P2 내부자 경보 (#516, ADR-0082): 보안 직원 접근 기록(P1 staff_access_events)을 규칙으로 묶어 만든 경보.
-- 사람 · 규칙당 열린 경보는 1개 — open_key(열려 있을 때만 "직원:규칙") UNIQUE로 막는다. 같은 규칙이 다시 걸리면 같은 행을 고친다.
-- 직원 · ADMIN(P1 platform_users)과 접근 기록(P1 staff_access_events)은 논리 참조(물리 FK 없음). 원문(이름 · 전화) · IP 칸은 두지 않는다.
-- 등급은 지금 규칙이 내는 CAUTION · HIGH만 허용한다. CRITICAL은 그것을 내는 조각(7b)이 자기 migration으로 더한다.
-- rule_code는 조각마다 규칙이 늘어(6b) CHECK를 두지 않는다.
CREATE TABLE insider_alerts
(
    alert_id             BIGINT       NOT NULL AUTO_INCREMENT,
    staff_id             BIGINT       NOT NULL,
    rule_code            VARCHAR(20)  NOT NULL,
    severity             VARCHAR(20)  NOT NULL,
    status               VARCHAR(20)  NOT NULL,
    summary              VARCHAR(300) NOT NULL,
    first_event_at       DATETIME(6)  NOT NULL,
    last_event_at        DATETIME(6)  NOT NULL,
    created_at           DATETIME(6)  NOT NULL,
    updated_at           DATETIME(6)  NOT NULL,
    closed_by_admin_id   BIGINT       NULL,
    closed_by_admin_name VARCHAR(100) NULL,
    closed_at            DATETIME(6)  NULL,
    close_memo           VARCHAR(200) NULL,
    open_key             VARCHAR(60) GENERATED ALWAYS AS
        (CASE WHEN status = 'OPEN' THEN CONCAT(staff_id, ':', rule_code) ELSE NULL END) PERSISTENT,
    PRIMARY KEY (alert_id),
    CONSTRAINT uk_insider_alerts_open UNIQUE (open_key),
    CONSTRAINT ck_insider_alerts_severity CHECK (severity IN ('CAUTION', 'HIGH')),
    CONSTRAINT ck_insider_alerts_status CHECK (status IN ('OPEN', 'CLOSED')),
    CONSTRAINT ck_insider_alerts_closed CHECK (
        (status = 'OPEN' AND closed_at IS NULL AND closed_by_admin_id IS NULL)
        OR (status = 'CLOSED' AND closed_at IS NOT NULL AND closed_by_admin_id IS NOT NULL)
    ),
    INDEX idx_insider_alerts_status_time (status, updated_at, alert_id),
    INDEX idx_insider_alerts_staff_rule (staff_id, rule_code, status)
);

-- 경보의 근거가 된 접근 기록 번호. 같은 기록이 두 번 와도 한 줄만 남는다(멱등)
CREATE TABLE insider_alert_evidences
(
    evidence_id     BIGINT      NOT NULL AUTO_INCREMENT,
    alert_id        BIGINT      NOT NULL,
    access_event_id BIGINT      NOT NULL,
    created_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (evidence_id),
    CONSTRAINT uk_insider_alert_evidences UNIQUE (alert_id, access_event_id),
    CONSTRAINT fk_insider_alert_evidences_alert FOREIGN KEY (alert_id) REFERENCES insider_alerts (alert_id)
);

-- 직원별 탐지 줄 세우기 — 같은 직원의 접근 기록은 이 행을 배타 잠금(INSERT … ON DUPLICATE KEY UPDATE)으로 잡고 하나씩 검사한다.
-- 서버가 여러 대여도 같은 직원의 집계가 겹치지 않게 메모리 잠금 대신 DB 행을 쓴다
CREATE TABLE insider_detection_states
(
    staff_id   BIGINT      NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (staff_id)
);
