import { ReloadOutlined, SearchOutlined } from '@ant-design/icons'
import { Button, Card, Input, Segmented, Space, Table } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { type MouseEvent, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { PageHeader } from '../../components/common/PageHeader'
import { fdsAlerts, type FdsAlert, type FdsResult } from '../../demo/alertMockData'
import { useDemo } from '../../demo/DemoContext'
import { won } from '../../utils/format'
import { PaymentStatusTag, ResultTag } from './alert/AlertTags'

type Filter = 'NEEDS_ATTENTION' | FdsResult | 'ALL'

function StatCard({
  title,
  count,
  description,
  tone,
  onClick,
}: {
  title: string
  count: number
  description: string
  tone?: 'review' | 'deny' | 'error'
  onClick: () => void
}) {
  return (
    <button type="button" className="dw-panel dw-stat-card dw-stat-card-button" onClick={onClick}>
      <div className="dw-stat-label">{title}</div>
      <div className={`dw-stat-value ${tone ? `dw-alert-stat-value--${tone}` : ''}`}>
        {count}
        <span className="dw-alert-stat-value-unit">건</span>
      </div>
      <div className="dw-stat-foot">{description}</div>
    </button>
  )
}

export function AlertListPage() {
  const { merchant } = useDemo()
  const navigate = useNavigate()
  const [filter, setFilter] = useState<Filter>('NEEDS_ATTENTION')
  const [query, setQuery] = useState('')
  const [currentPage, setCurrentPage] = useState(1)
  const alerts = useMemo(() => fdsAlerts.filter((item) => item.merchant === merchant), [merchant])
  const count = (result?: FdsResult) =>
    result ? alerts.filter((item) => item.result === result).length : alerts.length
  const rows = useMemo(
    () =>
      alerts
        .filter((item) =>
          filter === 'NEEDS_ATTENTION'
            ? item.result !== 'ALLOW'
            : filter === 'ALL' || item.result === filter,
        )
        .filter((item) => {
          const q = query.trim().toLowerCase()
          return (
            !q ||
            [item.paymentId, item.orderId, item.customer].some((value) =>
              value.toLowerCase().includes(q),
            )
          )
        }),
    [alerts, filter, query],
  )
  const columns: ColumnsType<FdsAlert> = [
    { title: '평가 시각', dataIndex: 'evaluatedAt', width: 162 },
    {
      title: '결제 ID',
      dataIndex: 'paymentId',
      width: 166,
      render: (value: string) => (
        <Button
          type="link"
          className="dw-alert-payment-link"
          onClick={(event: MouseEvent<HTMLElement>) => {
            event.stopPropagation()
            navigate(`/security/alerts/${value}`)
          }}
        >
          {value}
        </Button>
      ),
    },
    { title: '주문번호', dataIndex: 'orderId', width: 160 },
    { title: '고객', dataIndex: 'customer', width: 115 },
    { title: '금액', dataIndex: 'amount', align: 'right', width: 120, render: won },
    {
      title: '위험 점수',
      dataIndex: 'score',
      align: 'right',
      width: 104,
      render: (value: number | null) => <strong>{value ?? '—'}</strong>,
    },
    {
      title: '판정',
      dataIndex: 'result',
      width: 100,
      render: (value: FdsResult) => <ResultTag result={value} />,
    },
    {
      title: '오류 코드',
      dataIndex: 'errorCode',
      width: 130,
      render: (value?: string) => (value ? <code>{value}</code> : '—'),
    },
    {
      title: '결제 상태',
      dataIndex: 'paymentStatus',
      width: 150,
      render: (value: FdsAlert['paymentStatus']) => <PaymentStatusTag status={value} />,
    },
    { title: 'Case', dataIndex: 'caseId', width: 130, render: (value?: string) => value ?? '—' },
  ]
  const selectFilter = (nextFilter: Filter) => {
    setFilter(nextFilter)
    setCurrentPage(1)
  }

  return (
    <>
      <PageHeader
        title="경보"
        description="FDS가 결제마다 평가한 위험 점수와 판정입니다. 기본으로 REVIEW · DENY · ERROR만 표시합니다."
        actions={<span className="dw-demo-label">LOCAL DEMO</span>}
      />
      <div className="dw-stat-grid">
        <StatCard
          title="REVIEW · 추가 조사"
          count={count('REVIEW')}
          tone="review"
          description="시연용 기준상 추가 확인 대상"
          onClick={() => selectFilter('REVIEW')}
        />
        <StatCard
          title="DENY · 결제 차단"
          count={count('DENY')}
          tone="deny"
          description="시연용 차단 판정"
          onClick={() => selectFilter('DENY')}
        />
        <StatCard
          title="ERROR · 평가 실패"
          count={count('ERROR')}
          tone="error"
          description="위험 판정이 아님 · 자동 ALLOW 금지"
          onClick={() => selectFilter('ERROR')}
        />
        <StatCard
          title="전체 평가"
          count={count()}
          description="ALLOW 포함"
          onClick={() => selectFilter('ALL')}
        />
      </div>
      <Card className="dw-table-card dw-alert-card">
        <div className="dw-table-toolbar">
          <div>
            <h3>평가 결과</h3>
            <p>행 또는 결제 ID를 누르면 평가 근거를 확인합니다.</p>
          </div>
          <Space>
            <Input
              aria-label="경보 검색"
              placeholder="결제 ID, 주문번호, 고객"
              prefix={<SearchOutlined />}
              value={query}
              onChange={(event) => {
                setQuery(event.target.value)
                setCurrentPage(1)
              }}
              allowClear
              className="dw-alert-search"
            />
            <Button
              icon={<ReloadOutlined />}
              onClick={() => {
                setQuery('')
                selectFilter('NEEDS_ATTENTION')
              }}
            >
              초기화
            </Button>
          </Space>
        </div>
        <div className="dw-alert-tabs">
          <Segmented
            value={filter}
            onChange={(value) => selectFilter(value as Filter)}
            options={[
              {
                value: 'NEEDS_ATTENTION',
                label: `확인 필요 ${alerts.filter((item) => item.result !== 'ALLOW').length}`,
              },
              { value: 'REVIEW', label: `REVIEW ${count('REVIEW')}` },
              { value: 'DENY', label: `DENY ${count('DENY')}` },
              { value: 'ERROR', label: `ERROR ${count('ERROR')}` },
              { value: 'ALL', label: `전체 ${count()}` },
            ]}
          />
        </div>
        <Table
          rowKey="evaluationId"
          columns={columns}
          dataSource={rows}
          size="middle"
          scroll={{ x: 1300 }}
          onRow={(record) => ({
            onClick: () => navigate(`/security/alerts/${record.paymentId}`),
            style: { cursor: 'pointer' },
          })}
          pagination={{
            current: currentPage,
            pageSize: 20,
            showSizeChanger: false,
            showTotal: (total) => `총 ${total}건`,
            onChange: (page) => setCurrentPage(page),
          }}
        />
      </Card>
      <div className="dw-api-note">
        점수와 규칙은 화면 검증용 임시 데이터입니다. 실제 FDS 기준·상태값·API 계약은 P2 정본 확정 후
        연결해야 합니다.
      </div>
    </>
  )
}
