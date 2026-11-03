package com.donworry.identity.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "login_histories")
@Getter
@Immutable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoginHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "history_id")
    private Long historyId;

    @Column(name = "user_id")
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 20)
    private LoginEventType eventType;

    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "client_ip", length = 45)
    private String clientIp;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "login_reference", columnDefinition = "uuid")
    private UUID loginReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "logout_scope", length = 10)
    private LogoutScope logoutScope;

    public static LoginHistory record(
            Long userId,
            LoginEventType eventType,
            LocalDateTime occurredAt,
            String clientIp,
            String userAgent,
            UUID loginReference,
            LogoutScope logoutScope) {
        return LoginHistory.builder()
                .userId(userId)
                .eventType(eventType)
                .occurredAt(occurredAt)
                .clientIp(clientIp)
                .userAgent(userAgent)
                .loginReference(loginReference)
                .logoutScope(logoutScope)
                .build();
    }

    @Builder(access = AccessLevel.PRIVATE)
    private LoginHistory(
            Long userId,
            LoginEventType eventType,
            LocalDateTime occurredAt,
            String clientIp,
            String userAgent,
            UUID loginReference,
            LogoutScope logoutScope) {
        this.userId = userId;
        this.eventType = eventType;
        this.occurredAt = occurredAt;
        this.clientIp = clientIp;
        this.userAgent = userAgent;
        this.loginReference = loginReference;
        this.logoutScope = logoutScope;
    }
}
