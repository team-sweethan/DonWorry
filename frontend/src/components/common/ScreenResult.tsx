import { Button, Result } from 'antd'
import type { ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { roles } from '../../config/navigation'
import { useDemo } from '../../demo/DemoContext'

export function ScreenResult({
  kind,
  homePath,
  action,
}: {
  kind: '403' | '404' | '500'
  homePath?: string
  // 홈으로 가도 같은 화면이 나오는 경우 기본 버튼 대신 쓴다.
  action?: ReactNode
}) {
  const navigate = useNavigate()
  const { role } = useDemo()
  const heading =
    kind === '403'
      ? '접근 권한이 없습니다'
      : kind === '404'
        ? '페이지를 찾을 수 없습니다'
        : '일시적인 문제가 발생했어요'
  const sub =
    kind === '403'
      ? '현재 역할로는 이 페이지를 볼 수 없습니다.'
      : kind === '404'
        ? '주소를 확인하거나 홈으로 돌아가 주세요.'
        : '잠시 후 다시 시도해 주세요.'
  return (
    <div className="dw-error-view">
      <Result
        status={kind === '403' ? '403' : kind === '404' ? '404' : '500'}
        title={heading}
        subTitle={sub}
        extra={
          action ?? (
            <Button
              type="primary"
              onClick={() =>
                kind === '500'
                  ? window.location.reload()
                  : navigate(homePath ?? roles[role].landing)
              }
            >
              {kind === '500' ? '다시 시도' : '내 홈으로 이동'}
            </Button>
          )
        }
      />
    </div>
  )
}
