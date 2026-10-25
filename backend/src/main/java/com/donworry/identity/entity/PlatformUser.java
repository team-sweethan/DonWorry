package com.donworry.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "platform_users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlatformUser {

    public void changePasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
        this.passwordChangeRequired = false;
    }

    public void resetPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
        this.passwordChangeRequired = true;
    }

    public void changeStatus(UserStatus status) {
        this.status = status;
    }

    public static PlatformUser create(
            String loginId, String displayName, UserRole role, Long merchantId, String passwordHash) {
        return PlatformUser.builder()
                .loginId(loginId)
                .displayName(displayName)
                .role(role)
                .merchantId(merchantId)
                .passwordHash(passwordHash)
                .build();
    }

    @Builder(access = AccessLevel.PRIVATE)
    private PlatformUser(String loginId, String displayName, UserRole role, Long merchantId, String passwordHash) {
        this.loginId = loginId;
        this.displayName = displayName;
        this.role = role;
        this.merchantId = merchantId;
        this.passwordHash = passwordHash;
        this.status = UserStatus.ACTIVE;
        this.passwordChangeRequired = true;
        this.createdAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    @Column(name = "password_change_required", nullable = false)
    private boolean passwordChangeRequired;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "login_id", nullable = false, length = 100)
    private String loginId;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private UserStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 30)
    private UserRole role;

    @Column(name = "merchant_id")
    private Long merchantId;

    @Column(name = "created_at", nullable = false)
    // UTC DATETIME 값을 JVM 기본 시간대로 변환하지 않고 그대로 읽고 쓴다
    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    private LocalDateTime createdAt;
}
