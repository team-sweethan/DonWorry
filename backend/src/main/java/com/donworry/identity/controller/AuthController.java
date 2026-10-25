package com.donworry.identity.controller;

import com.donworry.identity.dto.ChangePasswordRequest;
import com.donworry.identity.dto.LoginRequest;
import com.donworry.identity.dto.MeResponse;
import com.donworry.identity.security.SessionUser;
import com.donworry.identity.service.AuthService;
import com.donworry.identity.service.PasswordChangeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;
    private final PasswordChangeService passwordChangeService;

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(
            @AuthenticationPrincipal SessionUser user,
            @Valid @RequestBody ChangePasswordRequest input,
            HttpServletRequest request,
            HttpServletResponse response) {
        passwordChangeService.change(user, input, request, response);
    }

    @PostMapping("/login")
    public MeResponse login(
            @Valid @RequestBody LoginRequest input, HttpServletRequest request, HttpServletResponse response) {
        return authService.login(input, request, response);
    }

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal SessionUser user) {
        return authService.me(user);
    }

    @PostMapping("/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logoutAll(
            @AuthenticationPrincipal SessionUser user, HttpServletRequest request, HttpServletResponse response) {
        authService.logoutAll(user, request, response);
    }
}
