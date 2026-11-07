package com.donworry.fds.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.donworry.fds.api.FdsEvaluationRequest;
import com.donworry.fds.api.FdsResult;
import com.donworry.fds.dto.CreateFdsBlocklistEntryRequest;
import com.donworry.fds.dto.FdsBlocklistPageResponse;
import com.donworry.fds.dto.FdsBlocklistStatusFilter;
import com.donworry.fds.entity.FdsBlocklistEntry;
import com.donworry.fds.entity.FdsEvaluation;
import com.donworry.fds.repository.FdsBlocklistEntryRepository;
import com.donworry.fds.repository.FdsEvaluationRepository;
import com.donworry.identity.api.CurrentStaff;
import com.donworry.identity.api.StaffSummary;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class FdsBlocklistServiceTests {

    @Mock
    FdsBlocklistEntryRepository entries;

    @Mock
    CurrentStaff currentStaff;

    @Mock
    FdsEvaluationRepository evaluations;

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

        service().deactivate(11L, "오탐으로 확인");

        assertThat(entry.isActive()).isFalse();
        assertThat(entry.getDeactivationReason()).isEqualTo("오탐으로 확인");
        assertThat(entry.getDeactivatedAt()).isNotNull();
    }

    @Test
    // 해제된 식별값은 새 사유로 다시 차단할 수 있다.
    void add_whenExistingEntryIsInactive_reactivatesEntry() {
        var entry = FdsBlocklistEntry.active("IP_ADDRESS", "203.0.113.10", "초기 사유", java.time.Instant.now());
        entry.deactivate("초기 해제", java.time.Instant.now());
        when(currentStaff.findCurrent()).thenReturn(Optional.of(staff("SECURITY_STAFF")));
        when(entries.findByIdentifierTypeAndIdentifierValue("IP_ADDRESS", "203.0.113.10"))
                .thenReturn(java.util.List.of(entry));

        service()
                .add(new CreateFdsBlocklistEntryRequest(
                        CreateFdsBlocklistEntryRequest.IdentifierType.IP_ADDRESS, "203.0.113.10", "재차단 사유"));

        assertThat(entry.isActive()).isTrue();
        assertThat(entry.getReason()).isEqualTo("재차단 사유");
        assertThat(entry.getDeactivationReason()).isNull();
        assertThat(entry.getDeactivatedAt()).isNull();
        verify(entries, never()).save(any());
    }

    @Test
    // 만료된 차단값을 다시 등록하면 영구 차단으로 되살린다.
    void add_whenExistingEntryIsExpired_reactivatesWithoutExpiry() {
        var entry = FdsBlocklistEntry.active("IP_ADDRESS", "203.0.113.10", "초기 사유", java.time.Instant.now());
        org.springframework.test.util.ReflectionTestUtils.setField(
                entry, "expiresAt", java.time.Instant.now().minusSeconds(1));
        when(currentStaff.findCurrent()).thenReturn(Optional.of(staff("SECURITY_STAFF")));
        when(entries.findByIdentifierTypeAndIdentifierValue("IP_ADDRESS", "203.0.113.10"))
                .thenReturn(java.util.List.of(entry));

        service()
                .add(new CreateFdsBlocklistEntryRequest(
                        CreateFdsBlocklistEntryRequest.IdentifierType.IP_ADDRESS, "203.0.113.10", "재차단 사유"));

        assertThat(entry.isActiveAt(java.time.Instant.now())).isTrue();
        assertThat(entry.getExpiresAt()).isNull();
        verify(entries, never()).save(any());
    }

    @Test
    // 목록은 활성 상태와 검색어를 DB 페이지 조회에 함께 전달한다.
    void list_whenActiveWithSearch_returnsMatchingPage() {
        var entry = FdsBlocklistEntry.active("DEVICE_ID", "device-1", "사기 결제 확인", java.time.Instant.now());
        when(currentStaff.findCurrent()).thenReturn(Optional.of(staff("SECURITY_STAFF")));
        when(entries.findActiveAtWithSearch(any(), eq("device"), any()))
                .thenReturn(new PageImpl<>(java.util.List.of(entry), PageRequest.of(0, 10), 1));

        var result = service().list(0, 10, FdsBlocklistStatusFilter.ACTIVE, " device ");

        assertThat(result.content()).singleElement().satisfies(item -> {
            assertThat(item.identifierType()).isEqualTo("DEVICE_ID");
            // 기기 ID는 가린 값으로 나간다 (#628). 8자 이하라 절반만
            assertThat(item.identifierValue()).isEqualTo("devi…");
        });
        verify(entries, never()).findInactiveAtWithSearch(any(), any(), any());
    }

    @Test
    // 경보 상세에서 등록하면 화면이 모르는 원문을 평가에서 찾아 저장한다. 가린 값이 들어가면 실제 결제와 대조되지 않는다 (#603)
    void addFromEvaluation_withBuyerEmail_savesRawValueFromEvaluation() {
        when(currentStaff.findCurrent()).thenReturn(Optional.of(staff("SECURITY_STAFF")));
        when(evaluations.findById(7L)).thenReturn(Optional.of(evaluationWithEmail("hong@example.test")));
        when(entries.findByIdentifierTypeAndIdentifierValue("BUYER_EMAIL", "hong@example.test"))
                .thenReturn(java.util.List.of());
        var saved = ArgumentCaptor.forClass(FdsBlocklistEntry.class);

        service().addFromEvaluation(7L, CreateFdsBlocklistEntryRequest.IdentifierType.BUYER_EMAIL, " 도용 의심 ");

        verify(entries).save(saved.capture());
        assertThat(saved.getValue().getIdentifierType()).isEqualTo("BUYER_EMAIL");
        assertThat(saved.getValue().getIdentifierValue()).isEqualTo("hong@example.test");
        assertThat(saved.getValue().getReason()).isEqualTo("도용 의심");
    }

    @Test
    // 평가에서 바로 등록하는 길도 보안 직원만 쓸 수 있다. 관리자는 조회만 한다
    void addFromEvaluation_whenNotSecurityStaff_throwsForbidden() {
        when(currentStaff.findCurrent()).thenReturn(Optional.of(staff("ADMIN")));

        var exception = org.assertj.core.api.Assertions.catchThrowable(() ->
                service().addFromEvaluation(7L, CreateFdsBlocklistEntryRequest.IdentifierType.BUYER_EMAIL, "도용 의심"));

        assertThat(exception).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) exception).getStatusCode().value())
                .isEqualTo(403);
        verify(entries, never()).save(any());
    }

    @Test
    // [차단 목록] 화면에 이메일 원문을 보내지 않는다 (#603, ISMS-P 2.6.3)
    void list_withBuyerEmailEntry_returnsMaskedValue() {
        var entry = FdsBlocklistEntry.active("BUYER_EMAIL", "hong@example.test", "도용 의심", java.time.Instant.now());
        when(currentStaff.findCurrent()).thenReturn(Optional.of(staff("ADMIN")));
        when(entries.findActiveAtWithSearch(any(), eq("hong"), any()))
                .thenReturn(new PageImpl<>(java.util.List.of(entry), PageRequest.of(0, 10), 1));

        var result = service().list(0, 10, FdsBlocklistStatusFilter.ACTIVE, "hong");

        assertThat(result.content())
                .singleElement()
                .satisfies(item -> assertThat(item.identifierValue()).isEqualTo("ho***@example.test"));
    }

    @Test
    // [차단 목록] 응답에 IP · 기기 ID 원문과 카드 토큰이 없다. 카드는 화면이 표시값(displayLabel)을 쓴다 (#628, ISMS-P 2.6.3)
    void list_withIpDeviceCardEntries_returnsMaskedOrNoRawValue() {
        var at = java.time.Instant.now();
        var ip = FdsBlocklistEntry.active("IP_ADDRESS", "203.0.113.10", "도용 의심", at);
        var device = FdsBlocklistEntry.active("DEVICE_ID", "dev-7f3a9c21-abcd", "도용 의심", at);
        var card = FdsBlocklistEntry.active("CARD_TOKEN", "tok_secret_card", "VISA •••• 4242", "도용 의심", at);
        when(currentStaff.findCurrent()).thenReturn(Optional.of(staff("ADMIN")));
        when(entries.findActiveAtWithSearch(any(), eq(""), any()))
                .thenReturn(new PageImpl<>(java.util.List.of(ip, device, card), PageRequest.of(0, 10), 3));

        var result = service().list(0, 10, FdsBlocklistStatusFilter.ACTIVE, "");

        assertThat(result.content())
                .extracting(
                        FdsBlocklistPageResponse.Item::identifierType,
                        FdsBlocklistPageResponse.Item::identifierValue,
                        FdsBlocklistPageResponse.Item::displayLabel)
                .containsExactly(
                        tuple("IP_ADDRESS", "203.0.*.*", null),
                        tuple("DEVICE_ID", "dev-7f3a…", null),
                        tuple("CARD_TOKEN", null, "VISA •••• 4242"));
    }

    private FdsEvaluation evaluationWithEmail(String buyerEmail) {
        var requestedAt = java.time.Instant.parse("2026-11-07T01:00:00Z");
        var request = new FdsEvaluationRequest(
                java.util.UUID.randomUUID(),
                101L,
                1L,
                "hong-gildong",
                150_000L,
                requestedAt,
                "device-1",
                "card-token-1",
                "VISA •••• 4242",
                "KR",
                "203.0.113.10",
                buyerEmail);
        return FdsEvaluation.completed(request, FdsResult.DENY, 100, requestedAt.plusSeconds(1));
    }

    private FdsBlocklistService service() {
        return new FdsBlocklistService(entries, currentStaff, evaluations);
    }

    private StaffSummary staff(String role) {
        return StaffSummary.builder().userId(3L).displayName("보안 직원").role(role).build();
    }
}
