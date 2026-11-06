-- add_security_case_assignment
-- 생성: 2026-11-06 22:52:06 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P3 FDS 사건 담당 자동 배정 (#608, ADR-0082 2절)
-- assignee_staff_id는 P1 platform_users의 논리 참조다(소유 담당이 달라 물리 FK 없음). NULL = 미배정
-- (보안 직원이 없을 때 만든 사건 · 이 마이그레이션 전에 만든 사건). 기존 사건은 ADMIN [미배정 배정]으로 채운다.
-- case_assignment_locks는 배정을 한 줄로 세우는 잠금 행이다. 배정하는 트랜잭션은 이 행을 첫 문장에서
-- SELECT ... FOR UPDATE로 잡은 뒤 직원별 미종결 담당 수를 센다 — 동시에 생긴 사건이 한 직원에게 몰리지 않게.
ALTER TABLE security_cases
    ADD COLUMN assignee_staff_id BIGINT      NULL,
    ADD COLUMN assigned_at       DATETIME(6) NULL;

-- 직원별 미종결 담당 수 · 직원 사건 목록
CREATE INDEX idx_security_cases_assignee_status ON security_cases (assignee_staff_id, status);

CREATE TABLE case_assignment_locks
(
    lock_id   BIGINT      NOT NULL AUTO_INCREMENT,
    lock_name VARCHAR(40) NOT NULL,
    PRIMARY KEY (lock_id),
    CONSTRAINT uk_case_assignment_locks_name UNIQUE (lock_name)
);

INSERT INTO case_assignment_locks (lock_name) VALUES ('FDS_CASE');
