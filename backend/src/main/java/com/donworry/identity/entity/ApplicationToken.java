package com.donworry.identity.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "application_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApplicationToken {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "token_id")
    private Long tokenId;

    @Column(name = "application_id", nullable = false)
    private Long applicationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 30)
    private ApplicationTokenPurpose purpose;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    @Column(name = "used_at")
    private LocalDateTime usedAt;

    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Builder(access = AccessLevel.PRIVATE)
    private ApplicationToken(
            Long applicationId,
            ApplicationTokenPurpose purpose,
            Long userId,
            String tokenHash,
            LocalDateTime createdAt,
            LocalDateTime expiresAt,
            LocalDateTime usedAt,
            LocalDateTime revokedAt) {
        this.applicationId = applicationId;
        this.purpose = purpose;
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.usedAt = usedAt;
        this.revokedAt = revokedAt;
    }

    public static ApplicationToken emailVerification(Long applicationId, String hash, LocalDateTime now) {
        return ApplicationToken.builder()
                .applicationId(applicationId)
                .purpose(ApplicationTokenPurpose.EMAIL_VERIFICATION)
                .tokenHash(hash)
                .createdAt(now)
                .expiresAt(now.plusHours(24))
                .build();
    }

    public static ApplicationToken initialPassword(Long applicationId, Long userId, String hash, LocalDateTime now) {
        return ApplicationToken.builder()
                .applicationId(applicationId)
                .userId(userId)
                .purpose(ApplicationTokenPurpose.INITIAL_PASSWORD_SETUP)
                .tokenHash(hash)
                .createdAt(now)
                .expiresAt(now.plusHours(72))
                .build();
    }

    public void revoke(LocalDateTime now) {
        if (usedAt == null && revokedAt == null) revokedAt = now;
    }

    public void use(LocalDateTime now) {
        usedAt = now;
    }
}
