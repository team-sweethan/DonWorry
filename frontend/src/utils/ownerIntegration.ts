import { ApiError } from '../api/client.ts'
import { getIntegration, type IntegrationInfo } from '../api/integration.ts'

export type OwnerIntegrationState =
  | { kind: 'loading' }
  | { kind: 'ready'; data: IntegrationInfo; refreshing: boolean }
  | { kind: 'error'; inactive: boolean }

/**
 * 새 조회·계정 변경 뒤 늦게 도착한 응답으로 연동 상태를 되돌리지 않는다.
 * 재조회 중에는 마지막 상태를 그대로 보여 주고, 실패하면 낡은 상태 대신 오류를 보여 준다.
 */
export function createOwnerIntegrationStore(
  load = (signal: AbortSignal) => getIntegration(undefined, 0, signal),
) {
  let state: OwnerIntegrationState = { kind: 'loading' }
  let request: AbortController | null = null
  const listeners = new Set<() => void>()
  const update = (next: OwnerIntegrationState) => {
    state = next
    listeners.forEach((listener) => listener())
  }
  const reset = () => {
    request?.abort()
    request = null
  }
  return {
    getSnapshot: () => state,
    subscribe: (listener: () => void) => {
      listeners.add(listener)
      return () => {
        listeners.delete(listener)
      }
    },
    reset,
    refresh: async () => {
      reset()
      const current = new AbortController()
      request = current
      update(state.kind === 'ready' ? { ...state, refreshing: true } : { kind: 'loading' })
      try {
        const data = await load(current.signal)
        if (request === current && !current.signal.aborted)
          update({ kind: 'ready', data, refreshing: false })
      } catch (error) {
        if (request === current && !current.signal.aborted)
          update({
            kind: 'error',
            inactive: error instanceof ApiError && error.code === 'INTEGRATION_005',
          })
      }
    },
  }
}
