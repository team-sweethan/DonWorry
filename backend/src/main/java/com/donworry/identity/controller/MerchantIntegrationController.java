package com.donworry.identity.controller;

import com.donworry.identity.dto.*;
import com.donworry.identity.dto.IntegrationRequests.*;
import com.donworry.identity.security.SessionUser;
import com.donworry.identity.service.MerchantIntegrationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class MerchantIntegrationController {
    private final MerchantIntegrationService service;

    @GetMapping("/api/owner/integration")
    public IntegrationResponse owner(
            @AuthenticationPrincipal SessionUser actor,
            HttpServletRequest request,
            @RequestParam(defaultValue = "0") int page) {
        return service.owner(actor, request, page);
    }

    @GetMapping("/api/admin/merchants/{id}/integration")
    public IntegrationResponse admin(
            @AuthenticationPrincipal SessionUser actor,
            @PathVariable Long id,
            HttpServletRequest request,
            @RequestParam(defaultValue = "0") int page) {
        return service.admin(actor, id, request, page);
    }

    @PostMapping("/api/owner/integration/keys")
    public IntegrationKeyResponse issue(
            @AuthenticationPrincipal SessionUser actor,
            @Valid @RequestBody KeyRequest input,
            HttpServletRequest request) {
        return service.key(actor, input, "ISSUE", request);
    }

    @PostMapping("/api/owner/integration/keys/reveal")
    public IntegrationKeyResponse reveal(
            @AuthenticationPrincipal SessionUser actor,
            @Valid @RequestBody KeyRequest input,
            HttpServletRequest request) {
        return service.key(actor, input, "REVEAL", request);
    }

    @PostMapping("/api/owner/integration/keys/reissue")
    public IntegrationKeyResponse reissue(
            @AuthenticationPrincipal SessionUser actor,
            @Valid @RequestBody KeyRequest input,
            HttpServletRequest request) {
        return service.key(actor, input, "REISSUE", request);
    }

    @PostMapping("/api/admin/merchants/{id}/integration/revoke")
    public ResponseEntity<Void> revoke(
            @AuthenticationPrincipal SessionUser actor,
            @PathVariable Long id,
            @Valid @RequestBody RevokeRequest input,
            HttpServletRequest request) {
        service.revoke(actor, id, input, request);
        return ResponseEntity.noContent().build();
    }
}
