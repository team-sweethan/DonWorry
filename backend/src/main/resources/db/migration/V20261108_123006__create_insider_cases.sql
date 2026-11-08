-- create_insider_cases
-- 생성: 2026-11-08 12:30:06 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P3 내부자 사건 (#670, ADR-0082 조각 8). 높음 이상 내부자 경보를 직원 한 명당 열린 사건 하나로 묶고 ADMIN이 판정한다.
-- 사람당 열린 사건은 1개 — open_staff_id(열려 있을 때만 staff_id) UNIQUE로 막는다. 사건 열기 · 판정은 FDS 사건 배정과 같은
-- 잠금 행(case_assignment_locks 'FDS_CASE')으로 한 줄로 세운다 — 사건이 열리는 순간 그 직원에게 새 FDS 사건이 배정되지 않게.
-- 판정: NORMAL_DUTY(정상 업무 — 정지 해제) · CAUTION_NOTICE(주의 조치 — 정지 해제 + 경고 기록) · DISCIPLINE_REQUEST(징계 요청 — 정지 유지).
-- 징계로 닫힌 사건만 나중에 사유를 적고 정지를 풀 수 있다(lock_release_*). 결과 RESTORE = 사용 중 복귀, DEACTIVATE = 비활성.
-- 직원 · ADMIN(P1 platform_users)은 논리 참조(물리 FK 없음). 원문(이름 · 전화번호) 칸은 없다.
-- decision_memo · lock_release_reason은 화면에서 "개인정보를 쓰지 마세요"로 안내하는 업무 메모다.
CREATE TABLE insider_cases
(
    insider_case_id             BIGINT       NOT NULL AUTO_INCREMENT,
    staff_id                    BIGINT       NOT NULL,
    status                      VARCHAR(20)  NOT NULL,
    severity                    VARCHAR(20)  NOT NULL,
    opened_at                   DATETIME(6)  NOT NULL,
    updated_at                  DATETIME(6)  NOT NULL,
    decision                    VARCHAR(30)  NULL,
    decision_memo               VARCHAR(200) NULL,
    decided_by_admin_id         BIGINT       NULL,
    decided_by_admin_name       VARCHAR(100) NULL,
    decided_at                  DATETIME(6)  NULL,
    lock_released_at            DATETIME(6)  NULL,
    lock_released_by_admin_id   BIGINT       NULL,
    lock_released_by_admin_name VARCHAR(100) NULL,
    lock_release_reason         VARCHAR(200) NULL,
    lock_release_outcome        VARCHAR(20)  NULL,
    open_staff_id               BIGINT GENERATED ALWAYS AS
        (CASE WHEN status = 'OPEN' THEN staff_id ELSE NULL END) PERSISTENT,
    PRIMARY KEY (insider_case_id),
    CONSTRAINT uk_insider_cases_open UNIQUE (open_staff_id),
    CONSTRAINT ck_insider_cases_status CHECK (status IN ('OPEN', 'CLOSED')),
    CONSTRAINT ck_insider_cases_severity CHECK (severity IN ('HIGH', 'CRITICAL')),
    CONSTRAINT ck_insider_cases_decided CHECK (
        (status = 'OPEN' AND decision IS NULL AND decided_by_admin_id IS NULL AND decided_at IS NULL)
        OR (status = 'CLOSED' AND decided_by_admin_id IS NOT NULL AND decided_at IS NOT NULL
            AND decision IN ('NORMAL_DUTY', 'CAUTION_NOTICE', 'DISCIPLINE_REQUEST'))
    ),
    CONSTRAINT ck_insider_cases_lock_release CHECK (
        (lock_released_at IS NULL AND lock_released_by_admin_id IS NULL
            AND lock_release_reason IS NULL AND lock_release_outcome IS NULL)
        OR (decision = 'DISCIPLINE_REQUEST' AND lock_released_at IS NOT NULL
            AND lock_released_by_admin_id IS NOT NULL AND lock_release_reason IS NOT NULL
            AND lock_release_outcome IN ('RESTORE', 'DEACTIVATE'))
    ),
    INDEX idx_insider_cases_status_time (status, updated_at, insider_case_id),
    INDEX idx_insider_cases_staff (staff_id, opened_at)
);

-- 사건에 묶인 경보 한 줄. 같은 경보가 다시 와도(재전달 · 높음 → 위험) 한 줄만 남는다(alert_id PK).
-- alert_id는 P2 insider_alerts 논리 참조. 규칙 · 등급 · 요약은 화면용 사본이고 원문 · IP는 없다.
CREATE TABLE insider_case_alerts
(
    alert_id        BIGINT       NOT NULL,
    insider_case_id BIGINT       NOT NULL,
    rule_code       VARCHAR(20)  NOT NULL,
    severity        VARCHAR(20)  NOT NULL,
    summary         VARCHAR(300) NOT NULL,
    raised_at       DATETIME(6)  NOT NULL,
    linked_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (alert_id),
    CONSTRAINT fk_insider_case_alerts_case FOREIGN KEY (insider_case_id) REFERENCES insider_cases (insider_case_id),
    CONSTRAINT ck_insider_case_alerts_severity CHECK (severity IN ('HIGH', 'CRITICAL')),
    INDEX idx_insider_case_alerts_case (insider_case_id, linked_at)
);
