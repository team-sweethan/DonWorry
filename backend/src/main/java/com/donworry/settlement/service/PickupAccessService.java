package com.donworry.settlement.service;

import com.donworry.identity.api.CurrentMerchant;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PickupAccessService {
    private final ObjectProvider<CurrentMerchant> currentMerchant;

    public Long requireMerchant() {
        var identity = currentMerchant.getIfAvailable();
        if (identity == null)
            throw new SettlementRequestException(503, "SETTLEMENT_AUTH_NOT_READY", "가맹점 인증 연결이 준비되지 않았습니다.");
        // 직원 세션이나 요청 본문으로 HMAC이 확인한 가맹점을 대체하지 않는다.
        return identity.findCurrent()
                .orElseThrow(() -> new SettlementRequestException(401, "SETTLEMENT_UNAUTHENTICATED", "가맹점 인증이 필요합니다."))
                .merchantId();
    }
}
