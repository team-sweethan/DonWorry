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
    private final com.donworry.identity.service.ApplicationMailQueryService mailQuery;
    private final com.donworry.identity.service.ApplicationMailRecoveryService recovery;

    @GetMapping("/{id}/mail-deliveries")
    public com.donworry.identity.dto.ApplicationMailResponses.MailPage history(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return mailQuery.history(id, page, size);
    }

    @PostMapping("/{id}/mail-resends")
    public com.donworry.identity.dto.ApplicationMailResponses.ResendResult resend(
            @AuthenticationPrincipal SessionUser actor,
            @PathVariable Long id,
            @Valid @RequestBody ResendRequest input,
            HttpServletRequest request) {
        return recovery.resend(actor, id, input.expectedDeliveryId(), request);
    }

    @GetMapping
    public ApplicationPage list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "false") String mailAttention) {
        if (!java.util.Set.of("true", "false").contains(mailAttention))
            throw new com.donworry.identity.security.ApplicationException(
                    com.donworry.identity.security.ApplicationErrorCode.INVALID_INPUT);
        return service.list(page, size, keyword, status, Boolean.parseBoolean(mailAttention));
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
