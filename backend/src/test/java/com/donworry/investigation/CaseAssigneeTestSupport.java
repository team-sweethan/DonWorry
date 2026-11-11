package com.donworry.investigation;

import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.repository.SecurityCaseRepository;
import java.time.Instant;

// 열린 사건은 담당자만 판정 · 열람한다 (#612). 자동 배정은 시드 보안 직원에게 가므로
// 판정 흐름을 보는 테스트는 로그인한 직원을 그 사건의 담당으로 바꿔 "담당자가 처리한다"는 전제를 세운다
public final class CaseAssigneeTestSupport {
    private CaseAssigneeTestSupport() {}

    public static void assign(SecurityCaseRepository repository, Long caseId, Long staffId) {
        SecurityCase securityCase = repository.findById(caseId).orElseThrow();
        securityCase.assignTo(staffId, Instant.now());
        repository.save(securityCase);
    }
}
