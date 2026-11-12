import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError } from '../../api/client'
import { getMerchantCase } from '../../api/merchantCases'
import type { MerchantCaseDetail } from '../../types/merchantCase'

type Entry = {
  /** 어떤 요청(새로고침 · 다시 시도)에 대한 응답인지 */
  key: string
  detail: MerchantCaseDetail | null | 'error' | 'forbidden'
  /** 다시 불러오기에 실패해 직전 상세를 계속 보여주는 중 */
  failed: boolean
}

export type CaseDetailState = {
  /** 보여줄 상세. 다시 부르는 중이거나 실패했어도 직전 상세가 있으면 남긴다 (#803) */
  detail: MerchantCaseDetail | null
  status: 'loading' | 'ready' | 'missing' | 'error' | 'forbidden'
  /** 직전 새로고침의 상세를 보여주는 중 */
  stale: boolean
  failed: boolean
}

/**
 * 펼친 사건들의 상세를 한곳에서 부른다. 진행 중 사건은 처음부터 펼쳐져 있어 맨 위 합계 문장에도 쓴다.
 * 접힌 사건은 부르지 않는다. 새로고침(refreshKey)이나 사건별 다시 시도 때만 다시 부른다.
 */
export function useMerchantCaseDetails(ids: string[], refreshKey: number) {
  const [entries, setEntries] = useState<Record<string, Entry>>({})
  const [retries, setRetries] = useState<Record<string, number>>({})
  const requested = useRef<Record<string, string>>({})
  const idsKey = ids.join(',')

  useEffect(() => {
    const wanted = idsKey ? idsKey.split(',') : []
    for (const id of wanted) {
      const key = `${refreshKey}/${retries[id] ?? 0}`
      if (requested.current[id] === key) continue
      requested.current[id] = key
      // 늦게 온 이전 응답이 새 응답을 덮지 않도록 지금 요청한 key와 같을 때만 반영한다
      const current = () => requested.current[id] === key
      getMerchantCase(id)
        .then((detail) => {
          if (current()) setEntries((prev) => ({ ...prev, [id]: { key, detail, failed: false } }))
        })
        .catch((error: unknown) => {
          if (!current()) return
          const forbidden = error instanceof ApiError && error.status === 403
          setEntries((prev) => {
            const last = prev[id]?.detail
            return {
              ...prev,
              [id]:
                !forbidden && last && typeof last === 'object'
                  ? { key, detail: last, failed: true }
                  : { key, detail: forbidden ? 'forbidden' : 'error', failed: true },
            }
          })
        })
    }
  }, [idsKey, refreshKey, retries])

  const get = useCallback(
    (id: string): CaseDetailState => {
      const entry = entries[id]
      const key = `${refreshKey}/${retries[id] ?? 0}`
      const fresh = entry?.key === key
      const detail = entry?.detail && typeof entry.detail === 'object' ? entry.detail : null
      if (!entry) return { detail: null, status: 'loading', stale: false, failed: false }
      if (entry.detail === null)
        return { detail: null, status: fresh ? 'missing' : 'loading', stale: false, failed: false }
      if (entry.detail === 'forbidden')
        return { detail: null, status: 'forbidden', stale: false, failed: true }
      if (entry.detail === 'error')
        return { detail: null, status: fresh ? 'error' : 'loading', stale: false, failed: true }
      return { detail, status: 'ready', stale: !fresh, failed: fresh && entry.failed }
    },
    [entries, refreshKey, retries],
  )
  const retry = useCallback(
    (id: string) => setRetries((prev) => ({ ...prev, [id]: (prev[id] ?? 0) + 1 })),
    [],
  )
  return { get, retry }
}
