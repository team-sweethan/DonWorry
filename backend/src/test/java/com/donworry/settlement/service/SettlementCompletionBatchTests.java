package com.donworry.settlement.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.settlement.repository.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SettlementCompletionBatchTests {
    private SettlementCompletionCandidate candidate(long id) {
        var c = mock(SettlementCompletionCandidate.class);
        when(c.getSettlementId()).thenReturn(id);
        when(c.getMerchantId()).thenReturn(10L);
        when(c.getOrderId()).thenReturn("ORDER-" + id);
        return c;
    }
    // 실패한 한 건과 이미 처리된 건을 만나도 같은 실행의 뒤쪽 대상을 계속 처리한다
    @Test
    void run_whenOneFails_continuesAndCountsEachOutcome() {
        var repo = mock(SettlementRepository.class);
        var tx = mock(SettlementCompletionTransactionService.class);
        var at = Instant.now().minusSeconds(1);
        var one = candidate(1);
        var two = candidate(2);
        var three = candidate(3);
        when(repo.completionUpperBound(at)).thenReturn(3L);
        when(repo.findCompletionCandidates(eq(at), eq(0L), eq(3L), any())).thenReturn(List.of(one, two));
        when(repo.findCompletionCandidates(eq(at), eq(2L), eq(3L), any())).thenReturn(List.of(three));
        when(tx.complete(1L, 10L, "ORDER-1", at)).thenThrow(new IllegalStateException("temporary database failure"));
        when(tx.complete(2L, 10L, "ORDER-2", at)).thenReturn("NOT_SCHEDULED");
        when(tx.complete(3L, 10L, "ORDER-3", at)).thenReturn("COMPLETED");
        var result = new SettlementCompletionBatchService(new SettlementCompletionCandidateService(repo), tx).run(at);
        assertThat(result.scanned()).isEqualTo(3);
        assertThat(result.completed()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        verify(repo, never()).findCompletionCandidates(eq(at), anyLong(), eq(4L), any());
    }
    // 잘못된 기준 시각은 조회도 실행도 하지 않아 미래 정산을 당겨 확정하지 않는다
    @Test
    void run_whenFuture_rejectsBeforeQuery() {
        var repo = mock(SettlementRepository.class);
        var tx = mock(SettlementCompletionTransactionService.class);
        var batch = new SettlementCompletionBatchService(new SettlementCompletionCandidateService(repo), tx);
        assertThatThrownBy(() -> batch.run(Instant.now().plusSeconds(3600)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> batch.run(null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repo, tx);
    }
    // 대상이 없으면 실행 서비스에 아무 쓰기도 요청하지 않는다
    @Test
    void run_whenEmpty_doesNotWrite() {
        var repo = mock(SettlementRepository.class);
        var tx = mock(SettlementCompletionTransactionService.class);
        when(repo.completionUpperBound(any())).thenReturn(0L);
        var result = new SettlementCompletionBatchService(new SettlementCompletionCandidateService(repo), tx)
                .run(Instant.now());
        assertThat(result.scanned()).isZero();
        verifyNoInteractions(tx);
    }
}
