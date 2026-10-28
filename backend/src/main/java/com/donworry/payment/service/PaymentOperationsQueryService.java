package com.donworry.payment.service;

import com.donworry.payment.dto.*;
import com.donworry.payment.repository.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
public class PaymentOperationsQueryService {
    private final PaymentOperationsRepository repository;
    private final PaymentFdsRecoveryRepository recoveries;

    public PaymentOperationsPageResponse findPage(PaymentOperationsQueryRequest query) {
        long total = repository.count(query);
        var rows = repository.findPage(query);
        // 읽은 시각을 기준으로 계산한다. DB 커밋 시각을 뜻하지 않는다.
        Instant observedAt = Instant.now();
        long pages = total / query.size() + (total % query.size() == 0 ? 0 : 1);
        return new PaymentOperationsPageResponse(
                rows.stream().map(row -> summary(row, observedAt)).toList(),
                query.page(),
                query.size(),
                total,
                pages,
                (long) query.page() + 1 < pages,
                observedAt);
    }

    public PaymentOperationDetailResponse findPayment(long paymentId) {
        PaymentOperationsQueryRequest.requirePositive(paymentId);
        var row = repository.findById(paymentId).orElseThrow(PaymentOperationsException::new);
        var p = row.payment();
        var r = row.request();
        var a = row.action();
        return new PaymentOperationDetailResponse(
                new PaymentOperationPaymentResponse(
                        p.getPaymentId(),
                        p.getMerchantId(),
                        p.getOrderRef(),
                        p.getAmount(),
                        p.getStatus().name(),
                        p.getCreatedAt(),
                        p.getStatusChangedAt(),
                        p.getAuthorizationRef() != null,
                        p.getDeclineCode()),
                r == null
                        ? new PaymentOperationRequestResponse("ABSENT", null, null, null, null)
                        : new PaymentOperationRequestResponse(
                                "PRESENT",
                                r.getRequestId(),
                                r.getAttemptNo(),
                                r.getIdempotencyKey(),
                                r.getProcessingStage().name()),
                r == null
                        ? new PaymentOperationEvaluationResponse("ABSENT", null, null, null, null, null, null)
                        : new PaymentOperationEvaluationResponse(
                                r.getEvaluationId() != null
                                                || r.getEvaluationResult() != null
                                                || r.getEvaluationRiskScore() != null
                                                || r.getEvaluationErrorCode() != null
                                                || r.getEvaluatedAt() != null
                                        ? "PRESENT"
                                        : "NOT_RECORDED",
                                r.getEvaluationRequestId(),
                                r.getEvaluationId(),
                                r.getEvaluationResult(),
                                r.getEvaluationRiskScore(),
                                r.getEvaluationErrorCode(),
                                r.getEvaluatedAt()),
                a == null
                        ? new PaymentOperationActionResponse(
                                "ABSENT", null, null, null, null, null, null, null, null, null)
                        : new PaymentOperationActionResponse(
                                "PRESENT",
                                a.getActionId(),
                                a.getDecisionId(),
                                a.getActionType().name(),
                                a.getIdempotencyKey(),
                                a.getStatus().name(),
                                a.getErrorCode(),
                                a.getRequestedAt(),
                                a.getCompletedAt(),
                                a.getCaptureId()),
                observation(row, Instant.now()),
                recoveries
                        .findByPaymentId(paymentId)
                        .map(recovery -> PaymentFdsRecoveryResponse.from(recovery, p, r))
                        .orElse(null));
    }

    private PaymentOperationSummaryResponse summary(PaymentOperationRow row, Instant observedAt) {
        var p = row.payment();
        var a = row.action();
        return new PaymentOperationSummaryResponse(
                p.getPaymentId(),
                p.getMerchantId(),
                p.getAmount(),
                p.getOrderRef(),
                p.getStatus().name(),
                p.getCreatedAt(),
                a == null ? null : a.getActionType().name(),
                a == null ? null : a.getStatus().name(),
                observation(row, observedAt));
    }

