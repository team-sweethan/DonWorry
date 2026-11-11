package com.donworry.payment.api;

// 가린 주문자 정보 (#619). 주문자 칸이 비어 있으면 그 칸은 null, pickupDiffers가 null이면 픽업자 정보 없음
public record MaskedContact(String ordererName, String ordererPhone, Boolean pickupDiffers) {}
