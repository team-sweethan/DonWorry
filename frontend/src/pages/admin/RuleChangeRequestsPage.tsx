import { Alert, Button, Card, Pagination, Space, Table, Tabs, Tag } from 'antd'
import { useEffect, useRef, useState } from 'react'
import {
  getFdsRuleChangeRequests,
  reviewFdsRuleChangeRequest,
  type FdsRuleChangeRequest,
  type FdsRuleChangeRequestPage,
} from '../../api/fds'
import { PageHeader } from '../../components/common/PageHeader'
import { refreshRuleChangeRequestBadge } from '../../hooks/useRuleChangeRequestBadge'

const tabs: Array<{ key: FdsRuleChangeRequest['status']; label: string }> = [
  { key: 'PENDING', label: '승인 대기' },
  { key: 'APPROVED', label: '승인' },
  { key: 'REJECTED', label: '반려' },
]

function formatDate(value: string | null) {
  return value
    ? new Intl.DateTimeFormat('ko-KR', { dateStyle: 'short', timeStyle: 'short' }).format(
        new Date(value),
      )
    : '—'
}

function StatusTag({ status }: { status: FdsRuleChangeRequest['status'] }) {
  const labels = { PENDING: '승인 대기', APPROVED: '승인', REJECTED: '반려' } as const
  const colors = { PENDING: 'gold', APPROVED: 'green', REJECTED: 'red' } as const
  return <Tag color={colors[status]}>{labels[status]}</Tag>
}

export function RuleChangeRequestsPage() {
  const [status, setStatus] = useState<FdsRuleChangeRequest['status']>('PENDING')
  const [page, setPage] = useState(0)
  const [result, setResult] = useState<FdsRuleChangeRequestPage | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [processingId, setProcessingId] = useState<number | null>(null)
  const requestSequence = useRef(0)
  const reload = () => {
    const sequence = ++requestSequence.current
    getFdsRuleChangeRequests(status, page)
      .then((next) => {
        if (sequence === requestSequence.current) setResult(next)
      })
      .catch(() => {
        if (sequence === requestSequence.current) setError('룰 변경 요청을 불러오지 못했습니다.')
      })
  }
  useEffect(reload, [status, page])
  const review = async (requestId: number, approve: boolean) => {
    setProcessingId(requestId)
    try {
      await reviewFdsRuleChangeRequest(requestId, approve)
      reload()
      refreshRuleChangeRequestBadge()
    } catch {
      setError('요청을 처리하지 못했습니다.')
    } finally {
      setProcessingId(null)
    }
  }
  return (
    <>
      <PageHeader
        title="룰 변경 요청함"
        description="보안 직원의 룰 끄기 요청을 승인하거나 반려합니다."
      />
      {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 16 }} />}
      <Card>
        <Tabs
          activeKey={status}
          onChange={(key) => {
            setError(null)
            setStatus(key as FdsRuleChangeRequest['status'])
            setPage(0)
          }}
          items={tabs.map((tab) => ({ key: tab.key, label: tab.label }))}
        />
        <Table
          rowKey="requestId"
          size="small"
          pagination={false}
          dataSource={result?.content ?? []}
          columns={[
            { title: '요청 ID', dataIndex: 'requestId', width: 100 },
            { title: '룰', dataIndex: 'ruleCode', render: (value: string) => <Tag>{value}</Tag> },
            { title: '요청자', dataIndex: 'requestedByName' },
            { title: '요청 사유', dataIndex: 'reason', ellipsis: true },
            { title: '요청 시각', dataIndex: 'requestedAt', render: formatDate },
            {
              title: '상태',
              dataIndex: 'status',
              render: (value: FdsRuleChangeRequest['status']) => <StatusTag status={value} />,
            },
            {
              title: '처리자',
              dataIndex: 'reviewedByName',
              render: (value: string | null) => value ?? '—',
            },
            { title: '처리 시각', dataIndex: 'reviewedAt', render: formatDate },
            {
              title: '처리',
              render: (_, request: FdsRuleChangeRequest) =>
                status === 'PENDING' ? (
                  <Space>
                    <Button
                      size="small"
                      type="primary"
                      loading={processingId === request.requestId}
                      onClick={() => void review(request.requestId, true)}
                    >
                      승인
                    </Button>
                    <Button
                      size="small"
                      danger
                      loading={processingId === request.requestId}
                      onClick={() => void review(request.requestId, false)}
                    >
                      반려
                    </Button>
                  </Space>
                ) : (
                  '—'
                ),
            },
          ]}
        />
        <Pagination
          current={page + 1}
          pageSize={10}
          total={result?.totalElements ?? 0}
          showSizeChanger={false}
          style={{ marginTop: 16, textAlign: 'right' }}
          onChange={(next) => setPage(next - 1)}
        />
      </Card>
    </>
  )
}
