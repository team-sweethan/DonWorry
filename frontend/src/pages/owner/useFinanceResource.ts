import { useEffect, useMemo, useState } from 'react'
import { koreanToday } from './financeView'
export type FinanceResource<T> =
  | { status: 'idle'; lastSuccess: string | null }
  // stale: 다시 조회하는 동안 화면을 비우지 않도록 넘기는 마지막 성공 데이터 (#803).
  // 다른 조건(기간 등)의 데이터일 수 있으므로 쓰는 쪽이 조건이 같은지 확인하고 표시한다.
  | { status: 'loading'; lastSuccess: string | null; stale?: T }
  | { status: 'error'; error: unknown; lastSuccess: string | null }
  | { status: 'success'; data: T; lastSuccess: string }
export function useFinanceResource<T>(
  key: string,
  load: (signal: AbortSignal) => Promise<T>,
  enabled = true,
): FinanceResource<T> {
  const request = useMemo(() => ({ key, load, enabled }), [key, load, enabled])
  const [state, setState] = useState<{
    request: typeof request
    value: FinanceResource<T>
    lastData?: T
  }>({
    request,
    value: { status: 'loading', lastSuccess: null },
  })
  useEffect(() => {
    if (!request.enabled) return
    const controller = new AbortController()
    void request
      .load(controller.signal)
      .then((data) => {
        if (!controller.signal.aborted)
          setState({
            request,
            value: { status: 'success', data, lastSuccess: new Date().toISOString() },
            lastData: data,
          })
      })
      .catch((error) => {
        if (!controller.signal.aborted)
          setState((previous) => ({
            request,
            value: { status: 'error', error, lastSuccess: previous.value.lastSuccess },
            lastData: previous.lastData,
          }))
      })
    return () => controller.abort()
  }, [request])
  return currentFinanceResource(state, request)
}
type FinanceResourceState<T, R> = { request: R; value: FinanceResource<T>; lastData?: T }
/** 지금 요청에 맞는 상태. 아직 응답이 없으면 마지막 성공 데이터를 stale로 붙여 로딩으로 본다. */
export function currentFinanceResource<T, R extends { enabled: boolean }>(
  state: FinanceResourceState<T, R>,
  request: R,
): FinanceResource<T> {
  if (!request.enabled) return { status: 'idle', lastSuccess: state.value.lastSuccess }
  return state.request === request
    ? state.value
    : { status: 'loading', lastSuccess: state.value.lastSuccess, stale: state.lastData }
}
export function useKoreanToday() {
  const [today, setToday] = useState(() => koreanToday())
  useEffect(() => {
    const refresh = () => setToday(koreanToday())
    const timer = setInterval(refresh, 30000)
    window.addEventListener('focus', refresh)
    return () => {
      clearInterval(timer)
      window.removeEventListener('focus', refresh)
    }
  }, [])
  return today
}
