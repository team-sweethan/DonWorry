package com.donworry.settlement.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "cash_flow_settings",
        uniqueConstraints = @UniqueConstraint(name = "uk_cash_flow_settings_merchant", columnNames = "merchant_id"))
public class CashFlowSetting {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "setting_id")
    private Long settingId;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "opening_balance", nullable = false)
    private Long openingBalance;

    @Column(name = "base_date", nullable = false)
    private LocalDate baseDate;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
