package com.donworry.fds.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

@Entity
@Table(name = "fds_operator_access_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OperatorAccessEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "operator_access_event_id")
    private Long id;

    @Column(name = "identity_event_id", nullable = false, unique = true)
    private Long identityEventId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "login_id", nullable = false, length = 100)
    private String loginId;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "http_method", nullable = false, length = 10)
    private String httpMethod;

    @Column(nullable = false, length = 512)
    private String path;

    @Enumerated(EnumType.STRING)
    @Column(name = "denied_type", nullable = false, length = 50)
    private OperatorAccessDeniedType deniedType;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "client_ip", length = 45)
    private String clientIp;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Builder(access = AccessLevel.PRIVATE)
    private OperatorAccessEvent(
            Long identityEventId,
            Long userId,
            String loginId,
            Long merchantId,
            String httpMethod,
            String path,
            OperatorAccessDeniedType deniedType,
            Instant occurredAt,
            String clientIp,
            Instant receivedAt) {
        this.identityEventId = identityEventId;
        this.userId = userId;
        this.loginId = loginId;
        this.merchantId = merchantId;
        this.httpMethod = httpMethod;
        this.path = path;
        this.deniedType = deniedType;
        this.occurredAt = occurredAt;
        this.clientIp = clientIp;
        this.receivedAt = receivedAt;
        this.createdAt = receivedAt;
    }

    public static OperatorAccessEvent received(
            Long identityEventId,
            Long userId,
            String loginId,
            Long merchantId,
            String httpMethod,
            String path,
            OperatorAccessDeniedType deniedType,
            Instant occurredAt,
            String clientIp,
            Instant receivedAt) {
        return builder()
                .identityEventId(identityEventId)
                .userId(userId)
                .loginId(loginId)
                .merchantId(merchantId)
                .httpMethod(httpMethod)
                .path(path)
                .deniedType(deniedType)
                .occurredAt(occurredAt)
                .clientIp(clientIp)
                .receivedAt(receivedAt)
                .build();
    }
}
