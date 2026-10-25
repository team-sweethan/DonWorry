package com.donworry.identity.controller;

import com.donworry.identity.dto.*;
import com.donworry.identity.dto.AdminAccountRequests.*;
import com.donworry.identity.security.SessionUser;
import com.donworry.identity.service.AdminAccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminAccountController {
    private final AdminAccountService service;

    @GetMapping("/users")
    public AdminUserPageResponse list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status) {
        return service.list(page, size, keyword, role, status);
    }

    @GetMapping("/users/{id}")
    public AdminUserResponse detail(@PathVariable Long id) {
        return service.detail(id);
    }

    @PostMapping("/users")
    public ResponseEntity<TemporaryPasswordResponse> create(
            @AuthenticationPrincipal SessionUser actor,
            @Valid @RequestBody CreateUserRequest input,
            HttpServletRequest request) {
        return ResponseEntity.status(201)
                .cacheControl(CacheControl.noStore())
                .body(service.create(actor, input, request));
    }

    @PutMapping("/users/{id}/permissions")
    public ResponseEntity<Void> permissions(
            @AuthenticationPrincipal SessionUser actor,
            @PathVariable Long id,
            @Valid @RequestBody SetPermissionsRequest input,
            HttpServletRequest request) {
        service.setPermissions(actor, id, input, request);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/users/{id}/status")
    public ResponseEntity<Void> status(
            @AuthenticationPrincipal SessionUser actor,
            @PathVariable Long id,
            @Valid @RequestBody SetStatusRequest input,
            HttpServletRequest request) {
        service.setStatus(actor, id, input, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users/{id}/password-reset")
    public ResponseEntity<TemporaryPasswordResponse> reset(
            @AuthenticationPrincipal SessionUser actor, @PathVariable Long id, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.resetPassword(actor, id, request));
    }

    @GetMapping("/merchants/options")
    public List<MerchantOptionResponse> merchants(@RequestParam(defaultValue = "") String keyword) {
        return service.merchantOptions(keyword);
    }
}
