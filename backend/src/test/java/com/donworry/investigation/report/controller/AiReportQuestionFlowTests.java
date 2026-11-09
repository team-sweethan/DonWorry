package com.donworry.investigation.report.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.SessionUserTestSupport;
import com.donworry.identity.api.CurrentStaff;
import com.donworry.identity.api.StaffSummary;
import com.donworry.investigation.entity.CaseType;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.report.client.AiQuestionOutcome;
import com.donworry.investigation.report.client.AiServerClient;
import com.donworry.investigation.report.dto.AiQuestionRequest;
import com.donworry.investigation.report.dto.AiQuestionResult;
import com.donworry.investigation.report.entity.AiReport;
import com.donworry.investigation.report.entity.AiReportQuestion;
import com.donworry.investigation.report.entity.QuestionStatus;
import com.donworry.investigation.report.repository.AiReportQuestionRepository;
import com.donworry.investigation.report.repository.AiReportRepository;
import com.donworry.investigation.report.service.AiSwitchService;
import com.donworry.investigation.repository.SecurityCaseRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

// 보고서 뒤 사건 질문 · 답변 (#752, ADR-0086) — 권한 · 대상 · 긴급정지 · 횟수 상한 · 저장 · 접근 기록을 실제 MariaDB로 본다
// ai-server · AI 스위치는 대역. 접근 기록 표는 직원 · 가맹점에 FK가 있어 실제 직원 행과 시드 가맹점(DEMOSHOP)을 쓴다
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("mariadb")
@SpringBootTest
@AutoConfigureMockMvc
@Import(MariaDbTestContainerConfig.class)
class AiReportQuestionFlowTests {

    private static final String QUESTIONS = "/api/security-cases/{caseId}/reports/{reportId}/questions";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SecurityCaseRepository securityCaseRepository;

    @Autowired
    AiReportRepository aiReportRepository;

    @Autowired
    AiReportQuestionRepository questionRepository;

    @MockitoBean
    CurrentStaff currentStaff;

    @MockitoBean
    AiServerClient aiServerClient;

    @MockitoBean
    AiSwitchService aiSwitchService;

    private final List<Long> staffIds = new ArrayList<>();
    private Long assignee;
    private Long other;
    private Long merchantId;

    @BeforeEach
    void setUp() {
        assignee = insertStaff();
        other = insertStaff();
        merchantId =
                jdbc.queryForObject("SELECT merchant_id FROM merchants WHERE merchant_code = 'DEMOSHOP'", Long.class);
        given(aiSwitchService.isAiAllowed()).willReturn(true);
        // 대역 스위치도 "켜짐 확인 → 저장"(whenAiAllowed)을 isAiAllowed 값대로 흉내 낸다
        given(aiSwitchService.whenAiAllowed(any()))
                .willAnswer(invocation -> aiSwitchService.isAiAllowed()
                        ? Optional.ofNullable(((Supplier<?>) invocation.getArgument(0)).get())
                        : Optional.empty());
    }

    @AfterEach
    void cleanUp() {
        for (Long staffId : staffIds) {
            jdbc.update("DELETE FROM ai_report_questions WHERE staff_id = ?", staffId);
            jdbc.update("DELETE FROM staff_access_events WHERE staff_id = ?", staffId);
            jdbc.update("DELETE FROM platform_users WHERE user_id = ?", staffId);
        }
        staffIds.clear();
    }

