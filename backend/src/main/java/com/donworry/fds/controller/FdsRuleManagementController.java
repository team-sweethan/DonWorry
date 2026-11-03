package com.donworry.fds.controller;

import com.donworry.fds.api.FdsRuleChangeRequestPage;
import com.donworry.fds.api.FdsRuleChangeRequestStatus;
import com.donworry.fds.api.FdsRulePolicySummary;
import com.donworry.fds.service.FdsRuleManagementService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/fds")
@RequiredArgsConstructor
public class FdsRuleManagementController {
    private final FdsRuleManagementService service;

    @GetMapping("/rule-policies")
    public List<FdsRulePolicySummary> policies() {
        return service.policies();
    }

    @PostMapping("/rules/{ruleCode}/disable-requests")
    public ResponseEntity<Void> requestDisable(@PathVariable String ruleCode, @RequestBody DisableRequest request) {
        service.requestDisable(ruleCode, request.reason());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/rules/{ruleCode}/active")
    public ResponseEntity<Void> setActive(@PathVariable String ruleCode, @RequestBody ActiveRequest request) {
        service.setActive(ruleCode, request.active());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/rule-change-requests")
    public FdsRuleChangeRequestPage requests(
            @RequestParam(defaultValue = "PENDING") FdsRuleChangeRequestStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return service.requests(status, Math.max(page, 0), Math.min(Math.max(size, 1), 10));
    }

    @PutMapping("/rule-change-requests/{requestId}/review")
    public ResponseEntity<Void> review(@PathVariable Long requestId, @RequestBody ReviewRequest request) {
        service.review(requestId, request.approve());
        return ResponseEntity.noContent().build();
    }

    public record ActiveRequest(boolean active) {}

    public record DisableRequest(String reason) {}

    public record ReviewRequest(boolean approve) {}
}
