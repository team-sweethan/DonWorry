package com.donworry.investigation.report.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
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
import com.donworry.investigation.report.entity.AiReport;
import com.donworry.investigation.report.repository.AiReportRepository;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

// AI 보고서 인쇄 · PDF 저장 기록 (#623) — 허용 · 거부를 직원 접근 기록에 남긴다 (ADR-0082)
// 접근 기록 표는 직원 · 가맹점에 FK가 있어 실제 직원 행과 시드 가맹점(DEMOSHOP)을 쓴다
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("mariadb")
@SpringBootTest
@AutoConfigureMockMvc
@Import(MariaDbTestContainerConfig.class)
class AiReportPrintFlowTests {

    private static final String PRINT = "/api/security-cases/{caseId}/reports/{reportId}/print";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SecurityCaseRepository securityCaseRepository;

    @Autowired
    AiReportRepository aiReportRepository;

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

    // 담당자가 인쇄 창을 열면 사건 번호와 함께 보고서 출력 허용 기록이 남는다
    @Test
    void recordPrint_whenAllowed_savesReportPrintAccess() throws Exception {
        SecurityCase securityCase = openCase(assignee);
        Long reportId = reportOf(securityCase);

        loginAs(assignee);
        perform(securityCase.getId(), reportId).andExpect(status().isNoContent());

        Map<String, Object> allowed = new HashMap<>();
        allowed.put("result", "ALLOWED");
        allowed.put("deny_reason", null);
        allowed.put("access_type", "REPORT_PRINT");
        assertThat(events(assignee, securityCase.getId())).containsExactly(allowed);
    }

    // 담당이 아닌 직원의 열린 사건 보고서 출력은 거부되고, 시도가 거부 기록으로 남는다
    @Test
    void recordPrint_openCaseByNonAssignee_isForbiddenAndDeniedRecorded() throws Exception {
        SecurityCase securityCase = openCase(assignee);
        Long reportId = reportOf(securityCase);

        loginAs(other);
        perform(securityCase.getId(), reportId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INV_028"));

        assertThat(events(other, securityCase.getId()))
                .containsExactly(
                        Map.of("result", "DENIED", "deny_reason", "NOT_ASSIGNEE", "access_type", "REPORT_PRINT"));
    }

    // 보고서 번호만 바꿔 다른 사건 보고서의 출력을 내 사건 기록으로 남길 수 없다
    @Test
    void recordPrint_reportOfOtherCase_returnsNotFound() throws Exception {
        SecurityCase mine = openCase(assignee);
        SecurityCase another = openCase(assignee);
        Long otherReportId = reportOf(another);

        loginAs(assignee);
        perform(mine.getId(), otherReportId).andExpect(status().isNotFound());

        assertThat(events(assignee, mine.getId())).isEmpty();
        assertThat(events(assignee, another.getId())).isEmpty();
    }

    private SecurityCase openCase(Long staffId) {
        SecurityCase securityCase =
                SecurityCase.open(merchantId, "customer-" + UUID.randomUUID(), CaseType.PAYMENT_FRAUD, Instant.now());
        securityCase.assignTo(staffId, Instant.now());
        return securityCaseRepository.save(securityCase);
    }

    private Long reportOf(SecurityCase securityCase) {
        return aiReportRepository
                .save(AiReport.pending(securityCase.getId(), 1, 1, Instant.now()))
                .getId();
    }

    private Long insertStaff() {
        String login = "s623_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        jdbc.update("""
                INSERT INTO platform_users
                    (login_id,password_hash,display_name,status,role,merchant_id,created_at,password_change_required)
                VALUES (?,'unused','보고서 출력 테스트','ACTIVE','SECURITY_STAFF',NULL,UTC_TIMESTAMP(6),false)
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

    private void loginAs(Long staffId) {
        given(currentStaff.findCurrent())
                .willReturn(Optional.of(StaffSummary.builder()
                        .userId(staffId)
                        .role("SECURITY_STAFF")
                        .build()));
    }

    private ResultActions perform(Long caseId, Long reportId) throws Exception {
        return mvc.perform(SessionUserTestSupport.asCurrentStaff(
                post(PRINT, caseId, reportId).with(csrf()), currentStaff));
    }
}
