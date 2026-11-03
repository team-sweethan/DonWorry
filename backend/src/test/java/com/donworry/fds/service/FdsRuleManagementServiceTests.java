package com.donworry.fds.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.donworry.fds.entity.FdsRuleChangeRequest;
import com.donworry.fds.entity.FdsRulePolicy;
import com.donworry.fds.repository.FdsRuleChangeRequestRepository;
import com.donworry.fds.repository.FdsRulePolicyRepository;
import com.donworry.identity.api.CurrentStaff;
import com.donworry.identity.api.StaffSummary;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class FdsRuleManagementServiceTests {

    @Mock
    FdsRulePolicyRepository policies;

    @Mock
    FdsRuleChangeRequestRepository requests;

    @Mock
    CurrentStaff currentStaff;

    @Mock
    FdsRulePolicy policy;

    @Test
    // 요청 시점의 표시 이름을 이력에 보존해 이후 계정 이름 변경과 무관하게 요청자를 표시한다.
    void requestDisable_storesRequesterNameSnapshot() {
        when(currentStaff.findCurrent()).thenReturn(Optional.of(staff(3L, "보안 직원")));
        when(policies.findByRuleCodeForUpdate("R03")).thenReturn(Optional.of(policy));
        when(policy.isActive()).thenReturn(true);
        when(requests.existsByRuleCodeAndStatus("R03", FdsRuleChangeRequest.Status.PENDING))
                .thenReturn(false);
        var saved = ArgumentCaptor.forClass(FdsRuleChangeRequest.class);

        service().requestDisable("R03", "오탐 여부를 확인하기 위해 일시 중지가 필요합니다.");

        verify(requests).save(saved.capture());
        assertThat(saved.getValue().getRequestedByUserId()).isEqualTo(3L);
        assertThat(saved.getValue().getRequestedByName()).isEqualTo("보안 직원");
        assertThat(saved.getValue().getReason()).isEqualTo("오탐 여부를 확인하기 위해 일시 중지가 필요합니다.");
        assertThat(saved.getValue().getStatus()).isEqualTo(FdsRuleChangeRequest.Status.PENDING);
    }

    @Test
    // 과도하게 긴 요청 사유는 DB에 저장하기 전에 차단한다.
    void requestDisable_whenReasonExceedsMaximumLength_throwsBadRequest() {
        when(currentStaff.findCurrent()).thenReturn(Optional.of(staff(3L, "보안 직원")));
        when(policies.findByRuleCodeForUpdate("R03")).thenReturn(Optional.of(policy));
        when(policy.isActive()).thenReturn(true);
        when(requests.existsByRuleCodeAndStatus("R03", FdsRuleChangeRequest.Status.PENDING))
                .thenReturn(false);

        var exception =
                org.assertj.core.api.Assertions.catchThrowable(() -> service().requestDisable("R03", "가".repeat(501)));

        assertThat(exception).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) exception).getStatusCode().value())
                .isEqualTo(400);
        verify(requests, never()).save(any());
    }

    @Test
    // 승인·반려 처리자 이름도 처리 시점 스냅샷으로 남긴다.
    void review_storesReviewerNameSnapshot() {
        var request = FdsRuleChangeRequest.disable("R03", 3L, "보안 직원", "사유", Instant.now());
        when(currentStaff.findCurrent()).thenReturn(Optional.of(staff(1L, "관리자")));
        when(requests.findRuleCodeById(7L)).thenReturn(Optional.of("R03"));
        when(policies.findByRuleCodeForUpdate("R03")).thenReturn(Optional.of(policy));
        when(requests.findByIdForUpdate(7L)).thenReturn(Optional.of(request));

        service().review(7L, true);

        assertThat(request.getStatus()).isEqualTo(FdsRuleChangeRequest.Status.APPROVED);
        assertThat(request.getReviewedByUserId()).isEqualTo(1L);
        assertThat(request.getReviewedByName()).isEqualTo("관리자");
        verify(policy).update(eq(false), eq(1L), any(Instant.class));
    }

    @Test
    // 다른 관리자나 직접 변경으로 이미 처리된 요청은 중복 처리하지 않는다.
    void review_whenRequestAlreadyProcessed_throwsConflict() {
        var request = FdsRuleChangeRequest.disable("R03", 3L, "보안 직원", "사유", Instant.now());
        request.review(FdsRuleChangeRequest.Status.APPROVED, 1L, "관리자", Instant.now());
        when(currentStaff.findCurrent()).thenReturn(Optional.of(staff(1L, "관리자")));
        when(requests.findRuleCodeById(7L)).thenReturn(Optional.of("R03"));
        when(policies.findByRuleCodeForUpdate("R03")).thenReturn(Optional.of(policy));
        when(requests.findByIdForUpdate(7L)).thenReturn(Optional.of(request));

        var exception =
                org.assertj.core.api.Assertions.catchThrowable(() -> service().review(7L, false));

        assertThat(exception).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) exception).getStatusCode().value())
                .isEqualTo(409);
        verify(policy, never()).update(anyBoolean(), anyLong(), any(Instant.class));
    }

    @Test
    // 관리자가 카탈로그에서 직접 끄면 남아 있던 요청도 승인으로 종결해 화면 상태를 맞춘다.
    void setActive_whenAdminDirectlyDisables_approvesPendingRequest() {
        var request = FdsRuleChangeRequest.disable("R03", 3L, "보안 직원", "사유", Instant.now());
        when(currentStaff.findCurrent()).thenReturn(Optional.of(staff(1L, "관리자")));
        when(policies.findByRuleCodeForUpdate("R03")).thenReturn(Optional.of(policy));
        when(policy.isActive()).thenReturn(true);
        when(requests.findByRuleCodeAndStatus("R03", FdsRuleChangeRequest.Status.PENDING))
                .thenReturn(java.util.List.of(request));

        service().setActive("R03", false);

        assertThat(request.getStatus()).isEqualTo(FdsRuleChangeRequest.Status.APPROVED);
        assertThat(request.getReviewedByName()).isEqualTo("관리자");
    }

    @Test
    // 보안 직원은 꺼진 룰을 직접 다시 켤 수 있다.
    void setActive_whenSecurityStaffEnables_updatesPolicy() {
        when(currentStaff.findCurrent()).thenReturn(Optional.of(staff(3L, "보안 직원")));
        when(policies.findByRuleCodeForUpdate("R03")).thenReturn(Optional.of(policy));
        when(policy.isActive()).thenReturn(false);
        service().setActive("R03", true);

        verify(policy).update(eq(true), eq(3L), any(Instant.class));
    }

    private FdsRuleManagementService service() {
        return new FdsRuleManagementService(policies, requests, currentStaff);
    }

    private StaffSummary staff(Long userId, String displayName) {
        return StaffSummary.builder()
                .userId(userId)
                .displayName(displayName)
                .role(userId == 1L ? "ADMIN" : "SECURITY_STAFF")
                .build();
    }
}
