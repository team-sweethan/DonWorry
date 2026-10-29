package com.donworry.identity.security;

import java.time.LocalDateTime;
import lombok.Getter;

@Getter
public class ApplicationResendLimitException extends ApplicationException {
    private final LocalDateTime retryAt;

    public ApplicationResendLimitException(LocalDateTime retryAt) {
        super(ApplicationErrorCode.RESEND_LIMITED);
        this.retryAt = retryAt;
    }
}
