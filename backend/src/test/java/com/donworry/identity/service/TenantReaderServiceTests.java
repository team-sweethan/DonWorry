package com.donworry.identity.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import com.donworry.identity.repository.MerchantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class TenantReaderServiceTests {
    private final MerchantRepository repository = mock(MerchantRepository.class);
    private final TenantReaderService service = new TenantReaderService(repository);

    // 잘못된 ID는 DB를 조회하지 않는다
    @Test
    void findById_whenIdInvalid_throwsException() {
        for (Long id : new Long[] {null, 0L, -1L}) {
            assertThatIllegalArgumentException().isThrownBy(() -> service.findById(id));
        }
        verifyNoInteractions(repository);
    }

    // DB 장애를 없는 가맹점으로 바꾸지 않는다
    @Test
    void findById_whenDatabaseFails_propagatesException() {
        var failure = new DataAccessResourceFailureException("database unavailable");
        given(repository.findById(1L)).willThrow(failure);
        assertThatThrownBy(() -> service.findById(1L)).isSameAs(failure);
    }
}
