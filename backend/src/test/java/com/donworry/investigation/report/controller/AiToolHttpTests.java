package com.donworry.investigation.report.controller;

import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.investigation.entity.CasePayment;
import com.donworry.investigation.entity.CaseType;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.report.client.AiServerClient;
import com.donworry.investigation.repository.CasePaymentRepository;
import com.donworry.investigation.repository.SecurityCaseRepository;
import com.donworry.investigation.service.PaymentInfoReader;
import com.donworry.investigation.service.PaymentInfoReader.PaymentInfo;
import com.donworry.investigation.service.PaymentInfoReader.RuleHit;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

// AI 서버가 부르는 읽기 Tool 2종을 HTTP로 확인한다 (ADR-0009 결정 1)
// 결제 · FDS 값(P5 · P2)은 목으로 바꿔 Tool 응답 모양과 Case 범위 검증만 본다
// 이 테스트만 쓰는 설정이라 컨텍스트가 따로 뜬다 — 끝나면 닫아 공유 MariaDB의 연결 한도를 넘지 않는다
// Tool API는 ai-server를 켰을 때만 생겨 이 테스트에서만 켠다 (기본 비활성은 AiToolControllerConditionTests)
// 요청마다 ai-server와 같은 서비스 키 헤더를 붙인다. ai-server 호출은 목으로 막아 재시작 복구가 밖으로 나가지 않게 한다
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("mariadb")
@SpringBootTest(properties = {"ai.server.enabled=true", "ai.server.api-key=" + AiToolHttpTests.API_KEY})
@AutoConfigureMockMvc
@Import(MariaDbTestContainerConfig.class)
class AiToolHttpTests {

    static final String API_KEY = "test-ai-key";
    static final String CASE_PAYMENTS = "/internal/ai/tools/get_case_payments";
    static final String FDS_EVIDENCE = "/internal/ai/tools/get_fds_evidence";

    static final Instant LINKED_AT = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @Autowired
    MockMvc mvc;

    @Autowired
    SecurityCaseRepository securityCaseRepository;

    @Autowired
    CasePaymentRepository casePaymentRepository;

    @MockitoBean
    PaymentInfoReader paymentInfoReader;

    @MockitoBean
    AiServerClient aiServerClient;

