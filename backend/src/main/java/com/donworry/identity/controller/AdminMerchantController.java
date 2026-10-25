package com.donworry.identity.controller;

import com.donworry.identity.dto.*;
import com.donworry.identity.dto.AdminMerchantRequests.*;
import com.donworry.identity.security.SessionUser;
import com.donworry.identity.service.AdminMerchantService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/merchants")
@RequiredArgsConstructor
public class AdminMerchantController {
    private final AdminMerchantService service;

    @GetMapping
    public AdminMerchantPageResponse list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(required = false) String status) {
        return service.list(page, size, keyword, status);
    }

    @GetMapping("/{id}")
    public AdminMerchantResponse detail(@PathVariable Long id) {
        return service.detail(id);
    }

    @PostMapping
    public ResponseEntity<AdminMerchantResponse> create(
            @AuthenticationPrincipal SessionUser actor,
            @Valid @RequestBody CreateMerchantRequest input,
            HttpServletRequest request) {
        return ResponseEntity.status(201).body(service.create(actor, input, request));
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<Void> status(
            @AuthenticationPrincipal SessionUser actor,
            @PathVariable Long id,
            @Valid @RequestBody SetMerchantStatusRequest input,
            HttpServletRequest request) {
        service.setStatus(actor, id, input, request);
        return ResponseEntity.noContent().build();
    }
}
