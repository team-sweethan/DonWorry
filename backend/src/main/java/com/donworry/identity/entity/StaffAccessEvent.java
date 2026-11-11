package com.donworry.identity.entity;

import com.donworry.identity.api.StaffAccess;
import com.donworry.identity.api.StaffAccessDenyReason;
import com.donworry.identity.api.StaffAccessFieldType;
import com.donworry.identity.api.StaffAccessResult;
import com.donworry.identity.api.StaffAccessType;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 접근 기록은 고치지 않는다. 원문 칸이 없다 — fieldType은 종류 코드, customerRef는 숫자 고객 번호 (ADR-0082)
@Entity
@Table(name = "staff_access_events")
@Getter
@Immutable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StaffAccessEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_id")
    private Long eventId;

    @Column(name = "staff_id", nullable = false)
    private Long staffId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_role", nullable = false, length = 20)
    private UserRole actorRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "access_type", nullable = false, length = 40)
    private StaffAccessType accessType;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 10)
    private StaffAccessResult result;

    @Enumerated(EnumType.STRING)
    @Column(name = "deny_reason", length = 40)
    private StaffAccessDenyReason denyReason;

    @Column(name = "case_id")
    private Long caseId;

    @Column(name = "merchant_id")
    private Long merchantId;

    @Column(name = "payment_id")
    private Long paymentId;

    @Column(name = "customer_ref", length = 100)
    private String customerRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "field_type", length = 30)
    private StaffAccessFieldType fieldType;

    @Column(name = "item_count")
    private Integer itemCount;

    @Column(name = "login_reference", columnDefinition = "uuid")
    private UUID loginReference;

    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    public static StaffAccessEvent allowed(
            Long staffId, UserRole actorRole, UUID loginReference, StaffAccess access, LocalDateTime occurredAt) {
        return of(staffId, actorRole, loginReference, access, StaffAccessResult.ALLOWED, null, occurredAt);
    }

    public static StaffAccessEvent denied(
            Long staffId,
            UserRole actorRole,
            UUID loginReference,
            StaffAccess access,
            StaffAccessDenyReason reason,
            LocalDateTime occurredAt) {
        Objects.requireNonNull(reason, "reason");
        return of(staffId, actorRole, loginReference, access, StaffAccessResult.DENIED, reason, occurredAt);
    }

    private static StaffAccessEvent of(
            Long staffId,
            UserRole actorRole,
            UUID loginReference,
            StaffAccess access,
            StaffAccessResult result,
            StaffAccessDenyReason denyReason,
            LocalDateTime occurredAt) {
        return StaffAccessEvent.builder()
                .staffId(staffId)
                .actorRole(actorRole)
                .accessType(access.accessType())
                .result(result)
                .denyReason(denyReason)
                .caseId(access.caseId())
                .merchantId(access.merchantId())
                .paymentId(access.paymentId())
                .customerRef(access.customerRef())
                .fieldType(access.fieldType())
                .itemCount(access.itemCount())
                .loginReference(loginReference)
                .occurredAt(occurredAt)
                .build();
    }

    @Builder(access = AccessLevel.PRIVATE)
    private StaffAccessEvent(
            Long staffId,
            UserRole actorRole,
            StaffAccessType accessType,
            StaffAccessResult result,
            StaffAccessDenyReason denyReason,
            Long caseId,
            Long merchantId,
            Long paymentId,
            String customerRef,
            StaffAccessFieldType fieldType,
            Integer itemCount,
            UUID loginReference,
            LocalDateTime occurredAt) {
        this.staffId = staffId;
        this.actorRole = actorRole;
        this.accessType = accessType;
        this.result = result;
        this.denyReason = denyReason;
        this.caseId = caseId;
        this.merchantId = merchantId;
        this.paymentId = paymentId;
        this.customerRef = customerRef;
        this.fieldType = fieldType;
        this.itemCount = itemCount;
        this.loginReference = loginReference;
        this.occurredAt = occurredAt;
    }
}
