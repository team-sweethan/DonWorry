package com.donworry.settlement.listener;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.settlement.api.PaymentRefundSucceeded;
import com.donworry.settlement.entity.Capture;
import com.donworry.settlement.repository.CaptureRepository;
import com.donworry.settlement.repository.SettlementRefundAdjustmentRepository;
import com.donworry.settlement.service.SettlementService;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("mariadb")
@SpringBootTest(
        properties = {"settlement.refund-receiving.enabled=true", "spring.datasource.hikari.maximum-pool-size=1"})
@Import(MariaDbTestContainerConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentRefundReceivedMariaDbTests {
    private static final long MERCHANT = 939401, PAYMENT = 9394001;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CaptureRepository captures;

    @Autowired
    SettlementService settlements;

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    IncompleteEventPublications publications;

    @MockitoSpyBean
    SettlementRefundAdjustmentRepository adjustments;

    private PaymentRefundSucceeded event;

    @BeforeEach
    void seed() {
        jdbc.update(
                "INSERT INTO merchants(merchant_id,merchant_code,display_name,status,pg_fee_rate,created_at) VALUES (?,'P6-REFUND-EVENT','refund event','ACTIVE',0.01,UTC_TIMESTAMP(6))",
                MERCHANT);
        var at = Instant.now().minusSeconds(864000).truncatedTo(ChronoUnit.MICROS);
        var capture = Capture.processing(PAYMENT, MERCHANT, "P6-REFUND-EVENT", 10000L, at);
        capture.complete(at);
        capture = captures.save(capture);
        settlements.createSettlement(capture);
        event = new PaymentRefundSucceeded(
                9394101L,
                PAYMENT,
                capture.getCaptureId(),
                MERCHANT,
                10000L,
                Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    @AfterEach
    void clean() {
        jdbc.update(
                "DELETE FROM EVENT_PUBLICATION WHERE EVENT_TYPE=? AND JSON_VALUE(SERIALIZED_EVENT,'$.paymentId')=?",
                PaymentRefundSucceeded.class.getName(),
                PAYMENT);
        jdbc.update("DELETE FROM settlement_refund_adjustments WHERE merchant_id=?", MERCHANT);
        jdbc.update("DELETE FROM settlements WHERE merchant_id=?", MERCHANT);
        jdbc.update("DELETE FROM captures WHERE merchant_id=?", MERCHANT);
        jdbc.update("DELETE FROM merchants WHERE merchant_id=?", MERCHANT);
    }

    private long receipts() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement_refund_adjustments WHERE payment_id=?", Long.class, PAYMENT);
    }

    private long completedPublications() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM EVENT_PUBLICATION WHERE EVENT_TYPE=? AND LISTENER_ID=? AND JSON_VALUE(SERIALIZED_EVENT,'$.paymentId')=? AND COMPLETION_DATE IS NOT NULL",
                Long.class,
                PaymentRefundSucceeded.class.getName(),
                PaymentRefundSucceededListener.LISTENER_ID,
                PAYMENT);
    }

    private void publish() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> publisher.publishEvent(event));
    }

    private void delivered(long count) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(receipts()).isEqualTo(1);
            assertThat(completedPublications()).isEqualTo(count);
        });
    }

    @Test
    void receive_whenProducerRollsBack_doesNotPersistOrDeliver() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            publisher.publishEvent(event);
            status.setRollbackOnly();
        });
        assertThat(receipts()).isZero();
        assertThat(completedPublications()).isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM EVENT_PUBLICATION WHERE EVENT_TYPE=? AND JSON_VALUE(SERIALIZED_EVENT,'$.paymentId')=?",
                        Long.class,
                        PaymentRefundSucceeded.class.getName(),
                        PAYMENT))
                .isZero();
    }

    // 한 연결만 있어도 수신 트랜잭션과 완료 기록이 종료되고 재전달은 원장을 늘리지 않는다.
    @Test
    void receive_whenCommittedAndDuplicated_recordsOnceAndCompletesBothPublications() {
        publish();
        delivered(1);
        publish();
        delivered(2);
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM settlement_refund_adjustments WHERE payment_id=?", String.class, PAYMENT))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT status FROM settlements WHERE payment_id=?", String.class, PAYMENT))
                .isEqualTo("SCHEDULED");
    }

    // 수신 저장 실패는 발행 기록을 미완료로 남겨 원래 이벤트만 다시 전달할 수 있게 한다.
    @Test
    void receive_whenStorageFails_redeliversOriginalPublication() {
        doThrow(new IllegalStateException("test receipt failure"))
                .when(adjustments)
                .save(any());
        publish();
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(jdbc.queryForObject(
                                "SELECT STATUS FROM EVENT_PUBLICATION WHERE EVENT_TYPE=? AND LISTENER_ID=? AND JSON_VALUE(SERIALIZED_EVENT,'$.paymentId')=?",
                                String.class,
                                PaymentRefundSucceeded.class.getName(),
                                PaymentRefundSucceededListener.LISTENER_ID,
                                PAYMENT))
                        .isEqualTo("FAILED"));
        assertThat(receipts()).isZero();
        var id = UUID.fromString(jdbc.queryForObject(
                "SELECT ID FROM EVENT_PUBLICATION WHERE EVENT_TYPE=? AND LISTENER_ID=? AND JSON_VALUE(SERIALIZED_EVENT,'$.paymentId')=?",
                String.class,
                PaymentRefundSucceeded.class.getName(),
                PaymentRefundSucceededListener.LISTENER_ID,
                PAYMENT));
        reset(adjustments);
        publications.resubmitIncompletePublications(
                publication -> publication.getIdentifier().equals(id));
        delivered(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM EVENT_PUBLICATION WHERE ID=?", Long.class, id.toString()))
                .isEqualTo(1);
    }
}
