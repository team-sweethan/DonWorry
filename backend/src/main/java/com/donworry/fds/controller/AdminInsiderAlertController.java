package com.donworry.fds.controller;

import com.donworry.fds.dto.CloseInsiderAlertRequest;
import com.donworry.fds.dto.InsiderAlertDetailResponse;
import com.donworry.fds.dto.InsiderAlertResponse;
import com.donworry.fds.service.InsiderAlertService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// ADMIN 내부자 경보 (#516, ADR-0082) — 관리자는 경보 근거(기록 종류 · 사건 번호)만 보고 원문은 보지 않는다
@RestController
@RequestMapping("/api/admin/insider-alerts")
@RequiredArgsConstructor
public class AdminInsiderAlertController {
    private final InsiderAlertService insiderAlertService;

    @GetMapping
    public List<InsiderAlertResponse> list(@RequestParam(defaultValue = "OPEN") String status) {
        return insiderAlertService.list(status);
    }

    // 사이드바 배지 — 사용자 동작 때만 부른다 (원문 열람 검토 배지와 같은 이유)
    @GetMapping("/open-count")
    public Map<String, Long> openCount() {
        return Map.of("count", insiderAlertService.openCount());
    }

    @GetMapping("/{alertId}")
    public InsiderAlertDetailResponse detail(@PathVariable Long alertId) {
        return insiderAlertService.detail(alertId);
    }

    @PostMapping("/{alertId}/close")
    public void close(
            @PathVariable Long alertId, @Valid @RequestBody(required = false) CloseInsiderAlertRequest request) {
        insiderAlertService.close(alertId, request);
    }
}
