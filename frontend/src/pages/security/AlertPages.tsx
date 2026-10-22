import { ArrowLeftOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons'
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Input,
  Segmented,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { type MouseEvent, useMemo, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { PageHeader } from '../../components/common/PageHeader'
import { ScreenResult } from '../../components/common/ScreenResult'
import { fdsAlerts, type FdsAlert, type FdsResult } from '../../demo/alertMockData'
import { useDemo } from '../../demo/DemoContext'
import { won } from '../../utils/format'

type Filter = 'NEEDS_ATTENTION' | FdsResult | 'ALL'
const resultStyle: Record<FdsResult, { background: string; color: string }> = {
  ALLOW: { background: '#DCFCE7', color: '#166534' },
  REVIEW: { background: '#FEF3C7', color: '#92400E' },
  DENY: { background: '#FEE2E2', color: '#991B1B' },
  ERROR: { background: '#F1F5F9', color: '#475569' },
}
const paymentStatusStyle: Record<
  FdsAlert['paymentStatus'],
  { label: string; background: string; color: string }
> = {
  PENDING: { label: '처리 대기 · PENDING', background: '#DBEAFE', color: '#1E40AF' },
  HELD: { label: '매입 보류 · HELD', background: '#FEF3C7', color: '#92400E' },
  DENIED: { label: '차단 · DENIED', background: '#FEE2E2', color: '#B91C1C' },
  DECLINED: { label: '승인 거절 · DECLINED', background: '#FEE2E2', color: '#B91C1C' },
  CAPTURED: { label: '매입 완료 · CAPTURED', background: '#DCFCE7', color: '#166534' },
  VOIDED: { label: '승인 취소 · VOIDED', background: '#F1F5F9', color: '#475569' },
  EXPIRED: { label: '승인 만료 · EXPIRED', background: '#F1F5F9', color: '#475569' },
}
function ResultTag({ result }: { result: FdsResult }) {
  const s = resultStyle[result]
  return (
    <Tag
      style={{
        ...s,
        border: 0,
        borderRadius: 99,
        padding: '3px 10px',
        fontSize: 12,
        fontWeight: 650,
        margin: 0,
      }}
    >
      {result}
    </Tag>
  )
}

function PaymentStatusTag({ status }: { status: FdsAlert['paymentStatus'] }) {
  const style = paymentStatusStyle[status]
  return (
    <Tag
      style={{
        background: style.background,
        color: style.color,
        border: 0,
        borderRadius: 99,
        padding: '3px 10px',
        fontSize: 12,
        fontWeight: 650,
        margin: 0,
      }}
    >
      {style.label}
    </Tag>
  )
}

function StatCard({
  title,
  count,
  description,
  color = '#0f172a',
  onClick,
}: {
  title: string
  count: number
  description: string
  color?: string
  onClick: () => void
}) {
  return (
    <button type="button" className="dw-panel dw-stat-card dw-stat-card-button" onClick={onClick}>
      <div className="dw-stat-label">{title}</div>
      <div className="dw-stat-value" style={{ color }}>
        {count}
        <span style={{ fontSize: 13, fontWeight: 500, marginLeft: 3 }}>건</span>
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
          style={{ padding: 0, fontWeight: 700 }}
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
          color="#B45309"
          description="시연용 기준상 추가 확인 대상"
          onClick={() => selectFilter('REVIEW')}
        />
        <StatCard
          title="DENY · 결제 차단"
          count={count('DENY')}
          color="#DC2626"
          description="시연용 차단 판정"
          onClick={() => selectFilter('DENY')}
        />
        <StatCard
          title="ERROR · 평가 실패"
          count={count('ERROR')}
          color="#475569"
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
      <Card className="dw-table-card" style={{ borderColor: '#E2E8F0' }}>
        <div className="dw-table-toolbar">
          <div>
            <h3>평가 결과</h3>
            <p>행 또는 결제 ID를 누르면 평가 근거를 확인합니다.</p>
          </div>
          <Space>
            <Input
              aria-label="경보 검색"
              placeholder="결제 ID, 주문번호, 고객"
              prefix={<SearchOutlined style={{ color: '#94a3b8' }} />}
              value={query}
              onChange={(event) => {
                setQuery(event.target.value)
                setCurrentPage(1)
              }}
              allowClear
              style={{ width: 260 }}
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

export function AlertDetailPage() {
  const { id } = useParams()
  const { merchant } = useDemo()
  const navigate = useNavigate()
  const alert = fdsAlerts.find((item) => item.paymentId === id && item.merchant === merchant)
  if (!alert) return <ScreenResult kind="404" />
  const score = alert.score
  return (
    <>
      <PageHeader
        title="경보 상세"
        description={`${alert.paymentId} · 주문 ${alert.orderId} · ${alert.merchant} · 고객 ${alert.customer}`}
        actions={
          <Space>
            {alert.caseId && (
              <Button onClick={() => navigate(`/security/cases/${alert.caseId}`)}>
                Case {alert.caseId} 보기
              </Button>
            )}
            <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/security/alerts')}>
              목록으로
            </Button>
          </Space>
        }
      />
      <div className="dw-section-grid">
        <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
          <Card
            title={<span className="dw-section-title">위험 점수</span>}
            extra={<ResultTag result={alert.result} />}
            style={{ borderColor: '#E2E8F0' }}
          >
            {score === null ? (
              <Alert
                type="warning"
                showIcon
                message="평가 불가 — 점수 없음"
                description={`오류 코드 ${alert.errorCode ?? '—'} · 위험 판정이 아니므로 자동 ALLOW 하지 않습니다.`}
              />
            ) : (
              <>
                <div className="dw-score-row">
                  <strong>{score}</strong>
                  <span>점</span>
                  <div className="dw-score-scale">
                    <div className="dw-score-allow" />
                    <div className="dw-score-review" />
                    <div className="dw-score-deny" />
                    <i style={{ left: `${Math.min(score, 99)}%` }} />
                  </div>
                </div>
                <div className="dw-score-labels">
                  <span>ALLOW</span>
                  <span>REVIEW</span>
                  <span>DENY</span>
                </div>
              </>
            )}
          </Card>
          <Card
            title={<span className="dw-section-title">위험 신호</span>}
            extra={
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                시연용 규칙
              </Typography.Text>
            }
            style={{ borderColor: '#E2E8F0' }}
          >
            <Table
              rowKey="name"
              size="small"
              pagination={false}
              columns={[
                {
                  title: '위험 신호',
                  dataIndex: 'name',
                  render: (value: string) => <strong>{value}</strong>,
                },
                {
                  title: '결과',
                  dataIndex: 'points',
                  render: (points: number) => (
                    <Tag color={points ? 'gold' : 'default'}>{points ? '적중' : '미적중'}</Tag>
                  ),
                },
                {
                  title: '점수',
                  dataIndex: 'points',
                  align: 'right',
                  render: (points: number) => <strong>{points ? `+${points}` : '0'}</strong>,
                },
                { title: '평가 당시 값', dataIndex: 'observed' },
                { title: '기준', dataIndex: 'threshold' },
              ]}
              dataSource={alert.signals}
            />
          </Card>
          <Card
            title={<span className="dw-section-title">같은 결제의 평가 이력</span>}
            extra={
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                시연 데이터
              </Typography.Text>
            }
            style={{ borderColor: '#E2E8F0' }}
          >
            <Table
              rowKey="evaluationId"
              size="small"
              pagination={false}
              columns={[
                { title: '평가 ID', dataIndex: 'evaluationId' },
                { title: '평가 시각', dataIndex: 'evaluatedAt' },
                {
                  title: '점수',
                  dataIndex: 'score',
                  align: 'right',
                  render: (value: number | null) => value ?? '—',
                },
                {
                  title: '판정',
                  dataIndex: 'result',
                  render: (value: FdsResult) => <ResultTag result={value} />,
                },
                {
                  title: '오류 코드',
                  dataIndex: 'errorCode',
                  render: (value?: string) => value ?? '—',
                },
              ]}
              dataSource={alert.history}
            />
          </Card>
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
          <Card
            title={<span className="dw-section-title">결제 처리 흐름</span>}
            extra={<span className="dw-demo-label">P5 조회 영역</span>}
            style={{ borderColor: '#E2E8F0' }}
          >
            <div className="dw-flow">
              <div>
                결제 접수 <span>{won(alert.amount)}</span>
              </div>
              <div>
                FDS {alert.result} <span>{score === null ? alert.errorCode : `${score}점`}</span>
              </div>
              <div>
                {alert.paymentStatus === 'HELD'
                  ? 'HELD · 매입 보류'
                  : `결제 상태 · ${alert.paymentStatus}`}
                <span>{alert.caseId ? `Case ${alert.caseId}` : '시연 상태'}</span>
              </div>
            </div>
          </Card>
          <Card
            title={<span className="dw-section-title">결제 정보</span>}
            style={{ borderColor: '#E2E8F0' }}
          >
            <Descriptions
              column={1}
              colon={false}
              labelStyle={{ color: '#64748b', width: 90 }}
              contentStyle={{ fontWeight: 600 }}
            >
              <Descriptions.Item label="결제 ID">{alert.paymentId}</Descriptions.Item>
              <Descriptions.Item label="주문">{alert.orderId}</Descriptions.Item>
              <Descriptions.Item label="고객">{alert.customer}</Descriptions.Item>
              <Descriptions.Item label="결제 금액">{won(alert.amount)}</Descriptions.Item>
              <Descriptions.Item label="현재 상태">
                <PaymentStatusTag status={alert.paymentStatus} />
              </Descriptions.Item>
            </Descriptions>
          </Card>
          <div className="dw-api-note">
            사장님 화면에는 FDS 점수와 위험 신호를 노출하지 않는 화면 구성 예시입니다.
          </div>
        </div>
      </div>
    </>
  )
}
