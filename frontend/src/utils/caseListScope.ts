import type { CaseScope } from './caseRules.ts'

/**
 * Case 목록 [내 사건만 / 전체 직원] (#612) — 상세에 갔다 와도 유지되게 탭 저장소에 둔다. 저장소를 못 쓰면 기본값.
 * 보안 홈은 항상 내 사건만 보이므로, 홈에서 목록으로 갈 때 MINE으로 맞춰 숫자가 같게 한다 (#640).
 */
const SCOPE_KEY = 'donworry.caseListScope'

export function readCaseListScope(): CaseScope {
  try {
    return sessionStorage.getItem(SCOPE_KEY) === 'ALL' ? 'ALL' : 'MINE'
  } catch {
    return 'MINE'
  }
}

export function saveCaseListScope(scope: CaseScope) {
  try {
    sessionStorage.setItem(SCOPE_KEY, scope)
  } catch {
    // 저장하지 못해도 이번 화면에서는 바뀐 값을 쓴다
  }
}
