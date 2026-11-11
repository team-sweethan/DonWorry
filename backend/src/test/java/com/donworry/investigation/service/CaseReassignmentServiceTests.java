package com.donworry.investigation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.donworry.identity.api.MerchantDirectoryReader;
import com.donworry.identity.api.SecurityStaffDirectory;
import com.donworry.identity.api.SecurityStaffEntry;
import com.donworry.identity.api.StaffSummary;
import com.donworry.investigation.dto.CaseHandOverRequest;
import com.donworry.investigation.dto.CaseReassignRequest;
import com.donworry.investigation.entity.AssignmentChangeReason;
import com.donworry.investigation.entity.AssignmentRequestStatus;
import com.donworry.investigation.entity.CaseType;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.repository.CaseAssignmentRequestRepository;
import com.donworry.investigation.repository.CaseHistoryRepository;
import com.donworry.investigation.repository.SecurityCaseRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// FDS 사건 담당 변경에서 내부자 사건 조사 중인 직원 빼기 (#670)
@ExtendWith(MockitoExtension.class)
class CaseReassignmentServiceTests {

    private static final Instant T0 = Instant.parse("2026-11-08T01:00:00Z");
    private static final Long FROM = 30L;
    private static final Long UNDER_INVESTIGATION = 31L;
    private static final Long OTHER = 32L;
    private static final StaffSummary ADMIN =
            StaffSummary.builder().userId(1L).displayName("관리자").role("ADMIN").build();

    @Mock
    SecurityCaseRepository securityCaseRepository;

    @Mock
    CaseAssignmentRequestRepository requestRepository;

    @Mock
    CaseHistoryRepository caseHistoryRepository;

    @Mock
    CaseAssignmentService caseAssignmentService;

    @Mock
    SecurityStaffDirectory staffDirectory;

    @Mock
    MerchantDirectoryReader merchantDirectory;

    @Mock
    SecurityStaffGuard guard;

    @InjectMocks
    CaseReassignmentService service;

    // [모두 넘기기]는 담당 수가 가장 적어도 내부자 사건 조사 중인 직원에게는 넘기지 않는다
    @Test
    void handOverAll_skipsStaffWithOpenInsiderCase() {
        when(guard.requireAdmin()).thenReturn(ADMIN);
        SecurityCase securityCase = caseAssignedTo(FROM);
        when(securityCaseRepository.findOpenByAssigneeForUpdate(FROM)).thenReturn(List.of(securityCase));
        when(caseAssignmentService.openCounts()).thenReturn(new HashMap<>(Map.of(FROM, 1L, OTHER, 2L)));
        when(caseAssignmentService.staffUnderInsiderCase()).thenReturn(Set.of(UNDER_INVESTIGATION));
        when(staffDirectory.securityStaff()).thenReturn(staff());
        when(requestRepository.findByCaseIdAndStatus(securityCase.getId(), AssignmentRequestStatus.PENDING))
                .thenReturn(Optional.empty());

        int moved = service.handOverAll(FROM, new CaseHandOverRequest(AssignmentChangeReason.WORKLOAD, null));

        assertThat(moved).isEqualTo(1);
        assertThat(securityCase.getAssigneeStaffId()).isEqualTo(OTHER);
    }

    // 관리자가 내부자 사건 조사 중인 직원을 담당자로 직접 지정하면 거절하고 담당을 그대로 둔다
    @Test
    void reassign_toStaffWithOpenInsiderCase_isRejected() {
        when(guard.requireAdmin()).thenReturn(ADMIN);
        SecurityCase securityCase = caseAssignedTo(FROM);
        when(securityCaseRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(securityCase));
        when(staffDirectory.securityStaff()).thenReturn(staff());
        when(caseAssignmentService.staffUnderInsiderCase()).thenReturn(Set.of(UNDER_INVESTIGATION));

        assertThatThrownBy(() -> service.reassign(
                        7L, new CaseReassignRequest(UNDER_INVESTIGATION, AssignmentChangeReason.WORKLOAD, null)))
                .isInstanceOf(InvestigationException.class)
                .extracting("errorCode")
                .isEqualTo(InvestigationErrorCode.STAFF_HAS_OPEN_INSIDER_CASE);
        assertThat(securityCase.getAssigneeStaffId()).isEqualTo(FROM);
    }

    private static List<SecurityStaffEntry> staff() {
        return List.of(
                new SecurityStaffEntry(FROM, "가", true),
                new SecurityStaffEntry(UNDER_INVESTIGATION, "나", true),
                new SecurityStaffEntry(OTHER, "다", true));
    }

    private static SecurityCase caseAssignedTo(Long staffId) {
        SecurityCase securityCase = SecurityCase.open(3L, "cust-1", CaseType.PAYMENT_FRAUD, T0);
        securityCase.assignTo(staffId, T0);
        return securityCase;
    }
}
