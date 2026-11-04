-- create_payout_refund_allocations
-- 생성: 2026-11-03 17:43:35 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다

-- P6 #493: 원 환불을 어느 후속 지급에서 얼마만큼 회수했는지 보존한다.
CREATE TABLE payout_refund_allocations (
    allocation_id BIGINT NOT NULL AUTO_INCREMENT,
    payout_id BIGINT NOT NULL,
    adjustment_id BIGINT NOT NULL,
    applied_amount BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY(allocation_id),
    CONSTRAINT uk_payout_refund_allocations_pair UNIQUE(payout_id,adjustment_id),
    INDEX idx_payout_refund_allocations_adjustment(adjustment_id),
    CONSTRAINT fk_payout_refund_allocations_payout FOREIGN KEY(payout_id) REFERENCES merchant_payouts(payout_id),
    CONSTRAINT fk_payout_refund_allocations_adjustment FOREIGN KEY(adjustment_id) REFERENCES settlement_refund_adjustments(adjustment_id),
    CONSTRAINT chk_payout_refund_allocations_positive CHECK(payout_id>0 AND adjustment_id>0 AND applied_amount>0)
);
