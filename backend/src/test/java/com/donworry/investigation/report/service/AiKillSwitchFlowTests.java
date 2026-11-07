package com.donworry.investigation.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.investigation.entity.CasePayment;
import com.donworry.investigation.entity.CaseType;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.report.client.AiServerClient;
import com.donworry.investigation.report.dto.AiInvestigationRequest;
import com.donworry.investigation.report.dto.AiInvestigationResult;
import com.donworry.investigation.report.entity.AiReport;
import com.donworry.investigation.report.entity.ReportStatus;
import com.donworry.investigation.report.repository.AiReportRepository;
import com.donworry.investigation.repository.CasePaymentRepository;
import com.donworry.investigation.repository.SecurityCaseRepository;
import com.donworry.investigation.service.PaymentInfoReader;
import com.donworry.investigation.service.PaymentInfoReader.PaymentInfo;
import com.donworry.investigation.service.PaymentInfoReader.RuleHit;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.ResourceAccessException;

// ai-server를 켠 서버에서 AI 조사 긴급정지가 AI로 이어지는 경로를 모두 막는지 실제 MariaDB로 확인한다 (ADR-0083 결정 4 · 5)
// 스위치(AiSwitchService)는 대역으로 둔다 — 꺼짐 기록을 공유 DB에 남기면 다른 테스트의 AI 경로가 막힌다
// ai-server · 결제 근거(P5 · P2)도 대역. 이 테스트만 쓰는 설정이라 끝나면 컨텍스트를 닫아 공유 MariaDB 연결 한도를 넘지 않는다
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("mariadb")
@SpringBootTest(properties = "ai.server.enabled=true")
@Import(MariaDbTestContainerConfig.class)
class AiKillSwitchFlowTests {

    @Autowired
    AiReportService aiReportService;

    @Autowired
    AiReportResultService resultService;

    @Autowired
    AiReportRepository aiReportRepository;

    @Autowired
    SecurityCaseRepository securityCaseRepository;

    @Autowired
    CasePaymentRepository casePaymentRepository;

    @Autowired
    AiAttemptTracker tracker;

    @Autowired
    ApplicationEventPublisher events;

    @MockitoBean
    AiServerClient aiServerClient;

    @MockitoBean
    PaymentInfoReader paymentInfoReader;

    @MockitoBean
    AiSwitchService aiSwitchService;

    // 대역 스위치도 "켜짐 확인 → 저장"(whenAiAllowed)을 isAiAllowed 값대로 흉내 낸다
    @BeforeEach
    void switchGateFollowsIsAiAllowed() {
        given(aiSwitchService.whenAiAllowed(any()))
                .willAnswer(invocation -> aiSwitchService.isAiAllowed()
                        ? Optional.ofNullable(((Supplier<?>) invocation.getArgument(0)).get())
                        : Optional.empty());
    }

    // 꺼져 있으면 새 요청은 ai-server를 부르지 않고 고정 보고서로 끝난다 — 사건 정보가 ai-server · LLM으로 나가지 않는다
    @Test
    void request_whenSwitchStopped_createsFixedReportWithoutCallingAiServer() {
        Long caseId = caseWithEvidence();
        given(aiSwitchService.isAiAllowed()).willReturn(false);

        aiReportService.request(caseId, 1);

        AiReport report = onlyReportOf(caseId);
        assertThat(report.getStatus()).isEqualTo(ReportStatus.SUCCEEDED);
        assertThat(provider(report)).isEqualTo("FIXED");
        verify(aiServerClient, never()).submit(any());
    }

    // 꺼진 상태로 재시작하면 남은 보고서를 AI로 재개하지 않고 같은 버전을 고정 보고서로 끝낸다
    @Test
    void resumeUnfinished_whenSwitchStopped_finishesWithFixedReport() {
        Long caseId = caseWithEvidence();
        given(aiSwitchService.isAiAllowed()).willReturn(true);
        aiReportService.request(caseId, 1);
        Long reportId = onlyReportOf(caseId).getId();
        // 재시작으로 메모리의 시도가 비워진 상태
        tracker.remove(reportId);
        given(aiSwitchService.isAiAllowed()).willReturn(false);

        aiReportService.resumeUnfinished();

        AiReport report = onlyReportOf(caseId);
        assertThat(report.getStatus()).isEqualTo(ReportStatus.SUCCEEDED);
        assertThat(provider(report)).isEqualTo("FIXED");
        assertThat(submittedFor(reportId)).isEqualTo(1);
    }

    // 끄는 순간 진행 중이던 보고서는 같은 버전에서 고정 보고서로 끝나고, 다시 켠 뒤 늦게 온 AI 결과도 반영되지 않는다
    @Test
    void stop_whenRunningReport_finishesFixedInSameReportVersion() {
        Long caseId = caseWithEvidence();
        given(aiSwitchService.isAiAllowed()).willReturn(true);
        aiReportService.request(caseId, 1);
        AiReport running = onlyReportOf(caseId);
        assertThat(running.getStatus()).isEqualTo(ReportStatus.RUNNING);
        String attemptId = tracker.current(running.getId()).orElseThrow().attemptId();
        given(aiSwitchService.isAiAllowed()).willReturn(false);

        events.publishEvent(new AiSwitchStopped());
        given(aiSwitchService.isAiAllowed()).willReturn(true);
        resultService.receive(running.getId(), aiSucceeded(running.getId(), attemptId));

        AiReport report = onlyReportOf(caseId);
        assertThat(report.getId()).isEqualTo(running.getId());
        assertThat(report.getReportVersion()).isEqualTo(1);
        assertThat(report.getStatus()).isEqualTo(ReportStatus.SUCCEEDED);
        assertThat(provider(report)).isEqualTo("FIXED");
    }

