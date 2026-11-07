package com.donworry.identity.api;

import lombok.Getter;

// 보안 정지 창구 오류 — 사장님 정지(OperatorAccountSuspensionException)와 같은 모양
@Getter
public class StaffAccountLockException extends RuntimeException {
    public enum Reason {
        USER_NOT_FOUND,
        NOT_SECURITY_STAFF,
        NOT_ADMIN
    }

    private final Reason reason;

    public StaffAccountLockException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }
}
