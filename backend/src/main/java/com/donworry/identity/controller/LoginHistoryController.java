package com.donworry.identity.controller;

import com.donworry.identity.dto.*;
import com.donworry.identity.security.SessionUser;
import com.donworry.identity.service.LoginHistoryQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class LoginHistoryController {
    private final LoginHistoryQueryService service;

    @GetMapping("/api/auth/login-histories")
    public ResponseEntity<LoginHistoryPageResponse> own(
            @AuthenticationPrincipal SessionUser user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.own(user, page, size));
    }

    @GetMapping("/api/auth/login-histories/latest-success")
    public ResponseEntity<LatestLoginResponse> latest(@AuthenticationPrincipal SessionUser user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.latest(user));
    }

    @GetMapping("/api/admin/users/{userId}/login-histories")
    public ResponseEntity<LoginHistoryPageResponse> account(
            @AuthenticationPrincipal SessionUser user,
            @PathVariable Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.account(user, userId, page, size));
    }
}
