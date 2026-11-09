package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.donworry.identity.api.MerchantDirectoryEntry;
import com.donworry.identity.api.MerchantDirectoryReader;
import com.donworry.identity.api.StaffAccess;
import com.donworry.identity.api.StaffAccessRecorder;
import com.donworry.identity.api.StaffAccessType;
import com.donworry.payment.dto.PaymentTransactionResponse;
import com.donworry.payment.entity.Payment;
import com.donworry.payment.repository.PaymentTransactionRow;
import com.donworry.payment.repository.PaymentTransactionsRepository;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

// 거래 상세 1건 (#732) — 승인된 테스트 목록만 (노트 issue-732 "승인된 테스트 목록")
// 기록 실패 시 상세를 내보내지 않는 것은 PaymentTransactionsRecordFailureTests가 실제 트랜잭션으로 확인한다
class PaymentTransactionsDetailTests {
    private static final Long MERCHANT = 11L;
    private static final Long PAYMENT = 501L;
    private static final String CUSTOMER = "11:5001";

    private final PaymentTransactionsRepository repository = mock(PaymentTransactionsRepository.class);
    private final StaffAccessRecorder recorder = mock(StaffAccessRecorder.class);
    private final MerchantDirectoryReader merchants = mock(MerchantDirectoryReader.class);
    private final PaymentTransactionsQueryService service =
            new PaymentTransactionsQueryService(repository, recorder, merchants);

    @BeforeEach
    void setUp() {
        when(merchants.findAll()).thenReturn(List.of(new MerchantDirectoryEntry(MERCHANT, "m-11", "케이크 가게", "ACTIVE")));
    }

    // 상세를 열어도 기록이 안 남으면 담당 밖 고객 열람을 INS-09가 셀 수 없다
    @Test
    void detail_recordsDetailViewWithPaymentMerchantAndCustomer() {
        var payment = Payment.pending(MERCHANT, CUSTOMER, "order-1", 1000L, Instant.parse("2026-11-09T01:00:00Z"));
        ReflectionTestUtils.setField(payment, "paymentId", PAYMENT);
        when(repository.findById(PAYMENT)).thenReturn(Optional.of(new PaymentTransactionRow(payment, null)));

        var detail = service.findDetail(PAYMENT);

        assertThat(detail.paymentId()).isEqualTo(PAYMENT);
        var access = ArgumentCaptor.forClass(StaffAccess.class);
        verify(recorder).recordAllowed(access.capture());
        assertThat(access.getValue().accessType()).isEqualTo(StaffAccessType.TRANSACTION_DETAIL_VIEW);
        assertThat(access.getValue().paymentId()).isEqualTo(PAYMENT);
        assertThat(access.getValue().merchantId()).isEqualTo(MERCHANT);
        assertThat(access.getValue().customerRef()).isEqualTo(CUSTOMER);
    }

    // 없는 번호에 500이 나거나, 열람하지 않은 결제가 기록에 쌓이면 안 된다
    @Test
    void detail_unknownPayment_returnsNotFoundWithoutRecord() {
        when(repository.findById(PAYMENT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findDetail(PAYMENT)).isInstanceOf(PaymentTransactionNotFoundException.class);

        verifyNoInteractions(recorder);
    }

    // 목록이 다시 이메일 · 카드 · 접속 · 기기 값을 20줄씩 한꺼번에 내보내면 1건 열람 기록이 의미가 없다
    @Test
    void list_responseHasNoContactOrEnvironmentFields() {
        List<String> fields = Arrays.stream(PaymentTransactionResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(fields)
                .doesNotContain(
                        "cardDisplayLabel", "ipCountryCode", "maskedIpAddress", "maskedDeviceId", "maskedBuyerEmail");
    }
}
