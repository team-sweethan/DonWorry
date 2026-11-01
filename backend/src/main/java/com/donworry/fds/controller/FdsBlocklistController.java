package com.donworry.fds.controller;

import com.donworry.fds.dto.CreateFdsBlocklistEntryRequest;
import com.donworry.fds.dto.DeactivateFdsBlocklistEntryRequest;
import com.donworry.fds.dto.FdsBlocklistPageResponse;
import com.donworry.fds.dto.FdsBlocklistStatusFilter;
import com.donworry.fds.dto.FdsBlocklistStatusResponse;
import com.donworry.fds.service.FdsBlocklistService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/fds/blocklist")
@RequiredArgsConstructor
public class FdsBlocklistController {
    private final FdsBlocklistService service;

    @GetMapping
    public FdsBlocklistPageResponse list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "ACTIVE") FdsBlocklistStatusFilter status,
            @RequestParam(defaultValue = "") String search) {
        return service.list(Math.max(page, 0), Math.min(Math.max(size, 1), 10), status, search);
    }

    @GetMapping("/status")
    public FdsBlocklistStatusResponse status(
            @RequestParam CreateFdsBlocklistEntryRequest.IdentifierType identifierType,
            @RequestParam String identifierValue) {
        return service.status(identifierType, identifierValue);
    }

    @PostMapping
    public ResponseEntity<Void> add(@Valid @RequestBody CreateFdsBlocklistEntryRequest request) {
        service.add(request);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{entryId}/deactivate")
    public ResponseEntity<Void> deactivate(
            @PathVariable Long entryId, @Valid @RequestBody DeactivateFdsBlocklistEntryRequest request) {
        service.deactivate(entryId, request.reason());
        return ResponseEntity.noContent().build();
    }
}
