package com.donworry.settlement.controller;

import com.donworry.settlement.dto.*;
import com.donworry.settlement.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "settlement.owner-http.enabled", havingValue = "true")
@RequestMapping("/api/v1/settlements/cash-flow")
public class OwnerCashFlowController {
    private final SettlementOwnerAccessService access;
    private final CashFlowManagementService management;

    @ModelAttribute
    void authenticate(HttpServletRequest request) {
        request.setAttribute("settlement.ownerMerchant", access.requireMerchant());
    }

    @GetMapping("/settings")
    public ResponseEntity<CashFlowSettingResponse> settings(
            @RequestAttribute("settlement.ownerMerchant") Long merchant) {
        return ok(management
                .settings(merchant)
                .orElseThrow(() ->
                        new SettlementRequestException(404, "SETTLEMENT_SETTING_REQUIRED", "최초 시작 잔액 설정이 필요합니다.")));
    }

    @PutMapping("/settings")
    public ResponseEntity<CashFlowSettingResponse> settings(
            @RequestAttribute("settlement.ownerMerchant") Long merchant,
            @Valid @RequestBody CashFlowSettingRequest request) {
        return ok(management.saveSettings(merchant, request.openingBalance(), request.baseDate()));
    }

    @GetMapping("/expenses")
    public ResponseEntity<List<ExpenseResponse>> expenses(
            @RequestAttribute("settlement.ownerMerchant") Long merchant,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to) {
        return ok(management.expenses(merchant, from, to));
    }

    @PostMapping("/expenses")
    public ResponseEntity<ExpenseResponse> create(
            @RequestAttribute("settlement.ownerMerchant") Long merchant, @Valid @RequestBody ExpenseRequest request) {
        return ResponseEntity.status(201)
                .cacheControl(CacheControl.noStore())
                .body(management.createExpense(merchant, request));
    }

    @PutMapping("/expenses/{id}")
    public ResponseEntity<ExpenseResponse> update(
            @RequestAttribute("settlement.ownerMerchant") Long merchant,
            @PathVariable Long id,
            @Valid @RequestBody ExpenseRequest request) {
        return ok(management.updateExpense(merchant, id, request));
    }

    @DeleteMapping("/expenses/{id}")
    public ResponseEntity<Void> delete(
            @RequestAttribute("settlement.ownerMerchant") Long merchant, @PathVariable Long id) {
        management.deleteExpense(merchant, id);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PutMapping("/expenses/{id}/completion")
    public ResponseEntity<ExpenseResponse> complete(
            @RequestAttribute("settlement.ownerMerchant") Long merchant, @PathVariable Long id) {
        return ok(management.completeExpense(merchant, id));
    }

    private <T> ResponseEntity<T> ok(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
}
