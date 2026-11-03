-- create_merchant_daily_payouts
-- 생성: 2026-11-03 14:40:56 KST
--
-- 규칙
-- * dev에 병합된 뒤에는 이 파일을 고치지 않는다. 바꿀 것이 있으면 새 migration을 만든다
-- * 다른 담당 테이블로 물리 FK를 걸지 않는다 (AGENTS.md 4절)
-- * 한 migration은 하나의 배포 가능한 스키마 전환만 담는다


-- P6 #474. 기존 완료 기록은 바꾸지 않고 한국 완료일별 보존 원장을 구성한다.
-- 쓰기를 중지하고 적용한다. 잘못된 이력/합계는 영구 테이블 생성 전에 거절한다.
CREATE TEMPORARY TABLE p6_payout_history_guards (
    valid INT NOT NULL CHECK (valid = 1)
);
INSERT INTO p6_payout_history_guards(valid)
SELECT 0 FROM settlements
WHERE status='COMPLETED' AND (completed_at IS NULL OR merchant_id<=0 OR settlement_amount<0
    OR DATE(completed_at + INTERVAL 9 HOUR) IS NULL)
LIMIT 1;
INSERT INTO p6_payout_history_guards(valid)
SELECT 0 FROM (
    SELECT merchant_id, DATE(completed_at + INTERVAL 9 HOUR) AS payout_date, SUM(settlement_amount) AS total
    FROM settlements WHERE status='COMPLETED'
    GROUP BY merchant_id, DATE(completed_at + INTERVAL 9 HOUR)
) history WHERE history.total > 9223372036854775807 LIMIT 1;
DROP TEMPORARY TABLE p6_payout_history_guards;

CREATE TABLE merchant_payouts (
    payout_id BIGINT NOT NULL AUTO_INCREMENT,
    merchant_id BIGINT NOT NULL,
    payout_date DATE NOT NULL,
    status VARCHAR(30) NOT NULL,
    origin VARCHAR(20) NOT NULL,
    settlement_total BIGINT NOT NULL,
    refund_deduction_total BIGINT NOT NULL DEFAULT 0,
    payout_amount BIGINT NOT NULL,
    completed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY(payout_id),
    CONSTRAINT uk_merchant_payouts_merchant_date UNIQUE(merchant_id,payout_date),
    CONSTRAINT chk_merchant_payouts_merchant CHECK(merchant_id>0),
    CONSTRAINT chk_merchant_payouts_origin CHECK(origin IN ('BATCH','LEGACY_BACKFILL')),
    CONSTRAINT chk_merchant_payouts_amounts CHECK(settlement_total>=0 AND refund_deduction_total>=0
        AND refund_deduction_total<=settlement_total AND payout_amount=settlement_total-refund_deduction_total),
    CONSTRAINT chk_merchant_payouts_state CHECK(
        (status='PENDING' AND completed_at IS NULL AND settlement_total=0 AND refund_deduction_total=0 AND payout_amount=0)
        OR (status='COMPLETED' AND completed_at IS NOT NULL))
);

CREATE TABLE merchant_payout_items (
    payout_item_id BIGINT NOT NULL AUTO_INCREMENT,
    payout_id BIGINT NOT NULL,
    settlement_id BIGINT NOT NULL,
    settlement_amount BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY(payout_item_id),
    CONSTRAINT uk_merchant_payout_items_settlement UNIQUE(settlement_id),
    INDEX idx_merchant_payout_items_payout(payout_id),
    CONSTRAINT fk_merchant_payout_items_payout FOREIGN KEY(payout_id) REFERENCES merchant_payouts(payout_id),
    CONSTRAINT fk_merchant_payout_items_settlement FOREIGN KEY(settlement_id) REFERENCES settlements(settlement_id),
    CONSTRAINT chk_merchant_payout_items_amount CHECK(settlement_amount>=0)
);

-- LEGACY_BACKFILL은 과거 여러 건의 완료를 일자별로 모은 근거이지 과거에도 하루 한 번 지급했다는 뜻이 아니다.
INSERT INTO merchant_payouts(merchant_id,payout_date,status,origin,settlement_total,refund_deduction_total,payout_amount,completed_at,created_at,updated_at)
SELECT merchant_id,DATE(completed_at + INTERVAL 9 HOUR),'COMPLETED','LEGACY_BACKFILL',
    SUM(settlement_amount),0,SUM(settlement_amount),MAX(completed_at),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
FROM settlements WHERE status='COMPLETED'
GROUP BY merchant_id,DATE(completed_at + INTERVAL 9 HOUR);

INSERT INTO merchant_payout_items(payout_id,settlement_id,settlement_amount,created_at)
SELECT p.payout_id,s.settlement_id,s.settlement_amount,UTC_TIMESTAMP(6)
FROM settlements s JOIN merchant_payouts p ON p.merchant_id=s.merchant_id
    AND p.payout_date=DATE(s.completed_at + INTERVAL 9 HOUR)
WHERE s.status='COMPLETED';
