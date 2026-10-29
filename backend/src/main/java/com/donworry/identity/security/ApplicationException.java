package com.donworry.identity.security;

import lombok.*;

@Getter
@RequiredArgsConstructor
public class ApplicationException extends RuntimeException {
    private final ApplicationErrorCode errorCode;
}
