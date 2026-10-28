package com.donworry.identity.security;

import com.donworry.payment.controller.PaymentFdsReevaluationController;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;

// 전역 CSRF 활성 환경에서도 동일한 재평가 HTTP 계약을 검사한다.
@WebMvcTest(controllers = PaymentFdsReevaluationController.class, properties = "security.csrf.enabled=true")
class PaymentFdsReevaluationCsrfEnabledTests extends PaymentFdsReevaluationHttpTests {}
