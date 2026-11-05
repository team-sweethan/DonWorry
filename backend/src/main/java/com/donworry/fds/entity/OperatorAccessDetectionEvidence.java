package com.donworry.fds.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

@Entity
@Table(name = "fds_operator_access_detection_evidences")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OperatorAccessDetectionEvidence {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "operator_access_detection_evidence_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "detection_id", nullable = false)
    private OperatorAccessDetection detection;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "operator_access_event_id", nullable = false)
    private OperatorAccessEvent event;

    @Column(name = "identity_event_id", nullable = false)
    private Long identityEventId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static OperatorAccessDetectionEvidence evidence(
            OperatorAccessDetection detection, OperatorAccessEvent event, Instant createdAt) {
        var evidence = new OperatorAccessDetectionEvidence();
        evidence.detection = detection;
        evidence.event = event;
        evidence.identityEventId = event.getIdentityEventId();
        evidence.occurredAt = event.getOccurredAt();
        evidence.createdAt = createdAt;
        return evidence;
    }
}
