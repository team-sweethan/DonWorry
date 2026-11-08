package com.donworry.investigation.controller;

import com.donworry.investigation.dto.InsiderCaseDecisionRequest;
import com.donworry.investigation.dto.InsiderCaseDetailResponse;
import com.donworry.investigation.dto.InsiderCaseResponse;
import com.donworry.investigation.dto.InsiderLockReleaseRequest;
import com.donworry.investigation.service.InsiderCaseService;
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

// ADMIN 내부자 사건 (#670) — 목록 · 열린 수 · 상세 · 판정 · 징계 사건 정지 해제
// 역할은 SecurityConfig가 막고, 서비스가 SecurityStaffGuard.requireAdmin으로 한 번 더 확인한다
@RestController
@RequestMapping("/api/admin/insider-cases")
@RequiredArgsConstructor
public class AdminInsiderCaseController {
    private final InsiderCaseService insiderCaseService;

    @GetMapping
    public List<InsiderCaseResponse> list(@RequestParam(defaultValue = "OPEN") String status) {
        return insiderCaseService.list(status);
    }

    // 사이드바 배지
    @GetMapping("/open-count")
    public Map<String, Long> openCount() {
        return Map.of("count", insiderCaseService.openCount());
    }

    @GetMapping("/{insiderCaseId}")
    public InsiderCaseDetailResponse detail(@PathVariable Long insiderCaseId) {
        return insiderCaseService.detail(insiderCaseId);
    }

    @PostMapping("/{insiderCaseId}/decision")
    public void decide(@PathVariable Long insiderCaseId, @Valid @RequestBody InsiderCaseDecisionRequest body) {
        insiderCaseService.decide(insiderCaseId, body);
    }

    @PostMapping("/{insiderCaseId}/lock-release")
    public void releaseLock(@PathVariable Long insiderCaseId, @Valid @RequestBody InsiderLockReleaseRequest body) {
        insiderCaseService.releaseLock(insiderCaseId, body);
    }
}
