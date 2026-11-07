package com.donworry.investigation.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.donworry.identity.api.CurrentStaff;
import com.donworry.identity.api.StaffSummary;
import com.donworry.investigation.report.client.AiServerProperties;
import com.donworry.investigation.report.entity.AiSwitchAction;
import com.donworry.investigation.report.entity.AiSwitchHistory;
import com.donworry.investigation.report.repository.AiSwitchHistoryRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

// AI 조사 긴급정지 스위치의 상태 판단 · 기록 (ADR-0083 결정 1 · 3)
@ExtendWith(MockitoExtension.class)
class AiSwitchServiceTests {

    @Mock
    AiSwitchHistoryRepository repository;

    @Mock
    CurrentStaff currentStaff;

    @Mock
    ApplicationEventPublisher events;

    // 배포 직후 기록이 하나도 없으면 켜짐 — 꺼짐으로 읽으면 모르는 사이 AI 조사가 전부 멈춘다
    @Test
    void isAiAllowed_whenConfigEnabledAndNoHistory_returnsTrue() {
        given(repository.findFirstByOrderByIdDesc()).willReturn(Optional.empty());

        assertThat(service(true).isAiAllowed()).isTrue();
    }

    // 끄면 누가 · 언제 · 왜를 한 줄로 남긴다 — 사유는 앞뒤 공백을 빼고, 이름은 그때 값
    @Test
    void stop_recordsActorNameReasonAndTime() {
        givenStaff("SECURITY_STAFF");
        given(repository.findFirstByOrderByIdDesc()).willReturn(Optional.empty());
        given(repository.findTop20ByOrderByIdDesc()).willReturn(List.of());
        Instant before = Instant.now().minusSeconds(1);

        service(true).stop("  요약에 없는 금액이 반복됨  ");

        ArgumentCaptor<AiSwitchHistory> saved = ArgumentCaptor.forClass(AiSwitchHistory.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getAction()).isEqualTo(AiSwitchAction.STOPPED);
        assertThat(saved.getValue().getReason()).isEqualTo("요약에 없는 금액이 반복됨");
        assertThat(saved.getValue().getChangedByUserId()).isEqualTo(7L);
        assertThat(saved.getValue().getChangedByName()).isEqualTo("김보안");
        assertThat(saved.getValue().getChangedAt()).isAfter(before).isBeforeOrEqualTo(Instant.now());
    }

    // 사유 없이는 끄지 못한다 — 사후 검토할 수 없는 정지 기록이 남지 않게
    @Test
    void stop_whenReasonBlank_rejectsAndKeepsState() {
        givenStaff("ADMIN");

        assertThatThrownBy(() -> service(true).stop("   "))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(repository, never()).save(any());
    }

    // 이미 꺼져 있으면 409 — 두 사람이 동시에 눌러 같은 상태 기록이 두 줄 생기지 않게
    @Test
    void stop_whenAlreadyStopped_returnsConflict() {
        givenStaff("ADMIN");
        given(repository.findFirstByOrderByIdDesc())
                .willReturn(Optional.of(AiSwitchHistory.record(
                        AiSwitchAction.STOPPED, "먼저 끔", 1L, "관리자", Instant.parse("2026-11-07T00:00:00Z"))));

        assertThatThrownBy(() -> service(true).stop("다시 끔"))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(repository, never()).save(any());
    }

    private AiSwitchService service(boolean configEnabled) {
        return new AiSwitchService(
                repository, AiServerProperties.builder().enabled(configEnabled).build(), currentStaff, events);
    }

    private void givenStaff(String role) {
        given(currentStaff.findCurrent())
                .willReturn(Optional.of(StaffSummary.builder()
                        .userId(7L)
                        .displayName("김보안")
                        .role(role)
                        .build()));
    }
}
