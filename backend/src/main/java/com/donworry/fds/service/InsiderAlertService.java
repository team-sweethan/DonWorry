package com.donworry.fds.service;

import com.donworry.fds.api.InsiderAlertCloser;
import com.donworry.fds.api.InsiderAlertEntry;
import com.donworry.fds.api.InsiderAlertEvidenceReader;
import com.donworry.fds.api.InsiderAlertMonitorReader;
import com.donworry.fds.api.InsiderAlertRaised;
import com.donworry.fds.api.InsiderAlertReader;
import com.donworry.fds.dto.CloseInsiderAlertRequest;
import com.donworry.fds.dto.InsiderAlertDetailResponse;
import com.donworry.fds.dto.InsiderAlertEvidenceResponse;
import com.donworry.fds.dto.InsiderAlertResponse;
import com.donworry.fds.entity.InsiderAlert;
import com.donworry.fds.entity.InsiderAlertStatus;
import com.donworry.fds.repository.InsiderAlertEvidenceRepository;
import com.donworry.fds.repository.InsiderAlertRepository;
import com.donworry.identity.api.CurrentStaff;
import com.donworry.identity.api.MerchantDirectoryEntry;
import com.donworry.identity.api.MerchantDirectoryReader;
import com.donworry.identity.api.SecurityStaffDirectory;
import com.donworry.identity.api.SecurityStaffEntry;
import com.donworry.identity.api.StaffAccessReader;
import com.donworry.identity.api.StaffAccessRecorded;
import com.donworry.identity.api.StaffSummary;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

