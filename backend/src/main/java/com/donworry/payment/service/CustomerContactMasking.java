package com.donworry.payment.service;

// 주문자 정보 가리기 규칙 (#619) — 이름은 첫 · 끝 글자만, 전화는 앞자리 · 끝 4자리만 남긴다
final class CustomerContactMasking {
    private CustomerContactMasking() {}

    // 홍길동 → 홍*동, 남궁민수 → 남**수, 이훈 → 이*, 김 → *
    static String name(String name) {
        if (name == null) return null;
        int[] codePoints = name.codePoints().toArray();
        if (codePoints.length <= 1) return "*";
        String first = new String(codePoints, 0, 1);
        if (codePoints.length == 2) return first + "*";
        return first + "*".repeat(codePoints.length - 2) + new String(codePoints, codePoints.length - 1, 1);
    }

    // 숫자만 저장된 번호를 받는다. 01012345678 → 010-****-5678, 0212345678 → 02-****-5678
    static String phone(String digits) {
        if (digits == null) return null;
        return areaCode(digits) + "-****-" + digits.substring(digits.length() - 4);
    }

    // 원문을 읽기 쉬운 모양으로 — 01012345678 → 010-1234-5678, 0212345678 → 02-1234-5678
    static String formatPhone(String digits) {
        if (digits == null) return null;
        String head = areaCode(digits);
        return head + "-" + digits.substring(head.length(), digits.length() - 4) + "-"
                + digits.substring(digits.length() - 4);
    }

    private static String areaCode(String digits) {
        return digits.startsWith("02") ? "02" : digits.substring(0, 3);
    }
}
