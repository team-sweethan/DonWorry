package com.donworry.fds.service;

import com.donworry.fds.dto.CreateFdsBlocklistEntryRequest;
import com.donworry.fds.dto.FdsBlocklistPageResponse;
import com.donworry.fds.dto.FdsBlocklistStatusResponse;
import com.donworry.fds.entity.FdsBlocklistEntry;
import com.donworry.fds.repository.FdsBlocklistEntryRepository;
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

    public FdsBlocklistPageResponse list(int page, int size) {
        requireViewer();
        var result = entries.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size));
        return new FdsBlocklistPageResponse(
                result.map(entry -> new FdsBlocklistPageResponse.Item(
                                entry.getBlocklistEntryId(),
                                entry.getIdentifierType(),
                                entry.getIdentifierValue(),
                                entry.getDisplayLabel(),
                                entry.getReason(),
                                entry.isActive(),
                                entry.getCreatedAt(),
                                entry.getExpiresAt()))
                        .toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
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
        var type = request.identifierType().name();
        var value = normalizeValue(request.identifierType(), request.identifierValue());
        var existing = entries.findByIdentifierTypeAndIdentifierValue(type, value).stream()
                .findFirst();
        if (existing.isPresent()) {
            var entry = existing.get();
            if (entry.isActiveAt(Instant.now())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 등록된 차단 식별값입니다.");
            }
            entry.reactivate(
                    optionalDisplayLabel(request.displayLabel()),
                    request.reason().trim());
            return;
        }
        entries.save(FdsBlocklistEntry.active(
                type,
                value,
                optionalDisplayLabel(request.displayLabel()),
                request.reason().trim(),
                Instant.now()));
    }

    @Transactional
    public void deactivate(Long entryId) {
        requireRole("SECURITY_STAFF");
        var entry = entries.findById(entryId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "차단 식별값을 찾을 수 없습니다."));
        entry.deactivate();
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
