package com.donworry.identity.service;

import com.donworry.identity.dto.MerchantApplicationRequests.*;
import com.donworry.identity.dto.MerchantApplicationResponses.*;
import com.donworry.identity.entity.ApplicationMailStatus;
import com.donworry.identity.security.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class MerchantApplicationService {
    private final ApplicationSettings settings;
    private final AccountLockService accountLocks;
    private final ApplicationPersistenceService persistence;
    private final ApplicationRateLimitService limits;
    private final LoginHistoryRequestMetadata metadata;
    private final ApplicationMailDispatchService dispatch;

    public Submission submit(SubmitRequest input, HttpServletRequest request) {
        settings.requireEnabled();
        String ip = metadata.clientIp(request);
        if (ip == null) throw new ApplicationException(ApplicationErrorCode.UNAVAILABLE);
        limits.submission(ip);
        PreparedApplicationMail prepared;
        try {
            prepared = accountLocks.withAccount(input.requestedLoginId(), () -> persistence.submit(input));
        } catch (DataIntegrityViolationException exception) {
            String message = exception.getMostSpecificCause().getMessage();
            if (message != null && message.contains("uk_merchant_applications_pending_login"))
                throw new ApplicationException(ApplicationErrorCode.LOGIN_ID_UNAVAILABLE);
            if (message != null
                    && (message.contains("uk_merchant_applications_pending_email")
                            || message.contains("uk_merchant_applications_pending_business")))
                throw new ApplicationException(ApplicationErrorCode.DUPLICATE);
            throw exception;
        }
        return new Submission(deliver(prepared).name());
    }

    public Verification verify(VerifyRequest input) {
        settings.requireEnabled();
        persistence.verify(input.token());
        return new Verification("REVIEW_PENDING");
    }

    public void resend(ResendRequest input, HttpServletRequest request) {
        settings.requireEnabled();
        String ip = metadata.clientIp(request);
        if (ip == null) throw new ApplicationException(ApplicationErrorCode.UNAVAILABLE);
        if (!limits.resend(input.email(), ip)) return;
        persistence.resend(input.email()).ifPresent(this::deliver);
    }

    private ApplicationMailStatus deliver(PreparedApplicationMail prepared) {
        return dispatch.deliver(
                prepared.deliveryId(),
                () -> dispatch.sender()
                        .sendVerification(prepared.email(), settings.verificationLink(prepared.token())));
    }
}
