package com.donworry.settlement.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 가맹점·지급일이 달라져도 같은 행을 잠가 환불과 지급 순서를 직렬화한다.
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "merchant_settlement_locks",
        uniqueConstraints =
                @UniqueConstraint(name = "uk_merchant_settlement_locks_merchant", columnNames = "merchant_id"))
public class MerchantSettlementLock {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "lock_id")
    private Long lockId;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