    // 답을 받으면 질문 · 답을 저장하고, 질문 접수를 사건 번호와 함께 접근 기록에 남긴다
    @Test
    void askQuestion_whenAnswered_storesAnswerAndAccessRecord() throws Exception {
        SecurityCase securityCase = openCase(assignee);
        AiReport report = aiReport(securityCase);
        answerWith("ANSWERED", "ANSWER");

        loginAs(assignee);
        ask(securityCase.getId(), report.getId(), "같은 IP 결제는 언제였나요?")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANSWERED"))
                .andExpect(jsonPath("$.kind").value("ANSWER"))
                .andExpect(jsonPath("$.sentences[0].text").value("같은 IP 결제는 3건입니다."));

        AiReportQuestion saved = onlyQuestionOf(assignee);
        assertThat(saved.getStatus()).isEqualTo(QuestionStatus.ANSWERED);
        assertThat(saved.getQuestion()).isEqualTo("같은 IP 결제는 언제였나요?");
        assertThat(saved.getCaseId()).isEqualTo(securityCase.getId());
        assertThat(saved.getAnswerBody()).containsEntry("kind", "ANSWER");
        assertThat(saved.getAnsweredAt()).isNotNull();
        assertThat(events(assignee, securityCase.getId()))
                .containsExactly(Map.of("result", "ALLOWED", "access_type", "REPORT_QUESTION"));
    }

    // ai-server 시간 초과면 질문은 FAILED로 끝나고, 사건 상태 · 판정은 그대로다 (자동 ACCEPT · REJECT 없음)
    @Test
    void askQuestion_whenAiServerFails_storesFailedWithoutChangingCase() throws Exception {
        SecurityCase securityCase = openCase(assignee);
        AiReport report = aiReport(securityCase);
        String caseStatusBefore = caseStatus(securityCase.getId());
        given(aiServerClient.ask(any())).willReturn(new AiQuestionOutcome.Failed("TIMEOUT", "ai-server 응답 시간 초과"));

        loginAs(assignee);
        ask(securityCase.getId(), report.getId(), "이 고객 평소 결제는?")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.kind").doesNotExist());

        AiReportQuestion saved = onlyQuestionOf(assignee);
        assertThat(saved.getStatus()).isEqualTo(QuestionStatus.FAILED);
        assertThat(saved.getAnswerBody())
                .containsEntry("error", Map.of("code", "TIMEOUT", "reason", "ai-server 응답 시간 초과"));
        assertThat(caseStatus(securityCase.getId())).isEqualTo(caseStatusBefore);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM payment_decisions WHERE report_id = ?", Long.class, report.getId()))
                .isZero();
    }

    // 같은 보고서에 최근 24시간 30개를 이미 물었으면 AI를 부르지 않고 질문 줄도 만들지 않는다
    @Test
    void askQuestion_whenDailyLimitReached_rejectsWithoutCallingAi() throws Exception {
        SecurityCase securityCase = openCase(assignee);
        AiReport report = aiReport(securityCase);
        for (int i = 0; i < 30; i++) {
            questionRepository.save(AiReportQuestion.asked(report, assignee, "질문 " + i, Instant.now()));
        }

        loginAs(assignee);
        ask(securityCase.getId(), report.getId(), "31번째 질문").andExpect(status().isTooManyRequests());

        verify(aiServerClient, never()).ask(any());
        assertThat(questionCount(assignee)).isEqualTo(30);
        assertThat(events(assignee, securityCase.getId())).isEmpty();
    }

    // AI 긴급정지 중이면 질문을 받지 않는다 — 사건 정보가 ai-server · LLM으로 나가지 않는다
    @Test
    void askQuestion_whenAiStopped_rejectsWithoutCallingAi() throws Exception {
        SecurityCase securityCase = openCase(assignee);
        AiReport report = aiReport(securityCase);
        given(aiSwitchService.isAiAllowed()).willReturn(false);

        loginAs(assignee);
        ask(securityCase.getId(), report.getId(), "신규 기기로 한 결제는?").andExpect(status().isServiceUnavailable());

        verify(aiServerClient, never()).ask(any());
        assertThat(questionCount(assignee)).isZero();
    }

