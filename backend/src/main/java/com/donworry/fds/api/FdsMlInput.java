package com.donworry.fds.api;

import java.util.List;
import lombok.Builder;

// 대상 결제와 이전 이력 묶음 (#409) — 이력은 결제당 1행, 대상 시각 이하만 담는다
// customerHistory: 같은 가맹점 · 같은 고객 / sharedCardHistory: 같은 가맹점에서 같은 카드를 쓴 다른 고객
// historyOverLimit이면 두 이력은 비어 있다 — 잘라 보내면 고객 건수 · 평균이 학습과 달라지므로 채점하지 않는다
@Builder
public record FdsMlInput(
        FdsMlTransaction target,
        List<FdsMlTransaction> customerHistory,
        List<FdsMlTransaction> sharedCardHistory,
        boolean historyOverLimit) {

    public FdsMlInput {
        customerHistory = List.copyOf(customerHistory);
        sharedCardHistory = List.copyOf(sharedCardHistory);
    }
}
