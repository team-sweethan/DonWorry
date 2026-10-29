package com.donworry.identity.controller;

import com.donworry.identity.dto.ApplicationReviewRequests.*;
import com.donworry.identity.dto.ApplicationReviewResponses.*;
import com.donworry.identity.security.SessionUser;
import com.donworry.identity.service.ApplicationReviewService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/merchant-applications")
@RequiredArgsConstructor
public class ApplicationReviewController {
    private final ApplicationReviewService service;

    @GetMapping
    public ApplicationPage list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(required = false) String status) {
        return service.list(page, size, keyword, status);
    }

    @GetMapping("/{id}")
    public Detail detail(@PathVariable Long id) {
        return service.detail(id);
    }

    @PostMapping("/{id}/approve")
    public ReviewResult approve(
            @AuthenticationPrincipal SessionUser actor,
            @PathVariable Long id,
            @Valid @RequestBody ApproveRequest input,
            HttpServletRequest request) {
        return service.approve(actor, id, input, request);
    }

    @PostMapping("/{id}/reject")
    public ReviewResult reject(
            @AuthenticationPrincipal SessionUser actor,
            @PathVariable Long id,
            @Valid @RequestBody RejectRequest input,
            HttpServletRequest request) {
        return service.reject(actor, id, input, request);
    }
}
