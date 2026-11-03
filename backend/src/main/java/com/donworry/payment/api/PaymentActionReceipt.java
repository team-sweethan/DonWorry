package com.donworry.payment.api;

public record PaymentActionReceipt(String status, Long targetOperationId, String errorCode) {
    public PaymentActionReceipt {
        boolean valid = switch (status == null ? "" : status) {
            case "PENDING" -> targetOperationId == null && errorCode == null;
            case "ACCEPTED" -> targetOperationId != null && targetOperationId > 0 && errorCode == null;
            case "REJECTED" ->
                targetOperationId == null
                        && errorCode != null
                        && !errorCode.isBlank()
                        && errorCode.codePointCount(0, errorCode.length()) <= 100;
            default -> false;
        };
        if (!valid) throw new IllegalArgumentException("조치 응답 상태와 결과가 일치하지 않습니다");
    }

    public static PaymentActionReceipt pending() {
        return new PaymentActionReceipt("PENDING", null, null);
    }

    public static PaymentActionReceipt accepted(Long targetOperationId) {
        return new PaymentActionReceipt("ACCEPTED", targetOperationId, null);
    }

    public static PaymentActionReceipt rejected(String errorCode) {
        return new PaymentActionReceipt("REJECTED", null, errorCode);
    }
}
