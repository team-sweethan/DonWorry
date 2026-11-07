package com.donworry.fds.api;

import java.util.Objects;
import java.util.Set;

// 직원 담당 사건 범위 (#643, ADR-0082 합의 3) — 가맹점 ID와 (가맹점, 고객 번호) 쌍
// 고객 번호는 "가맹점ID:고객" 형식의 가맹점 회원 번호(가명)라 원문 개인정보가 아니다
public record StaffCaseScope(Set<Long> merchantIds, Set<CaseCustomer> customers) {
    public StaffCaseScope {
        merchantIds = Set.copyOf(merchantIds);
        customers = Set.copyOf(customers);
    }

    public boolean hasMerchant(Long merchantId) {
        return merchantId != null && merchantIds.contains(merchantId);
    }

    // 가맹점을 고른 검색이면 그 가맹점의 고객인지까지 본다
    public boolean hasCustomer(Long merchantId, String customerRef) {
        return customerRef != null
                && customers.stream()
                        .anyMatch(c -> c.customerRef().equals(customerRef)
                                && (merchantId == null || merchantId.equals(c.merchantId())));
    }

    public record CaseCustomer(Long merchantId, String customerRef) {
        public CaseCustomer {
            Objects.requireNonNull(merchantId, "merchantId");
            Objects.requireNonNull(customerRef, "customerRef");
        }
    }
}
