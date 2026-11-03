package com.donworry.payment.service;

import com.donworry.identity.api.TenantReader;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentMerchantAccess {
    private final ObjectProvider<PaymentMerchantIdentity> identity;
    private final TenantReader tenants;

    public Long requireMerchant(boolean write) {
        var adapter = identity.getIfAvailable();
        if (adapter == null) throw new PaymentHttpException(503, "PAYMENT_AUTH_NOT_READY", "서버 간 인증 연결이 준비되지 않았습니다.");
        var id = adapter.currentMerchantId()
                .orElseThrow(() -> new PaymentHttpException(401, "PAYMENT_AUTH_REQUIRED", "가맹점 인증이 필요합니다."));
        if (id <= 0) throw new IllegalStateException("invalid authenticated merchant");
        var tenant = tenants.findById(id)
                .orElseThrow(() -> new PaymentHttpException(401, "PAYMENT_AUTH_REQUIRED", "가맹점 인증이 필요합니다."));
        if (!id.equals(tenant.merchantId())) throw new IllegalStateException("merchant identity mismatch");
        if (write && !"ACTIVE".equals(tenant.status()))
            throw new PaymentHttpException(403, "MERCHANT_INACTIVE", "신규 결제 요청을 할 수 없는 가맹점입니다.");
        return id;
    }
}
