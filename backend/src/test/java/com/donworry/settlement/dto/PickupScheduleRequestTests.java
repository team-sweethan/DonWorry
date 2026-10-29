package com.donworry.settlement.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PickupScheduleRequestTests {
    private static final Instant PICKUP_AT = Instant.parse("2026-11-01T06:00:00Z");
    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    // 여러 상품을 한 번의 픽업 예정 요청으로 전달할 수 있다.
    @Test
    void validate_whenMultipleItems_acceptsScheduleAndAllItems() {
        var items = List.of(new PickupOrderItemRequest("딸기 케이크", 2), new PickupOrderItemRequest("초코 케이크", 1));
        var request = new PickupScheduleRequest(PICKUP_AT, items);

        assertThat(validator.validate(request)).isEmpty();
        assertThat(request.scheduledPickupAt()).isEqualTo(PICKUP_AT);
        assertThat(request.items()).containsExactlyElementsOf(items);
    }

    // 상품이 있어도 픽업 예정 시각이 없으면 정산 일정을 계산할 수 없다.
    @Test
    void validate_whenPickupTimeMissing_rejectsScheduledPickupAt() {
        var request = new PickupScheduleRequest(null, List.of(new PickupOrderItemRequest("케이크", 1)));

        assertThat(validator.validate(request))
                .anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("scheduledPickupAt"));
    }

    // 주문 상품 목록은 누락되거나 비어 있을 수 없다.
    @ParameterizedTest
    @NullAndEmptySource
    void validate_whenItemsMissingOrEmpty_rejectsItems(List<PickupOrderItemRequest> items) {
        assertThat(validator.validate(new PickupScheduleRequest(PICKUP_AT, items)))
                .anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("items"));
    }

    // 목록 안의 null 항목도 정상 상품으로 받아들이지 않는다.
    @Test
    void validate_whenItemNull_rejectsNullElement() {
        var request = new PickupScheduleRequest(PICKUP_AT, Collections.singletonList(null));

        assertThat(validator.validate(request))
                .anySatisfy(v -> assertThat(v.getPropertyPath().toString()).startsWith("items[0]"));
    }

    // 중첩 DTO의 검증이 요청 전체에서도 적용되어야 한다.
    @Test
    void validate_whenItemNameBlank_rejectsNestedProductName() {
        var request = new PickupScheduleRequest(PICKUP_AT, List.of(new PickupOrderItemRequest(" ", 1)));

        assertThat(validator.validate(request))
                .anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("items[0].productName"));
    }

    // 상품 수량 오류를 중첩 검증에서 놓치지 않는다.
    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void validate_whenItemQuantityNonPositive_rejectsNestedQuantity(int quantity) {
        var request = new PickupScheduleRequest(PICKUP_AT, List.of(new PickupOrderItemRequest("케이크", quantity)));

        assertThat(validator.validate(request))
                .anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("items[0].quantity"));
    }
}
