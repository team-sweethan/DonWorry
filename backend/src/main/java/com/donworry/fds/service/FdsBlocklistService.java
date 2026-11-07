package com.donworry.fds.service;

import com.donworry.fds.dto.CreateFdsBlocklistEntryRequest;
import com.donworry.fds.dto.FdsBlocklistPageResponse;
import com.donworry.fds.dto.FdsBlocklistStatusFilter;
import com.donworry.fds.dto.FdsBlocklistStatusResponse;
import com.donworry.fds.entity.FdsBlocklistEntry;
import com.donworry.fds.repository.FdsBlocklistEntryRepository;
import com.donworry.fds.repository.FdsEvaluationRepository;
import com.donworry.identity.api.CurrentStaff;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FdsBlocklistService {
    private final FdsBlocklistEntryRepository entries;
    private final CurrentStaff currentStaff;
    private final FdsEvaluationRepository evaluations;

    public FdsBlocklistPageResponse list(int page, int size, FdsBlocklistStatusFilter status, String search) {
        requireViewer();
        var at = Instant.now();
        var normalizedSearch = normalizeSearch(search);
        var pageable = PageRequest.of(page, size);
        var result = status == FdsBlocklistStatusFilter.ACTIVE
                ? entries.findActiveAtWithSearch(at, normalizedSearch, pageable)
                : entries.findInactiveAtWithSearch(at, normalizedSearch, pageable);
        return new FdsBlocklistPageResponse(
                result.map(entry -> new FdsBlocklistPageResponse.Item(
                                entry.getBlocklistEntryId(),
                                entry.getIdentifierType(),
                                displayValue(entry),
                                entry.getDisplayLabel(),
                                entry.getReason(),
                                entry.getDeactivationReason(),
                                entry.isActive(),
                                entry.getCreatedAt(),
                                entry.getDeactivatedAt(),
                                entry.getExpiresAt()))
                        .toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    private String normalizeSearch(String search) {
        if (search == null) {
            return "";
        }
        var normalized = search.strip();
        if (normalized.codePointCount(0, normalized.length()) > 100) {
            throw new IllegalArgumentException("search must be at most 100 characters");
        }
        return normalized;
    }

    public FdsBlocklistStatusResponse status(CreateFdsBlocklistEntryRequest.IdentifierType type, String value) {
        requireViewer();
        var normalized = normalizeValue(type, value);
        var blocked = entries.findByIdentifierTypeAndIdentifierValue(type.name(), normalized).stream()
                .anyMatch(entry -> entry.isActiveAt(Instant.now()));
        return new FdsBlocklistStatusResponse(blocked);
    }

    @Transactional
    public void add(CreateFdsBlocklistEntryRequest request) {
        requireRole("SECURITY_STAFF");
        register(
                request.identifierType(),
                normalizeValue(request.identifierType(), request.identifierValue()),
                request.displayLabel(),
                request.reason());
    }

    // 경보 상세의 차단 후보 등록. 화면은 원문을 모르므로 서버가 평가에서 값을 찾는다 (#603)
    @Transactional
    public void addFromEvaluation(
            Long evaluationId, CreateFdsBlocklistEntryRequest.IdentifierType identifierType, String reason) {
        requireRole("SECURITY_STAFF");
        var evaluation = evaluations.findById(evaluationId).orElseThrow(FdsEvaluationNotFoundException::new);
        var value = switch (identifierType) {
            case CARD_TOKEN -> evaluation.getCardToken();
            case DEVICE_ID -> evaluation.getDeviceId();
            case BUYER_REF -> evaluation.getCustomerRef();
            case IP_ADDRESS -> evaluation.getIpAddress();
            case BUYER_EMAIL -> evaluation.getBuyerEmail();
        };
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이 평가에는 해당 식별값이 없습니다.");
        }
        var displayLabel = identifierType == CreateFdsBlocklistEntryRequest.IdentifierType.CARD_TOKEN
                ? evaluation.getCardDisplayLabel()
                : null;
        register(identifierType, normalizeValue(identifierType, value), displayLabel, reason);
    }

    private void register(
            CreateFdsBlocklistEntryRequest.IdentifierType identifierType,
            String value,
            String displayLabel,
            String reason) {
        var type = identifierType.name();
        var existing = entries.findByIdentifierTypeAndIdentifierValue(type, value).stream()
                .findFirst();
        if (existing.isPresent()) {
            var entry = existing.get();
            if (entry.isActiveAt(Instant.now())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 등록된 차단 식별값입니다.");
            }
            entry.reactivate(optionalDisplayLabel(displayLabel), reason.trim());
            return;
        }
        entries.save(FdsBlocklistEntry.active(
                type, value, optionalDisplayLabel(displayLabel), reason.trim(), Instant.now()));
    }

    // 목록 화면에 이메일 원문을 보내지 않는다 (#603). 검색 · 해제는 서버의 원문과 항목 ID로 그대로 된다
    private String displayValue(FdsBlocklistEntry entry) {
        return CreateFdsBlocklistEntryRequest.IdentifierType.BUYER_EMAIL.name().equals(entry.getIdentifierType())
                ? FdsIdentifierMasking.email(entry.getIdentifierValue())
                : entry.getIdentifierValue();
    }

    @Transactional
    public void deactivate(Long entryId, String reason) {
        requireRole("SECURITY_STAFF");
        var normalizedReason = requireReason(reason);
        var entry = entries.findById(entryId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "차단 식별값을 찾을 수 없습니다."));
        entry.deactivate(normalizedReason, Instant.now());
    }

    private String normalizeValue(CreateFdsBlocklistEntryRequest.IdentifierType type, String value) {
        var normalized = value.trim();
        if (type == CreateFdsBlocklistEntryRequest.IdentifierType.BUYER_EMAIL) {
            return normalized.toLowerCase(java.util.Locale.ROOT);
        }
        return normalized;
    }

    private String optionalDisplayLabel(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    private String requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.codePointCount(0, reason.length()) > 500) {
            throw new IllegalArgumentException("reason must be between 1 and 500 characters");
        }
        return reason.strip();
    }

    private void requireViewer() {
        var actor = requireCurrent();
        if (!"SECURITY_STAFF".equals(actor.role()) && !"ADMIN".equals(actor.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "권한이 없습니다.");
        }
    }

    private void requireRole(String role) {
        if (!role.equals(requireCurrent().role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "권한이 없습니다.");
        }
    }

    private com.donworry.identity.api.StaffSummary requireCurrent() {
        return currentStaff
                .findCurrent()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."));
    }
}
