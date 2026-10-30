package com.donworry.settlement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.settlement.entity.Capture;
import com.donworry.settlement.repository.CaptureRepository;
import com.donworry.settlement.repository.SettlementRepository;
import jakarta.persistence.EntityManager;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
class CaptureConcurrencyTests {
    // P6 단독 테스트의 가상 paymentId를 유지하고 P5 소비자는 별도 통합 테스트에서 검증한다.
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.donworry.payment.service.PaymentCaptureService paymentCaptureService;

    @Autowired
    CaptureService service;

    @Autowired
    EntityManager entityManager;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoSpyBean
    CaptureRepository captures;

    @MockitoSpyBean
    SettlementRepository settlements;

    @AfterEach
    void cleanUp() {
        reset(captures, settlements);
        // 다른 테스트가 같은 컨테이너에 저장한 기록은 지우지 않는다.
        for (long paymentId : new long[] {90001L, 90002L}) {
            settlements.findByPaymentId(paymentId).ifPresent(settlements::delete);
            captures.findByPaymentId(paymentId).ifPresent(captures::delete);
        }
    }

    // 같은 결제를 동시에 처음 처리해도 두 요청 모두 같은 매입을 반환한다
    @Test
    void processCapture_whenConcurrentDuplicate_returnsSameCapture() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return service.processCapture(90001L, 2L, "CONCURRENT-001", 10001L);
            });
            var second = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return service.processCapture(90001L, 2L, "CONCURRENT-001", 10001L);
            });
            Capture firstResult = first.get(20, TimeUnit.SECONDS);
            Capture secondResult = second.get(20, TimeUnit.SECONDS);
            assertThat(firstResult.getCaptureId()).isEqualTo(secondResult.getCaptureId());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM captures WHERE payment_id = ?", Long.class, 90001L))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settlements WHERE payment_id = ?", Long.class, 90001L))
                    .isEqualTo(1);
            assertThat(settlements.findByPaymentId(90001L).orElseThrow().getSettlementAmount())
                    // Flyway의 CAKESHOP 계약 PG 수수료율 2%와 모의 카드 수수료율 2%를 적용한다.
                    .isEqualTo(9601L);
        }
    }

    // 정산 저장 오류는 중복으로 숨기지 않고 매입까지 함께 롤백한다
    @Test
    void processCapture_whenSettlementSaveFails_rollsBackAndPropagates() {
        doAnswer(invocation -> {
                    throw new DataIntegrityViolationException("settlement save failure");
                })
                .when(settlements)
                .save(any());
        assertThatThrownBy(() -> service.processCapture(90002L, 2L, "FAIL-001", 10000L))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("settlement save failure");
        assertThat(captures.findByPaymentId(90002L)).isEmpty();
        assertThat(settlements.findByPaymentId(90002L)).isEmpty();
    }
}
