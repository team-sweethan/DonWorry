package com.donworry.settlement.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class PickupOrderItemRequestTests {
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

    // 정상 상품 정보는 이름과 수량을 손실 없이 전달한다.
    @Test
    void validate_whenValidItem_acceptsNameAndQuantity() {
        var request = new PickupOrderItemRequest("딸기 케이크", 2);

        assertThat(validator.validate(request)).isEmpty();
        assertThat(request.productName()).isEqualTo("딸기 케이크");
        assertThat(request.quantity()).isEqualTo(2);
    }

    // 상품을 구분할 이름이 없으면 화면에 정상적인 주문 내역을 표시할 수 없다.
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   "})
    void validate_whenNameMissing_rejectsProductName(String name) {
        var request = new PickupOrderItemRequest(name, 1);

        assertThat(validator.validate(request))
                .anySatisfy(violation ->
                        assertThat(violation.getPropertyPath().toString()).isEqualTo("productName"));
    }

    // CakeShop 주문 상품명의 저장 한계와 동일한 경계를 적용한다.
    @Test
    void validate_whenNameAtLengthBoundary_accepts150AndRejects151() {
        assertThat(validator.validate(new PickupOrderItemRequest("가".repeat(150), 1)))
                .isEmpty();
        assertThat(validator.validate(new PickupOrderItemRequest("가".repeat(151), 1)))
                .anySatisfy(violation ->
                        assertThat(violation.getPropertyPath().toString()).isEqualTo("productName"));
    }

    // 누락된 수량과 0 이하 수량은 실제 주문 상품 수량으로 사용할 수 없다.
    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, -1})
    void validate_whenQuantityMissingOrNonPositive_rejectsQuantity(Integer quantity) {
        var request = new PickupOrderItemRequest("초코 케이크", quantity);

        assertThat(validator.validate(request))
                .anySatisfy(violation ->
                        assertThat(violation.getPropertyPath().toString()).isEqualTo("quantity"));
    }
}
