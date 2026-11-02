package com.donworry.investigation.report.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.SessionUserTestSupport;
import com.donworry.fds.api.FdsMlTarget;
import com.donworry.fds.api.FdsResult;
import com.donworry.identity.api.CurrentStaff;
import com.donworry.identity.api.StaffSummary;
import com.donworry.investigation.report.dto.MlShadowScoreResponse;
import com.donworry.investigation.report.entity.MlRiskLevel;
import com.donworry.investigation.report.entity.MlScoreValues;
import com.donworry.investigation.report.entity.MlShadowScore;
import com.donworry.investigation.report.repository.MlShadowScoreRepository;
import com.donworry.investigation.report.service.MlShadowProperties;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

// 거래 내역 "ML 참고" 칸 조회 API (#441) — 실제 MariaDB에 기록을 넣고 HTTP 응답 계약을 확인한다
// 각 테스트는 트랜잭션 안에서 돌고 끝나면 롤백된다
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("mariadb")
@SpringBootTest
@AutoConfigureMockMvc
@Import(MariaDbTestContainerConfig.class)
@Transactional
class MlShadowScoreHttpTests {

    // DATETIME(6)은 마이크로초까지만 저장하므로 비교값도 맞춰 둔다
    static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);

    // 다른 테스트 기록과 겹치지 않게 큰 번호에서 하나씩 늘려 쓴다
    static final AtomicLong IDS = new AtomicLong(3_000_000_000L + System.nanoTime() % 1_000_000_000L);

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper mapper;

    @Autowired
    MlShadowScoreRepository mlShadowScoreRepository;

    @Autowired
    MlShadowProperties properties;

    @Autowired
    EntityManager entityManager;

    // 로그인 세션은 P1 공개 계약으로만 본다 — 보안 직원 7번
    @MockitoBean
    CurrentStaff currentStaff;

    @BeforeEach
    void loginAsSecurityStaff() {
        given(currentStaff.findCurrent())
                .willReturn(Optional.of(
                        StaffSummary.builder().userId(7L).role("SECURITY_STAFF").build()));
    }

    // 지금 모델의 기록만 결제당 1건 온다 — 다른 모델 기록 · 기록 없는 결제는 빠지고, 실패는 확률 없이 실패 코드만, 모델 입력값은 안 나간다
    @Test
    void mlShadowScores_mixedRecords_returnsCurrentModelOnePerPayment() throws Exception {
        String currentModel = properties.modelVersion();
        long scored = newId();
        long failed = newId();
        long otherModelOnly = newId();
        long noRecord = newId();
        mlShadowScoreRepository.save(MlShadowScore.succeeded(target(scored), currentModel, values(0.62, 0.3), NOW));
        mlShadowScoreRepository.save(
                MlShadowScore.succeeded(target(scored), "test-old-" + newId(), values(0.1, 0.5), NOW));
        mlShadowScoreRepository.save(MlShadowScore.failed(target(failed), currentModel, "ML_TIMEOUT", NOW));
        mlShadowScoreRepository.save(
                MlShadowScore.succeeded(target(otherModelOnly), "test-old-" + newId(), values(0.9, 0.5), NOW));
        entityManager.flush();

        String body = perform(get("/api/ml-shadow-scores")
                        .param("paymentIds", scored + "," + failed + "," + otherModelOnly + "," + noRecord))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].features").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();
        Map<Long, MlShadowScoreResponse> byPayment = Arrays.stream(
                        mapper.readValue(body, MlShadowScoreResponse[].class))
                .collect(Collectors.toMap(MlShadowScoreResponse::paymentId, Function.identity()));

        assertThat(byPayment).containsOnlyKeys(scored, failed);
        MlShadowScoreResponse success = byPayment.get(scored);
        assertThat(success.modelVersion()).isEqualTo(currentModel);
        assertThat(success.status()).isEqualTo("SUCCEEDED");
        assertThat(success.mlRiskLevel()).isEqualTo("HIGH");
        assertThat(success.fraudProbability()).isEqualByComparingTo("0.62");
        assertThat(success.threshold()).isEqualByComparingTo("0.3");
        assertThat(success.failureCode()).isNull();
        assertThat(success.scoredAt()).isEqualTo(NOW);
        MlShadowScoreResponse failure = byPayment.get(failed);
        assertThat(failure.status()).isEqualTo("FAILED");
        assertThat(failure.failureCode()).isEqualTo("ML_TIMEOUT");
        assertThat(failure.fraudProbability()).isNull();
        assertThat(failure.mlRiskLevel()).isNull();
    }

    // 결제 번호가 없거나 100개를 넘으면 400 INV_015 — 사건 칸 조회(payment-links)와 같은 규칙
    @Test
    void mlShadowScores_withoutIdsOrTooMany_returnsBadRequest() throws Exception {
        String tooMany =
                LongStream.rangeClosed(1, 101).mapToObj(String::valueOf).collect(Collectors.joining(","));

        perform(get("/api/ml-shadow-scores"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INV_015"));
        perform(get("/api/ml-shadow-scores").param("paymentIds", tooMany))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INV_015"));
    }

    private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(SessionUserTestSupport.asCurrentStaff(request, currentStaff));
    }

    private static FdsMlTarget target(long paymentId) {
        return FdsMlTarget.builder()
                .evaluationId(newId())
                .paymentId(paymentId)
                .result(FdsResult.ALLOW)
                .riskScore(30)
                .evaluatedAt(NOW)
                .build();
    }

    private static MlScoreValues values(double probability, double threshold) {
        return MlScoreValues.builder()
                .fraudProbability(probability)
                .threshold(threshold)
                .mlRiskLevel(probability >= threshold ? MlRiskLevel.HIGH : MlRiskLevel.LOW)
                .features(Map.of("amount", 600000))
                .latencyMs(20)
                .build();
    }

    private static long newId() {
        return IDS.incrementAndGet();
    }
}
