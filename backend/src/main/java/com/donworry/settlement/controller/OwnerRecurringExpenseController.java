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
@RequestMapping("/api/v1/settlements/cash-flow/recurring-expenses")
public class OwnerRecurringExpenseController {
    private final SettlementOwnerAccessService access;
    private final RecurringExpenseService service;

    @ModelAttribute
    void authenticate(HttpServletRequest request) {
        request.setAttribute("settlement.ownerMerchant", access.requireMerchant());
    }

    @GetMapping
    public ResponseEntity<List<RecurringExpenseResponse>> list(
            @RequestAttribute("settlement.ownerMerchant") Long merchant) {
        return ok(service.list(merchant));
    }

    @PostMapping
    public ResponseEntity<RecurringExpenseResponse> create(
            @RequestAttribute("settlement.ownerMerchant") Long merchant,
            @Valid @RequestBody RecurringExpenseRequest request) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(service.create(merchant, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> stop(
            @RequestAttribute("settlement.ownerMerchant") Long merchant, @PathVariable Long id) {
        service.stop(merchant, id);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/occurrences")
    public ResponseEntity<List<RecurringExpenseOccurrence>> occurrences(
            @RequestAttribute("settlement.ownerMerchant") Long merchant,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to) {
        return ok(service.occurrences(merchant, from, to));
    }

    @PutMapping("/{id}/occurrences/{date}/completion")
    public ResponseEntity<RecurringExpenseOccurrence> complete(
            @RequestAttribute("settlement.ownerMerchant") Long merchant,
            @PathVariable Long id,
            @PathVariable LocalDate date) {
        return ok(service.complete(merchant, id, date));
    }

    private <T> ResponseEntity<T> ok(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
}
