package com.donworry.fds.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 경보 근거 = 접근 기록 번호 (#516). (경보, 기록) UNIQUE라 같은 기록은 한 줄. 기록 내용은 복사하지 않는다
@Entity
@Table(name = "insider_alert_evidences")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InsiderAlertEvidence {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "evidence_id")
    private Long id;

    private Long alertId;

    // P1 staff_access_events 논리 참조
    private Long accessEventId;

    private Instant createdAt;

    private InsiderAlertEvidence(Long alertId, Long accessEventId, Instant now) {
        this.alertId = Objects.requireNonNull(alertId, "alertId");
        this.accessEventId = Objects.requireNonNull(accessEventId, "accessEventId");
        this.createdAt = Objects.requireNonNull(now, "now").truncatedTo(ChronoUnit.MICROS);
    }

    public static InsiderAlertEvidence of(Long alertId, Long accessEventId, Instant now) {
        return new InsiderAlertEvidence(alertId, accessEventId, now);
    }
}
