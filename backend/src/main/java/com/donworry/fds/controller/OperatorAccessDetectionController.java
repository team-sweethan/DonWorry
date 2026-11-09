package com.donworry.fds.controller;

import com.donworry.fds.api.FdsRuleCatalogEntry;
import com.donworry.fds.dto.OperatorAccessDetectionResponses;
import com.donworry.fds.service.OperatorAccessDetectionQueryService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/fds/operator-access-detections")
@RequiredArgsConstructor
public class OperatorAccessDetectionController {
    private final OperatorAccessDetectionQueryService service;

    @GetMapping
    public OperatorAccessDetectionResponses.Page list(
            @RequestParam(defaultValue = "ALL") String risk,
            @RequestParam(required = false) String rule,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return service.list(risk, rule, search, Math.max(page, 0), Math.min(Math.max(size, 1), 100));
    }

    @GetMapping("/accounts")
    public OperatorAccessDetectionResponses.AccountPage accounts(
            @RequestParam(defaultValue = "ALL") String risk,
            @RequestParam(required = false) String rule,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return service.accounts(risk, rule, search, Math.max(page, 0), Math.min(Math.max(size, 1), 100));
    }

    @GetMapping("/summary")
    public OperatorAccessDetectionResponses.Summary summary() {
        return service.summary();
    }

    @GetMapping("/rule-catalog")
    public List<FdsRuleCatalogEntry> ruleCatalog() {
        return service.ruleCatalog();
    }

    @GetMapping("/low/{eventId}")
    public OperatorAccessDetectionResponses.Detail lowRiskDetail(@PathVariable Long eventId) {
        return service.lowRiskDetail(eventId);
    }

    @GetMapping("/{detectionId}")
    public OperatorAccessDetectionResponses.Detail detail(@PathVariable Long detectionId) {
        return service.detail(detectionId);
    }
}
