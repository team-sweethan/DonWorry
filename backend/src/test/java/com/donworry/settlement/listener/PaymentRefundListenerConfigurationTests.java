package com.donworry.settlement.listener;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.settlement.service.RefundAdjustmentService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PaymentRefundListenerConfigurationTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(RefundAdjustmentService.class, () -> mock(RefundAdjustmentService.class))
            .withUserConfiguration(PaymentRefundSucceededListener.class);

    @Test
    void listener_whenNotEnabled_remainsUnregistered() {
        runner.run(c -> assertThat(c).doesNotHaveBean(PaymentRefundSucceededListener.class));
        runner.withPropertyValues("settlement.refund-receiving.enabled=false")
                .run(c -> assertThat(c).doesNotHaveBean(PaymentRefundSucceededListener.class));
    }

    @Test
    void listener_whenExplicitlyEnabled_registers() {
        runner.withPropertyValues("settlement.refund-receiving.enabled=true")
                .run(c -> assertThat(c).hasSingleBean(PaymentRefundSucceededListener.class));
    }
}
