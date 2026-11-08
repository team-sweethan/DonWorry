package com.donworry.fds.api;

import java.util.Collection;
import java.util.List;

// 경보 근거 접근 기록 번호 읽기 창구 (#675, ADR-0082 조각 9) — P4 내부자 사건 해제 검토 보고서가 집계에 쓴다
// 번호만 준다. 기록 내용은 identity StaffAccessReader.findByIds로 읽는다
public interface InsiderAlertEvidenceReader {

    // 여러 경보의 근거 기록 번호 — 중복 없음. 경보가 없거나 근거가 없으면 빈 목록
    List<Long> accessEventIds(Collection<Long> alertIds);
}
