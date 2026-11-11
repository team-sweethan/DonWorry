package com.donworry.payment.controller;

import com.donworry.payment.dto.PaymentTransactionDetailResponse;
import com.donworry.payment.dto.PaymentTransactionsPageResponse;
import com.donworry.payment.dto.PaymentTransactionsQueryRequest;
import com.donworry.payment.service.PaymentTransactionsExportService;
import com.donworry.payment.service.PaymentTransactionsQueryService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 보안 직원 거래 내역 (#363). 권한(SECURITY_STAFF · ADMIN)은 identity SecurityConfig가 검사한다
@RestController
@RequiredArgsConstructor
public class PaymentTransactionsController {
    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(ZoneId.of("Asia/Seoul"));

    private final PaymentTransactionsQueryService service;
    private final PaymentTransactionsExportService exportService;

    @GetMapping("/api/payment-transactions")
    ResponseEntity<PaymentTransactionsPageResponse> list(@RequestParam MultiValueMap<String, String> parameters) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.findPage(PaymentTransactionsQueryRequest.of(parameters, Instant.now())));
    }

    // 거래 상세 1건 (#732) — 열 때마다 열람 기록이 남는다
    @GetMapping("/api/payment-transactions/{paymentId:[0-9]+}")
    ResponseEntity<PaymentTransactionDetailResponse> detail(@PathVariable Long paymentId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.findDetail(paymentId));
    }

    // CSV 내보내기 (#651) — 목록과 같은 조건(주소 뒤 조건, page · size는 무시). 엑셀이 한글을 읽도록 UTF-8 BOM을 붙인다
    // POST다: 경보 중이면 계정 정지 · 기록이 남는 요청이라 외부 링크(GET)로 일으킬 수 없게 CSRF 토큰을 받는다
    @PostMapping("/api/payment-transactions/export")
    ResponseEntity<byte[]> export(@RequestParam MultiValueMap<String, String> parameters) {
        Instant now = Instant.now();
        String csv = "﻿" + exportService.exportCsv(PaymentTransactionsQueryRequest.of(parameters, now));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename("payment-transactions-" + FILE_TIME.format(now) + ".csv")
                                .build()
                                .toString())
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }
}
