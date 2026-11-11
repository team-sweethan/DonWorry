package com.donworry.payment.service;

import com.donworry.settlement.api.PaymentRefundSucceeded;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// P6 수신이 실패해 FAILED로 남은 환불 성공 발행만 다시 보낸다 (#383, ADR-0058 5번)
// 앱 전체 재발행 정책은 아직 정하지 않았으므로(docs/modulith/04) 이 이벤트로 범위를 좁힌다.
// 같은 성공 결과를 다시 보낼 뿐 환불은 다시 실행하지 않는다. P6는 cancellationId로 한 번만 반영한다.
// PROCESSING은 중단으로 단정하지 않고 두며, 상한을 넘긴 FAILED는 사람이 확인하도록 남긴다.
@Component
@Slf4j
@ConditionalOnProperty(name = "payment.refund-completion.enabled", havingValue = "true")
class PaymentRefundRedeliveryScheduler {

    private final IncompleteEventPublications publications;
    private final int maxAttempts;

    PaymentRefundRedeliveryScheduler(
            IncompleteEventPublications publications,
            @Value("${payment.refund-completion.redelivery-max-attempts:10}") int maxAttempts) {
        if (maxAttempts < 1) throw new IllegalArgumentException("재전달 최대 시도 횟수는 1 이상이어야 합니다.");
        this.publications = publications;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(
            initialDelayString = "${payment.refund-completion.redelivery-delay:PT5M}",
            fixedDelayString = "${payment.refund-completion.redelivery-delay:PT5M}")
    public void run() {
        var resubmitted = new AtomicInteger();
        var exhausted = new AtomicInteger();
        publications.resubmitIncompletePublications(publication -> {
            if (!failedRefund(publication)) return false;
            if (publication.getCompletionAttempts() >= maxAttempts) {
                exhausted.incrementAndGet();
                return false;
            }
            resubmitted.incrementAndGet();
            return true;
        });
        if (resubmitted.get() > 0) log.info("환불 성공 재전달 resubmitted={}", resubmitted.get());
        if (exhausted.get() > 0) log.error("환불 성공 재전달 상한 초과 exhausted={} maxAttempts={}", exhausted.get(), maxAttempts);
    }

    private static boolean failedRefund(EventPublication publication) {
        return publication.getEvent() instanceof PaymentRefundSucceeded
                && publication.getStatus() == EventPublication.Status.FAILED
                && publication.getCompletionDate().isEmpty();
    }
}
