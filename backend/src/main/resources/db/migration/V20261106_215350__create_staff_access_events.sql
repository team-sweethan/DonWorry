-- create_staff_access_events
-- 생성: 2026-11-06 21:53:50 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P1 #604 (ADR-0082): 보안 직원 · ADMIN의 고객정보 접근 기록. 허용(ALLOWED)과 거부(DENIED)를 모두 남긴다.
-- 사용자 승인으로 저장소 ERD · ADR 선행 반영, Notion 후속.
-- 이름 · 전화 같은 원문은 담지 않는다. 자유 글 칸이 없고 field_type은 칸 종류 코드, customer_ref는 숫자 고객 번호만이다.
-- staff_id · merchant_id는 같은 P1 표라 FK를 건다. case_id(P3) · payment_id(P5)는 논리 참조다.
-- login_reference는 login_histories.login_reference와 같은 로그인 번호(값만 저장, FK 없음). IP는 복사하지 않는다.
CREATE TABLE staff_access_events
(
    event_id        BIGINT       NOT NULL AUTO_INCREMENT,
    staff_id        BIGINT       NOT NULL,
    actor_role      VARCHAR(20)  NOT NULL,
    access_type     VARCHAR(40)  NOT NULL,
    result          VARCHAR(10)  NOT NULL,
    deny_reason     VARCHAR(40)  NULL,
    case_id         BIGINT       NULL,
    merchant_id     BIGINT       NULL,
    payment_id      BIGINT       NULL,
    customer_ref    VARCHAR(100) NULL,
    field_type      VARCHAR(30)  NULL,
    item_count      INT          NULL,
    login_reference UUID         NULL,
    occurred_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (event_id),
    CONSTRAINT fk_staff_access_events_staff FOREIGN KEY (staff_id) REFERENCES platform_users (user_id),
    CONSTRAINT fk_staff_access_events_merchant FOREIGN KEY (merchant_id) REFERENCES merchants (merchant_id),
    CONSTRAINT ck_staff_access_events_role CHECK (actor_role IN ('SECURITY_STAFF', 'ADMIN')),
    CONSTRAINT ck_staff_access_events_result CHECK (
        (result = 'ALLOWED' AND deny_reason IS NULL)
        OR (result = 'DENIED' AND deny_reason IS NOT NULL)
    ),
    CONSTRAINT ck_staff_access_events_item_count CHECK (item_count IS NULL OR item_count >= 0),
    INDEX idx_staff_access_events_staff_time (staff_id, occurred_at),
    INDEX idx_staff_access_events_staff_result_time (staff_id, result, occurred_at)
);
