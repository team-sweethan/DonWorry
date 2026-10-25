package com.donworry.identity.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "merchants")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Merchant {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "merchant_id")
    private Long merchantId;

    @Column(name = "merchant_code", nullable = false, length = 40)
    private String merchantCode;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MerchantStatus status;

    @Column(name = "pg_fee_rate", nullable = false, precision = 9, scale = 6)
    private BigDecimal pgFeeRate;

    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static Merchant create(String merchantCode, String displayName, BigDecimal pgFeeRate) {
        return Merchant.builder()
                .merchantCode(merchantCode)
                .displayName(displayName)
                .pgFeeRate(pgFeeRate)
                .build();
    }

    @Builder(access = AccessLevel.PRIVATE)
    private Merchant(String merchantCode, String displayName, BigDecimal pgFeeRate) {
        this.merchantCode = merchantCode;
        this.displayName = displayName;
        this.pgFeeRate = pgFeeRate;
        this.status = MerchantStatus.ACTIVE;
        this.createdAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    public void changeStatus(MerchantStatus status) {
        this.status = status;
    }
}
