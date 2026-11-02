package com.donworry.identity.api;

import java.util.Optional;

// 결제 상태 웹훅(돈워리 → 가맹점 notifyUrl)에 붙일 서명 헤더를 만든다 (ADR-0064 3절)
// 가맹점 API 서명(ADR-0037)과 같은 가맹점 키를 쓰지만 접두어가 달라(DONWORRY-WEBHOOK-V1) 서로 재사용할 수 없다.
// 서명 원문: DONWORRY-WEBHOOK-V1\n{가맹점 코드}\n{timestamp}\n{eventId}\n{sha256(body) hex}
// 키는 identity 밖으로 나가지 않는다 — 다른 모듈은 이 인터페이스로 헤더 값만 받는다
public interface MerchantWebhookSigner {

    // empty: 그 가맹점에 지금 쓸 수 있는 키가 없다(연동 전 · 키 미발급 · 키 사용 불가). 호출자는 보내지 않고 나중에 다시 시도한다
    // 없는 가맹점 ID · 형식이 틀린 eventId · null 본문은 IllegalArgumentException
    // 정지(INACTIVE) 가맹점도 서명한다 — 이미 받은 결제의 상태 변화는 계속 알린다
    Optional<SignedWebhook> sign(Long merchantId, String eventId, byte[] body);

    // 그대로 X-Merchant-Code · X-Timestamp · X-Event-Id · X-Signature 헤더에 넣는다. timestamp는 epoch 초 10자리
    record SignedWebhook(String merchantCode, String timestamp, String eventId, String signature) {}
}
