package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.donworry.settlement.api.PaymentRefundSucceeded;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.IncompleteEventPublications;

class PaymentRefundRedeliverySchedulerTests {

    private final IncompleteEventPublications publications = mock(IncompleteEventPublications.class);

    // P6 수신이 실패한 환불 성공 발행은 다시 보낸다
    @Test
    void run_whenRefundPublicationFailed_resubmitsIt() {
        var filter = filter(new PaymentRefundRedeliveryScheduler(publications, 3));

        assertThat(filter.test(publication(refund(), EventPublication.Status.FAILED, 1, null)))
                .isTrue();
    }

    // 처리 중이거나 이미 완료된 발행은 중단으로 단정하지 않고 두며, 다른 이벤트는 건드리지 않는다
    @Test
    void run_whenNotFailedRefund_leavesPublication() {
        var filter = filter(new PaymentRefundRedeliveryScheduler(publications, 3));

        assertThat(filter.test(publication(refund(), EventPublication.Status.PROCESSING, 1, null)))
                .isFalse();
        assertThat(filter.test(publication(refund(), EventPublication.Status.PUBLISHED, 0, null)))
                .isFalse();
        assertThat(filter.test(publication(refund(), EventPublication.Status.FAILED, 1, Instant.now())))
                .isFalse();
        assertThat(filter.test(publication("other event", EventPublication.Status.FAILED, 1, null)))
                .isFalse();
    }

    // 시도 횟수가 상한에 닿은 발행은 무한 반복하지 않고 사람이 확인하도록 남긴다
    @Test
    void run_whenAttemptsReachedLimit_leavesPublication() {
        var filter = filter(new PaymentRefundRedeliveryScheduler(publications, 3));

        assertThat(filter.test(publication(refund(), EventPublication.Status.FAILED, 2, null)))
                .isTrue();
        assertThat(filter.test(publication(refund(), EventPublication.Status.FAILED, 3, null)))
                .isFalse();
    }

    // 최대 시도 횟수가 1보다 작으면 재전달이 영영 일어나지 않으므로 시작하지 않는다
    @Test
    void constructor_whenMaxAttemptsBelowOne_throwsException() {
        assertThatThrownBy(() -> new PaymentRefundRedeliveryScheduler(publications, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @SuppressWarnings("unchecked")
    private Predicate<EventPublication> filter(PaymentRefundRedeliveryScheduler scheduler) {
        scheduler.run();
        ArgumentCaptor<Predicate<EventPublication>> captor = ArgumentCaptor.forClass(Predicate.class);
        verify(publications).resubmitIncompletePublications(captor.capture());
        return captor.getValue();
    }

    private static PaymentRefundSucceeded refund() {
        return new PaymentRefundSucceeded(1L, 2L, 3L, 4L, 10000L, Instant.parse("2026-11-06T00:00:00Z"));
    }

    private static EventPublication publication(
            Object event, EventPublication.Status status, int attempts, Instant completedAt) {
        var publication = mock(EventPublication.class);
        when(publication.getEvent()).thenReturn(event);
        when(publication.getStatus()).thenReturn(status);
        when(publication.getCompletionAttempts()).thenReturn(attempts);
        when(publication.getCompletionDate()).thenReturn(Optional.ofNullable(completedAt));
        return publication;
    }
}
