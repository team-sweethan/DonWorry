package com.donworry.investigation.controller;

import com.donworry.investigation.dto.RawViewReviewRequest;
import com.donworry.investigation.dto.RawViewReviewResponse;
import com.donworry.investigation.service.RawViewReviewService;
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

// ADMIN 원문 열람 사후 검토 (#619) — 목록 · 대기 건수 · [적절] / [소명 요청]
// 역할은 SecurityConfig가 막고, 서비스가 SecurityStaffGuard.requireAdmin으로 한 번 더 확인한다
@RestController
@RequestMapping("/api/admin/raw-view-reviews")
@RequiredArgsConstructor
public class AdminRawViewReviewController {
    private final RawViewReviewService rawViewReviewService;

    @GetMapping
    public List<RawViewReviewResponse> list(@RequestParam(defaultValue = "PENDING") String status) {
        return rawViewReviewService.list(status);
    }

    // 사이드바 배지 — 사용자 동작 때만 부른다 (담당 변경 요청 배지와 같은 이유)
    @GetMapping("/pending-count")
    public Map<String, Long> pendingCount() {
        return Map.of("count", rawViewReviewService.pendingCount());
    }

    @PostMapping("/{reviewId}/review")
    public void review(@PathVariable Long reviewId, @Valid @RequestBody RawViewReviewRequest body) {
        rawViewReviewService.review(reviewId, body);
    }
}
