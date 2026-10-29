package com.donworry.identity.service;

import com.donworry.identity.entity.*;
import com.donworry.identity.repository.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
@Slf4j
public class ApplicationRetentionService {
    private final MerchantApplicationRepository applications;
    private final ApplicationMailDeliveryRepository deliveries;
    private final ApplicationTokenRepository tokens;
    private final PlatformTransactionManager transactions;
    private final Clock identityClock;
    private long cursor;
    private long upper;

    // 수동 호출과 예약 호출이 겹치지 않고 실패 행 하나가 뒤 신청의 정리를 막지 않게 한다.
    public synchronized void clean() {
        long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
        var now =
                LocalDateTime.ofInstant(identityClock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        long deleted = 0, failed = 0;
        try {
            if (upper == 0) upper = applications.maximumId();
            var tx = new TransactionTemplate(transactions);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            while (System.nanoTime() - deadline < 0) {
                var ids = applications.retentionCandidates(
                        cursor, upper, now.minusDays(7), now.minusDays(30), PageRequest.of(0, 100));
                if (ids.isEmpty()) {
                    cursor = 0;
                    upper = 0;
                    break;
                }
                for (var id : ids) {
                    if (System.nanoTime() - deadline >= 0) break;
                    try {
                        Boolean done = tx.execute(s -> {
                            var a = applications.lockById(id).orElse(null);
                            if (a == null || !expired(a, now)) return false;
                            deliveries.deleteForApplication(id);
                            tokens.deleteForApplication(id);
                            return applications.deleteRetained(id) == 1;
                        });
                        if (Boolean.TRUE.equals(done)) deleted++;
                    } catch (org.springframework.dao.DataAccessResourceFailureException e) {
                        throw e;
                    } catch (RuntimeException e) {
                        failed++;
                        log.warn("신청 삭제 실패 errorType={}", e.getClass().getSimpleName());
                    }
                    cursor = id;
                }
            }
            log.info("신청 보관 정리 deleted={} failed={}", deleted, failed);
        } catch (RuntimeException e) {
            log.error("신청 보관 정리 중단 errorType={}", e.getClass().getSimpleName());
        }
    }

    private boolean expired(MerchantApplication a, LocalDateTime now) {
        return (a.getStatus() == ApplicationStatus.EMAIL_PENDING
                        && !a.getCreatedAt().plusDays(7).isAfter(now))
                || (a.getStatus() == ApplicationStatus.REJECTED
                        && a.getReviewedAt() != null
                        && !a.getReviewedAt().plusDays(30).isAfter(now));
    }
}
