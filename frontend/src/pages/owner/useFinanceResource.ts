import { useEffect, useMemo, useState } from 'react'
import { koreanToday } from './financeView'
export type FinanceResource<T> =
  | { status: 'idle'; lastSuccess: string | null }
  | { status: 'loading'; lastSuccess: string | null }
  | { status: 'error'; error: unknown; lastSuccess: string | null }
  | { status: 'success'; data: T; lastSuccess: string }
export function useFinanceResource<T>(
  key: string,
  load: (signal: AbortSignal) => Promise<T>,
  enabled = true,
): FinanceResource<T> {
  const request = useMemo(() => ({ key, load, enabled }), [key, load, enabled])
  const [state, setState] = useState<{ request: typeof request; value: FinanceResource<T> }>({
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
          })
      })
      .catch((error) => {
        if (!controller.signal.aborted)
          setState((previous) => ({
            request,
            value: { status: 'error', error, lastSuccess: previous.value.lastSuccess },
          }))
      })
    return () => controller.abort()
  }, [request])
  if (!enabled) return { status: 'idle', lastSuccess: state.value.lastSuccess }
  return state.request === request
    ? state.value
    : { status: 'loading', lastSuccess: state.value.lastSuccess }
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
