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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
class CaptureConcurrencyTests {
    @Autowired
    CaptureService service;

    @Autowired
    EntityManager entityManager;

    @MockitoSpyBean
    CaptureRepository captures;

    @MockitoSpyBean
    SettlementRepository settlements;

    @AfterEach
    void cleanUp() {
        reset(captures, settlements);
        settlements.deleteAll();
        captures.deleteAll();
    }

    // 같은 결제를 동시에 처음 처리해도 두 요청 모두 같은 매입을 반환한다
    @Test
    void processCapture_whenConcurrentDuplicate_returnsSameCapture() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger lookups = new AtomicInteger();
        doAnswer(invocation -> {
                    var existing = entityManager
                            .createQuery("select c from Capture c where c.paymentId = :paymentId", Capture.class)
                            .setParameter("paymentId", 90001L)
                            .getResultList()
                            .stream()
                            .findFirst();
                    if (lookups.incrementAndGet() <= 2) {
                        barrier.await(10, TimeUnit.SECONDS);
                    }
                    return existing;
                })
                .when(captures)
                .findByPaymentId(90001L);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.processCapture(90001L, 2L, "CONCURRENT-001", 10001L));
            var second = executor.submit(() -> service.processCapture(90001L, 2L, "CONCURRENT-001", 10001L));
            Capture firstResult = first.get(20, TimeUnit.SECONDS);
            Capture secondResult = second.get(20, TimeUnit.SECONDS);
            assertThat(firstResult.getCaptureId()).isEqualTo(secondResult.getCaptureId());
            assertThat(captures.count()).isEqualTo(1);
            assertThat(settlements.count()).isEqualTo(1);
            assertThat(settlements.findByPaymentId(90001L).orElseThrow().getSettlementAmount())
                    .isEqualTo(9701L);
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
