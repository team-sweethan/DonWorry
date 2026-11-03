package com.donworry.payment.repository;

import com.donworry.payment.dto.PaymentOperationCategory;
import com.donworry.payment.entity.*;
import lombok.Builder;

@Builder
public record PaymentOperationRow(
        Payment payment, PaymentRequest request, PaymentAction action, PaymentOperationCategory category) {}
