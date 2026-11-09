import { Alert, Button, Card, Descriptions, Modal, Pagination, Space, Table, Tabs, Tag } from 'antd'
import { useCallback, useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import {
  getFdsRuleChangeRequests,
  reviewFdsRuleChangeRequest,
  type FdsRuleChangeRequest,
  type FdsRuleChangeRequestPage,
} from '../../api/fds'
import { ListEmpty } from '../../components/common/ListEmpty'
import { PageHeader } from '../../components/common/PageHeader'
import { refreshRuleChangeRequestBadge } from '../../hooks/useRuleChangeRequestBadge'
import { displayRuleCode } from '../../utils/ruleDisplayCode'

const tabs: Array<{ key: FdsRuleChangeRequest['status']; label: string }> = [
  { key: 'PENDING', label: '승인 대기' },
  { key: 'APPROVED', label: '승인 완료' },
  { key: 'REJECTED', label: '반려' },
]

function RuleTypeTag({ ruleCode }: { ruleCode: string }) {
  const accountRule = ruleCode.startsWith('SEC_')
  return <Tag color={accountRule ? 'purple' : 'blue'}>{accountRule ? '계정 룰' : '결제 룰'}</Tag>
}

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
  const reload = useCallback(() => {
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
  }, [page, status])
  useEffect(() => {
    reload()
  }, [reload])
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
    <div className="dw-rule-management-page">
      <PageHeader
        title="룰 변경 요청"
        description="결제·계정 룰을 끄기 위한 요청을 검토하고 처리합니다."
      />
      {error && <Alert type="error" showIcon message={error} className="dw-page-alert" />}
      <Card className="dw-rule-records">
        <div className="dw-rule-records-heading">
          <div>
            <h2>룰 끄기 요청</h2>
            <p>요청 사유를 확인한 뒤 승인하거나 반려합니다.</p>
          </div>
          <span className="dw-rule-records-count">
            {result
              ? `${result.totalElements.toLocaleString('ko-KR')}건`
              : error
                ? '조회 실패'
                : '불러오는 중'}
          </span>
        </div>
        <Tabs
          activeKey={status}
          onChange={(key) => {
            setError(null)
            // 이전 탭의 행을 새 탭 결과처럼 잠깐 보여 주지 않는다.
            requestSequence.current += 1
            setResult(null)
            setStatus(key as FdsRuleChangeRequest['status'])
            setPage(0)
          }}
          items={tabs.map((tab) => ({ key: tab.key, label: tab.label }))}
        />
        <Table
          rowKey="requestId"
          pagination={false}
          scroll={{ x: 1080 }}
          loading={result === null && error === null}
          locale={{
            emptyText: (
              <ListEmpty
                title={
                  error
                    ? '요청 목록을 확인할 수 없습니다.'
                    : `${tabs.find((tab) => tab.key === status)?.label} 요청이 없습니다.`
                }
              />
            ),
          }}
          dataSource={result?.content ?? []}
          columns={[
            {
              title: '탐지 룰',
              dataIndex: 'ruleCode',
              width: 200,
              render: (value: string, request: FdsRuleChangeRequest) => (
                <div className="dw-rule-record-cell">
                  <strong>{displayRuleCode(value)}</strong>
                  <span>
                    <RuleTypeTag ruleCode={value} /> · 요청 #{request.requestId}
                  </span>
                </div>
              ),
            },
            {
              title: '요청자 · 시각',
              dataIndex: 'requestedByName',
              width: 170,
              render: (value: string, request: FdsRuleChangeRequest) => (
                <div className="dw-rule-record-cell">
                  <strong>{value}</strong>
                  <span>{formatDate(request.requestedAt)}</span>
                </div>
              ),
            },
            {
              title: '요청 사유',
              dataIndex: 'reason',
              width: 260,
              render: (value: string) => <div className="dw-rule-record-reason">{value}</div>,
            },
            {
              title: '상태',
              width: 110,
              dataIndex: 'status',
              render: (value: FdsRuleChangeRequest['status']) => <StatusTag status={value} />,
            },
            {
              title: '처리자 · 시각',
              dataIndex: 'reviewedByName',
              width: 170,
              render: (value: string | null, request: FdsRuleChangeRequest) => (
                <div className="dw-rule-record-cell">
                  <strong>{value ?? '—'}</strong>
                  {request.reviewedAt && <span>{formatDate(request.reviewedAt)}</span>}
                </div>
              ),
            },
            {
              title: '상세',
              width: 90,
              render: (_, request: FdsRuleChangeRequest) => (
                <Button
                  size="small"
                  onClick={(event) => {
                    event.stopPropagation()
                    setSelectedRequest(request)
                  }}
                >
                  요청 보기
                </Button>
              ),
            },
            {
              title: '처리',
              width: 150,
              render: (_, request: FdsRuleChangeRequest) =>
                status === 'PENDING' ? (
                  <Space size={6} wrap>
                    <Button
                      size="small"
                      type="primary"
                      loading={processingId === request.requestId}
                      onClick={(event) => {
                        event.stopPropagation()
                        void review(request.requestId, true)
                      }}
                    >
                      승인
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
                      반려
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
        <div className="dw-pagination-footer">
          <Pagination
            current={page + 1}
            pageSize={10}
            // Preserve the first-page pagination UI for an empty request tab.
            // A single effective item keeps previous/next disabled.
            total={Math.max(result?.totalElements ?? 0, 1)}
            showSizeChanger={false}
            hideOnSinglePage={false}
            showTotal={() => `총 ${result?.totalElements ?? 0}건`}
            onChange={(next) => setPage(next - 1)}
          />
        </div>
      </Card>
      <Modal
        title="룰 끄기 요청 상세"
        className="dw-rule-detail-modal"
        width={640}
        open={selectedRequest !== null}
        footer={<Button onClick={() => setSelectedRequest(null)}>닫기</Button>}
        onCancel={() => setSelectedRequest(null)}
      >
        {selectedRequest && (
          <Descriptions column={1} size="small">
            <Descriptions.Item label="요청 ID">{selectedRequest.requestId}</Descriptions.Item>
            <Descriptions.Item label="탐지 룰 코드">
              <Tag>{displayRuleCode(selectedRequest.ruleCode)}</Tag>
              <RuleTypeTag ruleCode={selectedRequest.ruleCode} />
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
    </div>
  )
}
