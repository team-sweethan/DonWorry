-- create_raw_view_reviews
-- 생성: 2026-11-07 02:40:52 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P3 #619 (ADR-0082 2절 · 4절): 보안 직원이 사건 결제의 주문자 전화 원문을 볼 때마다 1행 — ADMIN 사후 검토용.
-- 원문(전화번호)은 담지 않는다. 직원이 적은 이의제기 참조 · 가맹점 연락 시도 · 사유는 업무 메모다.
-- 화면에서 고객 이름 · 번호를 쓰지 않도록 안내한다 (접근 기록 staff_access_events에는 자유 글 칸이 없다).
-- 사건은 같은 P3 표라 FK, 결제(P5) · 직원 · ADMIN(P1)은 논리 참조(물리 FK 없음).
-- 처리 ADMIN 이름은 처리 시점 값을 남긴다(보안 직원 목록 SecurityStaffDirectory에 ADMIN이 없음).
-- 조사 기록이라 물리 삭제하지 않는다 (AGENTS.md 4절).
CREATE TABLE raw_view_reviews
(
    review_id                BIGINT       NOT NULL AUTO_INCREMENT,
    case_id                  BIGINT       NOT NULL,
    payment_id               BIGINT       NOT NULL,
    staff_id                 BIGINT       NOT NULL,
    dispute_ref              VARCHAR(100) NOT NULL,
    merchant_contact_attempt VARCHAR(200) NOT NULL,
    reason                   VARCHAR(500) NOT NULL,
    viewed_at                DATETIME(6)  NOT NULL,              -- UTC
    status                   VARCHAR(30)  NOT NULL,              -- PENDING / APPROPRIATE / EXPLANATION_REQUESTED
    reviewed_by_admin_id     BIGINT       NULL,
    reviewed_by_admin_name   VARCHAR(100) NULL,
    reviewed_at              DATETIME(6)  NULL,                  -- UTC
    review_memo              VARCHAR(200) NULL,
    PRIMARY KEY (review_id),
    CONSTRAINT fk_raw_view_reviews_case FOREIGN KEY (case_id) REFERENCES security_cases (case_id),
    CONSTRAINT ck_raw_view_reviews_status CHECK (status IN ('PENDING', 'APPROPRIATE', 'EXPLANATION_REQUESTED'))
);

-- ADMIN 검토 목록 (상태별 최근 순) · 직원별 열람 이력 (내부자 탐지 조각 6a 재료)
CREATE INDEX idx_raw_view_reviews_status_time ON raw_view_reviews (status, viewed_at, review_id);
CREATE INDEX idx_raw_view_reviews_staff_time ON raw_view_reviews (staff_id, viewed_at);
