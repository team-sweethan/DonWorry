// Case ID를 경로에 붙이므로 숫자인 식별자만 받는다. 빈 값은 저장된 설정을 지울 때 쓴다.
export function parseBoothCase(value: string | null): string | null {
  return value !== null && /^[1-9]\d{0,18}$/.test(value) ? value : null
}

// 진행자가 목록에서 다른 사건을 열면 보고서 단계도 그 사건을 따라가야 한다.
export function boothCaseForPath(
  pathname: string,
  configured: string | null,
  reportCaseId?: string | null,
): string | null {
  // 별도 보고서 예시를 보고 돌아와도 연락처를 준비한 사건은 유지한다.
  if (reportCaseId && pathname === `/security/cases/${reportCaseId}/report`)
    return parseBoothCase(configured)
  const match = /^\/security\/cases\/([1-9]\d{0,18})(?:\/|$)/.exec(pathname)
  return match?.[1] ?? parseBoothCase(configured)
}
