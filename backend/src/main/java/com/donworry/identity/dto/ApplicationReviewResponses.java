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
            LocalDateTime emailVerifiedAt) {
        public static Summary from(MerchantApplication a) {
            return Summary.builder()
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
            List<AdminMerchantResponse> duplicateMerchants) {}

    @Builder
    public record ReviewResult(String status, Long merchantId, Long userId, String mailStatus) {}
}
