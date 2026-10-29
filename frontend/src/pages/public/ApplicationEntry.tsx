import { Button } from 'antd'
import { useNavigate } from 'react-router-dom'
import { useApplicationConfig } from '../../hooks/useApplicationConfig'

export function ApplicationEntry({ large = false }: { large?: boolean }) {
  const config = useApplicationConfig()
  const navigate = useNavigate()
  return (
    <span>
      <Button
        type={large ? 'primary' : 'link'}
        size={large ? 'large' : 'middle'}
        disabled={!config.enabled}
        onClick={() => navigate('/apply')}
      >
        {config.loading
          ? '신청 확인 중'
          : config.error
            ? '신청 연결 확인 불가'
            : config.enabled
              ? '이용 신청'
              : '신청 준비 중'}
      </Button>
      {config.error && (
        <Button type="link" onClick={config.retry}>
          다시 확인
        </Button>
      )}
    </span>
  )
}
