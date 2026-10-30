package com.donworry.settlement.service;

import com.donworry.identity.api.CurrentStaff;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SettlementOwnerAccessService {
    private final CurrentStaff staff;

    public Long requireMerchant() {
        var owner = staff.findCurrent()
                .orElseThrow(() -> new SettlementRequestException(401, "SETTLEMENT_UNAUTHENTICATED", "로그인이 필요합니다."));
        if (!"OPERATOR".equals(owner.role())
                || !owner.permissions().contains("FINANCE_VIEW")
                || owner.merchantId() == null
                || owner.merchantId() <= 0) {
            throw new SettlementRequestException(403, "SETTLEMENT_FORBIDDEN", "가맹점 조회 권한이 없습니다.");
        }
        return owner.merchantId();
    }
}