// ADMIN 내부자 경보 목록 · 상세 · 정상 업무로 닫기 + 열린 경보 조회 창구 (#516) + 내부자 사건 판정용 경보 닫기 (#670)
// + 해제 검토 보고서용 근거 기록 번호 창구 (#675) + 내부자 관제 읽기 창구 (#679)
// 컨트롤러 권한 규칙(SecurityConfig)과 별도로 여기서도 ADMIN을 확인한다
@Service
@RequiredArgsConstructor
public class InsiderAlertService
        implements InsiderAlertReader, InsiderAlertCloser, InsiderAlertEvidenceReader, InsiderAlertMonitorReader {
    private final InsiderAlertRepository alerts;
    private final InsiderAlertEvidenceRepository evidence;
    private final StaffAccessReader accessReader;
    private final CurrentStaff currentStaff;
    private final SecurityStaffDirectory staffDirectory;
    private final MerchantDirectoryReader merchantDirectory;

    @Override
    @Transactional(readOnly = true)
    public boolean hasOpenAlert(Long staffId) {
        return alerts.existsByStaffIdAndStatus(staffId, InsiderAlertStatus.OPEN);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isOpen(Long alertId) {
        return alerts.findById(alertId).map(InsiderAlert::isOpen).orElse(false);
    }

    // 해제 검토 보고서 (#675) — 근거 기록 번호만 돌려준다
    @Override
    @Transactional(readOnly = true)
    public List<Long> accessEventIds(Collection<Long> alertIds) {
        if (alertIds.isEmpty()) return List.of();
        return evidence.findAccessEventIdsOf(alertIds);
    }

    // 주의 경보는 사건을 열지 않으므로 뺀다 — 알림을 보내는 조건(isRaisedToOthers)과 같다
    @Override
    @Transactional(readOnly = true)
    public List<InsiderAlertRaised> findOpenRaisedAlerts() {
        return alerts.findByStatusOrderByIdAsc(InsiderAlertStatus.OPEN).stream()
                .filter(alert -> alert.getSeverity().isRaisedToOthers())
                .map(alert -> InsiderAlertRaised.builder()
                        .alertId(alert.getId())
                        .staffId(alert.getStaffId())
                        .rule(alert.getRuleCode())
                        .severity(alert.getSeverity())
                        .summary(alert.getSummary())
                        .raisedAt(alert.getUpdatedAt())
                        .build())
                .toList();
    }

    // 내부자 관제 (#679) — ADMIN 확인은 부르는 쪽(P3 관제)이 한다
    @Override
    @Transactional(readOnly = true)
    public List<InsiderAlertEntry> findOpenAlerts() {
        return alerts.findByStatusOrderByIdAsc(InsiderAlertStatus.OPEN).stream()
                .map(InsiderAlertService::toEntry)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<InsiderAlertEntry> findAlerts(Long staffId, Instant from, Instant to) {
        return alerts.findTouchedBetween(staffId, from, to).stream()
                .map(InsiderAlertService::toEntry)
                .toList();
    }

    private static InsiderAlertEntry toEntry(InsiderAlert alert) {
        return InsiderAlertEntry.builder()
                .alertId(alert.getId())
                .staffId(alert.getStaffId())
                .rule(alert.getRuleCode())
                .severity(alert.getSeverity())
                .open(alert.isOpen())
                .summary(alert.getSummary())
                .createdAt(alert.getCreatedAt())
                .closedAt(alert.getClosedAt())
                .build();
    }

    // 판정하는 ADMIN 확인은 부르는 쪽(P3)이 한다. 잠금 순서는 정상 업무로 닫기와 같다(직원 행 먼저)
    // 직원 행을 잡은 뒤 읽으므로, 그사이 탐지가 막 연 경보까지 함께 닫는다
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int closeOpenAlerts(Long staffId, Long adminId, String adminName, String memo) {
        alerts.lockDetectionState(staffId);
        List<InsiderAlert> open = alerts.findByStaffIdAndStatus(staffId, InsiderAlertStatus.OPEN);
        Instant now = Instant.now();
        open.forEach(alert -> alert.close(adminId, adminName, memo, now));
        return open.size();
    }

    @Transactional(readOnly = true)
    public List<InsiderAlertResponse> list(String statusName) {
        requireAdmin();
        Map<Long, String> staffNames = staffNames();
        return alerts.findTop100ByStatusOrderByUpdatedAtDescIdDesc(parse(statusName)).stream()
                .map(a -> toResponse(a, staffNames))
                .toList();
    }

    @Transactional(readOnly = true)
    public InsiderAlertDetailResponse detail(Long alertId) {
        requireAdmin();
        InsiderAlert alert = alerts.findById(alertId).orElseThrow(InsiderAlertService::notFound);
        Map<Long, String> merchantNames = merchantDirectory.findAll().stream()
                .collect(Collectors.toMap(
                        MerchantDirectoryEntry::merchantId, MerchantDirectoryEntry::displayName, (a, b) -> a));
        List<InsiderAlertEvidenceResponse> rows = accessReader.findByIds(evidence.findAccessEventIds(alertId)).stream()
                .map(e -> toEvidence(e, merchantNames))
                .toList();
        return new InsiderAlertDetailResponse(toResponse(alert, staffNames()), rows);
    }

    @Transactional(readOnly = true)
    public long openCount() {
        requireAdmin();
        return alerts.countByStatus(InsiderAlertStatus.OPEN);
    }

    // 정상 업무로 닫기 — 닫힌 뒤에는 그 전 기록으로 같은 규칙 경보가 다시 열리지 않는다(InsiderDetectionService)
    // 탐지와 같은 직원별 잠금을 먼저 잡는다: 탐지가 읽어 둔 OPEN 경보를 닫은 뒤 덮어쓰지 못하게 (#627 리뷰)
    // 잠금 순서는 탐지와 같다(직원 행 → 경보 행). 직원 번호는 경보를 영속 상태로 올리지 않고 칸만 읽는다
    // READ_COMMITTED: 잠금을 기다린 뒤 탐지가 바꾼 최신 행을 읽어야 한다. REPEATABLE READ면 첫 조회 때 잡힌 스냅숏 때문에
    // MariaDB가 잠금 문장을 "Record has changed since last read"로 거절한다
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void close(Long alertId, CloseInsiderAlertRequest request) {
        StaffSummary admin = requireAdmin();
        Long staffId = alerts.findStaffIdById(alertId).orElseThrow(InsiderAlertService::notFound);
        alerts.lockDetectionState(staffId);
        InsiderAlert alert = alerts.findByIdForUpdate(alertId).orElseThrow(InsiderAlertService::notFound);
        if (!alert.isOpen()) throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 닫힌 경보입니다.");
        String memo =
                request == null || request.memo() == null || request.memo().isBlank()
                        ? null
                        : request.memo().strip();
        alert.close(admin.userId(), admin.displayName(), memo, Instant.now());
    }

    // 고객 번호(customerRef) · 로그인 번호는 근거 화면에 필요 없어 옮기지 않는다
    private static InsiderAlertEvidenceResponse toEvidence(StaffAccessRecorded e, Map<Long, String> merchantNames) {
        return InsiderAlertEvidenceResponse.builder()
                .eventId(e.eventId())
                .accessType(e.accessType().name())
                .result(e.result().name())
                .denyReason(e.denyReason() == null ? null : e.denyReason().name())
                .caseId(e.caseId())
                .merchantId(e.merchantId())
                .merchantName(e.merchantId() == null ? null : merchantNames.get(e.merchantId()))
                .paymentId(e.paymentId())
                .fieldType(e.fieldType() == null ? null : e.fieldType().name())
                .occurredAt(e.occurredAt())
                .build();
    }

    private InsiderAlertResponse toResponse(InsiderAlert a, Map<Long, String> staffNames) {
        return InsiderAlertResponse.builder()
                .alertId(a.getId())
                .staffId(a.getStaffId())
                .staffName(staffNames.get(a.getStaffId()))
                .ruleCode(a.getRuleCode().code())
                .ruleLabel(a.getRuleCode().label())
                .severity(a.getSeverity().name())
                .status(a.getStatus().name())
                .summary(a.getSummary())
                .evidenceCount(evidence.countByAlertId(a.getId()))
                .firstEventAt(a.getFirstEventAt())
                .lastEventAt(a.getLastEventAt())
                .createdAt(a.getCreatedAt())
                .updatedAt(a.getUpdatedAt())
                .closedByName(a.getClosedByAdminName())
                .closedAt(a.getClosedAt())
                .closeMemo(a.getCloseMemo())
                .build();
    }

    // 정지된 직원도 들어 있다 — 지난 경보의 이름을 보여 줘야 해서
    private Map<Long, String> staffNames() {
        return staffDirectory.securityStaff().stream()
                .collect(Collectors.toMap(SecurityStaffEntry::userId, SecurityStaffEntry::displayName, (a, b) -> a));
    }

    private StaffSummary requireAdmin() {
        StaffSummary actor = currentStaff
                .findCurrent()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."));
        if (!"ADMIN".equals(actor.role())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "권한이 없습니다.");
        return actor;
    }

    // controller가 entity enum을 모르게 문자열로 받는다 (ArchitectureTests)
    private static InsiderAlertStatus parse(String name) {
        try {
            return InsiderAlertStatus.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "알 수 없는 경보 상태입니다.");
        }
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "경보가 없습니다.");
    }
}
