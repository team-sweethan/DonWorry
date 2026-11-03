package com.donworry.identity.service;

import com.donworry.identity.entity.*;
import com.donworry.identity.repository.*;
import java.time.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
@DependsOnDatabaseInitialization
@ConditionalOnProperty(name = "donworry.integration.legacy-import-enabled", havingValue = "true")
public class MerchantKeyMigration implements SmartInitializingSingleton {
    private final MerchantRepository merchants;
    private final MerchantIntegrationRepository integrations;
    private final MerchantKeyHistoryRepository histories;
    private final MerchantKeyCipher cipher;
    private final PlatformTransactionManager transactions;
    private final Clock identityClock;
    private final MerchantLegacyKeySource legacyKeys;

    @Override
    public void afterSingletonsInstantiated() {
        // 모든 레거시 참조를 한 번에 옮긴다. 실패하면 서버가 요청을 받기 전에 기동을 중단한다.
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            for (var candidate : merchants.findAllByOrderByMerchantIdAsc()) {
                if (candidate.getSecretReference() == null) continue;
                var merchant = merchants.lockById(candidate.getMerchantId()).orElseThrow();
                if (integrations.findByMerchantId(merchant.getMerchantId()).isPresent()) continue;
                importKey(merchant);
            }
        });
    }

    private void importKey(Merchant merchant) {
        try {
            byte[] secret = legacyKeys.resolve(merchant.getSecretReference());
            var value = MerchantIntegration.empty(merchant.getMerchantId());
            byte[] nonce = cipher.newNonce();
            var now = LocalDateTime.ofInstant(identityClock.instant(), ZoneOffset.UTC);
            value.issue(
                    cipher.encrypt(merchant.getMerchantId(), 1, secret, nonce), nonce, cipher.activeId(), now, true);
            integrations.saveAndFlush(value);
            histories.save(MerchantKeyHistory.record(merchant.getMerchantId(), null, "MIGRATE", 1, null, now));
        } catch (RuntimeException exception) {
            // 드라이버·디코더 원인에 키가 들어갈 수 있어 입력과 원인을 연결하지 않는다.
            throw new IllegalStateException("Legacy merchant key import failed; verify server key configuration");
        }
    }
}
