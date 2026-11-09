package com.donworry.investigation.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.donworry.investigation.service.InsiderCaseChanged;
import java.util.Optional;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.IncompleteEventPublications;

// 해제 검토 보고서 알림 재전달 (#702) — 어떤 발행을 다시 보낼지만 본다. 실제 재전달 흐름은 InsiderReportRedeliveryMariaDbTests
class InsiderReportRedeliverySchedulerTests {

    private final IncompleteEventPublications publications = mock(IncompleteEventPublications.class);

    // 실패한 보고서 알림은 첫 시도 뒤 최대 3번까지 다시 보내고, 그 뒤로는 끝없이 다시 보내지 않는다
    // 시도 횟수는 첫 전달에서 1, 다시 보낼 때마다 1씩 는다 (Modulith 2.1 event_publication.COMPLETION_ATTEMPTS)
    @Test
    void redelivery_failedInsiderCaseChanged_resubmittedUntilMaxAttempts() {
        var filter = filter(new InsiderReportRedeliveryScheduler(publications, 3));

        assertThat(filter.test(failed(opened(), 1))).isTrue();
        assertThat(filter.test(failed(opened(), 3))).isTrue();
        assertThat(filter.test(failed(opened(), 4))).isFalse();
    }

    // 다른 이벤트의 실패 발행(환불 · 결제 등 다른 담당 것)은 건드리지 않는다 — 그쪽 처리가 두 번 반영되지 않게
    @Test
    void redelivery_otherEventTypes_notResubmitted() {
        var filter = filter(new InsiderReportRedeliveryScheduler(publications, 3));

        assertThat(filter.test(failed("other event", 1))).isFalse();
    }

    @SuppressWarnings("unchecked")
    private Predicate<EventPublication> filter(InsiderReportRedeliveryScheduler scheduler) {
        scheduler.run();
        ArgumentCaptor<Predicate<EventPublication>> captor = ArgumentCaptor.forClass(Predicate.class);
        verify(publications).resubmitIncompletePublications(captor.capture());
        return captor.getValue();
    }

    private static InsiderCaseChanged opened() {
        return new InsiderCaseChanged(10L, InsiderCaseChanged.Reason.OPENED, 500L);
    }

    private static EventPublication failed(Object event, int attempts) {
        var publication = mock(EventPublication.class);
        when(publication.getEvent()).thenReturn(event);
        when(publication.getStatus()).thenReturn(EventPublication.Status.FAILED);
        when(publication.getCompletionAttempts()).thenReturn(attempts);
        when(publication.getCompletionDate()).thenReturn(Optional.empty());
        return publication;
    }
}
