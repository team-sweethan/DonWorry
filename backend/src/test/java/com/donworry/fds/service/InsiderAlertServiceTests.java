package com.donworry.fds.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.donworry.fds.api.InsiderAlertRaised;
import com.donworry.fds.api.InsiderAlertSeverity;
import com.donworry.fds.api.InsiderRule;
import com.donworry.fds.entity.InsiderAlert;
import com.donworry.fds.entity.InsiderAlertStatus;
import com.donworry.fds.repository.InsiderAlertEvidenceRepository;
import com.donworry.fds.repository.InsiderAlertRepository;
import com.donworry.identity.api.CurrentStaff;
import com.donworry.identity.api.MerchantDirectoryReader;
import com.donworry.identity.api.SecurityStaffDirectory;
import com.donworry.identity.api.StaffAccessReader;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

// 내부자 사건 이관용 열린 경보 조회 (#670 Codex 리뷰) — P3가 서버 시작 때 이 목록으로 사건을 연다
class InsiderAlertServiceTests {
    private static final Instant T0 = Instant.parse("2026-11-08T01:00:00Z");

    private final InsiderAlertRepository alerts = mock(InsiderAlertRepository.class);
    private final InsiderAlertService service = new InsiderAlertService(
            alerts,
            mock(InsiderAlertEvidenceRepository.class),
            mock(StaffAccessReader.class),
            mock(CurrentStaff.class),
            mock(SecurityStaffDirectory.class),
            mock(MerchantDirectoryReader.class));

    // 열린 경보 중 높음 · 위험만 알림 모양으로 돌려준다 — 주의 경보나 닫힌 경보로 사건이 열리지 않게
    @Test
    void findOpenRaisedAlerts_excludesCautionAndClosed() {
        when(alerts.findByStatusOrderByIdAsc(InsiderAlertStatus.OPEN))
                .thenReturn(List.of(
                        alert(1L, 4L, InsiderRule.INS_03, InsiderAlertSeverity.CAUTION),
                        alert(2L, 4L, InsiderRule.INS_08, InsiderAlertSeverity.HIGH),
                        alert(3L, 5L, InsiderRule.INS_05, InsiderAlertSeverity.CRITICAL)));

        List<InsiderAlertRaised> raised = service.findOpenRaisedAlerts();

        assertThat(raised)
                .extracting(
                        InsiderAlertRaised::alertId,
                        InsiderAlertRaised::staffId,
                        InsiderAlertRaised::rule,
                        InsiderAlertRaised::severity)
                .containsExactly(
                        tuple(2L, 4L, InsiderRule.INS_08, InsiderAlertSeverity.HIGH),
                        tuple(3L, 5L, InsiderRule.INS_05, InsiderAlertSeverity.CRITICAL));
    }

    private static InsiderAlert alert(Long id, Long staffId, InsiderRule rule, InsiderAlertSeverity severity) {
        InsiderAlert alert = InsiderAlert.open(staffId, rule, severity, "테스트", T0, T0, T0);
        ReflectionTestUtils.setField(alert, "id", id);
        return alert;
    }
}
