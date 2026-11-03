package com.donworry.identity.security;

import com.donworry.payment.controller.PaymentFdsDeliveryController;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;

// 전역 CSRF 활성 환경에서도 동일한 전달 복구 HTTP 계약을 검사한다.
@WebMvcTest(controllers = PaymentFdsDeliveryController.class, properties = "security.csrf.enabled=true")
class PaymentFdsDeliveryCsrfEnabledTests extends PaymentFdsDeliveryHttpTests {}
