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

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant expiresAt;

    public boolean isActiveAt(Instant at) {
        return active && (expiresAt == null || expiresAt.isAfter(at));
    }
}
