package com.donworry.investigation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// Case 감사 이력 — INSERT 전용, 업무 변경과 같은 트랜잭션에서 저장한다
@Entity
@Immutable
@Table(name = "case_histories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CaseHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "history_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "case_id")
    private SecurityCase securityCase;

    @Enumerated(EnumType.STRING)
    private CaseHistoryEventType eventType;

    @Enumerated(EnumType.STRING)
    private ActorType actorType;

    // P1 platform_users 논리 참조 — SYSTEM이면 null
    private Long actorId;

    // 관련 정본을 찾을 식별자 · 변경 사실만 담는다. 다른 모듈 데이터를 복사하지 않는다
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> eventData;

    private Instant createdAt;

    @Builder(access = AccessLevel.PRIVATE)
    private CaseHistory(
            SecurityCase securityCase,
            CaseHistoryEventType eventType,
            ActorType actorType,
            Long actorId,
            Map<String, Object> eventData,
            Instant createdAt) {
        this.securityCase = Objects.requireNonNull(securityCase, "securityCase");
        this.eventType = Objects.requireNonNull(eventType, "eventType");
        this.actorType = Objects.requireNonNull(actorType, "actorType");
        this.actorId = actorId;
        this.eventData = eventData == null ? null : Map.copyOf(eventData);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public static CaseHistory bySystem(
            SecurityCase securityCase, CaseHistoryEventType eventType, Map<String, Object> eventData, Instant now) {
        return CaseHistory.builder()
                .securityCase(securityCase)
                .eventType(eventType)
                .actorType(ActorType.SYSTEM)
                .eventData(eventData)
                .createdAt(now)
                .build();
    }

    public static CaseHistory byStaff(
            SecurityCase securityCase,
            CaseHistoryEventType eventType,
            Long staffId,
            Map<String, Object> eventData,
            Instant now) {
        return CaseHistory.builder()
                .securityCase(securityCase)
                .eventType(eventType)
                .actorType(ActorType.STAFF)
                .actorId(Objects.requireNonNull(staffId, "staffId"))
                .eventData(eventData)
                .createdAt(now)
                .build();
    }
}
