package com.donworry.investigation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.SessionUserTestSupport;
import com.donworry.identity.api.CurrentStaff;
import com.donworry.identity.api.StaffAccessRecorder;
import com.donworry.identity.api.StaffSummary;
import com.donworry.investigation.entity.CasePayment;
import com.donworry.investigation.entity.CaseType;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.repository.CasePaymentRepository;
import com.donworry.investigation.repository.RawViewReviewRepository;
import com.donworry.investigation.repository.SecurityCaseRepository;
import com.donworry.payment.api.CustomerContactReader;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// 원문 열람 기록이 실패하면 원문을 내보내지 않고 사후 검토 행도 남기지 않는다 (fail closed, ADR-0082 4절 · #619)
// 기록 창구는 트랜잭션 프록시라 스파이로는 실패를 흉내 낼 수 없어 대역으로 바꾼다. 연락처 읽기도 대역(키 · 결제 준비 없이)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("mariadb")
@SpringBootTest
@AutoConfigureMockMvc
@Import({MariaDbTestContainerConfig.class, TestPaymentInfoStore.class, TestFinancialActionGateway.class})
class CaseContactRecordFailureTests {

    static final Long STAFF_ID = 7L;

    @Autowired
    MockMvc mvc;

    @Autowired
    SecurityCaseRepository securityCaseRepository;

    @Autowired
    CasePaymentRepository casePaymentRepository;

    @Autowired
    RawViewReviewRepository rawViewReviewRepository;

    @MockitoBean
    CurrentStaff currentStaff;

    @MockitoBean
    StaffAccessRecorder accessRecorder;

    @MockitoBean
    CustomerContactReader contactReader;

    // 기록 없이 원문이 나가면 사후 검토 · 탐지 모두 이 열람을 모른다
    @Test
    void rawView_whenRecordFails_returnsNothing() {
        SecurityCase securityCase =
                SecurityCase.open(1L, "customer-" + UUID.randomUUID(), CaseType.PAYMENT_FRAUD, Instant.now());
        securityCase.assignTo(STAFF_ID, Instant.now());
        Long caseId = securityCaseRepository.save(securityCase).getId();
        Long paymentId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);
        casePaymentRepository.save(CasePayment.link(securityCase, paymentId, Instant.now()));
        given(currentStaff.findCurrent())
                .willReturn(Optional.of(StaffSummary.builder()
                        .userId(STAFF_ID)
                        .role("SECURITY_STAFF")
                        .build()));
        given(contactReader.ordererPhone(paymentId)).willReturn(Optional.of("010-1234-5678"));
        willThrow(new IllegalStateException("기록 저장 실패")).given(accessRecorder).recordAllowed(any());

        assertThatThrownBy(() -> mvc.perform(SessionUserTestSupport.asCurrentStaff(
                        post("/api/security-cases/{caseId}/payments/{paymentId}/contact-raw-view", caseId, paymentId)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"disputeRef":"CB-1","merchantContactAttempt":"통화 실패","reason":"이의제기 확인"}
                                        """),
                        currentStaff)))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("기록 저장 실패");
        assertThat(rawViewReviewRepository.findByCaseId(caseId)).isEmpty();
    }
}