    static PaymentOperationObservationResponse observation(PaymentOperationRow row, Instant observedAt) {
        var p = row.payment();
        var category = row.category();
        var limitations = new ArrayList<String>();
        limitations.add("P3 인계·Case 및 P6 매입·정산 상태는 이 조회에서 확인하지 않습니다.");
        if (row.request() == null) limitations.add("P5 요청 행이 없어 평가 요청·결과를 확인할 수 없습니다.");
        else limitations.add("평가 정보는 P5 저장 스냅샷이며 P2의 최신 결과나 진행 여부가 아닙니다.");
        limitations.add("평가 호출·응답 시각은 신규 ERROR 복구 기록에만 보존하며 이 응답에는 포함하지 않습니다. 타임아웃 시각과 HTTP 재전송 횟수는 수집하지 않습니다.");
        Instant waitingSince = null;
        String basis = "UNKNOWN";
        String reason = switch (category) {
            case EVALUATION_PENDING -> "승인 참조가 없는 PENDING이며 요청 단계가 RECEIVED 또는 EVALUATED입니다.";
            case EVALUATION_ERROR -> "승인 참조가 없는 PENDING이며 NEEDS_REVIEW와 ERROR가 저장되어 있습니다.";
            case AUTO_CAPTURE_PENDING -> "승인 참조가 있는 PENDING이며 직원 조치가 없습니다.";
            case DECISION_PENDING -> "승인 참조가 있는 HELD이며 직원 조치가 없습니다.";
            case STAFF_CAPTURE_PENDING -> "HELD이며 직원 CAPTURE 조치가 PENDING입니다.";
            case VOID_FAILED -> "HELD이며 직원 VOID 조치가 VOID_DECLINED로 실패했습니다.";
            case FINANCIAL_COMPLETED -> "P5 금융 상태는 CAPTURED 또는 VOIDED이며 저장 조치와 모순이 없습니다.";
            case PAYMENT_TERMINATED -> "P5 상태는 DENIED, DECLINED 또는 EXPIRED이며 저장 정보와 모순이 없습니다.";
            case UNKNOWN -> "저장 상태의 조합이 일치하지 않거나 원인을 분류할 근거가 부족합니다.";
        };
        switch (category) {
            case DECISION_PENDING -> {
                waitingSince = p.getStatusChangedAt();
                if (waitingSince != null) basis = "HELD_STATUS_CHANGED_AT";
            }
            case STAFF_CAPTURE_PENDING -> {
                waitingSince = row.action().getRequestedAt();
                if (waitingSince != null) basis = "ACTION_REQUESTED_AT";
            }
            case FINANCIAL_COMPLETED, PAYMENT_TERMINATED, VOID_FAILED -> basis = "NOT_APPLICABLE";
            default -> {}
        }
        if (basis.equals("UNKNOWN")) limitations.add("정확한 대기 시작 시각을 확인할 수 없습니다. 최초 결제 시각으로 대신하지 않습니다.");
        Long elapsed = seconds(p.getCreatedAt(), observedAt);
        Long waiting = seconds(waitingSince, observedAt);
        if (elapsed == null || (waitingSince != null && waiting == null))
            limitations.add("저장 시각이 없거나 조회 시각보다 미래여서 해당 경과 시간을 계산하지 않습니다.");
        var reasons = new ArrayList<String>();
        reasons.add(reason);
        reasons.add("결제 상태=" + p.getStatus() + ", 요청 단계="
                + (row.request() == null ? "기록 없음" : row.request().getProcessingStage()) + ", 조치="
                + (row.action() == null
                        ? "기록 없음"
                        : row.action().getActionType() + "/" + row.action().getStatus()));
        return new PaymentOperationObservationResponse(
                category, reasons, limitations, elapsed, basis, waitingSince, waiting, observedAt);
    }

    private static Long seconds(Instant start, Instant end) {
        return start == null || start.isAfter(end)
                ? null
                : Duration.between(start, end).getSeconds();
    }
}
