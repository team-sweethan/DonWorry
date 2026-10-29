import { Alert, Button, Card, Descriptions, Modal, Pagination, Space, Table, Tabs, Tag } from 'antd'
import { useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
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
  { key: 'APPROVED', label: '승인 완료' },
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
  const labels = { PENDING: '승인 대기', APPROVED: '승인 완료', REJECTED: '반려' } as const
  const colors = { PENDING: 'gold', APPROVED: 'green', REJECTED: 'red' } as const
  return <Tag color={colors[status]}>{labels[status]}</Tag>
}

export function RuleChangeRequestsPage() {
  const [status, setStatus] = useState<FdsRuleChangeRequest['status']>('PENDING')
  const [page, setPage] = useState(0)
  const [result, setResult] = useState<FdsRuleChangeRequestPage | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [processingId, setProcessingId] = useState<number | null>(null)
  const [selectedRequest, setSelectedRequest] = useState<FdsRuleChangeRequest | null>(null)
  const requestSequence = useRef(0)
  // 관리자 홈에서 요청을 누르면 ?requestId=로 들어온다. 첫 목록에 있으면 상세를 바로 연다.
  const [searchParams] = useSearchParams()
  const linkedRequestId = useRef(Number(searchParams.get('requestId')))
  const reload = () => {
    const sequence = ++requestSequence.current
    getFdsRuleChangeRequests(status, page)
      .then((next) => {
        if (sequence !== requestSequence.current) return
        setResult(next)
        if (linkedRequestId.current) {
          const linked = next.content.find(
            (request) => request.requestId === linkedRequestId.current,
          )
          if (linked) setSelectedRequest(linked)
          linkedRequestId.current = 0
        }
      })
      .catch(() => {
        if (sequence === requestSequence.current) setError('룰 끄기 요청을 불러오지 못했습니다.')
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
      setError('룰 끄기 요청을 처리하지 못했습니다. 요청 상태를 확인한 뒤 다시 시도해 주세요.')
    } finally {
      setProcessingId(null)
    }
  }
  return (
    <>
      <PageHeader
        title="룰 끄기 요청"
        description="보안 직원의 탐지 룰 끄기 요청을 검토합니다. 승인하면 해당 룰이 중지되어 이상 거래 탐지에 사용되지 않습니다."
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
            {
              title: '탐지 룰 코드',
              dataIndex: 'ruleCode',
              render: (value: string) => <Tag>{value}</Tag>,
            },
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
                  <Space direction="vertical" size={6}>
                    <Button
                      size="small"
                      type="primary"
                      loading={processingId === request.requestId}
                      onClick={(event) => {
                        event.stopPropagation()
                        void review(request.requestId, true)
                      }}
                    >
                      룰 끄기 승인
                    </Button>
                    <Button
                      size="small"
                      danger
                      loading={processingId === request.requestId}
                      onClick={(event) => {
                        event.stopPropagation()
                        void review(request.requestId, false)
                      }}
                    >
                      요청 반려
                    </Button>
                  </Space>
                ) : (
                  '—'
                ),
            },
          ]}
          onRow={(request) => ({
            onClick: () => setSelectedRequest(request),
            style: { cursor: 'pointer' },
          })}
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
      <Modal
        title="룰 끄기 요청 상세"
        open={selectedRequest !== null}
        footer={<Button onClick={() => setSelectedRequest(null)}>닫기</Button>}
        onCancel={() => setSelectedRequest(null)}
      >
        {selectedRequest && (
          <Descriptions column={1} size="small">
            <Descriptions.Item label="요청 ID">{selectedRequest.requestId}</Descriptions.Item>
            <Descriptions.Item label="탐지 룰 코드">
              <Tag>{selectedRequest.ruleCode}</Tag>
            </Descriptions.Item>
            <Descriptions.Item label="요청자">{selectedRequest.requestedByName}</Descriptions.Item>
            <Descriptions.Item label="요청 시각">
              {formatDate(selectedRequest.requestedAt)}
            </Descriptions.Item>
            <Descriptions.Item label="상태">
              <StatusTag status={selectedRequest.status} />
            </Descriptions.Item>
            <Descriptions.Item label="요청 사유">
              <div style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
                {selectedRequest.reason}
              </div>
            </Descriptions.Item>
            {selectedRequest.reviewedByName && (
              <Descriptions.Item label="처리자">{selectedRequest.reviewedByName}</Descriptions.Item>
            )}
          </Descriptions>
        )}
      </Modal>
    </>
  )
}
