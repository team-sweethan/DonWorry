package com.donworry.fds.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

import com.donworry.fds.api.FdsRulePolicySummary;
import com.donworry.fds.entity.FdsRuleChangeRequest;
import com.donworry.fds.entity.FdsRulePolicy;
import com.donworry.fds.repository.FdsRuleChangeRequestRepository;
import com.donworry.fds.repository.FdsRulePolicyChangeHistoryRepository;
import com.donworry.fds.repository.FdsRulePolicyRepository;
import com.donworry.identity.api.CurrentStaff;
import com.donworry.identity.api.StaffSummary;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class FdsRuleManagementServiceTests {

    @Mock
    FdsRulePolicyRepository policies;

    @Mock
    FdsRuleChangeRequestRepository requests;

    @Mock
    FdsRulePolicyChangeHistoryRepository histories;

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

    // 감시 대상인 보안 직원이 자기 감시 규칙의 끄기를 요청하거나 직접 켜고 끌 수 있으면 감시가 무력해진다 (#685 결정 1)
    @Test
    void insiderRulePolicy_whenSecurityStaffRequestsOrToggles_isForbidden() {
        when(currentStaff.findCurrent()).thenReturn(Optional.of(staff(3L, "보안 직원")));
        when(policies.findByRuleCodeForUpdate("INS_03")).thenReturn(Optional.of(policy));
        when(policy.getRuleCode()).thenReturn("INS_03");

        var request = catchThrowable(() -> service().requestDisable("INS_03", "오탐 확인"));
        var toggle = catchThrowable(() -> service().setActive("INS_03", true));

        assertThat(List.of(request, toggle))
                .allSatisfy(exception -> assertThat(((ResponseStatusException) exception)
                                .getStatusCode()
                                .value())
                        .isEqualTo(403));
        verify(policy, never()).update(anyBoolean(), any(), any());
        verify(requests, never()).save(any());
        verify(histories, never()).save(any());
    }

    // (#688 Codex 리뷰) DB 비교는 대소문자를 가리지 않아(utf8mb4_unicode_ci) ins_03으로도 INS_03 정책이 잡힌다
    // 입력 문자열로 검사하면 보안 직원이 소문자 주소로 관리자 전용 규칙을 다시 켜거나 끄기를 요청한다
    @Test
    void insiderRulePolicy_whenSecurityStaffUsesLowercaseCode_isForbidden() {
        when(currentStaff.findCurrent()).thenReturn(Optional.of(staff(3L, "보안 직원")));
        when(policies.findByRuleCodeForUpdate("ins_03")).thenReturn(Optional.of(policy));
        when(policy.getRuleCode()).thenReturn("INS_03");

        lenient().when(policy.isActive()).thenReturn(true);
        var request = catchThrowable(() -> service().requestDisable("ins_03", "오탐 확인"));
        lenient().when(policy.isActive()).thenReturn(false);
        var toggle = catchThrowable(() -> service().setActive("ins_03", true));

        assertThat(List.of(request, toggle))
                .allSatisfy(exception -> assertThat(((ResponseStatusException) exception)
                                .getStatusCode()
                                .value())
                        .isEqualTo(403));
        verify(policy, never()).update(anyBoolean(), any(), any());
        verify(requests, never()).save(any());
        verify(histories, never()).save(any());
    }

    // (#688 Codex 보안 리뷰) 감시 대상인 보안 직원이 어떤 내부자 규칙이 꺼졌는지 알면 그 규칙에 걸리는 행동만 골라 감시를 피한다
    // 관리자는 계정 룰 탭 스위치에 이 상태가 필요하다
    @Test
    void policies_whenSecurityStaff_hidesInsiderRules() {
        var payment = mock(FdsRulePolicy.class);
        when(payment.getRuleCode()).thenReturn("R03");
        var insider = mock(FdsRulePolicy.class);
        when(insider.getRuleCode()).thenReturn("INS_08");
        when(policies.findAll()).thenReturn(List.of(payment, insider));

        when(currentStaff.findCurrent()).thenReturn(Optional.of(staff(3L, "보안 직원")));
        var staffView = service().policies();
        when(currentStaff.findCurrent()).thenReturn(Optional.of(staff(1L, "관리자")));
        var adminView = service().policies();

        assertThat(staffView).extracting(FdsRulePolicySummary::ruleCode).containsExactly("R03");
        assertThat(adminView).extracting(FdsRulePolicySummary::ruleCode).containsExactly("R03", "INS_08");
    }

    // SEC-INS-05(높음 경보 중 내보내기 차단)가 꺼지면 내부자가 차단 · 정지 없이 거래 내역을 가져간다 (#685 결정 2)
    // 끌 수 없게 하는 방법은 정책 줄을 두지 않는 것 — 어느 마이그레이션도 INS_05 줄을 넣지 않아야 한다
    @Test
    void setActive_whenExportBlockRule_isNotFound() throws IOException {
        when(currentStaff.findCurrent()).thenReturn(Optional.of(staff(1L, "관리자")));

        var exception = catchThrowable(() -> service().setActive("INS_05", false));

        assertThat(((ResponseStatusException) exception).getStatusCode().value())
                .isEqualTo(404);
        verify(histories, never()).save(any());
        var migrations = new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/*.sql");
        assertThat(migrations).isNotEmpty();
        for (var migration : migrations) {
            assertThat(migration.getContentAsString(StandardCharsets.UTF_8))
                    .as(migration.getFilename())
                    .doesNotContain("'INS_05'");
        }
    }

    private FdsRuleManagementService service() {
        return new FdsRuleManagementService(policies, requests, histories, currentStaff);
    }

    private StaffSummary staff(Long userId, String displayName) {
        return StaffSummary.builder()
                .userId(userId)
                .displayName(displayName)
                .role(userId == 1L ? "ADMIN" : "SECURITY_STAFF")
                .build();
    }
}
