package com.donworry.investigation.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.investigation.entity.CasePayment;
import com.donworry.investigation.entity.CaseStatus;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.json.JsonMapper;

// ai-server를 켰을 때(ai.server.enabled=true) 조사 접수 · 결과 반영 · 재시도 · 마감 · 복구를 실제 MariaDB로 확인한다
// ai-server는 목(AiServerClient)으로 두고 결과는 콜백 HTTP로 보낸다 (ADR-0009 결정 1 · 2 · 3)
// 이 테스트만 쓰는 설정이라 컨텍스트가 따로 뜬다 — 끝나면 닫아 공유 MariaDB의 연결 한도를 넘지 않는다
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("mariadb")
@SpringBootTest(properties = "ai.server.enabled=true")
@AutoConfigureMockMvc
@Import(MariaDbTestContainerConfig.class)
class AiInvestigationFlowTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper mapper;

    @Autowired
    AiReportService aiReportService;

    @Autowired
    AiReportResultService resultService;

    @Autowired
    AiReportWriter writer;

    @Autowired
    AiReportRepository aiReportRepository;

    @Autowired
    SecurityCaseRepository securityCaseRepository;

    @Autowired
    CasePaymentRepository casePaymentRepository;

    @MockitoBean
    AiServerClient aiServerClient;

    // 접수 → RUNNING → 성공 콜백 → SUCCEEDED, Case는 판정 대기로 바뀐다
    @Test
    void request_succeedsViaCallback_andCaseBecomesDecisionPending() throws Exception {
        Fixture f = caseWithPayment();

        aiReportService.request(f.caseId(), 1);

        AiReport report = reportOf(f);
        assertThat(report.getStatus()).isEqualTo(ReportStatus.RUNNING);
        List<AiInvestigationRequest> sent = submittedFor(report.getId());
        assertThat(sent).singleElement().satisfies(request -> {
            assertThat(request.caseId()).isEqualTo(String.valueOf(f.caseId()));
            assertThat(request.evidenceVersion()).isEqualTo(1);
            assertThat(request.customerRef()).isEqualTo(f.customerRef());
        });

        callback(report.getId(), succeeded(report.getId(), sent.getFirst().attemptId(), f));

        assertThat(reportOf(f).getStatus()).isEqualTo(ReportStatus.SUCCEEDED);
        assertThat(caseStatus(f)).isEqualTo(CaseStatus.DECISION_PENDING);
    }

    // ai-server에 접수가 안 되면 총 3회 시도한 뒤 FAILED — 직원은 수동 판정으로 간다
    @Test
    void request_aiServerDown_failsAfterThreeAttempts() {
        Fixture f = caseWithPayment();
        willThrow(new ResourceAccessException("connection refused"))
                .given(aiServerClient)
                .submit(any());

        aiReportService.request(f.caseId(), 1);

        AiReport report = reportOf(f);
        assertThat(report.getStatus()).isEqualTo(ReportStatus.FAILED);
        assertThat(error(report)).containsEntry("code", "AI_SERVER_UNAVAILABLE").containsEntry("attempts", 3);
        assertThat(submittedFor(report.getId())).hasSize(3);
        assertThat(caseStatus(f)).isEqualTo(CaseStatus.DECISION_PENDING);
    }

    // 현재 시도가 아닌 결과는 반영하지 않는다
    @Test
    void callback_withOtherAttempt_isIgnored() throws Exception {
        Fixture f = caseWithPayment();
        aiReportService.request(f.caseId(), 1);
        Long reportId = reportOf(f).getId();

        callback(reportId, succeeded(reportId, "not-current", f));

        assertThat(reportOf(f).getStatus()).isEqualTo(ReportStatus.RUNNING);
    }

    // 재시도할 수 있는 실패는 새 토큰으로 다시 맡기고, 이전 토큰의 늦은 결과는 무시한다
    @Test
    void retryableFailure_resubmitsWithNewAttempt() throws Exception {
        Fixture f = caseWithPayment();
        aiReportService.request(f.caseId(), 1);
        Long reportId = reportOf(f).getId();
        String first = submittedFor(reportId).getFirst().attemptId();

        callback(reportId, failed(reportId, first, "AGENT_ERROR"));

        List<AiInvestigationRequest> sent = submittedFor(reportId);
        assertThat(sent).hasSize(2);
        String second = sent.get(1).attemptId();
        assertThat(second).isNotEqualTo(first);
        assertThat(reportOf(f).getStatus()).isEqualTo(ReportStatus.RUNNING);

        callback(reportId, succeeded(reportId, first, f));
        assertThat(reportOf(f).getStatus()).isEqualTo(ReportStatus.RUNNING);

        callback(reportId, succeeded(reportId, second, f));
        assertThat(reportOf(f).getStatus()).isEqualTo(ReportStatus.SUCCEEDED);
    }

    // 다시 해도 같은 실패(근거 누락)는 재시도 없이 끝낸다
    @Test
    void finalFailure_endsWithoutRetry() throws Exception {
        Fixture f = caseWithPayment();
        aiReportService.request(f.caseId(), 1);
        Long reportId = reportOf(f).getId();

        callback(reportId, failed(reportId, submittedFor(reportId).getFirst().attemptId(), "EVIDENCE_MISSING"));

        assertThat(reportOf(f).getStatus()).isEqualTo(ReportStatus.FAILED);
        assertThat(error(reportOf(f))).containsEntry("code", "EVIDENCE_MISSING");
        assertThat(submittedFor(reportId)).hasSize(1);
    }

    // 보고서 속 결제가 Case 소속이 아니면 저장하지 않고 실패 1회로 센다
    @Test
    void invalidBody_countsAsFailedAttempt() throws Exception {
        Fixture f = caseWithPayment();
        aiReportService.request(f.caseId(), 1);
        Long reportId = reportOf(f).getId();
        AiInvestigationResult wrong =
                succeeded(reportId, submittedFor(reportId).getFirst().attemptId(), f);
        Map<String, Object> body = new HashMap<>(wrong.reportBody());
        body.put("paymentAnalyses", List.of(analysis("999999999")));

        callback(reportId, new AiInvestigationResult(wrong.reportId(), wrong.attemptId(), "SUCCEEDED", body, null));

        assertThat(reportOf(f).getStatus()).isEqualTo(ReportStatus.RUNNING);
        assertThat(submittedFor(reportId)).hasSize(2);
    }

    // 결과 마감을 넘기면 ai-server에 묻는다 — 끝났으면 반영, 아니면 실패 1회로 다시 맡긴다
    @Test
    void overdue_takesFinishedResultFromStatus() {
        Fixture f = caseWithPayment();
        aiReportService.request(f.caseId(), 1);
        Long reportId = reportOf(f).getId();
        String attemptId = submittedFor(reportId).getFirst().attemptId();
        given(aiServerClient.status(reportId)).willReturn(Optional.of(succeeded(reportId, attemptId, f)));

        resultService.checkOverdue(Instant.now().plus(10, ChronoUnit.MINUTES));

        assertThat(reportOf(f).getStatus()).isEqualTo(ReportStatus.SUCCEEDED);
    }

    @Test
    void overdue_withoutResult_resubmits() {
        Fixture f = caseWithPayment();
        aiReportService.request(f.caseId(), 1);
        Long reportId = reportOf(f).getId();
        given(aiServerClient.status(reportId)).willReturn(Optional.empty());

        resultService.checkOverdue(Instant.now().plus(10, ChronoUnit.MINUTES));

        assertThat(submittedFor(reportId)).hasSize(2);
        assertThat(reportOf(f).getStatus()).isEqualTo(ReportStatus.RUNNING);
    }

    // 결과가 202 처리보다 먼저 와서 끝났으면 RUNNING으로 덮어쓰지 않는다
    @Test
    void markRunning_doesNotOverwriteFinishedReport() {
        Fixture f = caseWithPayment();
        AiReport report = aiReportRepository.save(AiReport.pending(f.caseId(), 1, 1, Instant.now()));
        writer.succeed(report.getId(), Map.of("summary", List.of("끝")), Instant.now());

        writer.markRunning(report.getId());

        assertThat(aiReportRepository.findById(report.getId()).orElseThrow().getStatus())
                .isEqualTo(ReportStatus.SUCCEEDED);
    }

    // 조사 중 결제가 병합돼 증거 버전이 오르면, 끝난 뒤 다음 버전 조사를 맡긴다 (ADR-0009 결정 3 완료 후 비교)
    @Test
    void finish_whenEvidenceMovedOn_requestsNextVersion() throws Exception {
        Fixture f = caseWithPayment();
        aiReportService.request(f.caseId(), 1);
        AiReport first = reportOf(f);
        SecurityCase securityCase = securityCaseRepository.findById(f.caseId()).orElseThrow();
        securityCase.increaseEvidenceVersion();
        securityCaseRepository.save(securityCase);

        callback(
                first.getId(),
                succeeded(first.getId(), submittedFor(first.getId()).getFirst().attemptId(), f));

        List<AiReport> reports = aiReportRepository.findByCaseIdOrderByReportVersionAsc(f.caseId());
        assertThat(reports)
                .extracting(AiReport::getReportVersion, AiReport::getStatus)
                .containsExactly(tuple(1, ReportStatus.SUCCEEDED), tuple(2, ReportStatus.RUNNING));
        assertThat(submittedFor(reports.get(1).getId()))
                .singleElement()
                .extracting(AiInvestigationRequest::evidenceVersion)
                .isEqualTo(2);
    }

    // 재시작 복구 — RUNNING 보고서는 ai-server에 남은 결과부터 가져온다
    @Test
    void recover_runningReport_takesResultFromAiServer() {
        Fixture f = caseWithPayment();
        AiReport report = aiReportRepository.save(AiReport.pending(f.caseId(), 1, 1, Instant.now()));
        writer.markRunning(report.getId());
        given(aiServerClient.status(report.getId()))
                .willReturn(Optional.of(succeeded(report.getId(), "before-restart", f)));

        resultService.recover(aiReportRepository.findById(report.getId()).orElseThrow());

        assertThat(aiReportRepository.findById(report.getId()).orElseThrow().getStatus())
                .isEqualTo(ReportStatus.SUCCEEDED);
        assertThat(submittedFor(report.getId())).isEmpty();
    }

    record Fixture(Long caseId, String customerRef, Long paymentId) {}

    private Fixture caseWithPayment() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String customerRef = "customer-" + UUID.randomUUID();
        SecurityCase securityCase =
                securityCaseRepository.save(SecurityCase.open(1L, customerRef, CaseType.PAYMENT_FRAUD, now));
        long paymentId = System.nanoTime();
        casePaymentRepository.save(CasePayment.link(securityCase, paymentId, now));
        return new Fixture(securityCase.getId(), customerRef, paymentId);
    }

    private AiReport reportOf(Fixture f) {
        return aiReportRepository
                .findByCaseIdOrderByReportVersionAsc(f.caseId())
                .getFirst();
    }

    private CaseStatus caseStatus(Fixture f) {
        return securityCaseRepository.findById(f.caseId()).orElseThrow().getStatus();
    }

    private List<AiInvestigationRequest> submittedFor(Long reportId) {
        return Mockito.mockingDetails(aiServerClient).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("submit"))
                .map(invocation -> (AiInvestigationRequest) invocation.getArgument(0))
                .filter(request -> request.reportId().equals(String.valueOf(reportId)))
                .toList();
    }

    private void callback(Long reportId, AiInvestigationResult result) throws Exception {
        mvc.perform(post("/internal/ai/reports/{reportId}/result", reportId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(result)))
                .andExpect(status().isOk());
    }

    private static AiInvestigationResult succeeded(Long reportId, String attemptId, Fixture f) {
        Map<String, Object> body = Map.of(
                "overview", Map.of("caseId", String.valueOf(f.caseId())),
                "summary", List.of("요약"),
                "paymentAnalyses", List.of(analysis(String.valueOf(f.paymentId()))),
                "timeline", List.of(),
                "overallAnalysis", List.of(),
                "additionalChecks", List.of(),
                "recommendation", Map.of("summary", "추가 확인", "limitations", List.of()),
                "evidence", List.of());
        return new AiInvestigationResult(String.valueOf(reportId), attemptId, "SUCCEEDED", body, null);
    }

    private static Map<String, Object> analysis(String paymentId) {
        return Map.of("paymentId", paymentId, "recommendation", "NEEDS_CHECK");
    }

    private static AiInvestigationResult failed(Long reportId, String attemptId, String code) {
        return new AiInvestigationResult(
                String.valueOf(reportId), attemptId, "FAILED", null, Map.of("code", code, "reason", "테스트"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> error(AiReport report) {
        return (Map<String, Object>) report.getReportBody().get("error");
    }
}
