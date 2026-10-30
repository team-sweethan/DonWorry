package com.donworry.settlement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.donworry.identity.api.TenantReader;
import com.donworry.identity.api.TenantSummary;
import com.donworry.settlement.api.CaptureCompleted;
import com.donworry.settlement.entity.Capture;
import com.donworry.settlement.entity.CaptureStatus;
import com.donworry.settlement.entity.Settlement;
import com.donworry.settlement.entity.SettlementStatus;
import com.donworry.settlement.repository.CaptureRepository;
import com.donworry.settlement.repository.SettlementRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

class SettlementFlowTests {
    // 계약 PG 요율만 유효해도 카드 수수료 합산 후 음수가 되면 정산하지 않는다.
    @Test
    void calculate_whenFeesExceedGross_rejectsNegativeSettlement() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SettlementFeeCalculator().calculate(35500L, new BigDecimal("0.99")));
    }

    private TenantSummary merchant(String rate) {
        return TenantSummary.builder()
                .merchantId(2L)
                .name("test")
                .status("ACTIVE")
                .pgFeeRate(new BigDecimal(rate))
                .build();
    }

    private Capture completedCapture() {
        var capture = Capture.processing(1L, 2L, "ORDER-001", 35500L, Instant.now());
        capture.complete(Instant.now());
        ReflectionTestUtils.setField(capture, "captureId", 10L);
        return capture;
    }

    private CaptureRepository captures;
    private SettlementRepository settlements;
    private SettlementFeeCalculator calculator;
    private TenantReader tenants;
    private SettlementService settlementService;
    private CaptureService captureService;
    private ApplicationEventPublisher events;

    // 가맹점별 실제 비율과 0%를 반영하고 원 미만 수수료는 절사한다.
    @ParameterizedTest
    @CsvSource({"0, 0, 34790", "0.0127, 450, 34340", "0.02, 710, 34080", "0.000001, 0, 34790"})
    void createSettlement_whenMerchantRateVaries_storesAppliedRateAndAmounts(String rate, long pgFee, long net) {
        when(tenants.findById(2L)).thenReturn(Optional.of(merchant(rate)));
        when(settlements.save(any(Settlement.class))).thenAnswer(call -> call.getArgument(0));

        var result = settlementService.createSettlement(completedCapture());

        assertThat(result.getPgFeeRate()).isEqualByComparingTo(rate);
        assertThat(result.getCardFeeAmount()).isEqualTo(710L);
        assertThat(result.getPgFeeAmount()).isEqualTo(pgFee);
        assertThat(result.getSettlementAmount()).isEqualTo(net);
    }

    // 계약 요율이 바뀌어도 이미 생성한 정산을 다시 계산하지 않는다.
    @Test
    void createSettlement_whenRateChanges_keepsExistingSnapshot() {
        when(tenants.findById(2L)).thenReturn(Optional.of(merchant("0.0127")));
        when(settlements.save(any(Settlement.class))).thenAnswer(call -> call.getArgument(0));
        var capture = completedCapture();
        var original = settlementService.createSettlement(capture);
        when(settlements.findByCaptureId(10L)).thenReturn(Optional.of(original));
        when(tenants.findById(2L)).thenReturn(Optional.of(merchant("0.03")));

        assertThat(settlementService.createSettlement(capture)).isSameAs(original);
        assertThat(original.getPgFeeRate()).isEqualByComparingTo("0.0127");
        assertThat(original.getSettlementAmount()).isEqualTo(34340L);
        verify(tenants, times(1)).findById(2L);
        verify(settlements, times(1)).save(any());
    }

    // 없는 가맹점에 임시 요율을 적용해 정산하지 않는다.
    @Test
    void createSettlement_whenMerchantMissing_doesNotUseDefaultRate() {
        assertThatThrownBy(() -> settlementService.createSettlement(completedCapture()))
                .isInstanceOf(IllegalStateException.class);
        verify(settlements, never()).save(any());
    }

    // 조회 장애를 기본 수수료율로 숨기지 않는다.
    @Test
    void createSettlement_whenMerchantLookupFails_propagatesFailure() {
        var failure = new DataAccessResourceFailureException("merchant lookup unavailable");
        when(tenants.findById(2L)).thenThrow(failure);
        assertThatThrownBy(() -> settlementService.createSettlement(completedCapture()))
                .isSameAs(failure);
        verify(settlements, never()).save(any());
    }

    // 계약 범위를 벗어난 요율은 임의 반올림 없이 거절한다.
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"-0.01", "1", "0.0000001"})
    void calculate_whenRateInvalid_rejectsInsteadOfRounding(String rate) {
        var value = rate == null ? null : new BigDecimal(rate);
        assertThatIllegalArgumentException().isThrownBy(() -> calculator.calculate(35500L, value));
    }

    @BeforeEach
    void setUp() {
        captures = mock(CaptureRepository.class);
        settlements = mock(SettlementRepository.class);
        calculator = new SettlementFeeCalculator();
        tenants = mock(TenantReader.class);
        settlementService = new SettlementService(settlements, calculator, tenants);
        events = mock(ApplicationEventPublisher.class);
        captureService = new CaptureService(new CaptureTransactionService(
                captures, settlementService, events, mock(OrderFulfillmentService.class)));
    }

    // 정상 매입은 완료되고 수수료를 차감한 정산 예정금이 생성된다
    @Test
    void processCapture_whenValid_createsScheduledSettlement() {
        when(captures.save(any(Capture.class))).thenAnswer(invocation -> {
            Capture capture = invocation.getArgument(0);
            // DB의 IDENTITY 키 할당만 대신한다. 업무 처리는 실제 서비스를 실행한다.
            ReflectionTestUtils.setField(capture, "captureId", 10L);
            return capture;
        });
        when(tenants.findById(2L)).thenReturn(Optional.of(merchant("0.01")));
        Capture result = captureService.processCapture(1L, 2L, "ORDER-001", 10001L);

        assertThat(result.getStatus()).isEqualTo(CaptureStatus.COMPLETED);
        assertThat(result.getCapturedAt()).isNotNull();
        assertThat(result.getRetryCount()).isZero();
        ArgumentCaptor<Settlement> saved = ArgumentCaptor.forClass(Settlement.class);
        verify(settlements).save(saved.capture());
        Settlement settlement = saved.getValue();
        assertThat(settlement.getCaptureId()).isEqualTo(10L);
        assertThat(settlement.getPaymentId()).isEqualTo(1L);
        assertThat(settlement.getMerchantId()).isEqualTo(2L);
        assertThat(settlement.getGrossAmount()).isEqualTo(10001L);
        assertThat(settlement.getCardFeeRate()).isEqualByComparingTo("0.02");
        assertThat(settlement.getPgFeeRate()).isEqualByComparingTo("0.01");
        assertThat(settlement.getCardFeeAmount()).isEqualTo(200L);
        assertThat(settlement.getPgFeeAmount()).isEqualTo(100L);
        assertThat(settlement.getSettlementAmount()).isEqualTo(9701L);
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.SCHEDULED);

        var completed = ArgumentCaptor.forClass(CaptureCompleted.class);
        var order = inOrder(settlements, events);
        order.verify(settlements).save(any(Settlement.class));
        order.verify(events).publishEvent(completed.capture());
        assertThat(completed.getValue().paymentId()).isEqualTo(result.getPaymentId());
        assertThat(completed.getValue().captureId()).isEqualTo(result.getCaptureId());
        assertThat(completed.getValue().capturedAt()).isEqualTo(result.getCapturedAt());
    }

    // 완료된 결제의 중복 요청은 매입과 정산을 다시 생성하지 않는다
    @Test
    void processCapture_whenAlreadyCompleted_doesNotCreateDuplicates() {
        Capture existing = Capture.processing(1L, 2L, "ORDER-001", 10000L, Instant.now());
        existing.complete(Instant.now());
        when(captures.findByPaymentId(1L)).thenReturn(Optional.of(existing));
        assertThat(captureService.processCapture(1L, 2L, "ORDER-001", 10000L)).isSameAs(existing);
        verify(captures, never()).save(any());
        verifyNoInteractions(settlements, events);
    }

    // 각 수수료는 원 미만을 각각 절사한다
    @ParameterizedTest
    @CsvSource({"1, 0, 0, 1", "99, 1, 0, 98", "10001, 200, 100, 9701"})
    void calculate_whenFractionalFees_truncatesEachFee(long amount, long cardFee, long pgFee, long net) {
        var fees = calculator.calculate(amount, new BigDecimal("0.01"));
        assertThat(fees.cardFeeAmount()).isEqualTo(cardFee);
        assertThat(fees.pgFeeAmount()).isEqualTo(pgFee);
        assertThat(fees.settlementAmount()).isEqualTo(net);
    }

    // 유효하지 않은 금액은 매입 저장과 수수료 계산을 거부한다
    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void processCapture_whenInvalidAmount_throwsException(Long amount) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> captureService.processCapture(1L, 2L, "ORDER-001", amount));
        verifyNoInteractions(captures, settlements);
        assertThatIllegalArgumentException().isThrownBy(() -> calculator.calculate(amount, new BigDecimal("0.01")));
    }

    // 완료되지 않았거나 저장되지 않은 매입은 정산하지 않는다
    @Test
    void createSettlement_whenCaptureNotEligible_throwsException() {
        Capture capture = Capture.processing(1L, 2L, "ORDER-001", 10000L, Instant.now());
        assertThatIllegalArgumentException().isThrownBy(() -> settlementService.createSettlement(capture));
        capture.complete(Instant.now());
        assertThatIllegalArgumentException().isThrownBy(() -> settlementService.createSettlement(capture));
        verifyNoInteractions(settlements);
    }

    // 이미 정산이 있으면 재생성하지 않는다
    @Test
    void createSettlement_whenAlreadyExists_returnsExisting() {
        Capture capture = Capture.processing(1L, 2L, "ORDER-001", 10000L, Instant.now());
        capture.complete(Instant.now());
        ReflectionTestUtils.setField(capture, "captureId", 10L);
        Settlement existing = mock(Settlement.class);
        when(settlements.findByCaptureId(10L)).thenReturn(Optional.of(existing));
        assertThat(settlementService.createSettlement(capture)).isSameAs(existing);
        verify(settlements, never()).save(any());
        verifyNoInteractions(tenants);
    }

    // 정산 저장이 실패하면 완료 이벤트를 발행하지 않는다
    @Test
    void processCapture_whenSettlementSaveFails_doesNotPublishCompletion() {
        when(captures.save(any(Capture.class))).thenAnswer(invocation -> {
            Capture capture = invocation.getArgument(0);
            ReflectionTestUtils.setField(capture, "captureId", 10L);
            return capture;
        });
        when(tenants.findById(2L)).thenReturn(Optional.of(merchant("0.01")));
        var failure = new DataAccessResourceFailureException("database unavailable");
        when(settlements.save(any(Settlement.class))).thenThrow(failure);

        assertThatThrownBy(() -> captureService.processCapture(1L, 2L, "ORDER-001", 10001L))
                .isSameAs(failure);
        verifyNoInteractions(events);
    }
}
