package com.donworry.identity.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "merchant_integrations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MerchantIntegration {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long integrationId;

    @Column(nullable = false)
    private Long merchantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private IntegrationStatus status;

    @Column(length = 1024)
    private byte[] encryptedSecret;

    @Column(length = 12)
    private byte[] encryptionNonce;

    @Column(length = 40)
    private String masterKeyId;

    @Column(nullable = false)
    private long keyVersion;

    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    private LocalDateTime keyIssuedAt;

    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    private LocalDateTime connectedAt;

    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public static MerchantIntegration empty(Long merchantId) {
        var value = new MerchantIntegration();
        value.merchantId = merchantId;
        value.status = IntegrationStatus.NO_KEY;
        return value;
    }

    public void issue(byte[] ciphertext, byte[] nonce, String masterId, LocalDateTime now, boolean migrated) {
        encryptedSecret = ciphertext.clone();
        encryptionNonce = nonce.clone();
        masterKeyId = masterId;
        keyVersion++;
        keyIssuedAt = now;
        updatedAt = now;
        connectedAt = migrated ? now : null;
        status = migrated ? IntegrationStatus.CONNECTED : IntegrationStatus.PENDING_CONNECTION;
    }

    public void revoke(LocalDateTime now) {
        encryptedSecret = null;
        encryptionNonce = null;
        masterKeyId = null;
        keyIssuedAt = null;
        connectedAt = null;
        status = IntegrationStatus.NO_KEY;
        keyVersion++;
        updatedAt = now;
    }
}
