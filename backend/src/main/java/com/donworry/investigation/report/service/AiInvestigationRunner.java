package com.donworry.investigation.report.service;

import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.report.client.AiServerClient;
import com.donworry.investigation.report.client.AiServerProperties;
import com.donworry.investigation.report.dto.AiInvestigationRequest;
import com.donworry.investigation.report.entity.AiReport;
import com.donworry.investigation.repository.SecurityCaseRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

// ai-server에 조사를 맡기고(시도 토큰 발급 · 접수 · RUNNING), 재시도 · 종료를 맡는다 (ADR-0009 결정 2)
// 총 3회 시도 — 접수 실패 · 실패 결과 · 결과 검증 실패 · 결과 마감 초과를 각각 실패 1회로 센다
@Slf4j
@Component
@RequiredArgsConstructor
class AiInvestigationRunner {

    private final AiServerClient aiServerClient;
    private final AiServerProperties properties;
    private final AiAttemptTracker tracker;
    private final AiReportWriter writer;
    private final SecurityCaseRepository securityCaseRepository;
    private final ApplicationEventPublisher events;
    private final AiSwitchService aiSwitchService;
    private final FixedReportRunner fixedReportRunner;

    // 새 보고서 조사를 맡긴다 (트랜잭션 밖에서 부른다 — 외부 HTTP 호출, 02-code 4절)
    void start(AiReport report) {
        synchronized (tracker.lockFor(report.getId())) {
            submitNext(report, "AI_SERVER_UNAVAILABLE", "ai-server 접수 실패");
        }
    }

    // 시도가 남았으면 새 토큰으로 다시 맡기고, 다 썼으면 FAILED로 끝낸다 (호출자가 보고서 잠금을 잡고 부른다)
    void retryOrFail(AiReport report, String code, String reason) {
        log.warn("조사 시도 실패 reportId={} code={} reason={}", report.getId(), code, reason);
        submitNext(report, code, reason);
    }

    // 다시 해도 같은 결과(근거 누락 · 증거 버전 변경)는 재시도 없이 끝낸다 (호출자가 보고서 잠금을 잡고 부른다)
    void finishFailed(AiReport report, String code, String reason) {
        Instant now = now();
        int attempts = Math.max(1, tracker.attemptsMade(report.getId()));
        tracker.remove(report.getId());
        if (writer.fail(report.getId(), AiReportWriter.error(code, reason, attempts, now), now)) {
            events.publishEvent(new AiReportFinished(report.getCaseId()));
        }
    }

    // 긴급정지 중 (ADR-0083 결정 4 · 5) — ai-server에 다시 맡기지 않고 같은 버전을 고정 보고서로 끝낸다 (호출자가 보고서 잠금을 잡고 부른다)
    // 시도를 지우므로 그 뒤에 온 이 보고서의 콜백은 "진행 중인 시도 없음"으로 무시된다
    void finishFixed(AiReport report) {
        tracker.remove(report.getId());
        if (fixedReportRunner.run(report)) {
            events.publishEvent(new AiReportFinished(report.getCaseId()));
        }
    }

    // AI 본문 저장은 정지와 겹치지 않게 스위치 잠금 안에서 한다 (#632 Codex 리뷰) — 정지가 먼저면 고정 보고서로 끝낸다
    // 완료 이벤트(다음 버전 요청 · Case 갱신)는 잠금 밖에서 — 그 안의 외부 호출 동안 정지를 붙잡지 않게
    void finishSucceeded(AiReport report, Map<String, Object> body) {
        Optional<Boolean> saved = aiSwitchService.whenAiAllowed(() -> writer.succeed(report.getId(), body, now()));
        if (saved.isEmpty()) {
            log.info("AI 조사 정지 중 — AI 본문을 저장하지 않고 고정 보고서로 끝냄 reportId={}", report.getId());
            finishFixed(report);
            return;
        }
        tracker.remove(report.getId());
        if (saved.get()) {
            events.publishEvent(new AiReportFinished(report.getCaseId()));
        }
    }

    private void submitNext(AiReport report, String lastCode, String lastReason) {
        String code = lastCode;
        String reason = lastReason;
        while (true) {
            // 접수 시도마다 스위치를 본다 — 접수가 늦거나 실패하는 사이 끄면 남은 재시도를 ai-server로 보내지 않는다 (#632 Codex 리뷰)
            if (!aiSwitchService.isAiAllowed()) {
                log.info("AI 조사 정지 중 — ai-server에 맡기지 않고 고정 보고서로 끝냄 reportId={}", report.getId());
                finishFixed(report);
                return;
            }
            var attempt =
                    tracker.next(report.getId(), now().plus(properties.resultTimeout()), AiReportService.MAX_ATTEMPTS);
            if (attempt.isEmpty()) {
                finishFailed(report, code, reason);
                return;
            }
            try {
                aiServerClient.submit(request(report, attempt.get().attemptId()));
                writer.markRunning(report.getId());
                return;
            } catch (RestClientException e) {
                code = "AI_SERVER_UNAVAILABLE";
                reason = e.getClass().getSimpleName();
                log.warn(
                        "ai-server 접수 실패 reportId={} attempt={}",
                        report.getId(),
                        attempt.get().number(),
                        e);
            }
        }
    }

    private AiInvestigationRequest request(AiReport report, String attemptId) {
        SecurityCase securityCase =
                securityCaseRepository.findById(report.getCaseId()).orElseThrow();
        return new AiInvestigationRequest(
                String.valueOf(report.getId()),
                attemptId,
                String.valueOf(report.getCaseId()),
                report.getEvidenceVersion(),
                report.getReportVersion(),
                String.valueOf(securityCase.getMerchantId()),
                securityCase.getCustomerRef(),
                securityCase.getCreatedAt().toString());
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
