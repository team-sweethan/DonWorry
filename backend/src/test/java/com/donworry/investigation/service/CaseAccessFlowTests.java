package com.donworry.investigation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.SessionUserTestSupport;
import com.donworry.identity.api.CurrentStaff;
import com.donworry.identity.api.StaffSummary;
import com.donworry.investigation.dto.CaseDetailResponse;
import com.donworry.investigation.entity.CaseType;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.repository.PaymentDecisionRepository;
import com.donworry.investigation.repository.SecurityCaseRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

// 열린 사건은 담당자만 열람 · 처리하고, 허용 · 거부를 접근 기록에 남긴다 (#612, ADR-0082)
// 접근 기록 표는 직원 · 가맹점에 FK가 있어 실제 직원 행과 시드 가맹점(DEMOSHOP)을 쓴다
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("mariadb")
@SpringBootTest
@AutoConfigureMockMvc
@Import({MariaDbTestContainerConfig.class, TestPaymentInfoStore.class, TestFinancialActionGateway.class})
class CaseAccessFlowTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper mapper;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SecurityCaseRepository securityCaseRepository;

    @Autowired
    PaymentDecisionRepository paymentDecisionRepository;

    @Autowired
    CaseIntakeService caseIntakeService;

    @Autowired
    CaseQueryService caseQueryService;

    @Autowired
    TestPaymentInfoStore payments;

    @MockitoBean
    CurrentStaff currentStaff;

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
    }

    @AfterEach
    void cleanUp() {
        for (Long staffId : staffIds) {
            jdbc.update("DELETE FROM staff_access_events WHERE staff_id = ?", staffId);
            jdbc.update("DELETE FROM platform_users WHERE user_id = ?", staffId);
        }
        staffIds.clear();
    }

    // 담당이 아닌 직원은 진행 중인 사건 상세를 받지 못하고, 시도가 거부 기록으로 남는다
    @Test
    void caseDetail_openCaseByNonAssignee_isForbiddenAndDeniedRecorded() throws Exception {
        SecurityCase securityCase = openCase(assignee);

        loginAs(other);
        perform(get("/api/security-cases/{caseId}", securityCase.getId()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INV_028"))
                .andExpect(jsonPath("$.securityCase").doesNotExist());

        assertThat(events(other, securityCase.getId()))
                .containsExactly(
                        Map.of("result", "DENIED", "deny_reason", "NOT_ASSIGNEE", "access_type", "CASE_DETAIL_VIEW"));
    }

    // 담당자는 상세를 받고, 열람이 같은 요청에서 허용 기록으로 남는다 (읽기 전용 트랜잭션이면 기록이 실패한다)
    @Test
    void caseDetail_openCaseByAssignee_returnsDetailAndAllowedRecorded() throws Exception {
        SecurityCase securityCase = openCase(assignee);

        loginAs(assignee);
        perform(get("/api/security-cases/{caseId}", securityCase.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.securityCase.caseId").value(securityCase.getId()));

        assertThat(events(assignee, securityCase.getId())).containsExactly(allowedView());
    }

    // 종료된 사건은 담당이 아니어도 연다 — 비슷한 과거 사건 비교 화면. 열람은 기록한다
    @Test
    void caseDetail_closedCaseByOtherStaff_returnsDetailAndAllowedRecorded() throws Exception {
        SecurityCase securityCase = openCase(assignee);
        securityCase.close(Instant.now());
        securityCaseRepository.save(securityCase);

        loginAs(other);
        perform(get("/api/security-cases/{caseId}", securityCase.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.securityCase.status").value("CLOSED"));

        assertThat(events(other, securityCase.getId())).containsExactly(allowedView());
    }

    // 담당이 아닌 직원의 판정은 거부되고 판정 · 인계가 저장되지 않는다 — 돈이 움직이는 인계가 생기면 안 된다
    @Test
    void decide_byNonAssignee_isForbiddenAndNothingSaved() throws Exception {
        Long caseId = heldCase();
        assignTo(caseId, assignee);
        CaseDetailResponse detail = caseQueryService.detail(caseId);

        loginAs(other);
        perform(post("/api/security-cases/{caseId}/decisions", caseId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "evidenceVersion", detail.securityCase().evidenceVersion(),
                                "reportId", detail.reports().getLast().reportId(),
                                "decisions",
                                        List.of(Map.of(
                                                "casePaymentId",
                                                detail.payments().getFirst().casePaymentId(),
                                                "decision",
                                                "ACCEPT",
                                                "reason",
                                                "판정 사유"))))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INV_028"));

        assertThat(paymentDecisionRepository.findByCasePaymentSecurityCaseId(caseId))
                .isEmpty();
        assertThat(events(other, caseId))
                .containsExactly(
                        Map.of("result", "DENIED", "deny_reason", "NOT_ASSIGNEE", "access_type", "CASE_DECISION"));
    }

    // 보고서 다시 요청 · 인계 다시 확인 · 보고서 진행 조회도 담당자만 — 응답에 상세 · 진행 정보가 실린다
    @ParameterizedTest
    @CsvSource({
        "POST, /api/security-cases/{caseId}/reports, REPORT_REQUEST",
        "POST, /api/security-cases/{caseId}/handoffs/1/recheck, HANDOFF_RECHECK",
        "GET, /api/security-cases/{caseId}/reports/1/progress, REPORT_PROGRESS_VIEW"
    })
    void assigneeOnlyActions_byNonAssignee_areForbiddenAndDeniedRecorded(String method, String path, String accessType)
            throws Exception {
        SecurityCase securityCase = openCase(assignee);

        loginAs(other);
        perform(request(HttpMethod.valueOf(method), path, securityCase.getId()).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INV_028"));

        assertThat(events(other, securityCase.getId()))
                .containsExactly(Map.of("result", "DENIED", "deny_reason", "NOT_ASSIGNEE", "access_type", accessType));
    }

    private SecurityCase openCase(Long staffId) {
        SecurityCase securityCase = SecurityCase.open(merchantId, customer(), CaseType.PAYMENT_FRAUD, Instant.now());
        securityCase.assignTo(staffId, Instant.now());
        return securityCaseRepository.save(securityCase);
    }

    // P5 HELD 이벤트 대신 테스트 결제를 Case 접수로 넘긴다 — 보고서가 나와 판정할 수 있는 사건이 된다
    private Long heldCase() {
        Instant now = Instant.now();
        Long paymentId = payments.registerHeld(300_000L, 65, TestPaymentInfoStore.DEFAULT_RULES, now)
                .paymentId();
        return caseIntakeService.receive(
                new HeldPayment(paymentId, merchantId, customer(), 300_000L, "TEST-AUTH-" + paymentId, null, now));
    }

    // 자동 배정은 시드 직원에게 가므로 테스트 직원으로 바꾼다
    private void assignTo(Long caseId, Long staffId) {
        SecurityCase securityCase = securityCaseRepository.findById(caseId).orElseThrow();
        securityCase.assignTo(staffId, Instant.now());
        securityCaseRepository.save(securityCase);
    }

    private Long insertStaff() {
        String login = "s612_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        jdbc.update("""
                INSERT INTO platform_users
                    (login_id,password_hash,display_name,status,role,merchant_id,created_at,password_change_required)
                VALUES (?,'unused','담당 판단 테스트','ACTIVE','SECURITY_STAFF',NULL,UTC_TIMESTAMP(6),false)
                """, login);
        Long staffId = jdbc.queryForObject("SELECT user_id FROM platform_users WHERE login_id = ?", Long.class, login);
        staffIds.add(staffId);
        return staffId;
    }

    private List<Map<String, Object>> events(Long staffId, Long caseId) {
        return jdbc.queryForList(
                "SELECT result, deny_reason, access_type FROM staff_access_events WHERE staff_id = ? AND case_id = ?"
                        + " ORDER BY event_id",
                staffId,
                caseId);
    }

    private static Map<String, Object> allowedView() {
        Map<String, Object> row = new HashMap<>();
        row.put("result", "ALLOWED");
        row.put("deny_reason", null);
        row.put("access_type", "CASE_DETAIL_VIEW");
        return row;
    }

    private void loginAs(Long staffId) {
        given(currentStaff.findCurrent())
                .willReturn(Optional.of(StaffSummary.builder()
                        .userId(staffId)
                        .role("SECURITY_STAFF")
                        .build()));
    }

    private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(SessionUserTestSupport.asCurrentStaff(request, currentStaff));
    }

    private static String customer() {
        return "customer-" + UUID.randomUUID();
    }
}
