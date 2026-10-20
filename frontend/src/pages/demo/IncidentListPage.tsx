import { useMemo, useState } from 'react'
import { ArrowRightOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons'
import { Button, Card, Input, Select, Space, Table } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useNavigate } from 'react-router-dom'
import { PageHeader } from '../../components/common/PageHeader'
import { StatusBadge, type PaymentStatus } from '../../components/ui/StatusBadge'
import { useDemo } from '../../demo/DemoContext'
import { incidents, type Incident } from '../../demo/mockData'
import { won } from '../../utils/format'

export function IncidentListPage() {
  const navigate = useNavigate()
  const { merchant, rejectedOrderIds } = useDemo()
  const [search, setSearch] = useState('')
  const [status, setStatus] = useState<string>('all')
  const [tableKey, setTableKey] = useState(0)
  const records = useMemo(
    () =>
      incidents
        .filter((x) => x.merchant === merchant)
        .map((x) =>
          rejectedOrderIds.includes(x.orderId) ? { ...x, status: 'rejected' as PaymentStatus } : x,
        ),
    [merchant, rejectedOrderIds],
  )
  const filtered = useMemo(
    () =>
      records.filter((row) => {
        const matchQuery =
          !search ||
          [row.id, row.orderId, row.category].some((v) =>
            v.toLowerCase().includes(search.toLowerCase().trim()),
          )
        return matchQuery && (status === 'all' || row.status === status)
      }),
    [records, search, status],
  )
  const columns: ColumnsType<Incident> = [
    {
      title: '사건번호',
      dataIndex: 'id',
      width: 170,
      render: (id: string, row) => (
        <Button
          type="link"
          style={{ padding: 0, fontWeight: 700 }}
          onClick={() => navigate(`/security/incidents/${row.id}`)}
        >
          {id}
        </Button>
      ),
    },
    {
      title: '주문번호',
      dataIndex: 'orderId',
      width: 155,
      render: (v) => <span style={{ fontWeight: 600 }}>{v}</span>,
    },
    { title: '가맹점', dataIndex: 'merchant', width: 120 },
    { title: '탐지 사유', dataIndex: 'category' },
    { title: '거래 금액', dataIndex: 'amount', align: 'right', width: 130, render: won },
    { title: '발생 시각', dataIndex: 'occurredAt', width: 162 },
    {
      title: '처리 상태',
      dataIndex: 'status',
      width: 116,
      render: (v: PaymentStatus) => <StatusBadge status={v} />,
    },
    {
      title: '',
      key: 'action',
      width: 54,
      align: 'center',
      render: (_, row) => (
        <Button
          type="text"
          size="small"
          aria-label={`${row.id} 상세 보기`}
          icon={<ArrowRightOutlined />}
          onClick={() => navigate(`/security/incidents/${row.id}`)}
        />
      ),
    },
  ]
  const pending = records.filter((x) => x.status === 'review').length
  const processing = records.filter((x) => x.status === 'inProgress').length
  const completed = records.filter((x) => ['done', 'rejected'].includes(x.status)).length
  return (
    <>
      <PageHeader
        title="사건 목록"
        description="탐지된 의심 거래와 조치 현황을 확인합니다."
        actions={
          <>
            <span className="dw-demo-label">DEMO DATA</span>
            <Button
              icon={<ReloadOutlined />}
              onClick={() => {
                setSearch('')
                setStatus('all')
                setTableKey((x) => x + 1)
              }}
            >
              초기화
            </Button>
          </>
        }
      />
      <div className="dw-stat-grid">
        <StatCard title="전체 사건" value={records.length} foot="선택한 가맹점 기준" />
        <StatCard title="검토 대기" value={pending} foot="담당자 확인 필요" color="#B45309" />
        <StatCard title="검토 중" value={processing} foot="현재 진행 상태" color="#2563EB" />
        <StatCard
          title="처리 완료"
          value={completed}
          foot="검토 완료 · 반려 포함"
          color="#15803D"
        />
      </div>
      <Card className="dw-table-card" bordered style={{ borderColor: '#E2E8F0' }}>
        <div className="dw-table-toolbar">
          <div>
            <h3>사건 현황</h3>
            <p>사건번호 또는 주문번호를 클릭하면 상세 화면으로 이동합니다.</p>
          </div>
          <Space size={8}>
            <Input
              aria-label="사건 검색"
              placeholder="사건번호, 주문번호, 탐지 사유"
              prefix={<SearchOutlined style={{ color: '#94a3b8' }} />}
              style={{ width: 275 }}
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              allowClear
            />
            <Select
              aria-label="처리 상태 필터"
              style={{ width: 132 }}
              value={status}
              onChange={setStatus}
              options={[
                { value: 'all', label: '전체 상태' },
                { value: 'review', label: '검토 대기' },
                { value: 'inProgress', label: '검토 중' },
                { value: 'done', label: '처리 완료' },
                { value: 'rejected', label: '결제 반려' },
              ]}
            />
          </Space>
        </div>
        <Table
          rowKey="id"
          key={tableKey}
          columns={columns}
          dataSource={filtered}
          size="middle"
          scroll={{ x: 1150 }}
          locale={{
            emptyText: (
              <div style={{ padding: 36, color: '#64748b' }}>
                {search || status !== 'all'
                  ? '검색 조건에 맞는 사건이 없습니다.'
                  : '등록된 사건이 없습니다.'}
              </div>
            ),
          }}
          pagination={{
            pageSize: 20,
            showSizeChanger: false,
            showTotal: (total) => `총 ${total}건`,
            position: ['bottomRight'],
          }}
        />
      </Card>
      <div style={{ height: 18 }} />
      <div className="dw-api-note">
        이 페이지의 가맹점·사건·건수는 디자인 검증용 Mock 데이터입니다. 실제 서비스에서는 서버 API로
        연결해야 합니다.
      </div>
    </>
  )
}

function StatCard({
  title,
  value,
  foot,
  color = '#0F172A',
}: {
  title: string
  value: number
  foot: string
  color?: string
}) {
  return (
    <div className="dw-panel dw-stat-card">
      <div className="dw-stat-label">{title}</div>
      <div className="dw-stat-value" style={{ color }}>
        {value}
        <span style={{ fontSize: 13, fontWeight: 500, marginLeft: 3 }}>건</span>
      </div>
      <div className="dw-stat-foot">{foot}</div>
    </div>
  )
}
