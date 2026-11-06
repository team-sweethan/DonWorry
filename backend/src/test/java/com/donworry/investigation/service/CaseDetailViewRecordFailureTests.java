package com.donworry.investigation.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.SessionUserTestSupport;
import com.donworry.identity.api.CurrentStaff;
import com.donworry.identity.api.StaffAccessRecorder;
import com.donworry.identity.api.StaffSummary;
import com.donworry.investigation.entity.CaseType;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.repository.SecurityCaseRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// 열람 기록이 실패하면 상세를 내보내지 않는다 (fail closed, ADR-0082 4절 · #612)
// 기록 창구는 트랜잭션 프록시라 스파이로는 실패를 흉내 낼 수 없어 이 클래스만 대역으로 바꾼다
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("mariadb")
@SpringBootTest
@AutoConfigureMockMvc
@Import({MariaDbTestContainerConfig.class, TestPaymentInfoStore.class, TestFinancialActionGateway.class})
class CaseDetailViewRecordFailureTests {

    static final Long STAFF_ID = 7L;

    @Autowired
    MockMvc mvc;

    @Autowired
    SecurityCaseRepository securityCaseRepository;

    @MockitoBean
    CurrentStaff currentStaff;

    @MockitoBean
    StaffAccessRecorder accessRecorder;

    // 담당자라도 열람을 기록하지 못하면 상세 응답이 나가지 않는다
    @Test
    void caseDetail_whenAllowedRecordFails_doesNotReturnDetail() {
        SecurityCase securityCase =
                SecurityCase.open(1L, "customer-" + UUID.randomUUID(), CaseType.PAYMENT_FRAUD, Instant.now());
        securityCase.assignTo(STAFF_ID, Instant.now());
        Long caseId = securityCaseRepository.save(securityCase).getId();
        given(currentStaff.findCurrent())
                .willReturn(Optional.of(StaffSummary.builder()
                        .userId(STAFF_ID)
                        .role("SECURITY_STAFF")
                        .build()));
        willThrow(new IllegalStateException("기록 저장 실패")).given(accessRecorder).recordAllowed(any());

        assertThatThrownBy(() -> mvc.perform(SessionUserTestSupport.asCurrentStaff(
                        get("/api/security-cases/{caseId}", caseId), currentStaff)))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("기록 저장 실패");
    }
}
