package com.donworry.investigation.report.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.investigation.entity.CaseStatus;
import com.donworry.investigation.entity.CaseType;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.repository.SecurityCaseRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

// 같은 카드 · 기기 · IP 지난 사건(#821)의 후보 사건 조회가 실제 DB에서 범위를 지키는지 본다
// LinkedCaseFinder는 별도 트랜잭션을 열어 되돌리는 테스트 데이터를 못 보므로, 찾는 쪽이 쓰는 조회를 직접 부른다
@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
@Transactional
class LinkedCaseQueryMariaDbTests {

    private static final Long MERCHANT = 98_210L;
    private static final Instant NOW = Instant.parse("2026-11-15T00:00:00Z");

    @Autowired
    private SecurityCaseRepository securityCaseRepository;

    // 열린 사건 · 같은 고객 사건 · 90일보다 먼저 닫힌 사건 · 다른 가맹점 사건은 "지난 판정"으로 나오지 않는다
    @Test
    void pastCaseQuery_openSameCustomerOldOrOtherMerchant_excluded() {
        SecurityCase expected = closed(MERCHANT, "other-1", NOW.minus(10, ChronoUnit.DAYS));
        securityCaseRepository.save(SecurityCase.open(MERCHANT, "other-2", CaseType.PAYMENT_FRAUD, NOW));
        closed(MERCHANT, "customer-1", NOW.minus(5, ChronoUnit.DAYS));
        closed(MERCHANT, "other-3", NOW.minus(91, ChronoUnit.DAYS));
        closed(MERCHANT + 1, "other-4", NOW.minus(5, ChronoUnit.DAYS));

        var found =
                securityCaseRepository
                        .findTop100ByMerchantIdAndCustomerRefNotAndStatusAndClosedAtGreaterThanEqualOrderByClosedAtDesc(
                                MERCHANT,
                                "customer-1",
                                CaseStatus.CLOSED,
                                NOW.minus(LinkedCaseFinder.LOOKBACK_DAYS, ChronoUnit.DAYS));

        assertThat(found).extracting(SecurityCase::getId).containsExactly(expected.getId());
    }

    private SecurityCase closed(Long merchantId, String customerRef, Instant closedAt) {
        SecurityCase securityCase =
                SecurityCase.open(merchantId, customerRef, CaseType.PAYMENT_FRAUD, closedAt.minusSeconds(3600));
        securityCase.close(closedAt);
        return securityCaseRepository.save(securityCase);
    }
}
