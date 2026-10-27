package com.donworry.investigation.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.investigation.entity.CasePayment;
import com.donworry.investigation.entity.CaseType;
import com.donworry.investigation.entity.SecurityCase;
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
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

// 실제 MariaDB에서 P4 보고서 요청 · 저장 · 조회를 확인한다 (ADR-0009 결정 2 · 3)
// 결제 근거(P5 · P2)는 목으로 바꿔 보고서 결과만 본다. 저장이 커밋돼야 하므로 롤백 테스트가 아니다
// 이 테스트만 쓰는 설정이라 컨텍스트가 따로 뜬다 — 끝나면 닫아 공유 MariaDB의 연결 한도를 넘지 않는다
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
class AiReportServiceTests {

    @Autowired
    AiReportService aiReportService;

    @Autowired
    AiReportRepository aiReportRepository;

    @Autowired
    SecurityCaseRepository securityCaseRepository;

    @Autowired
    CasePaymentRepository casePaymentRepository;

    @MockitoBean
    PaymentInfoReader paymentInfoReader;

    // 근거가 다 있으면 SUCCEEDED 보고서가 DB에 남고, 화면용 조회에도 8개 섹션 본문이 나온다
    @Test
    void request_withEvidence_savesSucceededReport() {
        SecurityCase securityCase = caseWithPayment();
        givenEvidenceFor(securityCase);

        aiReportService.request(securityCase.getId(), 1);

        AiReport saved = reportsOf(securityCase).getFirst();
        assertThat(saved.getStatus()).isEqualTo(ReportStatus.SUCCEEDED);
        assertThat(saved.getReportVersion()).isEqualTo(1);
        assertThat(saved.getEvidenceVersion()).isEqualTo(1);
        assertThat(saved.getCompletedAt()).isNotNull();
        assertThat(aiReportService.findByCase(securityCase.getId()))
                .singleElement()
                .satisfies(snapshot -> {
                    assertThat(snapshot.isSucceeded()).isTrue();
                    assertThat(snapshot.reportBody())
                            .containsOnlyKeys(
                                    "overview",
                                    "summary",
                                    "paymentAnalyses",
                                    "timeline",
                                    "overallAnalysis",
                                    "additionalChecks",
                                    "recommendation",
                                    "evidence");
                });
    }

    // 같은 증거 버전을 두 번 요청해도 보고서는 하나다
    @Test
    void request_sameEvidenceVersionTwice_keepsOneReport() {
        SecurityCase securityCase = caseWithPayment();
        givenEvidenceFor(securityCase);

        aiReportService.request(securityCase.getId(), 1);
        aiReportService.request(securityCase.getId(), 1);

        assertThat(reportsOf(securityCase)).hasSize(1);
    }

    // 결제 근거가 빠지면 FAILED로 끝내고 error는 DB에만 남긴다 — 화면용 조회의 본문은 null
    @Test
    void request_missingEvidence_savesFailedWithHiddenError() {
        SecurityCase securityCase = caseWithPayment();
        given(paymentInfoReader.findAll(anyCollection())).willReturn(Map.of());

        aiReportService.request(securityCase.getId(), 1);

        AiReport saved = reportsOf(securityCase).getFirst();
        assertThat(saved.getStatus()).isEqualTo(ReportStatus.FAILED);
        assertThat(saved.getReportBody()).containsKey("error");
        assertThat(error(saved)).containsEntry("code", "EVIDENCE_MISSING");
        assertThat(aiReportService.findByCase(securityCase.getId()))
                .singleElement()
                .satisfies(snapshot -> {
                    assertThat(snapshot.isFailed()).isTrue();
                    assertThat(snapshot.reportBody()).isNull();
                });
    }

    // 조회 오류가 계속되면 총 3회 시도한 뒤 FAILED로 끝낸다
    @Test
    void request_lookupKeepsFailing_failsAfterThreeAttempts() {
        SecurityCase securityCase = caseWithPayment();
        given(paymentInfoReader.findAll(anyCollection())).willThrow(new IllegalStateException("P5 응답 없음"));

        aiReportService.request(securityCase.getId(), 1);

        AiReport saved = reportsOf(securityCase).getFirst();
        assertThat(saved.getStatus()).isEqualTo(ReportStatus.FAILED);
        assertThat(error(saved)).containsEntry("code", "GENERATION_ERROR").containsEntry("attempts", 3);
        verify(paymentInfoReader, times(3)).findAll(anyCollection());
    }

    // 결제가 병합돼 증거 버전이 오르면 다음 보고서 버전이 쌓인다
    @Test
    void request_newEvidenceVersion_addsNextReportVersion() {
        SecurityCase securityCase = caseWithPayment();
        givenEvidenceFor(securityCase);
        aiReportService.request(securityCase.getId(), 1);

        securityCase.increaseEvidenceVersion();
        securityCaseRepository.save(securityCase);
        aiReportService.request(securityCase.getId(), 2);

        assertThat(reportsOf(securityCase))
                .extracting(AiReport::getReportVersion, AiReport::getEvidenceVersion)
                .containsExactly(tuple(1, 1), tuple(2, 2));
    }

    // 같은 Case에 같은 요청이 동시에 와도 UNIQUE 오류 없이 보고서 하나로 합류한다 (ADR-0009 결정 3)
    @Test
    void request_concurrentSameCase_joinsIntoOneReport() throws Exception {
        SecurityCase securityCase = caseWithPayment();
        givenEvidenceFor(securityCase);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Void> request = () -> {
            start.await();
            aiReportService.request(securityCase.getId(), 1);
            return null;
        };
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Void> first = executor.submit(request);
            Future<Void> second = executor.submit(request);
            start.countDown();
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        }

        assertThat(reportsOf(securityCase))
                .singleElement()
                .extracting(AiReport::getStatus)
                .isEqualTo(ReportStatus.SUCCEEDED);
    }

    // 끝난 보고서는 다른 결과로 덮어쓰지 않는다 (ADR-0009 결정 2)
    @Test
    void finishedReport_isNotOverwritten() {
        Instant now = Instant.now();
        AiReport report = AiReport.pending(1L, 1, 1, now);

        assertThat(report.fail(Map.of("code", "EVIDENCE_MISSING"), now)).isTrue();
        assertThat(report.succeed(Map.of("summary", List.of()), now)).isFalse();
        assertThat(report.getStatus()).isEqualTo(ReportStatus.FAILED);
    }

    private SecurityCase caseWithPayment() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        SecurityCase securityCase = securityCaseRepository.save(
                SecurityCase.open(1L, "customer-" + UUID.randomUUID(), CaseType.PAYMENT_FRAUD, now));
        casePaymentRepository.save(CasePayment.link(securityCase, System.nanoTime(), now));
        return securityCase;
    }

    private void givenEvidenceFor(SecurityCase securityCase) {
        Long paymentId = casePaymentRepository
                .findBySecurityCaseIdOrderByLinkedAtAsc(securityCase.getId())
                .getFirst()
                .getPaymentId();
        given(paymentInfoReader.findAll(anyCollection()))
                .willReturn(Map.of(
                        paymentId,
                        new PaymentInfo(
                                paymentId,
                                600_000L,
                                "HELD",
                                Instant.now(),
                                65,
                                "REVIEW",
                                List.of(new RuleHit("HIGH_AMOUNT", "고액 결제", 35)))));
    }

    private List<AiReport> reportsOf(SecurityCase securityCase) {
        return aiReportRepository.findByCaseIdOrderByReportVersionAsc(securityCase.getId());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> error(AiReport report) {
        return (Map<String, Object>) report.getReportBody().get("error");
    }
}
