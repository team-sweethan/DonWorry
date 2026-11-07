package com.donworry.investigation.report.repository;

import com.donworry.investigation.report.entity.AiSwitchHistory;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

// 스위치 기록은 추가만 한다 — 고치기 · 지우기를 노출하지 않는다 (ADR-0083 결정 1)
public interface AiSwitchHistoryRepository extends Repository<AiSwitchHistory, Long> {

    AiSwitchHistory save(AiSwitchHistory history);

    // 지금 상태 = 마지막 기록. 비어 있으면 켜짐
    Optional<AiSwitchHistory> findFirstByOrderByIdDesc();

    // 화면의 최근 변경 기록 (최신순 20건)
    List<AiSwitchHistory> findTop20ByOrderByIdDesc();
}
