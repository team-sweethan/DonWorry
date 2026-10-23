package com.donworry.settlement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.donworry.settlement.api.CaptureCompleted;
import com.donworry.settlement.entity.Capture;
import com.donworry.settlement.entity.CaptureStatus;
import com.donworry.settlement.entity.Settlement;
import com.donworry.settlement.entity.SettlementStatus;
import com.donworry.settlement.repository.CaptureRepository;
import com.donworry.settlement.repository.SettlementRepository;
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
    private CaptureRepository captures;
    private SettlementRepository settlements;
    private SettlementFeeCalculator calculator;
    private SettlementService settlementService;
    private CaptureService captureService;
    private ApplicationEventPublisher events;

    @BeforeEach
    void setUp() {
        captures = mock(CaptureRepository.class);
        settlements = mock(SettlementRepository.class);
        calculator = new SettlementFeeCalculator();
        settlementService = new SettlementService(settlements, calculator);
        events = mock(ApplicationEventPublisher.class);
        captureService = new CaptureService(new CaptureTransactionService(captures, settlementService, events));
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
        var fees = calculator.calculate(amount);
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
        assertThatIllegalArgumentException().isThrownBy(() -> calculator.calculate(amount));
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
    }

    // 정산 저장이 실패하면 완료 이벤트를 발행하지 않는다
    @Test
    void processCapture_whenSettlementSaveFails_doesNotPublishCompletion() {
        when(captures.save(any(Capture.class))).thenAnswer(invocation -> {
            Capture capture = invocation.getArgument(0);
            ReflectionTestUtils.setField(capture, "captureId", 10L);
            return capture;
        });
        var failure = new DataAccessResourceFailureException("database unavailable");
        when(settlements.save(any(Settlement.class))).thenThrow(failure);

        assertThatThrownBy(() -> captureService.processCapture(1L, 2L, "ORDER-001", 10001L))
                .isSameAs(failure);
        verifyNoInteractions(events);
    }
}
