package com.donworry.fds.service;

import com.donworry.fds.entity.OperatorAccessDeniedType;
import com.donworry.fds.entity.OperatorAccessDetection;
import com.donworry.fds.entity.OperatorAccessDetectionEvidence;
import com.donworry.fds.entity.OperatorAccessEvent;
import com.donworry.fds.repository.FdsRulePolicyRepository;
import com.donworry.fds.repository.OperatorAccessDetectionEvidenceRepository;
import com.donworry.fds.repository.OperatorAccessDetectionRepository;
import com.donworry.fds.repository.OperatorAccessEventRepository;
import com.donworry.identity.api.OperatorAccessDenied;
import com.donworry.identity.api.OperatorAccountStatus;
import com.donworry.identity.api.OperatorAccountStatusReader;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class OperatorAccessDetectionTransactionService {
    private static final Rule OUT_OF_ROLE = new Rule("SEC_OPERATOR_OUT_OF_ROLE_REPEAT", Duration.ofMinutes(5), 5);
    private static final Rule PERMISSION_MISSING =
            new Rule("SEC_OPERATOR_PERMISSION_MISSING_REPEAT", Duration.ofMinutes(1), 30);

    private final OperatorAccessEventRepository events;
    private final OperatorAccessDetectionRepository detections;
    private final OperatorAccessDetectionEvidenceRepository evidences;
    private final FdsRulePolicyRepository rulePolicies;
    private final OperatorAccountStatusReader statusReader;
    private final OperatorAccessDetectionActionService actions;
    private final Clock clock;

    public OperatorAccessDetectionTransactionService(
            OperatorAccessEventRepository events,
            OperatorAccessDetectionRepository detections,
            OperatorAccessDetectionEvidenceRepository evidences,
            FdsRulePolicyRepository rulePolicies,
            OperatorAccountStatusReader statusReader,
            OperatorAccessDetectionActionService actions,
            Clock clock) {
        this.events = events;
        this.detections = detections;
        this.evidences = evidences;
        this.rulePolicies = rulePolicies;
        this.statusReader = statusReader;
        this.actions = actions;
        this.clock = clock;
    }

    /** 호출자 트랜잭션에 합류하면 계정 잠금이 풀린 뒤 커밋되므로 항상 독립 트랜잭션으로 끝낸다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void receive(OperatorAccessDenied input) {
        if (events.findByIdentityEventId(input.eventId()).isPresent()) return;
        var deniedType = OperatorAccessDeniedType.valueOf(input.deniedType().name());
        var rule = ruleFor(deniedType);
        if (rulePolicies
                .findByRuleCode(rule.code())
                .map(policy -> !policy.isActive())
                .orElse(false)) return;
        var now = clock.instant();
        var event = events.save(OperatorAccessEvent.received(
                input.eventId(),
                input.userId(),
                input.loginId(),
                input.merchantId(),
                input.httpMethod(),
                input.path(),
                deniedType,
                input.occurredAt(),
                input.clientIp(),
                now));
        var status = statusReader.findById(event.getUserId());
        if (status.isEmpty() || status.get().status() == OperatorAccountStatus.INACTIVE) return;
        // 늦게 수신한 이벤트는 자신과 이후 발생 이벤트의 판정 구간에 모두 영향을 줄 수 있다.
        var candidates = events.findByUserIdAndDeniedTypeAndOccurredAtBetweenOrderByOccurredAtAsc(
                event.getUserId(),
                event.getDeniedType(),
                event.getOccurredAt(),
                event.getOccurredAt().plus(rule.window()));
        for (var candidate : candidates) {
            if (detect(candidate, rule, status.get().lastActivatedAt(), now)) return;
        }
    }

    private boolean detect(OperatorAccessEvent event, Rule rule, Instant lastActivatedAt, Instant now) {
        var initialStart = event.getOccurredAt().minus(rule.window());
        var effectiveStart = lastActivatedAt == null ? initialStart : max(initialStart, lastActivatedAt);
        List<OperatorAccessEvent> matched = events.findByUserIdAndDeniedTypeAndOccurredAtBetweenOrderByOccurredAtAsc(
                event.getUserId(), event.getDeniedType(), effectiveStart, event.getOccurredAt());
        if (matched.size() < rule.threshold()) return false;
        var detection = detections.saveAndFlush(OperatorAccessDetection.detected(
                event,
                rule.code(),
                (int) rule.window().toSeconds(),
                rule.threshold(),
                matched.size(),
                effectiveStart,
                event.getOccurredAt(),
                lastActivatedAt,
                now));
        evidences.saveAll(matched.stream()
                .map(value -> OperatorAccessDetectionEvidence.evidence(detection, value, now))
                .toList());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                actions.suspend(detection.getId(), event.getUserId());
            }
        });
        return true;
    }

    private static Rule ruleFor(OperatorAccessDeniedType type) {
        return type == OperatorAccessDeniedType.OUT_OF_ROLE ? OUT_OF_ROLE : PERMISSION_MISSING;
    }

    private static Instant max(Instant left, Instant right) {
        return left.isAfter(right) ? left : right;
    }

    private record Rule(String code, Duration window, int threshold) {}
}
