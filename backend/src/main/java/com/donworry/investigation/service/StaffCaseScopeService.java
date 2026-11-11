package com.donworry.investigation.service;

import com.donworry.fds.api.StaffCaseScope;
import com.donworry.fds.api.StaffCaseScopeReader;
import com.donworry.investigation.repository.SecurityCaseRepository;
import com.donworry.investigation.repository.SecurityCaseRepository.AssigneeCaseScope;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// fds 질문지(StaffCaseScopeReader)의 답 — 직원 담당 사건의 가맹점 · 고객 번호 (#643, ADR-0082 합의 3)
// fds 탐지 트랜잭션 안에서 읽기만 한다. 주문자 이름 · 전화 원문은 다루지 않는다
@Service
@RequiredArgsConstructor
class StaffCaseScopeService implements StaffCaseScopeReader {
    private final SecurityCaseRepository cases;

    @Override
    @Transactional(readOnly = true)
    public StaffCaseScope findScope(Long staffId, Instant closedAfter) {
        List<AssigneeCaseScope> rows = cases.findScopeOfAssignee(staffId, closedAfter);
        return new StaffCaseScope(
                rows.stream().map(AssigneeCaseScope::getMerchantId).collect(Collectors.toSet()),
                rows.stream()
                        .map(r -> new StaffCaseScope.CaseCustomer(r.getMerchantId(), r.getCustomerRef()))
                        .collect(Collectors.toSet()));
    }
}
