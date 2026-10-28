package com.donworry.fds.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

@Entity
@Table(name = "fds_rule_policies")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FdsRulePolicy {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long rulePolicyId;

    @Column(nullable = false, unique = true, length = 10)
    private String ruleCode;

    @Column(nullable = false)
    private boolean active;

    private Long updatedByUserId;

    @Column(nullable = false)
    private Instant updatedAt;

    public void update(boolean active, Long actorUserId, Instant updatedAt) {
        this.active = active;
        this.updatedByUserId = actorUserId;
        this.updatedAt = updatedAt;
    }
}
