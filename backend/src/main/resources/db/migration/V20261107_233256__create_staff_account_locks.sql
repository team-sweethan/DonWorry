-- create_staff_account_locks
-- 생성: 2026-11-07 23:32:56 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P1 보안 직원 계정 보안 정지 기록 (#647, ADR-0082 · ADR-0084).
-- 정지하면 platform_users.status를 SUSPENDED로 바꾸고, 왜 · 누가 정지했고 누가 어느 판정으로 풀었는지는 이 표에 남긴다.
-- 사람당 열린 정지는 1개 — open_key(열려 있을 때만 staff_id) UNIQUE로 막는다. 원문(이름 · 전화번호) 칸은 없다.
-- 정지 주체: 시스템 자동 정지는 locked_by_type = 'SYSTEM' + locked_by NULL, 관리자 정지는 'STAFF' + 관리자 번호 (admin_change_histories.actor_type과 같은 방식).
-- 해제 결과: RESTORE = 사용 중으로 복귀, DEACTIVATE = 비활성(퇴사 · 징계).
-- reason_code · release_decision은 조각마다 값이 늘어 CHECK를 두지 않는다.
-- staff_id · locked_by · released_by는 같은 P1 표라 FK를 건다. source_alert_id(P2 insider_alerts) · release_case_id(P3 내부자 사건)는 논리 참조.
CREATE TABLE staff_account_locks
(
    lock_id          BIGINT      NOT NULL AUTO_INCREMENT,
    staff_id         BIGINT      NOT NULL,
    reason_code      VARCHAR(40) NOT NULL,
    source_alert_id  BIGINT      NULL,
    locked_by_type   VARCHAR(20) NOT NULL,
    locked_by        BIGINT      NULL,
    status           VARCHAR(20) NOT NULL,
    locked_at        DATETIME(6) NOT NULL,
    released_at      DATETIME(6) NULL,
    released_by      BIGINT      NULL,
    release_case_id  BIGINT      NULL,
    release_decision VARCHAR(40) NULL,
    release_outcome  VARCHAR(20) NULL,
    open_key         BIGINT GENERATED ALWAYS AS
        (CASE WHEN status = 'OPEN' THEN staff_id ELSE NULL END) PERSISTENT,
    PRIMARY KEY (lock_id),
    CONSTRAINT uk_staff_account_locks_open UNIQUE (open_key),
    CONSTRAINT fk_staff_account_locks_staff FOREIGN KEY (staff_id) REFERENCES platform_users (user_id),
    CONSTRAINT fk_staff_account_locks_locked_by FOREIGN KEY (locked_by) REFERENCES platform_users (user_id),
    CONSTRAINT fk_staff_account_locks_released_by FOREIGN KEY (released_by) REFERENCES platform_users (user_id),
    CONSTRAINT ck_staff_account_locks_locked_by CHECK (
        (locked_by_type = 'STAFF' AND locked_by IS NOT NULL)
        OR (locked_by_type = 'SYSTEM' AND locked_by IS NULL)
    ),
    CONSTRAINT ck_staff_account_locks_status CHECK (status IN ('OPEN', 'RELEASED')),
    CONSTRAINT ck_staff_account_locks_released CHECK (
        (status = 'OPEN' AND released_at IS NULL AND released_by IS NULL AND release_outcome IS NULL)
        OR (status = 'RELEASED' AND released_at IS NOT NULL AND released_by IS NOT NULL
            AND release_outcome IN ('RESTORE', 'DEACTIVATE'))
    ),
    INDEX idx_staff_account_locks_staff_time (staff_id, locked_at)
);

