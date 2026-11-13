import { Button, Space } from 'antd'
import { KeyOutlined } from '@ant-design/icons'
import type { IntegrationStatus, KeyAction } from '../../api/integration'

export function IntegrationKeyActions({
  status,
  disabled,
  onAction,
}: {
  status: IntegrationStatus
  disabled: boolean
  onAction: (action: KeyAction) => void
}) {
  return (
    <Space wrap className="owner-integration-key-actions">
      <Button
        type={status === 'NO_KEY' ? 'primary' : 'default'}
        icon={<KeyOutlined />}
        disabled={disabled || status !== 'NO_KEY'}
        onClick={() => onAction('issue')}
      >
        시크릿 키 발급
      </Button>
      <Button
        type={status === 'PENDING_CONNECTION' ? 'primary' : 'default'}
        disabled={disabled || status !== 'PENDING_CONNECTION'}
        onClick={() => onAction('reveal')}
      >
        키 다시 보기
      </Button>
      <Button danger disabled={disabled || status === 'NO_KEY'} onClick={() => onAction('reissue')}>
        시크릿 키 재발급
      </Button>
    </Space>
  )
}