    // Case에 묶인 결제가 P5 값 그대로 나오고, 증거 추적용 출처 · 버전 · 조회 시각이 붙는다. ID는 문자열이다
    @Test
    void casePayments_returnsLinkedPaymentsWithTrace() throws Exception {
        SecurityCase securityCase = openCase();
        long first = link(securityCase);
        long second = link(securityCase);
        givenInfo(info(first, 600_000L, 65), info(second, 120_000L, 30));

        mvc.perform(get(CASE_PAYMENTS).param("caseId", securityCase.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value(securityCase.getId().toString()))
                .andExpect(jsonPath("$.evidenceVersion").value(1))
                .andExpect(jsonPath("$.source").value("P3·P5"))
                .andExpect(jsonPath("$.retrievedAt").isNotEmpty())
                .andExpect(jsonPath("$.payments.length()").value(2))
                .andExpect(jsonPath("$.payments[0].paymentId").value(String.valueOf(first)))
                .andExpect(jsonPath("$.payments[0].amount").value(600_000))
                .andExpect(jsonPath("$.payments[0].paymentStatus").value("HELD"))
                .andExpect(jsonPath("$.missingPaymentIds").isEmpty());
    }

    // 결제별 FDS 점수 · 판정 · 적중 룰이 P2 값 그대로 나온다
    @Test
    void fdsEvidence_returnsScoreAndRuleHits() throws Exception {
        SecurityCase securityCase = openCase();
        long paymentId = link(securityCase);
        givenInfo(info(paymentId, 600_000L, 65));

        mvc.perform(get(FDS_EVIDENCE).param("caseId", securityCase.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("P2"))
                .andExpect(jsonPath("$.payments[0].paymentId").value(String.valueOf(paymentId)))
                .andExpect(jsonPath("$.payments[0].riskScore").value(65))
                .andExpect(jsonPath("$.payments[0].fdsResult").value("REVIEW"))
                .andExpect(jsonPath("$.payments[0].ruleHits[0].code").value("HIGH_AMOUNT"))
                .andExpect(jsonPath("$.payments[0].ruleHits[0].name").value("고액 결제"))
                .andExpect(jsonPath("$.payments[0].ruleHits[0].appliedScore").value(35))
                .andExpect(jsonPath("$.missingPaymentIds").isEmpty());
    }

    // 적중 룰의 기준값 · 실제값은 정리한 문장만 나가고 원본 JSON은 나가지 않는다 (#326)
    @Test
    void getFdsEvidence_ruleHit_includesCriterionAndObserved() throws Exception {
        SecurityCase securityCase = openCase();
        long paymentId = link(securityCase);
        givenInfo(new PaymentInfo(
                paymentId,
                600_000L,
                "HELD",
                Instant.now(),
                30,
                "REVIEW",
                List.of(new RuleHit(
                        "R01", "고액 결제", 30, "{\"amountGte\":500000,\"score\":30}", "{\"amount\":600000}"))));

        mvc.perform(get(FDS_EVIDENCE).param("caseId", securityCase.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payments[0].ruleHits[0].criterion").value("50만 원 이상"))
                .andExpect(jsonPath("$.payments[0].ruleHits[0].observed").value("60만 원"))
                .andExpect(
                        jsonPath("$.payments[0].ruleHits[0].thresholdSnapshot").doesNotExist())
                .andExpect(jsonPath("$.payments[0].ruleHits[0].observedValue").doesNotExist());
    }

    // paymentIds를 주면 그 결제만 나온다
    @Test
    void fdsEvidence_withPaymentIds_returnsOnlyThose() throws Exception {
        SecurityCase securityCase = openCase();
        link(securityCase);
        long second = link(securityCase);
        givenInfo(info(second, 120_000L, 30));

        mvc.perform(get(FDS_EVIDENCE)
                        .param("caseId", securityCase.getId().toString())
                        .param("paymentIds", String.valueOf(second)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payments.length()").value(1))
                .andExpect(jsonPath("$.payments[0].paymentId").value(String.valueOf(second)));
    }

    // 다른 Case의 결제를 섞어 요청하면 거부한다 — 다른 Case · 가맹점 데이터가 새지 않게
    @Test
    void fdsEvidence_paymentOfOtherCase_isRejected() throws Exception {
        SecurityCase mine = openCase();
        link(mine);
        long othersPayment = link(openCase());

        mvc.perform(get(FDS_EVIDENCE)
                        .param("caseId", mine.getId().toString())
                        .param("paymentIds", String.valueOf(othersPayment)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INV_006"));
    }

    @Test
    void casePayments_unknownCase_returnsNotFound() throws Exception {
        mvc.perform(get(CASE_PAYMENTS).param("caseId", String.valueOf(Long.MAX_VALUE)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INV_001"));
    }

    @Test
    void casePayments_withoutCaseId_returnsBadRequest() throws Exception {
        mvc.perform(get(CASE_PAYMENTS)).andExpect(status().isBadRequest());
        mvc.perform(get(CASE_PAYMENTS).param("caseId", "abc")).andExpect(status().isBadRequest());
    }

    // 값을 못 찾은 결제는 추측값 없이 null로 두고 누락 목록에 넣는다
    @Test
    void tools_missingInfo_returnsNullAndMissingList() throws Exception {
        SecurityCase securityCase = openCase();
        long found = link(securityCase);
        long missing = link(securityCase);
        givenInfo(info(found, 600_000L, 65));

        mvc.perform(get(CASE_PAYMENTS).param("caseId", securityCase.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payments[1].paymentId").value(String.valueOf(missing)))
                .andExpect(jsonPath("$.payments[1].amount").isEmpty())
                .andExpect(jsonPath("$.missingPaymentIds[0]").value(String.valueOf(missing)));
        mvc.perform(get(FDS_EVIDENCE).param("caseId", securityCase.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payments[1].riskScore").isEmpty())
                .andExpect(jsonPath("$.payments[1].ruleHits").isEmpty())
                .andExpect(jsonPath("$.missingPaymentIds[0]").value(String.valueOf(missing)));
    }

    // Tool은 읽기 전용이다 — 바꾸는 요청은 받지 않는다
    @Test
    void tools_rejectWriteMethods() throws Exception {
        mvc.perform(post(CASE_PAYMENTS).param("caseId", "1")).andExpect(status().isMethodNotAllowed());
        mvc.perform(post(FDS_EVIDENCE).param("caseId", "1")).andExpect(status().isMethodNotAllowed());
    }

    // 서비스 키가 없거나 틀리면 보안 체인이 먼저 막아 결제 · FDS 근거를 내주지 않는다 (ADR-0041)
    @Test
    void tools_withoutValidKey_returnUnauthorized() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get(CASE_PAYMENTS).param("caseId", "1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AI_SERVER_AUTH_001"));
        mvc.perform(MockMvcRequestBuilders.get(FDS_EVIDENCE)
                        .param("caseId", "1")
                        .header(AiServerClient.API_KEY_HEADER, "wrong-key"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AI_SERVER_AUTH_001"));
    }

    private SecurityCase openCase() {
        return securityCaseRepository.save(SecurityCase.open(
                1L,
                "customer-" + UUID.randomUUID(),
                CaseType.PAYMENT_FRAUD,
                Instant.now().truncatedTo(ChronoUnit.MICROS)));
    }

    // 응답은 연결 시각 순이다 — 같은 시각으로 겹치지 않게 연결할 때마다 1초씩 늦춘다
    private long link(SecurityCase securityCase) {
        long paymentId = System.nanoTime();
        int order = casePaymentRepository
                .findBySecurityCaseIdOrderByLinkedAtAsc(securityCase.getId())
                .size();
        casePaymentRepository.save(CasePayment.link(securityCase, paymentId, LINKED_AT.plusSeconds(order)));
        return paymentId;
    }

    private void givenInfo(PaymentInfo... infos) {
        Map<Long, PaymentInfo> found = new HashMap<>();
        for (PaymentInfo info : infos) {
            found.put(info.paymentId(), info);
        }
        given(paymentInfoReader.findAll(anyCollection())).willReturn(found);
    }

    private static PaymentInfo info(long paymentId, long amount, int riskScore) {
        return new PaymentInfo(
                paymentId,
                amount,
                "HELD",
                Instant.now(),
                riskScore,
                "REVIEW",
                List.of(new RuleHit("HIGH_AMOUNT", "고액 결제", 35)));
    }

    // ai-server처럼 요청마다 서비스 키를 붙인다
    private static MockHttpServletRequestBuilder get(String url) {
        return MockMvcRequestBuilders.get(url).header(AiServerClient.API_KEY_HEADER, API_KEY);
    }

    private static MockHttpServletRequestBuilder post(String url) {
        return MockMvcRequestBuilders.post(url).header(AiServerClient.API_KEY_HEADER, API_KEY);
    }
}
