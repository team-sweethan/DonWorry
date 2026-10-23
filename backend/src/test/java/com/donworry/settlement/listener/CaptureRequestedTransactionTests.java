package com.donworry.settlement.listener;

import static org.mockito.Mockito.*;

import com.donworry.settlement.api.CaptureRequested;
import com.donworry.settlement.service.CaptureService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

@SpringJUnitConfig(CaptureRequestedTransactionTests.Config.class)
class CaptureRequestedTransactionTests {
    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    CaptureService service;

    @Autowired
    AbstractPlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        reset(service);
    }

    // P5가 커밋하기 전에는 호출하지 않고 커밋한 뒤에 매입 서비스를 호출한다
    @Test
    void publish_whenCommitted_invokesServiceAfterCommit() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            events.publishEvent(request());
            verifyNoInteractions(service);
        });
        verify(service).processCapture(1L, 2L, "ORDER-001", 10001L);
    }

    // 발행 측 롤백은 매입으로 이어지지 않는다
    @Test
    void publish_whenRolledBack_doesNotInvokeService() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            events.publishEvent(request());
            status.setRollbackOnly();
        });
        verifyNoInteractions(service);
    }

    // 트랜잭션 없는 발행은 계약 위반이므로 리스너가 처리하지 않는다
    @Test
    void publish_whenNoTransaction_doesNotInvokeService() {
        events.publishEvent(request());
        verifyNoInteractions(service);
    }

    private CaptureRequested request() {
        return CaptureRequested.builder()
                .paymentId(1L)
                .merchantId(2L)
                .orderId("ORDER-001")
                .amount(10001L)
                .build();
    }

    @Configuration
    @EnableTransactionManagement
    static class Config {
        @Bean
        CaptureService captureService() {
            return mock(CaptureService.class);
        }

        @Bean
        CaptureRequestedListener listener(CaptureService service) {
            return new CaptureRequestedListener(service);
        }

        @Bean
        AbstractPlatformTransactionManager transactionManager() {
            // DB 대신 Spring의 실제 커밋·롤백 콜백만 실행해 수신 시점을 검증한다.
            return new AbstractPlatformTransactionManager() {
                @Override
                protected Object doGetTransaction() {
                    return new Object();
                }

                @Override
                protected void doBegin(Object transaction, TransactionDefinition definition) {}

                @Override
                protected void doCommit(DefaultTransactionStatus status) {}

                @Override
                protected void doRollback(DefaultTransactionStatus status) {}
            };
        }
    }
}
