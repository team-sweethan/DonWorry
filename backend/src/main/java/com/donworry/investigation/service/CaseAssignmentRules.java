package com.donworry.investigation.service;

import com.donworry.identity.api.SecurityStaffEntry;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

// FDS 사건 자동 배정 순서 (#608): 미종결 담당 최소 → 이름 오름차순 → 직원 ID 오름차순. 정지 직원 · 제외 직원은 빠진다
// 건수가 같으면 이름 순으로 돌아가며 받는다. 이름 순 순환만 쓰지 않는 이유는 건수 차이가 벌어지기 때문이다
final class CaseAssignmentRules {

    private static final Comparator<Choice> ORDER = Comparator.comparingLong(Choice::openCountBefore)
            .thenComparing(Choice::displayName)
            .thenComparing(Choice::staffId);

    private CaseAssignmentRules() {}

    record Choice(Long staffId, String displayName, long openCountBefore) {}

    static Optional<Choice> choose(
            Collection<SecurityStaffEntry> staff, Map<Long, Long> openCounts, Set<Long> excluded) {
        return rank(staff, openCounts, excluded).stream().findFirst();
    }

    // 다음에 받을 순서대로 — 담당 현황 화면의 "배정 순서"도 같은 순서를 쓴다
    static List<Choice> rank(Collection<SecurityStaffEntry> staff, Map<Long, Long> openCounts, Set<Long> excluded) {
        return staff.stream()
                .filter(SecurityStaffEntry::active)
                .filter(entry -> !excluded.contains(entry.userId()))
                .map(entry ->
                        new Choice(entry.userId(), entry.displayName(), openCounts.getOrDefault(entry.userId(), 0L)))
                .sorted(ORDER)
                .toList();
    }
}
