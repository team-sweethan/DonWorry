-- create_payment_sessions
-- 생성: 2026-11-02 14:28:18 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P5 돈워리 호스팅 결제창 (ADR-0064, #402). 가맹점 서버가 결제를 준비하면 세션을 만들고,
-- 구매자는 세션 키로 결제창을 연다. 결제창에서 결제하면 기존 결제 접수(payments)로 이어진다.
-- 상태 변경 알림(웹훅)은 notified_* 컬럼으로 마지막으로 알린 상태를 기억해 다시 보낸다. 발송은 웹훅 PR(②)에서 연결하며,
-- 그 전에는 결제 접수 시 next_notify_at만 기록되고 아무도 보내지 않는다.
-- submitted_*는 처음 결제를 누른 카드·기기·IP다. 접수 뒤 평가·승인 중 실패해도 재시도는 이 값으로 같은 시도를 이어 간다.
-- merchant_id는 P1 소유 merchants의 논리 참조다(물리 FK 없음).
CREATE TABLE payment_sessions (
    session_id BIGINT NOT NULL AUTO_INCREMENT,
    session_key VARCHAR(64) NOT NULL,
    merchant_id BIGINT NOT NULL,
    request_key UUID NOT NULL,
    attempt_key UUID NOT NULL,
    order_ref VARCHAR(100) NOT NULL,
    customer_ref VARCHAR(19) NOT NULL,
    amount BIGINT NOT NULL,
    order_created_at DATETIME(6) NOT NULL,
    order_name VARCHAR(100) NOT NULL,
    buyer_email VARCHAR(254) NULL,
    success_url VARCHAR(500) NOT NULL,
    fail_url VARCHAR(500) NOT NULL,
    notify_url VARCHAR(500) NULL,
    status VARCHAR(20) NOT NULL,
    payment_id BIGINT NULL,
    submitted_card VARCHAR(1) NULL,
    submitted_device_id VARCHAR(100) NULL,
    submitted_ip VARCHAR(45) NULL,
    submitted_at DATETIME(6) NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    notified_status VARCHAR(30) NULL,
    notified_authorized BOOLEAN NULL,
    notify_attempts INT NOT NULL DEFAULT 0,
    next_notify_at DATETIME(6) NULL,
    PRIMARY KEY (session_id),
    CONSTRAINT uk_payment_sessions_session_key UNIQUE (session_key),
    CONSTRAINT uk_payment_sessions_merchant_request UNIQUE (merchant_id, request_key),
    CONSTRAINT uk_payment_sessions_attempt UNIQUE (merchant_id, attempt_key),
    CONSTRAINT uk_payment_sessions_payment UNIQUE (payment_id),
    CONSTRAINT fk_payment_sessions_payment FOREIGN KEY (payment_id) REFERENCES payments (payment_id),
    CONSTRAINT chk_payment_sessions_values CHECK (
        merchant_id > 0 AND amount > 0 AND notify_attempts >= 0 AND expires_at > order_created_at),
    CONSTRAINT chk_payment_sessions_status CHECK (
        (status = 'OPEN' AND payment_id IS NULL) OR (status = 'SUBMITTED' AND payment_id IS NOT NULL)),
    INDEX idx_payment_sessions_next_notify (next_notify_at)
);