    // AI 정지 중 Spring이 만든 고정 보고서에는 묻지 못한다
    @Test
    void askQuestion_onFixedReport_rejects() throws Exception {
        SecurityCase securityCase = openCase(assignee);
        AiReport report = aiReportPending(securityCase);
        report.succeed(Map.of("investigationTrace", Map.of("provider", "FIXED")), Instant.now());
        report = aiReportRepository.save(report);

        loginAs(assignee);
        ask(securityCase.getId(), report.getId(), "신규 기기로 한 결제는?").andExpect(status().isConflict());

        verify(aiServerClient, never()).ask(any());
        assertThat(questionCount(assignee)).isZero();
    }

    // 담당이 아닌 직원은 열린 사건 보고서에 묻지 못하고, 시도가 거부 기록으로 남는다
    @Test
    void askQuestion_whenNotAssigneeOfOpenCase_rejectsAndRecordsDenied() throws Exception {
        SecurityCase securityCase = openCase(assignee);
        AiReport report = aiReport(securityCase);

        loginAs(other);
        ask(securityCase.getId(), report.getId(), "같은 IP 결제는 언제였나요?")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INV_028"));

        verify(aiServerClient, never()).ask(any());
        assertThat(questionCount(other)).isZero();
        assertThat(events(other, securityCase.getId()))
                .containsExactly(Map.of("result", "DENIED", "access_type", "REPORT_QUESTION"));
    }

    // 보고서 번호만 바꿔 다른 사건 보고서에 내 사건 권한으로 묻지 못한다
    @Test
    void askQuestion_reportOfOtherCase_returnsNotFound() throws Exception {
        SecurityCase mine = openCase(assignee);
        SecurityCase another = openCase(other);
        AiReport othersReport = aiReport(another);

        loginAs(assignee);
        ask(mine.getId(), othersReport.getId(), "같은 IP 결제는 언제였나요?").andExpect(status().isNotFound());

        verify(aiServerClient, never()).ask(any());
        assertThat(questionCount(assignee)).isZero();
        assertThat(events(assignee, mine.getId())).isEmpty();
    }

    // 이어서 묻기 — 같은 보고서 · 같은 직원의 끝난 답 최근 3개만, 물은 순서대로 ai-server에 보낸다 (실패 · 다른 직원 · 다른 보고서 제외)
    @Test
    void askQuestion_withPreviousTurns_sendsLatestThreeOwnSuccessfulTurns() throws Exception {
        SecurityCase securityCase = openCase(assignee);
        AiReport report = aiReport(securityCase);
        AiReport otherReport = aiReport(securityCase, 2);
        answered(report, assignee, "첫 질문");
        answered(report, assignee, "둘째 질문");
        answered(report, other, "다른 직원 질문");
        answered(otherReport, assignee, "다른 보고서 질문");
        answered(report, assignee, "셋째 질문");
        answered(report, assignee, "넷째 질문");
        AiReportQuestion failed = AiReportQuestion.asked(report, assignee, "실패한 질문", Instant.now());
        failed.fail(Map.of("code", "TIMEOUT", "reason", "시간 초과"), Instant.now());
        questionRepository.save(failed);
        answerWith("ANSWERED", "ANSWER");

        loginAs(assignee);
        ask(securityCase.getId(), report.getId(), "그럼 2번 결제는요?").andExpect(status().isOk());

        ArgumentCaptor<AiQuestionRequest> sent = ArgumentCaptor.forClass(AiQuestionRequest.class);
        verify(aiServerClient).ask(sent.capture());
        assertThat(sent.getValue().previous())
                .extracting(AiQuestionRequest.PreviousTurn::question)
                .containsExactly("둘째 질문", "셋째 질문", "넷째 질문");
        assertThat(sent.getValue().previous().getFirst().kind()).isEqualTo("ANSWER");
    }

