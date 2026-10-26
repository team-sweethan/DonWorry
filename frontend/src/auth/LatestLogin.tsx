import { useEffect, useState } from 'react'
import { Button } from 'antd'
import { getLatestLogin } from '../api/loginHistories'
import type { LoginHistory } from '../api/loginHistories'
import { emptyLoginMessage, loginTime } from '../utils/loginHistory'

export function LatestLogin() {
  const [revision, setRevision] = useState(0)
  // 단일 세션 정책상 로그인 중에는 값이 바뀌지 않는다. 화면 이동마다 다시 조회하지 않는다.
  const key = String(revision)
  const [result, setResult] = useState<{
    key: string
    value?: LoginHistory | null
    failed?: boolean
  } | null>(null)
  useEffect(() => {
    const controller = new AbortController()
    let alive = true
    getLatestLogin(controller.signal)
      .then((data) => {
        if (alive) setResult({ key, value: data.latestSuccess })
      })
      .catch(() => {
        if (alive) setResult({ key, failed: true })
      })
    return () => {
      alive = false
      controller.abort()
    }
  }, [key])
  const current = result?.key === key ? result : null
  return (
    <span className="dw-last-login" role="status">
      {!current ? (
        '로그인 기록 확인 중…'
      ) : current.failed ? (
        <>
          로그인 기록 조회 실패{' '}
          <Button size="small" onClick={() => setRevision((r) => r + 1)}>
            다시 시도
          </Button>
        </>
      ) : current.value ? (
        `마지막 로그인: ${loginTime(current.value.occurredAt, true)} (${current.value.clientIp ?? '확인 불가'})`
      ) : (
        emptyLoginMessage
      )}
    </span>
  )
}
