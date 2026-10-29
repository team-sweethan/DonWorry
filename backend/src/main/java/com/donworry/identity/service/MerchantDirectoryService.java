package com.donworry.identity.service;

import com.donworry.identity.api.MerchantDirectoryEntry;
import com.donworry.identity.api.MerchantDirectoryReader;
import com.donworry.identity.repository.MerchantRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MerchantDirectoryService implements MerchantDirectoryReader {
    private final MerchantRepository merchants;

    @Override
    public List<MerchantDirectoryEntry> findAll() {
        return merchants.findAllByOrderByMerchantIdAsc().stream()
                .map(merchant -> new MerchantDirectoryEntry(
                        merchant.getMerchantId(),
                        merchant.getMerchantCode(),
                        merchant.getDisplayName(),
                        merchant.getStatus().name()))
                .toList();
    }
}
