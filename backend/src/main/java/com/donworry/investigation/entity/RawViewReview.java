package com.donworry.investigation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 주문자 전화 원문 열람 1건 = ADMIN 사후 검토 1행 (#619, ADR-0082). 원문은 담지 않는다
// 근거 3칸은 직원이 적은 업무 메모 — 화면에서 고객 이름 · 번호를 쓰지 않도록 안내한다
@Entity
@Table(name = "raw_view_reviews")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RawViewReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "review_id")
    private Long id;

    private Long caseId;

    // P5 payments 논리 참조
    private Long paymentId;

    // P1 platform_users 논리 참조
    private Long staffId;

    private String disputeRef;

    private String merchantContactAttempt;

    private String reason;

    private Instant viewedAt;

    @Enumerated(EnumType.STRING)
    private RawViewReviewStatus status;

    private Long reviewedByAdminId;

    // 처리 시점의 ADMIN 표시 이름 — SecurityStaffDirectory에 ADMIN이 없어 함께 남긴다
    private String reviewedByAdminName;

    private Instant reviewedAt;

    private String reviewMemo;

    @Builder(access = AccessLevel.PRIVATE)
    private RawViewReview(
            Long caseId,
            Long paymentId,
            Long staffId,
            String disputeRef,
            String merchantContactAttempt,
            String reason,
            Instant viewedAt) {
        this.caseId = Objects.requireNonNull(caseId, "caseId");
        this.paymentId = Objects.requireNonNull(paymentId, "paymentId");
        this.staffId = Objects.requireNonNull(staffId, "staffId");
        this.disputeRef = Objects.requireNonNull(disputeRef, "disputeRef");
        this.merchantContactAttempt = Objects.requireNonNull(merchantContactAttempt, "merchantContactAttempt");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.viewedAt = Objects.requireNonNull(viewedAt, "viewedAt").truncatedTo(ChronoUnit.MICROS);
        this.status = RawViewReviewStatus.PENDING;
    }

    public static RawViewReview record(
            Long caseId,
            Long paymentId,
            Long staffId,
            String disputeRef,
            String merchantContactAttempt,
            String reason,
            Instant now) {
        return RawViewReview.builder()
                .caseId(caseId)
                .paymentId(paymentId)
                .staffId(staffId)
                .disputeRef(disputeRef)
                .merchantContactAttempt(merchantContactAttempt)
                .reason(reason)
                .viewedAt(now)
                .build();
    }

    public void review(RawViewReviewStatus result, Long adminId, String adminName, String memo, Instant now) {
        if (status != RawViewReviewStatus.PENDING) {
            throw new IllegalStateException("이미 검토한 원문 열람입니다");
        }
        if (result == null || result == RawViewReviewStatus.PENDING) {
            throw new IllegalArgumentException("검토 결과가 아닙니다");
        }
        this.status = result;
        this.reviewedByAdminId = Objects.requireNonNull(adminId, "adminId");
        this.reviewedByAdminName = adminName;
        this.reviewMemo = memo;
        this.reviewedAt = Objects.requireNonNull(now, "now").truncatedTo(ChronoUnit.MICROS);
    }

    public boolean isPending() {
        return status == RawViewReviewStatus.PENDING;
    }
}
