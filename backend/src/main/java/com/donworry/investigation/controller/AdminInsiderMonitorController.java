package com.donworry.investigation.controller;

import com.donworry.investigation.dto.InsiderMonitorStaffResponse;
import com.donworry.investigation.dto.InsiderMonitorTimelineResponse;
import com.donworry.investigation.service.InsiderMonitorService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// ADMIN 내부자 관제 한 화면 (#679, ADR-0082 조각 10) — 직원별 단계 · 한 직원의 타임라인. 읽기만
// 역할은 SecurityConfig가 막고, 서비스가 SecurityStaffGuard.requireAdmin으로 한 번 더 확인한다
@RestController
@RequestMapping("/api/admin/insider-monitor")
@RequiredArgsConstructor
public class AdminInsiderMonitorController {
    private final InsiderMonitorService monitorService;

    @GetMapping("/staff")
    public List<InsiderMonitorStaffResponse> staff() {
        return monitorService.staff();
    }

    @GetMapping("/staff/{staffId}/timeline")
    public InsiderMonitorTimelineResponse timeline(@PathVariable Long staffId) {
        return monitorService.timeline(staffId);
    }
}
