/** 관리자 사이드바의 승인 대기 룰 끄기 요청 수다. */
export type RuleChangeRequestBadgeSnapshot = number | null

export function createRuleChangeRequestBadgeStore(load: () => Promise<number>) {
  let value: RuleChangeRequestBadgeSnapshot = null
  let sequence = 0
  const listeners = new Set<() => void>()
  const set = (next: RuleChangeRequestBadgeSnapshot) => {
    if (next === value) return
    value = next
    listeners.forEach((listener) => listener())
  }
  return {
    getSnapshot: () => value,
    subscribe(listener: () => void) {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
    async refresh() {
      const current = ++sequence
      try {
        const count = await load()
        if (current === sequence) set(count)
      } catch {
        // 배지는 보조 표시다. 요청 화면이 실제 오류를 안내한다.
      }
    },
    reset() {
      sequence += 1
      set(null)
    },
  }
}
