package com.donworry.payment.service;

import com.donworry.payment.dto.PaymentCancellationResponse;
import com.donworry.payment.entity.*;
import com.donworry.payment.repository.*;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentCancellationResultService {
    private final PaymentCancellationRepository cancellations;
    private final PaymentCancellationRequestRepository requests;
    private final PaymentActionRepository actions;

    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentCancellationResponse result(PaymentCancellationRequest request, Payment payment) {
        require(request.getPaymentId().equals(payment.getPaymentId())
                && request.getMerchantId().equals(payment.getMerchantId())
                && payment.getAmount() > 0);
        return switch (request.getResolution()) {
            case DEFERRED ->
                response(
                        request,
                        payment,
                        "DEFERRED",
                        null,
                        null,
                        request.getUpdatedAt(),
                        null,
                        "PAYMENT_IN_PROGRESS",
                        "QUERY_PAYMENT_THEN_RESUBMIT");
            case REVIEW_REQUIRED ->
                response(
                        request,
                        payment,
                        "REVIEW_REQUIRED",
                        null,
                        null,
                        request.getUpdatedAt(),
                        null,
                        "PAYMENT_REVIEW_REQUIRED",
                        "CONTACT_SUPPORT");
            case REJECTED ->
                response(
                        request,
                        payment,
                        "REJECTED",
                        null,
                        null,
                        request.getUpdatedAt(),
                        null,
                        "PAYMENT_NOT_CANCELABLE",
                        "NONE");
            case LEGACY_VOID -> legacy(request, payment);
            case ACCEPTED, LINKED -> operation(request, payment);
        };
    }

    private PaymentCancellationResponse operation(PaymentCancellationRequest request, Payment payment) {
        var work = cancellations.findByPaymentId(payment.getPaymentId()).orElseThrow(() -> inconsistent());
        var source = requests.findById(work.getSourceRequestId()).orElseThrow(() -> inconsistent());
        boolean own = request.getResolution() == PaymentCancellationResolution.ACCEPTED;
        require(source.getResolution() == PaymentCancellationResolution.ACCEPTED
                && source.getPaymentId().equals(payment.getPaymentId())
                && source.getMerchantId().equals(payment.getMerchantId())
                && own == source.getRequestId().equals(request.getRequestId())
                && work.getAmount().equals(payment.getAmount())
                && !work.getAcceptedAt().isBefore(source.getUpdatedAt()));
        require(payment.getAuthorizationRef() != null
                && !payment.getAuthorizationRef().isBlank());
        if (work.getOperation() == PaymentCancellationOperation.VOID)
            require(payment.getStatus()
                    == (work.getStatus() == PaymentCancellationStatus.SUCCEEDED
                            ? PaymentStatus.VOIDED
                            : PaymentStatus.HELD));
        else require(payment.getStatus() == PaymentStatus.CAPTURED);
        String status = work.getStatus().name();
        String code = null;
        String next = "NONE";
        switch (work.getStatus()) {
            case PROCESSING, UNKNOWN -> {
                next = "QUERY_CANCELLATION";
                if (!own) code = "CANCELLATION_ALREADY_IN_PROGRESS";
            }
            case FAILED -> {
                next = "CONTACT_SUPPORT";
                code = own ? "CANCELLATION_DECLINED" : "CANCELLATION_PREVIOUSLY_FAILED";
            }
            case SUCCEEDED -> {
                if (!own) status = "ALREADY_CANCELED";
            }
        }
        return response(
                request,
                payment,
                status,
                work.getCancellationId(),
                work.getOperation().name(),
                later(request.getUpdatedAt(), work.getUpdatedAt()),
                work.getCompletedAt(),
                code,
                next);
    }

    private PaymentCancellationResponse legacy(PaymentCancellationRequest request, Payment payment) {
        require(payment.getStatus() == PaymentStatus.VOIDED
                && payment.getAuthorizationRef() != null
                && !payment.getAuthorizationRef().isBlank());
        require(cancellations.findByPaymentId(payment.getPaymentId()).isEmpty());
        var action = actions.findByPaymentId(payment.getPaymentId()).orElse(null);
        if (request.getExistingVoidActionId() != null)
            require(action != null && request.getExistingVoidActionId().equals(action.getActionId()));
        if (action != null)
            require(action.getActionType() == PaymentActionType.VOID
                    && action.getStatus() == PaymentActionStatus.SUCCEEDED
                    && action.getCompletedAt() != null);
        return response(
                request,
                payment,
                "ALREADY_CANCELED",
                null,
                "VOID",
                request.getUpdatedAt(),
                action == null ? null : action.getCompletedAt(),
                null,
                "NONE");
    }

    private PaymentCancellationResponse response(
            PaymentCancellationRequest request,
            Payment payment,
            String status,
            Long id,
            String operation,
            Instant updated,
            Instant completed,
            String code,
            String next) {
        return PaymentCancellationResponse.builder()
                .requestKey(request.getRequestKey())
                .paymentId(payment.getPaymentId())
                .requestStatus(status)
                .cancellationId(id)
                .operation(operation)
                .amount(payment.getAmount())
                .requestedAt(request.getCreatedAt())
                .updatedAt(updated)
                .completedAt(completed)
                .code(code)
                .nextAction(next)
                .build();
    }

    private Instant later(Instant first, Instant second) {
        return first.isAfter(second) ? first : second;
    }

    private void require(boolean valid) {
        if (!valid) throw inconsistent();
    }

    private IllegalStateException inconsistent() {
        return new IllegalStateException("취소 기록의 연결 근거가 일치하지 않습니다");
    }
}
