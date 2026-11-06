package com.donworry.investigation.dto;

import lombok.Builder;

// 사건 결제 1건의 주문자 정보 — 가린 값만 (#619). available=false면 이 결제에 저장된 주문자 정보가 없다
// pickupDiffers: true = 픽업자가 주문자와 다름, false = 같음, null = 픽업자 정보 없음
@Builder
public record CaseContactResponse(
        Long paymentId, boolean available, String ordererName, String ordererPhone, Boolean pickupDiffers) {}
