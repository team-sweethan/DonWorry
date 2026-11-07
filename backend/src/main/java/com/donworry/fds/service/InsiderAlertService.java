package com.donworry.fds.service;

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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

// ADMIN 내부자 경보 목록 · 상세 · 정상 업무로 닫기 + 열린 경보 조회 창구 (#516)
// 컨트롤러 권한 규칙(SecurityConfig)과 별도로 여기서도 ADMIN을 확인한다
@Service
@RequiredArgsConstructor
public class InsiderAlertService implements InsiderAlertReader {
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
    @Transactional
    public void close(Long alertId, CloseInsiderAlertRequest request) {
        StaffSummary admin = requireAdmin();
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
