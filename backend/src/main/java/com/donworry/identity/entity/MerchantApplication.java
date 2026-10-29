package com.donworry.identity.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "merchant_applications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MerchantApplication {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "application_id")
    private Long applicationId;

    @Column(name = "merchant_name", nullable = false, length = 100)
    private String merchantName;

    @Column(name = "business_registration_number", nullable = false, length = 10)
    private String businessRegistrationNumber;

    @Column(name = "contact_name", nullable = false, length = 100)
    private String contactName;

    @Column(name = "email", nullable = false, length = 254)
    private String email;

    @Column(name = "phone", nullable = false, length = 15)
    private String phone;

    @Column(name = "inquiry", length = 1000)
    private String inquiry;

    @Column(name = "consent_version", nullable = false, length = 20)
    private String consentVersion;

    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    @Column(name = "consented_at", nullable = false)
    private LocalDateTime consentedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ApplicationStatus status;

    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    @Column(name = "email_verified_at")
    private LocalDateTime emailVerifiedAt;

    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "reviewer_user_id")
    private Long reviewerUserId;

    @Column(name = "rejection_reason", length = 1000)
    private String rejectionReason;

    @Column(name = "merchant_id")
    private Long merchantId;

    @Column(name = "user_id")
    private Long userId;

    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Builder(access = AccessLevel.PRIVATE)
    private MerchantApplication(
            String merchantName,
            String businessRegistrationNumber,
            String contactName,
            String email,
            String phone,
            String inquiry,
            String consentVersion,
            LocalDateTime consentedAt,
            ApplicationStatus status,
            LocalDateTime emailVerifiedAt,
            LocalDateTime reviewedAt,
            Long reviewerUserId,
            String rejectionReason,
            Long merchantId,
            Long userId,
            LocalDateTime createdAt) {
        this.merchantName = merchantName;
        this.businessRegistrationNumber = businessRegistrationNumber;
        this.contactName = contactName;
        this.email = email;
        this.phone = phone;
        this.inquiry = inquiry;
        this.consentVersion = consentVersion;
        this.consentedAt = consentedAt;
        this.status = status;
        this.emailVerifiedAt = emailVerifiedAt;
        this.reviewedAt = reviewedAt;
        this.reviewerUserId = reviewerUserId;
        this.rejectionReason = rejectionReason;
        this.merchantId = merchantId;
        this.userId = userId;
        this.createdAt = createdAt;
    }

    public static MerchantApplication submit(
            String merchantName,
            String businessRegistrationNumber,
            String contactName,
            String email,
            String phone,
            String inquiry,
            String consentVersion,
            LocalDateTime now) {
        return MerchantApplication.builder()
                .merchantName(merchantName)
                .businessRegistrationNumber(businessRegistrationNumber)
                .contactName(contactName)
                .email(email)
                .phone(phone)
                .inquiry(inquiry)
                .consentVersion(consentVersion)
                .consentedAt(now)
                .createdAt(now)
                .status(ApplicationStatus.EMAIL_PENDING)
                .build();
    }

    public void verifyEmail(LocalDateTime now) {
        this.status = ApplicationStatus.REVIEW_PENDING;
        this.emailVerifiedAt = now;
    }
}
