-- create_settlement_refund_adjustments
-- 생성: 2026-11-03 15:49:41 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다


-- P6 #478: 수신 사실과 순정산 조정액을 보존한다. 원 매입·정산·지급은 변경하지 않는다.
CREATE TABLE settlement_refund_adjustments (
    adjustment_id BIGINT NOT NULL AUTO_INCREMENT,
    cancellation_id BIGINT NOT NULL,
    payment_id BIGINT NOT NULL,
    capture_id BIGINT NOT NULL,
    settlement_id BIGINT NOT NULL,
    merchant_id BIGINT NOT NULL,
    refund_amount BIGINT NOT NULL,
    card_fee_return BIGINT NOT NULL,
    pg_fee_return BIGINT NOT NULL,
    adjustment_amount BIGINT NOT NULL,
    remaining_amount BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL,
    disposition VARCHAR(30) NULL,
    canceled_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY(adjustment_id),
    CONSTRAINT uk_refund_adjustments_cancellation UNIQUE(cancellation_id),
    CONSTRAINT uk_refund_adjustments_payment UNIQUE(payment_id),
    CONSTRAINT uk_refund_adjustments_settlement UNIQUE(settlement_id),
    INDEX idx_refund_adjustments_pending(status,merchant_id,adjustment_id),
    CONSTRAINT fk_refund_adjustments_settlement FOREIGN KEY(settlement_id) REFERENCES settlements(settlement_id),
    CONSTRAINT fk_refund_adjustments_capture FOREIGN KEY(capture_id) REFERENCES captures(capture_id),
    CONSTRAINT chk_refund_adjustments_ids CHECK(cancellation_id>0 AND payment_id>0 AND merchant_id>0),
    CONSTRAINT chk_refund_adjustments_amounts CHECK(refund_amount>0 AND card_fee_return>=0 AND pg_fee_return>=0
        AND adjustment_amount>=0 AND remaining_amount>=0 AND remaining_amount<=adjustment_amount
        AND CAST(card_fee_return AS DECIMAL(21,0))+pg_fee_return+adjustment_amount=refund_amount),
    CONSTRAINT chk_refund_adjustments_state CHECK(
        (status='PENDING' AND disposition IS NULL AND remaining_amount=adjustment_amount)
        OR (status='APPLIED' AND disposition IS NOT NULL AND
            ((disposition='UNPAID_EXCLUSION' AND remaining_amount=0) OR disposition='RECOVERY')))
);
