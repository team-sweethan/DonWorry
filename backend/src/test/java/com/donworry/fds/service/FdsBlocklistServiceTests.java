package com.donworry.fds.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.donworry.fds.dto.CreateFdsBlocklistEntryRequest;
import com.donworry.fds.entity.FdsBlocklistEntry;
import com.donworry.fds.repository.FdsBlocklistEntryRepository;
import com.donworry.identity.api.CurrentStaff;
import com.donworry.identity.api.StaffSummary;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class FdsBlocklistServiceTests {

    @Mock
    FdsBlocklistEntryRepository entries;

    @Mock
    CurrentStaff currentStaff;

    @Test
    // 보안 직원이 등록한 카드 토큰은 활성 차단 목록 항목으로 저장한다.
    void add_whenSecurityStaff_savesActiveEntry() {
        when(currentStaff.findCurrent()).thenReturn(Optional.of(staff("SECURITY_STAFF")));
        when(entries.findByIdentifierTypeAndIdentifierValue("CARD_TOKEN", "token-1"))
                .thenReturn(java.util.List.of());
        var saved = ArgumentCaptor.forClass(FdsBlocklistEntry.class);

        service()
                .add(new CreateFdsBlocklistEntryRequest(
                        CreateFdsBlocklistEntryRequest.IdentifierType.CARD_TOKEN, " token-1 ", "사기 결제로 확인"));

        verify(entries).save(saved.capture());
        assertThat(saved.getValue().getIdentifierType()).isEqualTo("CARD_TOKEN");
        assertThat(saved.getValue().getIdentifierValue()).isEqualTo("token-1");
        assertThat(saved.getValue().getReason()).isEqualTo("사기 결제로 확인");
        assertThat(saved.getValue().isActive()).isTrue();
    }

    @Test
    // 같은 종류와 식별값은 중복 등록하지 않는다.
    void add_whenIdentifierAlreadyExists_throwsConflict() {
        when(currentStaff.findCurrent()).thenReturn(Optional.of(staff("SECURITY_STAFF")));
        when(entries.findByIdentifierTypeAndIdentifierValue("DEVICE_ID", "device-1"))
                .thenReturn(java.util.List.of(
                        FdsBlocklistEntry.active("DEVICE_ID", "device-1", "카드 테스팅", java.time.Instant.now())));

        var exception = org.assertj.core.api.Assertions.catchThrowable(() -> service()
                .add(new CreateFdsBlocklistEntryRequest(
                        CreateFdsBlocklistEntryRequest.IdentifierType.DEVICE_ID, "device-1", "카드 테스팅")));

        assertThat(exception).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) exception).getStatusCode().value())
                .isEqualTo(409);
        verify(entries, never()).save(any());
    }

    @Test
    // 이메일 차단값은 대소문자 차이로 우회되지 않도록 소문자로 저장한다.
    void add_whenBuyerEmailNormalizesToLowerCase_savesNormalizedValue() {
        when(currentStaff.findCurrent()).thenReturn(Optional.of(staff("SECURITY_STAFF")));
        when(entries.findByIdentifierTypeAndIdentifierValue("BUYER_EMAIL", "hong@example.test"))
                .thenReturn(java.util.List.of());
        var saved = ArgumentCaptor.forClass(FdsBlocklistEntry.class);

        service()
                .add(new CreateFdsBlocklistEntryRequest(
                        CreateFdsBlocklistEntryRequest.IdentifierType.BUYER_EMAIL, "Hong@Example.Test", "사기 결제 확인"));

        verify(entries).save(saved.capture());
        assertThat(saved.getValue().getIdentifierValue()).isEqualTo("hong@example.test");
    }

    @Test
    // 차단 해제는 행을 삭제하지 않고 활성 상태만 바꾼다.
    void deactivate_whenSecurityStaff_marksExistingEntryInactive() {
        var entry = FdsBlocklistEntry.active("DEVICE_ID", "device-1", "오탐 확인", java.time.Instant.now());
        when(currentStaff.findCurrent()).thenReturn(Optional.of(staff("SECURITY_STAFF")));
        when(entries.findById(11L)).thenReturn(Optional.of(entry));

        service().deactivate(11L);

        assertThat(entry.isActive()).isFalse();
    }

    @Test
    // 해제된 식별값은 새 사유로 다시 차단할 수 있다.
    void add_whenExistingEntryIsInactive_reactivatesEntry() {
        var entry = FdsBlocklistEntry.active("IP_ADDRESS", "203.0.113.10", "초기 사유", java.time.Instant.now());
        entry.deactivate();
        when(currentStaff.findCurrent()).thenReturn(Optional.of(staff("SECURITY_STAFF")));
        when(entries.findByIdentifierTypeAndIdentifierValue("IP_ADDRESS", "203.0.113.10"))
                .thenReturn(java.util.List.of(entry));

        service()
                .add(new CreateFdsBlocklistEntryRequest(
                        CreateFdsBlocklistEntryRequest.IdentifierType.IP_ADDRESS, "203.0.113.10", "재차단 사유"));

        assertThat(entry.isActive()).isTrue();
        assertThat(entry.getReason()).isEqualTo("재차단 사유");
        verify(entries, never()).save(any());
    }

    private FdsBlocklistService service() {
        return new FdsBlocklistService(entries, currentStaff);
    }

    private StaffSummary staff(String role) {
        return StaffSummary.builder().userId(3L).displayName("보안 직원").role(role).build();
    }
}
