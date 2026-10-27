package com.donworry.identity.api;

import java.util.Optional;

// 직원 세션이 아닌, 현재 서버 간 요청의 서명으로 확인한 가맹점이다.
public interface CurrentMerchant {
    Optional<MerchantIdentity> findCurrent();
}
