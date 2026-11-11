package com.donworry.investigation.controller;

import com.donworry.investigation.dto.LoginSecurityResponse;
import com.donworry.investigation.service.LoginSecurityService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// ADMIN 로그인 보안 대시보드 (#770, ADR-0082) — 전체 로그인 실패율 · 보안 직원 로그인 · 탈취 의심 경보. 읽기만
// 역할은 SecurityConfig가 막고, 서비스가 SecurityStaffGuard.requireAdmin으로 한 번 더 확인한다
@RestController
@RequiredArgsConstructor
public class AdminLoginSecurityController {
    private final LoginSecurityService loginSecurityService;

    @GetMapping("/api/admin/login-security")
    public LoginSecurityResponse dashboard() {
        return loginSecurityService.dashboard();
    }
}
