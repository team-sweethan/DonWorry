package com.donworry.identity.service;

import com.donworry.identity.repository.MerchantRequestNonceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
@Slf4j
public class DatabaseMerchantNonceStore implements MerchantNonceStore {
    private static final DateTimeFormatter SQL_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS").withZone(ZoneOffset.UTC);
    private final MerchantRequestNonceRepository repository;
    private final PlatformTransactionManager transactions;
    private final Clock identityClock;
    private final com.donworry.identity.repository.MerchantIntegrationRepository integrations;

    @Override
    public boolean consume(Long merchantId, UUID nonce, Instant expiresAt, long keyVersion) {
        if (merchantId == null || merchantId <= 0) throw new IllegalArgumentException("invalid merchant identity");
        Objects.requireNonNull(nonce);
        Objects.requireNonNull(expiresAt);
        if (!expiresAt.isAfter(identityClock.instant())) return false;
        try {
            // 인증 기록은 업무 실패·외부 트랜잭션 롤백과 무관하게 먼저 확정한다.
            return Boolean.TRUE.equals(transaction().execute(status -> {
                // 같은 가맹점의 짧은 인증 기록만 직렬화해 정상 중복을 SQL 오류로 발생시키지 않는다.
                if (!repository.lockMerchant(merchantId))
                    throw new DataIntegrityViolationException("merchant not found");
                // 서명 확인 뒤 키가 바뀌었으면 폐기된 키 요청을 소비하지 않는다.
                if (!integrations
                        .findByMerchantId(merchantId)
                        .filter(i -> i.getStatus() != com.donworry.identity.entity.IntegrationStatus.NO_KEY
                                && i.getKeyVersion() == keyVersion)
                        .isPresent()) return false;
                // 잠금 대기 중 서명이 만료됐다면 삭제된 옛 nonce를 다시 소비하지 않는다.
                if (!expiresAt.isAfter(identityClock.instant())) return false;
                if (repository.containsNonce(merchantId, nonce.toString())) return false;
                repository.insertNonce(merchantId, nonce.toString(), SQL_TIME.format(expiresAt));
                return true;
            }));
        } catch (DataIntegrityViolationException exception) {
            String cause = exception.getMostSpecificCause().getMessage();
            if (cause != null && cause.contains("uk_merchant_request_nonces_merchant_nonce")) return false;
            // FK·저장 형식 오류를 nonce 중복으로 숨기지 않는다.
            throw exception;
        }
    }

    @Scheduled(initialDelay = 0, fixedDelay = 60_000)
    public void deleteExpired() {
        String cutoff = SQL_TIME.format(identityClock.instant());
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        try {
            while (true) {
                Integer deleted = transaction().execute(status -> repository.deleteExpiredBatch(cutoff));
                if (deleted == null || deleted < 1000 || System.nanoTime() - deadline >= 0) return;
            }
        } catch (RuntimeException exception) {
            // 만료 전 기록은 유지한다. 삭제 실패는 다음 실행에 재시도한다.
            log.error("HMAC nonce 만료 삭제 실패 errorType={}", exception.getClass().getSimpleName());
        }
    }

    private TransactionTemplate transaction() {
        var transaction = new TransactionTemplate(transactions);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction;
    }
}