    private void answerWith(String status, String kind) {
        given(aiServerClient.ask(any())).willAnswer(invocation -> {
            AiQuestionRequest request = invocation.getArgument(0);
            return new AiQuestionOutcome.Answered(AiQuestionResult.builder()
                    .questionId(request.questionId())
                    .status(status)
                    .kind(kind)
                    .sentences(List.of(Map.of("text", "같은 IP 결제는 3건입니다.", "facts", List.of("연결-1"))))
                    .checks(List.of())
                    .facts(List.of(Map.of("id", "연결-1", "text", "같은 IP 결제 3건", "source", "REPORT")))
                    .toolCalls(List.of())
                    .trace(List.of())
                    .build());
        });
    }

    private void answered(AiReport report, Long staffId, String question) {
        AiReportQuestion row = AiReportQuestion.asked(report, staffId, question, Instant.now());
        Map<String, Object> body = new HashMap<>();
        body.put("kind", "ANSWER");
        body.put("sentences", List.of(Map.of("text", question + " 답", "facts", List.of("연결-1"))));
        body.put("facts", List.of());
        row.answer(QuestionStatus.ANSWERED, body, Instant.now());
        questionRepository.save(row);
    }

    private SecurityCase openCase(Long staffId) {
        SecurityCase securityCase =
                SecurityCase.open(merchantId, "customer-" + UUID.randomUUID(), CaseType.PAYMENT_FRAUD, Instant.now());
        securityCase.assignTo(staffId, Instant.now());
        return securityCaseRepository.save(securityCase);
    }

    private AiReport aiReport(SecurityCase securityCase) {
        return aiReport(securityCase, 1);
    }

    // AI가 만든 성공 보고서 (provider OPENAI)
    private AiReport aiReport(SecurityCase securityCase, int version) {
        AiReport report = aiReportRepository.save(AiReport.pending(securityCase.getId(), version, 1, Instant.now()));
        report.succeed(Map.of("investigationTrace", Map.of("provider", "OPENAI")), Instant.now());
        return aiReportRepository.save(report);
    }

    private AiReport aiReportPending(SecurityCase securityCase) {
        return aiReportRepository.save(AiReport.pending(securityCase.getId(), 1, 1, Instant.now()));
    }

    private AiReportQuestion onlyQuestionOf(Long staffId) {
        Long questionId = jdbc.queryForObject(
                "SELECT question_id FROM ai_report_questions WHERE staff_id = ?", Long.class, staffId);
        return questionRepository.findById(questionId).orElseThrow();
    }

    private long questionCount(Long staffId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ai_report_questions WHERE staff_id = ?", Long.class, staffId);
    }

    private String caseStatus(Long caseId) {
        return jdbc.queryForObject("SELECT status FROM security_cases WHERE case_id = ?", String.class, caseId);
    }

    private Long insertStaff() {
        String login = "s752_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        jdbc.update("""
                INSERT INTO platform_users
                    (login_id,password_hash,display_name,status,role,merchant_id,created_at,password_change_required)
                VALUES (?,'unused','사건 질문 테스트','ACTIVE','SECURITY_STAFF',NULL,UTC_TIMESTAMP(6),false)
                """, login);
        Long staffId = jdbc.queryForObject("SELECT user_id FROM platform_users WHERE login_id = ?", Long.class, login);
        staffIds.add(staffId);
        return staffId;
    }

    private List<Map<String, Object>> events(Long staffId, Long caseId) {
        return jdbc.queryForList(
                "SELECT result, access_type FROM staff_access_events WHERE staff_id = ? AND case_id = ?"
                        + " ORDER BY event_id",
                staffId,
                caseId);
    }

    private void loginAs(Long staffId) {
        given(currentStaff.findCurrent())
                .willReturn(Optional.of(StaffSummary.builder()
                        .userId(staffId)
                        .role("SECURITY_STAFF")
                        .build()));
    }

    private ResultActions ask(Long caseId, Long reportId, String question) throws Exception {
        return mvc.perform(SessionUserTestSupport.asCurrentStaff(
                post(QUESTIONS, caseId, reportId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"" + question + "\"}"),
                currentStaff));
    }
}
