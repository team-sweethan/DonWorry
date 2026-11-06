package com.donworry.investigation.controller;

import com.donworry.investigation.dto.CaseContactResponse;
import com.donworry.investigation.dto.ContactMatchRequest;
import com.donworry.investigation.dto.ContactMatchResponse;
import com.donworry.investigation.dto.RawViewRequest;
import com.donworry.investigation.dto.RawViewResponse;
import com.donworry.investigation.service.CaseContactService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 사건 결제 1건의 주문자 정보 (#619, ADR-0082) — 담당 보안 직원만, 열린 사건만
// 역할은 SecurityConfig가 막고, 담당 · 사건 소속 결제는 서비스가 확인한다. 연락처 응답은 브라우저 · 프록시에 저장하지 않는다
@RestController
@RequestMapping("/api/security-cases/{caseId}/payments/{paymentId}")
@RequiredArgsConstructor
public class CaseContactController {
    private final CaseContactService caseContactService;

    @GetMapping("/contact")
    public ResponseEntity<CaseContactResponse> contact(@PathVariable Long caseId, @PathVariable Long paymentId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(caseContactService.masked(caseId, paymentId));
    }

    @PostMapping("/contact-match")
    public ContactMatchResponse match(
            @PathVariable Long caseId, @PathVariable Long paymentId, @Valid @RequestBody ContactMatchRequest body) {
        return caseContactService.match(caseId, paymentId, body.phone());
    }

    @PostMapping("/contact-raw-view")
    public ResponseEntity<RawViewResponse> rawView(
            @PathVariable Long caseId, @PathVariable Long paymentId, @Valid @RequestBody RawViewRequest body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(caseContactService.rawView(caseId, paymentId, body));
    }
}
