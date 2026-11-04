package com.donworry.identity.entity;

import com.donworry.identity.api.OperatorAccessDenied;
import com.donworry.identity.api.OperatorAccessDeniedType;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "operator_access_denials")
@Getter
@Immutable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OperatorAccessDenial {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "denial_id")
    private Long denialId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "http_method", nullable = false, length = OperatorAccessDenied.MAX_HTTP_METHOD_LENGTH)
    private String httpMethod;

    @Column(name = "path", nullable = false, length = 512)
    private String path;

    @Enumerated(EnumType.STRING)
    @Column(name = "denied_type", nullable = false, length = 30)
    private OperatorAccessDeniedType deniedType;

    @Column(name = "client_ip", length = 45)
    private String clientIp;

    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    public static OperatorAccessDenial record(
            Long userId,
            Long merchantId,
            String httpMethod,
            String path,
            OperatorAccessDeniedType deniedType,
            String clientIp,
            LocalDateTime occurredAt) {
        return OperatorAccessDenial.builder()
                .userId(userId)
                .merchantId(merchantId)
                .httpMethod(httpMethod)
                .path(path)
                .deniedType(deniedType)
                .clientIp(clientIp)
                .occurredAt(occurredAt)
                .build();
    }

    @Builder(access = AccessLevel.PRIVATE)
    private OperatorAccessDenial(
            Long userId,
            Long merchantId,
            String httpMethod,
            String path,
            OperatorAccessDeniedType deniedType,
            String clientIp,
            LocalDateTime occurredAt) {
        this.userId = userId;
        this.merchantId = merchantId;
        this.httpMethod = httpMethod;
        this.path = path;
        this.deniedType = deniedType;
        this.clientIp = clientIp;
        this.occurredAt = occurredAt;
    }
}
