package com.donworry.investigation.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.fds.api.InsiderAlertSeverity;
import com.donworry.investigation.entity.InsiderCase;
import com.donworry.investigation.entity.InsiderCaseStatus;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

// 내부자 사건 (#670) — 실제 MariaDB의 사람당 열린 사건 1개 제약. 각 테스트는 끝나면 롤백된다
@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
@Transactional
class InsiderCaseRepositoryTests {

    static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @Autowired
    InsiderCaseRepository cases;

    @Autowired
    EntityManager entityManager;

    // 잠금을 지나친 경로가 있어도 DB가 같은 직원의 두 번째 열린 사건을 막아, 열린 사건은 1개로 남는다
    @Test
    void openCase_insertTwiceSameStaff_keepsOneOpenCase() {
        Long staffId = 8_000_000_000L + System.nanoTime() % 1_000_000_000L;
        cases.save(InsiderCase.open(staffId, InsiderAlertSeverity.HIGH, NOW));
        entityManager.flush();

        assertThatThrownBy(() -> {
                    cases.save(InsiderCase.open(staffId, InsiderAlertSeverity.CRITICAL, NOW));
                    entityManager.flush();
                })
                .isInstanceOf(DataIntegrityViolationException.class);
        entityManager.clear();
        assertThat(cases.findByStaffIdAndStatus(staffId, InsiderCaseStatus.OPEN))
                .isPresent();
    }
}
