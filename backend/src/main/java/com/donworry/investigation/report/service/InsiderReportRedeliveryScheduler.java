package com.donworry.investigation.report.service;

import com.donworry.investigation.service.InsiderCaseChanged;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 사실 읽기가 실패해 FAILED로 남은 해제 검토 보고서 알림만 다시 보낸다 (#702)
// 앱 전체 재발행 정책은 아직 정하지 않았으므로(docs/modulith/04) 이 이벤트로 범위를 좁힌다 — 환불 재전달(PaymentRefundRedeliveryScheduler)과 같은 방식
// InsiderCaseChanged를 받는 곳은 InsiderReportListener 하나다. 다른 수신자가 생기면 그쪽 실패까지 다시 보내게 되므로 거르는 조건을 다시 본다
// 보고서만 다시 만든다. 사건 · 정지 상태는 바꾸지 않고, 같은 계기 판은 하나라 다시 와도 판이 늘지 않는다 (AGENTS.md 6절)
// 처리 중(PROCESSING)은 중단으로 단정하지 않고 둔다. 재시도 상한을 넘기면 error 로그만 남기고 두며,
// 판이 없으므로 다음 서버 시작 때 InsiderReportBackfill이 채운다
@Slf4j
@Component
class InsiderReportRedeliveryScheduler {

    private final IncompleteEventPublications publications;
    private final int maxRetries;

    InsiderReportRedeliveryScheduler(
            IncompleteEventPublications publications,
            @Value("${donworry.insider-report.redelivery-max-retries:3}") int maxRetries) {
        if (maxRetries < 1) throw new IllegalArgumentException("보고서 재시도 횟수는 1 이상이어야 합니다.");
        this.publications = publications;
        this.maxRetries = maxRetries;
    }

    @Scheduled(
            initialDelayString = "${donworry.insider-report.redelivery-delay:PT5M}",
            fixedDelayString = "${donworry.insider-report.redelivery-delay:PT5M}")
    public void run() {
        var resubmitted = new AtomicInteger();
        var exhausted = new AtomicInteger();
        publications.resubmitIncompletePublications(publication -> {
            if (!failedReport(publication)) return false;
            // 시도 횟수는 첫 전달 1에서 시작해 다시 보낼 때마다 1씩 는다 — 첫 시도 + 재시도 maxRetries번까지
            if (publication.getCompletionAttempts() > maxRetries) {
                exhausted.incrementAndGet();
                return false;
            }
            resubmitted.incrementAndGet();
            return true;
        });
        if (resubmitted.get() > 0) log.info("해제 검토 보고서 다시 보냄 resubmitted={}", resubmitted.get());
        if (exhausted.get() > 0)
            log.error("해제 검토 보고서 재시도 상한 초과 — 다음 서버 시작 때 다시 채움 exhausted={} maxRetries={}", exhausted.get(), maxRetries);
    }

    private static boolean failedReport(EventPublication publication) {
        return publication.getEvent() instanceof InsiderCaseChanged
                && publication.getStatus() == EventPublication.Status.FAILED
                && publication.getCompletionDate().isEmpty();
    }
}
