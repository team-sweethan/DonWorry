import { ArrowRightOutlined } from '@ant-design/icons'
import { Link } from 'react-router-dom'
import type { useApplicationConfig } from '../../hooks/useApplicationConfig'

type ApplicationConfig = ReturnType<typeof useApplicationConfig>
type Variant = 'primary' | 'small' | 'inverse' | 'link'

// 신청 기능이 꺼져 있거나 확인 중이면 이동하지 않고 상태를 보여 준다.
// 한 화면에 진입점이 여러 개라 설정 조회는 화면에서 한 번 하고 결과를 넘겨받는다.
export function ApplicationEntry({
  config,
  variant = 'primary',
}: {
  config: ApplicationConfig
  variant?: Variant
}) {
  const className = variant === 'link' ? 'dw-apply-link' : `dw-btn dw-btn-${variant}`
  if (config.enabled) {
    return (
      <Link className={className} to="/apply" viewTransition>
        이용 신청하기
        {variant !== 'small' && <ArrowRightOutlined aria-hidden="true" />}
      </Link>
    )
  }
  const label = config.loading
    ? '신청 확인 중'
    : config.error
      ? '신청 연결 확인 불가'
      : '신청 준비 중'
  return (
    <span className="dw-apply-state">
      <span className={`${className} is-disabled`} aria-disabled="true" role="link">
        {label}
      </span>
      {config.error && (
        <button type="button" className="dw-apply-retry" onClick={config.retry}>
          다시 확인
        </button>
      )}
    </span>
  )
}
