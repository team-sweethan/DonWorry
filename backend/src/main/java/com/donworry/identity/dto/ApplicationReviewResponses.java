package com.donworry.identity.dto;

import com.donworry.identity.entity.MerchantApplication;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;

public final class ApplicationReviewResponses {
    private ApplicationReviewResponses() {}

    @Builder
    public record Summary(
            Long applicationId,
            String merchantName,
            String businessRegistrationNumber,
            String contactName,
            String status,
            LocalDateTime createdAt,
            LocalDateTime emailVerifiedAt,
            ApplicationMailResponses.MailItem latestMail) {
        public static Summary from(MerchantApplication a) {
            return from(a, null);
        }

        public static Summary from(MerchantApplication a, ApplicationMailResponses.MailItem mail) {
            return Summary.builder()
                    .latestMail(mail)
                    .applicationId(a.getApplicationId())
                    .merchantName(a.getMerchantName())
                    .businessRegistrationNumber(a.getBusinessRegistrationNumber())
                    .contactName(a.getContactName())
                    .status(a.getStatus().name())
                    .createdAt(a.getCreatedAt())
                    .emailVerifiedAt(a.getEmailVerifiedAt())
                    .build();
        }
    }

    @Builder
    public record ApplicationPage(List<Summary> items, int page, int size, long totalElements) {}

    @Builder
    public record Detail(
            Summary application,
            String requestedLoginId,
            String email,
            String phone,
            String inquiry,
            String consentVersion,
            LocalDateTime consentedAt,
            LocalDateTime reviewedAt,
            Long reviewerUserId,
            String reviewerName,
            String rejectionReason,
            Long merchantId,
            Long userId,
            String loginId,
            Boolean passwordChangeRequired,
            List<AdminMerchantResponse> duplicateMerchants,
            ApplicationMailResponses.MailItem latestMail,
            boolean resendAllowed,
            String resendBlockedReason,
            LocalDateTime nextResendAt) {}

    @Builder
    public record ReviewResult(String status, Long merchantId, Long userId, String mailStatus) {}
}
