import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError } from '../../api/client'
import { getMerchantCase } from '../../api/merchantCases'

import { currentMerchantCaseDetail } from './merchantCaseView'
import type { CaseDetailEntry, CaseDetailState } from './merchantCaseView'
export type { CaseDetailState } from './merchantCaseView'

/**
 * 펼친 사건들의 상세를 한곳에서 부른다. 진행 중 사건은 처음부터 펼쳐져 있어 맨 위 합계 문장에도 쓴다.
 * 접힌 사건은 부르지 않는다. 새로고침(refreshKey)이나 사건별 다시 시도 때만 다시 부른다.
 */
export function useMerchantCaseDetails(ids: string[], refreshKey: number) {
  const [entries, setEntries] = useState<Record<string, CaseDetailEntry>>({})
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
      return currentMerchantCaseDetail(entry, key)
    },
    [entries, refreshKey, retries],
  )
  const retry = useCallback(
    (id: string) => setRetries((prev) => ({ ...prev, [id]: (prev[id] ?? 0) + 1 })),
    [],
  )
  return { get, retry }
}
