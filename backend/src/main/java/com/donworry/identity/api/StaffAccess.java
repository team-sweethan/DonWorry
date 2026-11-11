package com.donworry.identity.api;

import java.util.Objects;
import java.util.regex.Pattern;
import lombok.Builder;

// 접근 한 건의 대상. 누가 · 언제 · 어느 로그인인지는 기록하는 쪽이 세션 · 시계로 정한다 (호출 쪽이 바꿀 수 없음)
// customerRef는 가맹점 회원 번호(숫자, 저장 형식 "가맹점ID:번호" 포함)만 받는다. 검색어를 그대로 넘기면 이름 · 전화가 기록에 남는다
@Builder
public record StaffAccess(
        StaffAccessType accessType,
        Long caseId,
        Long merchantId,
        Long paymentId,
        String customerRef,
        StaffAccessFieldType fieldType,
        Integer itemCount) {
    private static final Pattern CUSTOMER_REF = Pattern.compile("([1-9][0-9]{0,18}:)?[1-9][0-9]{0,18}");

    public StaffAccess {
        Objects.requireNonNull(accessType, "accessType");
        if (customerRef != null && !isCustomerRef(customerRef))
            throw new IllegalArgumentException("customerRef는 숫자 고객 번호만 기록한다");
        if (itemCount != null && itemCount < 0) throw new IllegalArgumentException("itemCount");
    }

    // 검색어를 기록해도 되는지 호출 쪽이 미리 확인한다 (#615). 숫자 고객 번호가 아니면 고객 번호 없이 기록한다
    public static boolean isCustomerRef(String value) {
        return value != null && CUSTOMER_REF.matcher(value).matches();
    }
}