    // 접수 뒤 꺼졌는데 현재 시도의 결과가 오면 AI 본문을 저장하지 않고 고정 보고서로 끝낸다 (끄기 · 콜백이 겹친 경우)
    @Test
    void receive_whenStoppedAfterSubmit_ignoresAiResultAndFinishesFixed() {
        Long caseId = caseWithEvidence();
        given(aiSwitchService.isAiAllowed()).willReturn(true);
        aiReportService.request(caseId, 1);
        Long reportId = onlyReportOf(caseId).getId();
        String attemptId = tracker.current(reportId).orElseThrow().attemptId();
        given(aiSwitchService.isAiAllowed()).willReturn(false);

        resultService.receive(reportId, aiSucceeded(reportId, attemptId));

        AiReport report = onlyReportOf(caseId);
        assertThat(report.getStatus()).isEqualTo(ReportStatus.SUCCEEDED);
        assertThat(provider(report)).isEqualTo("FIXED");
        assertThat(report.getReportBody().get("summary")).isNotEqualTo(List.of("AI 요약"));
    }

    // 꺼진 뒤 결과 마감을 넘기면 ai-server에 다시 맡기지 않고 고정 보고서로 끝낸다
    @Test
    void checkOverdue_whenStopped_finishesFixedWithoutResubmit() {
        Long caseId = caseWithEvidence();
        given(aiSwitchService.isAiAllowed()).willReturn(true);
        aiReportService.request(caseId, 1);
        Long reportId = onlyReportOf(caseId).getId();
        given(aiServerClient.status(reportId)).willReturn(Optional.empty());
        given(aiSwitchService.isAiAllowed()).willReturn(false);

        resultService.checkOverdue(Instant.now().plus(1, ChronoUnit.DAYS));

        AiReport report = onlyReportOf(caseId);
        assertThat(report.getStatus()).isEqualTo(ReportStatus.SUCCEEDED);
        assertThat(provider(report)).isEqualTo("FIXED");
        assertThat(submittedFor(reportId)).isEqualTo(1);
    }

    // 접수 재시도 중에 끄면 남은 재시도를 ai-server로 보내지 않고 고정 보고서로 끝낸다 (#632 Codex 리뷰 재현)
    // 첫 접수가 실패하는 사이 정지가 저장된 상황 — 반복 앞에서 한 번만 확인하면 3번 다 보내고 FAILED로 끝난다
    @Test
    void submit_whenStoppedDuringRetry_finishesFixedWithoutFurtherSubmit() {
        Long caseId = caseWithEvidence();
        AtomicBoolean allowed = new AtomicBoolean(true);
        given(aiSwitchService.isAiAllowed()).willAnswer(invocation -> allowed.get());
        willAnswer(invocation -> {
                    allowed.set(false);
                    throw new ResourceAccessException("connection refused");
                })
                .given(aiServerClient)
                .submit(any());

        aiReportService.request(caseId, 1);

        AiReport report = onlyReportOf(caseId);
        assertThat(report.getStatus()).isEqualTo(ReportStatus.SUCCEEDED);
        assertThat(provider(report)).isEqualTo("FIXED");
        assertThat(submittedFor(report.getId())).isEqualTo(1);
    }

    private Long caseWithEvidence() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        SecurityCase securityCase = securityCaseRepository.save(
                SecurityCase.open(1L, "customer-" + UUID.randomUUID(), CaseType.PAYMENT_FRAUD, now));
        long paymentId = System.nanoTime();
        casePaymentRepository.save(CasePayment.link(securityCase, paymentId, now));
        given(paymentInfoReader.findAll(anyCollection()))
                .willReturn(Map.of(
                        paymentId,
                        new PaymentInfo(
                                paymentId,
                                600_000L,
                                "HELD",
                                now,
                                65,
                                "REVIEW",
                                List.of(new RuleHit("HIGH_AMOUNT", "고액 결제", 35)))));
        return securityCase.getId();
    }

    private AiReport onlyReportOf(Long caseId) {
        List<AiReport> reports = aiReportRepository.findByCaseIdOrderByReportVersionAsc(caseId);
        assertThat(reports).hasSize(1);
        return reports.getFirst();
    }

    private long submittedFor(Long reportId) {
        return Mockito.mockingDetails(aiServerClient).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("submit"))
                .map(invocation -> (AiInvestigationRequest) invocation.getArgument(0))
                .filter(request -> request.reportId().equals(String.valueOf(reportId)))
                .count();
    }

    private static AiInvestigationResult aiSucceeded(Long reportId, String attemptId) {
        return new AiInvestigationResult(
                String.valueOf(reportId), attemptId, "SUCCEEDED", Map.of("summary", List.of("AI 요약")), null);
    }

    @SuppressWarnings("unchecked")
    private static String provider(AiReport report) {
        Map<String, Object> trace = (Map<String, Object>) report.getReportBody().get("investigationTrace");
        return trace == null ? null : (String) trace.get("provider");
    }
}
