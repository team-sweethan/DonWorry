package com.donworry.identity.service;

import com.donworry.identity.entity.ApplicationMailStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ApplicationMailDispatchService {
    private final ApplicationMailSender mail;
    private final ApplicationPersistenceService persistence;

    public ApplicationMailSender sender() {
        return mail;
    }

    public ApplicationMailStatus deliver(Long deliveryId, Runnable send) {
        ApplicationMailStatus status = ApplicationMailStatus.SENT;
        String failure = null;
        try {
            send.run();
        } catch (org.springframework.mail.MailPreparationException
                | org.springframework.mail.MailAuthenticationException exception) {
            status = ApplicationMailStatus.FAILED;
            failure = "SMTP_REJECTED";
        } catch (RuntimeException exception) {
            status = ApplicationMailStatus.UNKNOWN;
            failure = "SMTP_OUTCOME_UNKNOWN";
        }
        try {
            persistence.complete(deliveryId, status, failure);
        } catch (RuntimeException exception) {
            log.warn("신청 메일 결과 저장 실패 deliveryId={}", deliveryId);
            return ApplicationMailStatus.UNKNOWN;
        }
        return status;
    }
}
