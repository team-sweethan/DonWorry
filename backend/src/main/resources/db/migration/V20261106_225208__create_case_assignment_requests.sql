-- create_case_assignment_requests
-- 생성: 2026-11-06 22:52:08 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P3 담당 보안 직원의 담당 변경 요청 (#608, ADR-0082 2절)
-- 대기(PENDING) 중에는 그 사건의 판정만 막고, ADMIN이 승인(새 담당 지정)하거나 거절한다.
-- 사건당 대기 요청은 1건 — pending_case_id(대기일 때만 case_id) UNIQUE로 막는다.
-- 직원 · ADMIN ID는 P1 platform_users의 논리 참조(물리 FK 없음). 사건은 같은 P3 표라 FK를 건다.
-- 처리 ADMIN 이름은 처리 시점 값을 남긴다(보안 직원 목록 SecurityStaffDirectory에 ADMIN이 없음).
-- memo · review_memo는 업무 메모다. 화면에서 개인정보를 쓰지 않도록 안내한다.
-- 조사 기록이라 물리 삭제하지 않는다 (AGENTS.md 4절).
CREATE TABLE case_assignment_requests
(
    request_id             BIGINT       NOT NULL AUTO_INCREMENT,
    case_id                BIGINT       NOT NULL,
    requested_by_staff_id  BIGINT       NOT NULL,
    reason                 VARCHAR(30)  NOT NULL,              -- CONFLICT_OF_INTEREST / OTHER
    memo                   VARCHAR(200) NULL,                  -- 사유가 OTHER면 필수 (서비스 검사)
    status                 VARCHAR(20)  NOT NULL,
    requested_at           DATETIME(6)  NOT NULL,              -- UTC
    reviewed_by_admin_id   BIGINT       NULL,
    reviewed_by_admin_name VARCHAR(100) NULL,
    reviewed_at            DATETIME(6)  NULL,                  -- UTC
    review_memo            VARCHAR(200) NULL,                  -- 거절이면 필수 (서비스 검사)
    new_assignee_staff_id  BIGINT       NULL,                  -- 승인일 때 새 담당
    pending_case_id        BIGINT GENERATED ALWAYS AS (CASE WHEN status = 'PENDING' THEN case_id ELSE NULL END) PERSISTENT,
    PRIMARY KEY (request_id),
    CONSTRAINT uk_case_assignment_requests_pending UNIQUE (pending_case_id),
    CONSTRAINT fk_case_assignment_requests_case FOREIGN KEY (case_id) REFERENCES security_cases (case_id),
    CONSTRAINT ck_case_assignment_requests_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'))
);

-- ADMIN 요청 목록 (상태별 최근 순) · 사건별 대기 요청 확인
CREATE INDEX idx_case_assignment_requests_status_time ON case_assignment_requests (status, requested_at, request_id);
CREATE INDEX idx_case_assignment_requests_case_status ON case_assignment_requests (case_id, status);
