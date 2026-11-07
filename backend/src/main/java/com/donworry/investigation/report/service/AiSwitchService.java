package com.donworry.investigation.report.service;

import com.donworry.identity.api.CurrentStaff;
import com.donworry.identity.api.StaffSummary;
import com.donworry.investigation.report.client.AiServerProperties;
import com.donworry.investigation.report.dto.AiSwitchStatusResponse;
import com.donworry.investigation.report.entity.AiSwitchAction;
import com.donworry.investigation.report.entity.AiSwitchHistory;
import com.donworry.investigation.report.repository.AiSwitchHistoryRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

// AI 조사 긴급정지 스위치 (ADR-0083) — 설정 ai.server.enabled와 스위치가 둘 다 켜져야 AI를 쓴다
// 끄기는 보안 직원 · ADMIN, 다시 켜기는 ADMIN만 (가이드라인 3.2 "중단 · 재개승인"). SecurityConfig와 같은 규칙을 여기서도 확인한다
@Slf4j
@Service
@RequiredArgsConstructor
public class AiSwitchService {

    static final int REASON_MAX_LENGTH = 500;

    // identity.api가 내보내는 역할 이름 (ADR-0020)
    private static final String ADMIN = "ADMIN";
    private static final String SECURITY_STAFF = "SECURITY_STAFF";
    private static final Set<String> CAN_STOP = Set.of(ADMIN, SECURITY_STAFF);
    private static final Set<String> CAN_RESUME = Set.of(ADMIN);

    private final AiSwitchHistoryRepository repository;
    private final AiServerProperties aiServerProperties;
    private final CurrentStaff currentStaff;

    // 보고서를 다룰 때마다 부른다 — 마지막 기록을 읽으므로 끄면 다음 처리부터 바로 적용된다 (재시작 없음)
    public boolean isAiAllowed() {
        return aiServerProperties.enabled() && isSwitchOn();
    }

    public AiSwitchStatusResponse status() {
        currentStaffWith(CAN_STOP);
        boolean switchOn = isSwitchOn();
        return AiSwitchStatusResponse.builder()
                .configEnabled(aiServerProperties.enabled())
                .switchOn(switchOn)
                .aiActive(aiServerProperties.enabled() && switchOn)
                .histories(repository.findTop20ByOrderByIdDesc().stream()
                        .map(AiSwitchStatusResponse.History::from)
                        .toList())
                .build();
    }

    public AiSwitchStatusResponse stop(String reason) {
        change(AiSwitchAction.STOPPED, reason, CAN_STOP);
        return status();
    }

    public AiSwitchStatusResponse resume(String reason) {
        change(AiSwitchAction.RESUMED, reason, CAN_RESUME);
        return status();
    }

    // 지금 상태를 읽고 기록을 남기는 사이에 다른 요청이 끼지 않게 묶는다 — 같은 상태 기록이 두 줄 생기지 않게 (서버 1대 전제, ADR-0083 결정 5)
    // save는 Spring Data 기본 트랜잭션으로 바로 커밋된다 — 커밋 뒤에 잠금이 풀린다
    private synchronized void change(AiSwitchAction action, String reason, Set<String> allowedRoles) {
        StaffSummary staff = currentStaffWith(allowedRoles);
        String trimmed = reason == null ? "" : reason.strip();
        if (trimmed.isEmpty() || trimmed.length() > REASON_MAX_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "사유를 1~500자로 입력해 주세요.");
        }
        boolean stopping = action == AiSwitchAction.STOPPED;
        if (stopping != isSwitchOn()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, stopping ? "AI 조사가 이미 정지되어 있습니다." : "AI 조사가 이미 켜져 있습니다.");
        }
        repository.save(AiSwitchHistory.record(
                action,
                trimmed,
                staff.userId(),
                staff.displayName(),
                Instant.now().truncatedTo(ChronoUnit.MICROS)));
        log.warn("AI 조사 스위치 변경 action={} staffId={}", action, staff.userId());
    }

    // 기록이 없으면 켜짐 — 배포 직후 기존 동작이 바뀌지 않는다 (ADR-0083 결정 1)
    private boolean isSwitchOn() {
        return repository.findFirstByOrderByIdDesc().map(h -> !h.isStopped()).orElse(true);
    }

    private StaffSummary currentStaffWith(Set<String> allowedRoles) {
        StaffSummary staff = currentStaff
                .findCurrent()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."));
        if (!allowedRoles.contains(staff.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "이 작업을 할 권한이 없습니다.");
        }
        return staff;
    }
}
