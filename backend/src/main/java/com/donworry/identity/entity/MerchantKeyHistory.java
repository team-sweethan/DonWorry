package com.donworry.identity.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "merchant_key_histories")
@Getter
@Immutable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MerchantKeyHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long historyId;

    @Column(nullable = false)
    private Long merchantId;

    private Long actorUserId;

    @Column(nullable = false, length = 30)
    private String action;

    @Column(nullable = false)
    private long keyVersion;

    @Column(length = 500)
    private String reason;

    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    @Column(nullable = false)
    private LocalDateTime createdAt;

    public static MerchantKeyHistory record(
            Long merchantId, Long actorId, String action, long version, String reason, LocalDateTime now) {
        return MerchantKeyHistory.builder()
                .merchantId(merchantId)
                .actorUserId(actorId)
                .action(action)
                .keyVersion(version)
                .reason(reason)
                .createdAt(now)
                .build();
    }

    @Builder(access = AccessLevel.PRIVATE)
    private MerchantKeyHistory(
            Long merchantId, Long actorUserId, String action, long keyVersion, String reason, LocalDateTime createdAt) {
        this.merchantId = merchantId;
        this.actorUserId = actorUserId;
        this.action = action;
        this.keyVersion = keyVersion;
        this.reason = reason;
        this.createdAt = createdAt;
    }
}
