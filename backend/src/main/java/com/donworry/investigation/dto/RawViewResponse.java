package com.donworry.investigation.dto;

// 주문자 전화 원문 — 화면에 한 번 보여 주고 저장하지 않는다 (응답 Cache-Control: no-store, #619)
public record RawViewResponse(String ordererPhone) {
    @Override
    public String toString() {
        return "RawViewResponse[생략]";
    }
}
