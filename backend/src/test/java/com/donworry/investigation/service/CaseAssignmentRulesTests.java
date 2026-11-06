package com.donworry.investigation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.donworry.identity.api.SecurityStaffEntry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CaseAssignmentRulesTests {

    // 미종결 담당 최소 → 이름 오름차순 → 직원 ID 오름차순, 정지 · 제외 직원은 후보에서 빠진다 (#608 규칙 A)
    @Test
    void choose_picksLeastOpenThenNameThenId_skippingInactive() {
        List<SecurityStaffEntry> staff = List.of(
                new SecurityStaffEntry(1L, "다", true),
                new SecurityStaffEntry(2L, "가", true),
                new SecurityStaffEntry(3L, "나", true),
                new SecurityStaffEntry(4L, "나", true),
                new SecurityStaffEntry(5L, "가", false));
        Map<Long, Long> openCounts = Map.of(1L, 0L, 2L, 1L, 3L, 1L, 4L, 1L);

        // 건수가 가장 적은 "다"가 이름보다 먼저
        assertThat(CaseAssignmentRules.choose(staff, openCounts, Set.of()))
                .hasValueSatisfying(choice -> assertThat(choice.staffId()).isEqualTo(1L));
        // 건수가 같으면 이름 순, 이름까지 같으면 ID 순. 정지 직원(5)은 건수 0이어도 빠진다
        assertThat(CaseAssignmentRules.rank(staff, Map.of(1L, 1L, 2L, 1L, 3L, 1L, 4L, 1L), Set.of()))
                .extracting(CaseAssignmentRules.Choice::staffId)
                .containsExactly(2L, 3L, 4L, 1L);
        // 제외 직원(넘기는 직원)은 건수가 적어도 받지 않는다
        assertThat(CaseAssignmentRules.choose(staff, openCounts, Set.of(1L)))
                .hasValueSatisfying(choice -> assertThat(choice.staffId()).isEqualTo(2L));
        // 받을 직원이 없으면 비어 있다 — 호출 쪽이 미배정으로 둔다
        assertThat(CaseAssignmentRules.choose(List.of(new SecurityStaffEntry(5L, "가", false)), Map.of(), Set.of()))
                .isEmpty();
    }
}
