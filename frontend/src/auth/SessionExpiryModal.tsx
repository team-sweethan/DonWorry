import { useEffect, useState, useSyncExternalStore } from 'react'
import { Alert, Button, Modal, Space, Typography } from 'antd'
import { useNavigate } from 'react-router-dom'
import { formatRemaining, sessionActivity, sessionTime } from '../api/sessionActivity'
import { useAuth } from './useAuth'

export function SessionExpiryModal() {
  const { store, loggingOut } = useAuth()
  const activity = useSyncExternalStore(sessionActivity.subscribe, sessionActivity.getSnapshot)
  const [now, setNow] = useState(Date.now)
  const [renewing, setRenewing] = useState(false)
  const [error, setError] = useState<{ at: number | undefined; message: string } | null>(null)
  const navigate = useNavigate()
  useEffect(() => {
    const tick = () => {
      store.expireIfInactive()
      setNow(Date.now())
    }
    tick()
    const timer = window.setInterval(tick, 1000)
    window.addEventListener('focus', tick)
    document.addEventListener('visibilitychange', tick)
    return () => {
      window.clearInterval(timer)
      window.removeEventListener('focus', tick)
      document.removeEventListener('visibilitychange', tick)
    }
  }, [store])
  const { remaining, warning } = sessionTime(activity?.at ?? now, now)
  return (
    <Modal
      open={
        !!activity &&
        (warning || ((renewing || sessionActivity.hasPendingRequests()) && remaining === 0))
      }
      title="로그인 유지 시간이 곧 만료됩니다"
      closable={false}
      maskClosable={false}
      keyboard={false}
      footer={
        <Space>
          <Button
            disabled={renewing}
            loading={loggingOut}
            onClick={async () => {
              setError(null)
              try {
                await store.logout()
                navigate('/login', { replace: true })
              } catch {
                setError({
                  at: activity?.at,
                  message: '로그아웃하지 못했습니다. 다시 시도해 주세요.',
                })
              }
            }}
          >
            로그아웃
          </Button>
          <Button
            type="primary"
            loading={renewing}
            disabled={loggingOut}
            onClick={async () => {
              setError(null)
              setRenewing(true)
              try {
                await store.renew()
              } catch {
                setError({
                  at: activity?.at,
                  message: '로그인을 연장하지 못했습니다. 다시 시도해 주세요.',
                })
              } finally {
                setRenewing(false)
              }
            }}
          >
            연장
          </Button>
        </Space>
      }
    >
      <Typography.Paragraph>계속 이용하시려면 로그인 시간을 연장해 주세요.</Typography.Paragraph>
      <Typography.Paragraph>남은 시간: {formatRemaining(remaining)}</Typography.Paragraph>
      {error && error.at === activity?.at && (
        <Alert type="error" showIcon message={error.message} />
      )}
    </Modal>
  )
}
