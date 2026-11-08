package com.donworry.fds.controller;

import com.donworry.fds.api.FdsRuleCatalogEntry;
import com.donworry.fds.service.InsiderRuleCatalogService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 내부자 규칙 카드 (#685) — 감시 기준값이 보이므로 관리자만 (SecurityConfig)
@RestController
@RequestMapping("/api/fds/insider-detections")
@RequiredArgsConstructor
public class InsiderRuleCatalogController {
    private final InsiderRuleCatalogService service;

    @GetMapping("/rule-catalog")
    public List<FdsRuleCatalogEntry> ruleCatalog() {
        return service.ruleCatalog();
    }
}
