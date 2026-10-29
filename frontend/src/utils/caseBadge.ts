import type { CaseStatus } from '../types/case.ts'

/**
 * 보안 직원 사이드바 "Case 목록" 배지 (#224). 처리 필요 = 판정 대기 + 금융 처리 실패 (프로토타입 `needAction`).
 * 숫자는 사용자 동작(메뉴 이동 · 탭 복귀 · 판정 · 다시 확인) 때만 다시 센다.
 * 타이머로 부르면 요청마다 세션이 연장돼 30분 무활동 만료(ADR-0014)가 오지 않는다.
 */

export type CaseCounts = Record<CaseStatus, number>

export function needActionCount(counts: CaseCounts) {
  return counts.DECISION_PENDING + counts.ACTION_FAILED
}

/** 아직 한 번도 받지 못했으면 null — 화면은 배지를 그리지 않는다. */
export type CaseBadgeSnapshot = number | null

export function createCaseBadgeStore(load: () => Promise<CaseCounts>) {
  let value: CaseBadgeSnapshot = null
  // 늦게 온 이전 응답이 새 응답을 덮지 않게 마지막 요청만 반영한다. reset도 순번을 올려 이전 계정 응답을 버린다
  let sequence = 0
  const listeners = new Set<() => void>()
  const set = (next: CaseBadgeSnapshot) => {
    if (next === value) return
    value = next
    listeners.forEach((listener) => listener())
  }
  return {
    getSnapshot: () => value,
    subscribe(listener: () => void) {
      listeners.add(listener)
      return () => {
        listeners.delete(listener)
      }
    },
    /** 실패하면 직전 숫자를 유지한다 — 0으로 바꾸면 처리할 건이 없는 것처럼 보인다. */
    async refresh() {
      const current = ++sequence
      try {
        const counts = await load()
        if (current === sequence) set(needActionCount(counts))
      } catch {
        // 배지는 보조 표시다. 오류 안내는 각 화면의 조회가 맡는다
      }
    },
    /** 로그아웃 · 계정 전환 때 이전 계정의 숫자를 지운다. */
    reset() {
      sequence += 1
      set(null)
    },
  }
}
