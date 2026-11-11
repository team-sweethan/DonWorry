package com.donworry.investigation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.donworry.identity.api.SecurityStaffDirectory;
import com.donworry.identity.api.SecurityStaffEntry;
import com.donworry.investigation.entity.CaseType;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.repository.CaseHistoryRepository;
import com.donworry.investigation.repository.InsiderCaseRepository;
import com.donworry.investigation.repository.SecurityCaseRepository;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// FDS 사건 자동 배정에서 내부자 사건 조사 중인 직원 빼기 (#670)
@ExtendWith(MockitoExtension.class)
class CaseAssignmentServiceTests {

    private static final Instant T0 = Instant.parse("2026-11-08T01:00:00Z");

    @Mock
    SecurityCaseRepository securityCaseRepository;

    @Mock
    CaseHistoryRepository caseHistoryRepository;

    @Mock
    InsiderCaseRepository insiderCases;

    @Mock
    SecurityStaffDirectory staffDirectory;

    @Mock
    SecurityStaffGuard guard;

    @InjectMocks
    CaseAssignmentService service;

    // 열린 내부자 사건이 있는 직원은 1순위여도 새 사건을 받지 않고, 다음 직원이 받는다
    @Test
    void assignNew_skipsStaffWithOpenInsiderCase() {
        when(securityCaseRepository.countOpenByAssignee()).thenReturn(List.of());
        when(staffDirectory.securityStaff())
                .thenReturn(List.of(new SecurityStaffEntry(31L, "가", true), new SecurityStaffEntry(32L, "나", true)));
        when(insiderCases.findOpenStaffIds()).thenReturn(Set.of(31L));
        SecurityCase securityCase = SecurityCase.open(3L, "cust-1", CaseType.PAYMENT_FRAUD, T0);

        service.assignNew(securityCase, T0);

        assertThat(securityCase.getAssigneeStaffId()).isEqualTo(32L);
    }
}
