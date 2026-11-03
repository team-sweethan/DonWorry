package com.donworry.investigation.report.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.investigation.report.entity.AiReport;
import com.donworry.investigation.report.entity.ReportStatus;
import jakarta.persistence.EntityManager;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

// 실제 MariaDB에서 ai_reports 저장 · 버전 제약 · 최대 버전 조회를 확인한다
// 각 테스트는 트랜잭션 안에서 돌고 끝나면 롤백된다
@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
@Transactional
class AiReportRepositoryTests {

    // DATETIME(6)은 마이크로초까지만 저장하므로 비교값도 맞춰 둔다
    static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);

    // 다른 테스트가 커밋한 보고서(작은 자동 증가 Case ID)와 겹치지 않게 큰 값에서 하나씩 늘려 쓴다
    static final AtomicLong CASE_IDS = new AtomicLong(1_000_000_000L + System.nanoTime() % 1_000_000_000L);

    @Autowired
    AiReportRepository aiReportRepository;

    @Autowired
    EntityManager entityManager;

    // 같은 Case에 같은 보고서 버전을 두 번 저장하면 DB가 막는다
    @Test
    void aiReport_sameCaseAndVersionTwice_violatesUniqueConstraint() {
        long caseId = newCaseId();
        aiReportRepository.save(AiReport.pending(caseId, 1, 1, NOW));
        entityManager.flush();

        assertThatThrownBy(() -> {
                    aiReportRepository.save(AiReport.pending(caseId, 1, 2, NOW));
                    entityManager.flush();
                })
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // 최대 보고서 버전은 해당 Case 것만 보고, 보고서가 없으면 빈 값이다
    @Test
    void findMaxReportVersion_returnsLatestOfThatCaseOnly() {
        long caseId = newCaseId();
        long otherCaseId = newCaseId();
        long emptyCaseId = newCaseId();
        aiReportRepository.save(AiReport.pending(caseId, 1, 1, NOW));
        aiReportRepository.save(AiReport.pending(caseId, 2, 2, NOW));
        aiReportRepository.save(AiReport.pending(otherCaseId, 5, 3, NOW));
        entityManager.flush();

        assertThat(aiReportRepository.findMaxReportVersion(caseId)).contains(2);
        assertThat(aiReportRepository.findMaxReportVersion(emptyCaseId)).isEmpty();
    }

    // 새 보고서를 저장하고 다시 읽으면 PENDING · 본문 없음 · 생성 시각 그대로다
    @Test
    void aiReport_pendingSavedAndReloaded_keepsValues() {
        long caseId = newCaseId();
        Instant createdWithNanos = NOW.plusNanos(123);
        AiReport saved = aiReportRepository.save(AiReport.pending(caseId, 1, 3, createdWithNanos));
        entityManager.flush();
        entityManager.clear();

        AiReport reloaded = aiReportRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getCaseId()).isEqualTo(caseId);
        assertThat(reloaded.getReportVersion()).isEqualTo(1);
        assertThat(reloaded.getEvidenceVersion()).isEqualTo(3);
        assertThat(reloaded.getStatus()).isEqualTo(ReportStatus.PENDING);
        assertThat(reloaded.getReportBody()).isNull();
        assertThat(reloaded.getCompletedAt()).isNull();
        assertThat(reloaded.getCreatedAt()).isEqualTo(saved.getCreatedAt());
    }

    // 보고서는 버전별로 쌓으므로 저장소가 물리 삭제 메서드를 노출하지 않는다 (AGENTS.md 6절)
    @Test
    void aiReportRepository_doesNotExposeDelete() {
        assertThat(Arrays.asList(AiReportRepository.class.getMethods()))
                .extracting(Method::getName)
                .noneMatch(name -> name.startsWith("delete"));
    }

    private static long newCaseId() {
        return CASE_IDS.incrementAndGet();
    }
}
