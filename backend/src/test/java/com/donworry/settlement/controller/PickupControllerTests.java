package com.donworry.settlement.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.donworry.identity.api.CurrentMerchant;
import com.donworry.settlement.dto.PickupOrderItemRequest;
import com.donworry.settlement.dto.PickupResponse;
import com.donworry.settlement.service.OrderFulfillmentService;
import com.donworry.settlement.service.PickupAccessService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PickupControllerTests {
    // 설정을 켜기 전에는 픽업 저장 API를 노출하지 않는다.
    @Test
    void context_whenNotEnabled_doesNotExposePickupController() {
        new ApplicationContextRunner()
                .withUserConfiguration(PickupController.class)
                .run(context -> assertThat(context).doesNotHaveBean(PickupController.class));
    }

    // 인증 연결이 없으면 잘못된 본문도 저장 서비스로 보내지 않는다.
    @Test
    void pickup_whenAuthenticationMissing_rejectsBeforeService() throws Exception {
        var access = new PickupAccessService(new StaticListableBeanFactory().getBeanProvider(CurrentMerchant.class));
        var service = mock(OrderFulfillmentService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new PickupController(access, service))
                .setControllerAdvice(new PickupExceptionHandler())
                .build();
        mvc.perform(put("/api/v1/order-fulfillments/ORDER/schedule")
                        .contentType("application/json")
                        .content("invalid-json"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SETTLEMENT_AUTH_NOT_READY"));
        verifyNoInteractions(service);
    }

    // 상품 전체를 전달하면서 본문의 임의 가맹점 ID는 사용하지 않는다.
    @Test
    void schedule_whenValid_usesAuthenticatedMerchantAndAllItems() throws Exception {
        var access = mock(PickupAccessService.class);
        var service = mock(OrderFulfillmentService.class);
        when(access.requireMerchant()).thenReturn(7L);
        when(service.recordSchedule(eq(7L), eq("ORDER"), any(), anyList()))
                .thenReturn(new PickupResponse("ORDER", "STORED", "PENDING_CAPTURE"));
        var mvc = MockMvcBuilders.standaloneSetup(new PickupController(access, service))
                .setControllerAdvice(new PickupExceptionHandler())
                .build();
        mvc.perform(put("/api/v1/order-fulfillments/ORDER/schedule")
                        .contentType("application/json")
                        .content("""
                                {"scheduledPickupAt":"2026-11-01T06:00:00Z","merchantId":999,
                                 "items":[{"productName":"딸기 케이크","quantity":2},{"productName":"초코 케이크","quantity":1}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.linkStatus").value("PENDING_CAPTURE"));
        verify(service)
                .recordSchedule(
                        7L,
                        "ORDER",
                        Instant.parse("2026-11-01T06:00:00Z"),
                        List.of(new PickupOrderItemRequest("딸기 케이크", 2), new PickupOrderItemRequest("초코 케이크", 1)));
    }

    // 잘못된 상품 본문은 서비스에 진입하기 전에 검증한다.
    @Test
    void schedule_whenItemsInvalid_returnsBadRequest() throws Exception {
        var access = mock(PickupAccessService.class);
        when(access.requireMerchant()).thenReturn(7L);
        var service = mock(OrderFulfillmentService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new PickupController(access, service))
                .setControllerAdvice(new PickupExceptionHandler())
                .build();
        for (String items : List.of(
                "", ",\"items\":[]", ",\"items\":[null]", ",\"items\":[{\"productName\":\"케이크\",\"quantity\":0}]")) {
            mvc.perform(put("/api/v1/order-fulfillments/ORDER/schedule")
                            .contentType("application/json")
                            .content("{\"scheduledPickupAt\":\"2026-11-01T06:00:00Z\"" + items + "}"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    // 완료 API는 상품 목록 없이 실제 시각만 전달한다.
    @Test
    void completion_whenValid_delegatesActualTimeOnly() throws Exception {
        var access = mock(PickupAccessService.class);
        when(access.requireMerchant()).thenReturn(7L);
        var service = mock(OrderFulfillmentService.class);
        when(service.recordCompletion(any(), any(), any())).thenReturn(new PickupResponse("ORDER", "STORED", "LINKED"));
        var mvc = MockMvcBuilders.standaloneSetup(new PickupController(access, service))
                .setControllerAdvice(new PickupExceptionHandler())
                .build();
        mvc.perform(put("/api/v1/order-fulfillments/ORDER/completion")
                        .contentType("application/json")
                        .content("{\"pickedUpAt\":\"2026-11-01T06:15:00Z\"}"))
                .andExpect(status().isOk());
        verify(service).recordCompletion(7L, "ORDER", Instant.parse("2026-11-01T06:15:00Z"));
    }
}
