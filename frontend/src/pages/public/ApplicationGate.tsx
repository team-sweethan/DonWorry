import type { ReactNode } from 'react'
import { Button, Result, Spin } from 'antd'
import { Link } from 'react-router-dom'
import { useApplicationConfig } from '../../hooks/useApplicationConfig'

export function ApplicationGate({ children }: { children: ReactNode }) {
  const config = useApplicationConfig()
  if (config.loading)
    return (
      <main className="dw-error-view" role="status">
        <Spin />
        <p>신청 가능 여부를 확인하고 있습니다.</p>
      </main>
    )
  if (!config.enabled)
    return (
      <Result
        status={config.error ? 'warning' : 'info'}
        title={
          config.error
            ? '신청 서비스에 연결할 수 없습니다'
            : '현재 신청 서비스를 이용할 수 없습니다'
        }
        subTitle={
          config.error
            ? '잠시 후 다시 확인해 주세요.'
            : '신청·이메일 확인·첫 비밀번호 설정이 일시 중지되어 있습니다.'
        }
        extra={
          <>
            {config.error && <Button onClick={config.retry}>다시 확인</Button>}{' '}
            <Link to="/">홈</Link> · <Link to="/login">로그인</Link>
          </>
        }
      />
    )
  return children
}
