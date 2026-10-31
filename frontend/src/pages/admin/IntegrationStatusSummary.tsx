import { useEffect, useState } from 'react'
import { Alert, Button, Card, Space, Spin, Tag } from 'antd'
import { getIntegration, integrationLabels, type IntegrationInfo } from '../../api/integration'

export function IntegrationStatusSummary({ merchantId }: { merchantId: number }) {
  const [revision, setRevision] = useState(0)
  const [data, setData] = useState<IntegrationInfo | null>(null)
  const [error, setError] = useState(false)
  useEffect(() => {
    const controller = new AbortController()
    getIntegration(merchantId, 0, controller.signal)
      .then((value) => {
        if (!controller.signal.aborted) setData(value)
      })
      .catch(() => {
        if (!controller.signal.aborted) setError(true)
      })
    return () => controller.abort()
  }, [merchantId, revision])
  return (
    <Card
      title="쇼핑몰 연동 상태"
      extra={
        <Button
          onClick={() => {
            setData(null)
            setError(false)
            setRevision((value) => value + 1)
          }}
        >
          새로고침
        </Button>
      }
    >
      {error ? (
        <Alert
          type="warning"
          showIcon
          message="연동 상태를 확인하지 못했습니다. 다시 조회해 주세요."
        />
      ) : data ? (
        <Space wrap>
          <Tag color={data.status === 'CONNECTED' ? 'success' : 'default'}>
            {integrationLabels[data.status]}
          </Tag>
          <span>가맹점 코드: {data.merchantCode}</span>
          <span>연결 확인 (UTC): {data.connectedAt?.replace('T', ' ') ?? '기록 없음'}</span>
        </Space>
      ) : (
        <Spin />
      )}
    </Card>
  )
}
