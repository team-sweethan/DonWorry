package com.donworry.settlement.controller;

import com.donworry.settlement.dto.SettlementReportResponse;
import com.donworry.settlement.service.SettlementOwnerAccessService;
import com.donworry.settlement.service.SettlementReportService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "settlement.owner-http.enabled", havingValue = "true")
@RequestMapping("/api/v1/settlements")
public class OwnerSettlementController {
    private final SettlementOwnerAccessService access;
    private final SettlementReportService reports;

    @ModelAttribute
    void authenticate(HttpServletRequest request) {
        request.setAttribute("settlement.ownerMerchant", access.requireMerchant());
    }

    @GetMapping("/report")
    public ResponseEntity<SettlementReportResponse> report(
            @RequestAttribute("settlement.ownerMerchant") Long merchant,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reports.report(merchant, from, to));
    }
}
