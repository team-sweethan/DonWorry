package com.donworry.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.donworry.identity.entity.Merchant;
import com.donworry.identity.entity.MerchantStatus;
import com.donworry.identity.repository.MerchantRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MerchantDirectoryServiceTests {

    @Mock
    private MerchantRepository merchants;

    // 경보의 과거 가맹점도 표시할 수 있도록 정지 가맹점까지 목록에 포함한다.
    @Test
    void findAll_returnsActiveAndInactiveMerchantsInIdOrder() {
        var active = Merchant.create("CAKESHOP", "CakeShop", BigDecimal.ZERO);
        var inactive = Merchant.create("DEMOSHOP", "DemoShop", BigDecimal.ZERO);
        inactive.changeStatus(MerchantStatus.INACTIVE);
        given(merchants.findAllByOrderByMerchantIdAsc()).willReturn(List.of(active, inactive));

        var result = new MerchantDirectoryService(merchants).findAll();

        assertThat(result).extracting(entry -> entry.merchantCode()).containsExactly("CAKESHOP", "DEMOSHOP");
        assertThat(result.get(0).displayName()).isEqualTo("CakeShop");
        assertThat(result.get(1).status()).isEqualTo("INACTIVE");
    }
}
