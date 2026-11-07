import { useCallback, useEffect, useRef, useState } from 'react'
import { getCaseCounts } from '../api/cases'
import {
  WATCH_MS,
  initialWatchState,
  onCountsReceived,
  onListLoaded,
  watchStep,
  type WatchState,
} from '../utils/caseListWatch'
import type { CaseScope } from '../utils/caseRules'
import { applyCaseBadgeCounts } from './useSecurityCaseBadge'

/**
 * 보안 홈 · Case 목록에서 Case 변화를 감지한다 (#553). 규칙은 utils/caseListWatch.ts.
 *
 * - 화면 진입 때 기준 건수를 받고, WATCH_MS마다 다시 받아 바뀌면 onChange(새로 생긴 Case 수)를 부른다.
 *   화면은 목록을 다시 불러온 뒤 listLoaded(성공 여부)를 알려야 한다. 성공해야 기준 건수가 확정되고,
 *   실패하면 다음 조회에서 다시 알린다.
 * - 마지막 사용자 동작부터 WATCH_LIMIT_MS가 지나면 멈춘다(stopped). restart()로 다시 시작한다.
 * - 탭이 보이지 않으면 조회하지 않는다. 돌아오면 바로 한 번 조회하고 제한 시간을 다시 센다.
 *   숨어 있던 사이의 변화도 기준 건수와 비교해 반영한다.
 * - 받은 건수로 사이드바 배지(#224)도 맞춘다.
 * - scope는 화면이 보여 주는 범위다 (#640). 그 범위의 건수만 비교해, 남의 Case 변화로 내 화면을 다시 불러오지 않는다.
 *
 * 실시간 알림(SSE)으로 바꿀 때는 이 훅의 안쪽만 서버 이벤트 구독으로 바꾸고, 반환 모양은 유지한다.
 */
export function useCaseListWatch(onChange: (added: number) => void, scope: CaseScope) {
  const onChangeRef = useRef(onChange)
  useEffect(() => {
    onChangeRef.current = onChange
  })
  const state = useRef<WatchState>(initialWatchState)
  // 범위를 바꾸면 비교 기준도 바뀐다. 기준을 비워 다음 조회 결과를 새 기준으로 삼는다
  const scopeRef = useRef(scope)
  useEffect(() => {
    if (scopeRef.current === scope) return
    scopeRef.current = scope
    state.current = initialWatchState
  }, [scope])
  // 사용자 동작마다 1씩 올라 조회를 처음부터 다시 시작하고 제한 시간을 다시 센다
  const [generation, setGeneration] = useState(0)
  const [stoppedGeneration, setStoppedGeneration] = useState<number | null>(null)
  const [visible, setVisible] = useState(() => document.visibilityState === 'visible')

  /** 새로고침처럼 화면이 목록을 직접 다시 불러올 때 부른다. 기준 건수도 새로 받는다. */
  const restart = useCallback(() => {
    state.current = initialWatchState
    setGeneration((g) => g + 1)
  }, [])

  /** 화면의 목록 조회가 끝나면 부른다. 변화를 알린 뒤의 조회일 때만 의미가 있다. */
  const listLoaded = useCallback((ok: boolean) => {
    state.current = onListLoaded(state.current, ok)
  }, [])

  useEffect(() => {
    const onVisibility = () => {
      const now = document.visibilityState === 'visible'
      setVisible(now)
      // 탭 복귀는 사용자 동작이다. 기준 건수는 그대로 두어 숨어 있던 사이의 변화를 잡는다
      if (now) setGeneration((g) => g + 1)
    }
    document.addEventListener('visibilitychange', onVisibility)
    return () => document.removeEventListener('visibilitychange', onVisibility)
  }, [])

  useEffect(() => {
    if (!visible) return
    let alive = true
    let timer: number | undefined
    const from = Date.now()

    const check = async () => {
      try {
        const counts = await getCaseCounts()
        if (!alive) return
        applyCaseBadgeCounts(counts)
        const next = onCountsReceived(state.current, counts[scopeRef.current])
        state.current = next.state
        if (next.notify !== null) onChangeRef.current(next.notify)
      } catch {
        // 건수 조회는 보조 기능이다. 실패하면 다음 차례에 다시 본다. 오류 안내는 목록 조회가 맡는다
      }
    }
    const schedule = () => {
      timer = window.setTimeout(() => {
        if (!watchStep(from, Date.now()).poll) {
          setStoppedGeneration(generation)
          return
        }
        void check().then(() => {
          if (alive) schedule()
        })
      }, WATCH_MS)
    }

    void check().then(() => {
      if (alive) schedule()
    })
    return () => {
      alive = false
      window.clearTimeout(timer)
    }
  }, [visible, generation])

  return { stopped: stoppedGeneration === generation, restart, listLoaded }
}
