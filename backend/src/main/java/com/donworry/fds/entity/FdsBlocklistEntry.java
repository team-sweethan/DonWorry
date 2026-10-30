package com.donworry.fds.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

@Entity
@Table(name = "fds_blocklist_entries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FdsBlocklistEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long blocklistEntryId;

    @Column(nullable = false, length = 30)
    private String identifierType;

    @Column(nullable = false, length = 255)
    private String identifierValue;

    @Column(name = "display_label", length = 50)
    private String displayLabel;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(length = 500)
    private String deactivationReason;

    @Column(nullable = false)
    private boolean active;

    private Instant deactivatedAt;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant expiresAt;

    @Builder(access = AccessLevel.PRIVATE)
    private FdsBlocklistEntry(
            String identifierType,
            String identifierValue,
            String displayLabel,
            String reason,
            Instant createdAt,
            Instant expiresAt) {
        this.identifierType = identifierType;
        this.identifierValue = identifierValue;
        this.displayLabel = displayLabel;
        this.reason = reason;
        this.active = true;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public static FdsBlocklistEntry active(
            String identifierType, String identifierValue, String displayLabel, String reason, Instant createdAt) {
        return FdsBlocklistEntry.builder()
                .identifierType(identifierType)
                .identifierValue(identifierValue)
                .displayLabel(displayLabel)
                .reason(reason)
                .createdAt(createdAt)
                .build();
    }

    public static FdsBlocklistEntry active(
            String identifierType, String identifierValue, String reason, Instant createdAt) {
        return active(identifierType, identifierValue, null, reason, createdAt);
    }

    public boolean isActiveAt(Instant at) {
        return active && (expiresAt == null || expiresAt.isAfter(at));
    }

    public void deactivate(String deactivationReason, Instant deactivatedAt) {
        this.active = false;
        this.deactivationReason = deactivationReason;
        this.deactivatedAt = deactivatedAt;
    }

    public void reactivate(String displayLabel, String reason) {
        this.active = true;
        this.expiresAt = null;
        this.deactivationReason = null;
        this.deactivatedAt = null;
        if (displayLabel != null) {
            this.displayLabel = displayLabel;
        }
        this.reason = reason;
    }
}
