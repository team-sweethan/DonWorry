package com.donworry.identity.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "application_mail_deliveries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApplicationMailDelivery {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "delivery_id")
    private Long deliveryId;

    @Column(name = "application_id", nullable = false)
    private Long applicationId;

    @Column(name = "token_id")
    private Long tokenId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 30)
    private ApplicationMailKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ApplicationMailStatus status;

    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "failure_code", length = 40)
    private String failureCode;

    @Builder(access = AccessLevel.PRIVATE)
    private ApplicationMailDelivery(
            Long applicationId,
            Long tokenId,
            ApplicationMailKind kind,
            ApplicationMailStatus status,
            LocalDateTime startedAt,
            LocalDateTime completedAt,
            String failureCode) {
        this.applicationId = applicationId;
        this.tokenId = tokenId;
        this.kind = kind;
        this.status = status;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.failureCode = failureCode;
    }

    public static ApplicationMailDelivery verification(Long applicationId, Long tokenId, LocalDateTime now) {
        return ApplicationMailDelivery.builder()
                .applicationId(applicationId)
                .tokenId(tokenId)
                .kind(ApplicationMailKind.EMAIL_VERIFICATION)
                .status(ApplicationMailStatus.PENDING)
                .startedAt(now)
                .build();
    }

    public void complete(ApplicationMailStatus status, String failureCode, LocalDateTime now) {
        this.status = status;
        this.failureCode = failureCode;
        this.completedAt = now;
    }
}
