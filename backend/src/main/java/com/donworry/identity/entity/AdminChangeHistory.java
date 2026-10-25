package com.donworry.identity.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "admin_change_histories")
@Getter
@Immutable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdminChangeHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "history_id")
    private Long historyId;

    @Column(name = "actor_user_id", nullable = false)
    private Long actorUserId;

    @Column(name = "target_user_id")
    private Long targetUserId;

    @Column(name = "target_merchant_id")
    private Long targetMerchantId;

    @Column(name = "change_type", nullable = false, length = 40)
    private String changeType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_data")
    private String beforeData;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_data")
    private String afterData;

    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static AdminChangeHistory forUser(Long actorId, Long targetId, String type, String before, String after) {
        return AdminChangeHistory.builder()
                .actorUserId(actorId)
                .targetUserId(targetId)
                .changeType(type)
                .beforeData(before)
                .afterData(after)
                .build();
    }

    @Builder(access = AccessLevel.PRIVATE)
    private AdminChangeHistory(
            Long actorUserId, Long targetUserId, String changeType, String beforeData, String afterData) {
        this.actorUserId = actorUserId;
        this.targetUserId = targetUserId;
        this.changeType = changeType;
        this.beforeData = beforeData;
        this.afterData = afterData;
        this.createdAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}
