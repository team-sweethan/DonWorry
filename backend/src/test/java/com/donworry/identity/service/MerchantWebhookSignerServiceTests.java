package com.donworry.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.donworry.identity.api.MerchantWebhookSigner.SignedWebhook;
import com.donworry.identity.dto.MerchantKeyMaterial;
import com.donworry.identity.dto.MerchantSignedRequest;
import com.donworry.identity.entity.Merchant;
import com.donworry.identity.repository.MerchantRepository;
import com.donworry.identity.security.AuthErrorCode;
import com.donworry.identity.security.MerchantAuthenticationException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import org.junit.jupiter.api.Test;

// 결제 상태 웹훅 서명 (ADR-0064 3절)
class MerchantWebhookSignerServiceTests {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BODY =
            "{\"eventId\":\"evt_101_VOIDED_N\",\"paymentStatus\":\"VOIDED\"}".getBytes(StandardCharsets.UTF_8);
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(1_793_077_200L), ZoneOffset.UTC);

    private final MerchantRepository merchants = mock(MerchantRepository.class);
    private final MerchantSecretService secrets = mock(MerchantSecretService.class);
    private final MerchantWebhookSignerService signer = new MerchantWebhookSignerService(merchants, secrets, CLOCK);

    // ADR-0064 3절 서명 원문을 독립 구현(Node crypto)으로 계산한 고정 값과 바이트 단위로 같다
    @Test
    void sign_whenKeyAvailable_matchesIndependentVector() {
        givenMerchant(7L, "CAKESHOP");
        when(secrets.resolve("CAKESHOP")).thenReturn(new MerchantKeyMaterial(KEY, 3L));

        Optional<SignedWebhook> signed = signer.sign(7L, "evt_101_VOIDED_N", BODY);

        assertThat(signed)
                .contains(new SignedWebhook(
                        "CAKESHOP",
                        "1793077200",
                        "evt_101_VOIDED_N",
                        "4cbf577a6437322c2bd87a129769e558adc5d1efe98817917a2309b7600f837d"));
    }

    // 같은 키라도 API 요청 서명과 접두어 · 원문이 달라 웹훅 서명을 API 요청 서명으로 쓸 수 없다
    @Test
    void signature_differsFromApiRequestSignature() {
        String webhook =
                MerchantWebhookSignerService.signature(KEY, "CAKESHOP", "1793077200", "evt_101_VOIDED_N", BODY);
        String api = HexFormat.of()
                .formatHex(MerchantHmacSigner.sign(
                        KEY,
                        new MerchantSignedRequest(
                                "CAKESHOP",
                                "1793077200",
                                "evt_101_VOIDED_N",
                                "",
                                "POST",
                                "/",
                                "application/json",
                                "",
                                BODY)));

        assertThat(webhook).hasSize(64).isNotEqualTo(api);
    }

    // 본문 · eventId · 시각 · 가맹점 코드를 하나만 바꿔도 서명이 달라진다
    @Test
    void signature_changesWhenAnyPartChanges() {
        String base = MerchantWebhookSignerService.signature(KEY, "CAKESHOP", "1793077200", "evt_101_VOIDED_N", BODY);

        assertThat(MerchantWebhookSignerService.signature(
                        KEY, "CAKESHOP", "1793077200", "evt_101_VOIDED_N", "{}".getBytes(StandardCharsets.UTF_8)))
                .isNotEqualTo(base);
        assertThat(MerchantWebhookSignerService.signature(KEY, "CAKESHOP", "1793077200", "evt_101_HELD_A", BODY))
                .isNotEqualTo(base);
        assertThat(MerchantWebhookSignerService.signature(KEY, "CAKESHOP", "1793077201", "evt_101_VOIDED_N", BODY))
                .isNotEqualTo(base);
        assertThat(MerchantWebhookSignerService.signature(KEY, "OTHERSHOP", "1793077200", "evt_101_VOIDED_N", BODY))
                .isNotEqualTo(base);
    }

    // 쓸 수 있는 키가 없으면(연동 전 · 키 미발급 · 사용 불가) 서명하지 않고 empty — 호출자가 나중에 다시 보낸다
    @Test
    void sign_whenNoUsableKey_isEmpty() {
        givenMerchant(7L, "CAKESHOP");
        when(secrets.resolve("CAKESHOP"))
                .thenThrow(new MerchantAuthenticationException(AuthErrorCode.MERCHANT_AUTHENTICATION_REQUIRED))
                .thenThrow(new MerchantAuthenticationException(AuthErrorCode.MERCHANT_AUTHENTICATION_UNAVAILABLE));

        assertThat(signer.sign(7L, "evt_101_VOIDED_N", BODY)).isEmpty();
        assertThat(signer.sign(7L, "evt_101_VOIDED_N", BODY)).isEmpty();
    }

    // 없는 가맹점 · 형식이 틀린 eventId(줄바꿈 등) · null 본문은 잘못된 호출이다 — 키를 꺼내지 않는다
    @Test
    void sign_whenInvalidInput_isIllegalArgument() {
        when(merchants.findById(99L)).thenReturn(Optional.empty());

        assertThatIllegalArgumentException().isThrownBy(() -> signer.sign(99L, "evt_1_HELD_A", BODY));
        assertThatIllegalArgumentException().isThrownBy(() -> signer.sign(7L, "evt_1\nX-Injected: 1", BODY));
        assertThatIllegalArgumentException().isThrownBy(() -> signer.sign(7L, "", BODY));
        assertThatIllegalArgumentException().isThrownBy(() -> signer.sign(7L, "evt_1_HELD_A", null));
        assertThatIllegalArgumentException().isThrownBy(() -> signer.sign(null, "evt_1_HELD_A", BODY));
        verify(secrets, never()).resolve(org.mockito.ArgumentMatchers.anyString());
    }

    private void givenMerchant(Long merchantId, String code) {
        Merchant merchant = mock(Merchant.class);
        when(merchant.getMerchantCode()).thenReturn(code);
        when(merchants.findById(merchantId)).thenReturn(Optional.of(merchant));
    }
}
