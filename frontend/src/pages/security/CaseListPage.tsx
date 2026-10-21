import { useEffect, useMemo, useState } from 'react'
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons'
import { Button, Card, Input, Select, Space, Table, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useNavigate } from 'react-router-dom'
import { getCases } from '../../api/cases'
import { PageHeader } from '../../components/common/PageHeader'
import { useDemo } from '../../demo/DemoContext'
import type { CaseStatus, CaseSummary } from '../../types/case'
import { mergeRemainingMin, slaState } from '../../utils/caseRules'
import { won } from '../../utils/format'
import { caseStatusLabel, dateTime, reportStatusLabel } from './case/caseLabels'
import { CodeTag } from './case/CodeTag'

type StatusFilter = 'OPEN_ONLY' | 'ALL' | CaseStatus

const statusOptions: Array<{ value: StatusFilter; label: string }> = [
  { value: 'OPEN_ONLY', label: '열린 Case' },
  { value: 'ALL', label: '전체' },
  ...(Object.keys(caseStatusLabel) as CaseStatus[]).map((s) => ({
    value: s,
    label: caseStatusLabel[s].label,
  })),
]

export function CaseListPage() {
  const navigate = useNavigate()
  const { merchant } = useDemo()
  const [search, setSearch] = useState('')
  const [status, setStatus] = useState<StatusFilter>('OPEN_ONLY')
  const [refreshKey, setRefreshKey] = useState(0)
  // 어떤 요청(가맹점·새로고침)에 대한 응답인지 함께 저장해 늦게 온 응답이 덮어쓰지 않게 한다.
  const [result, setResult] = useState<{
    merchant: string
    refreshKey: number
    cases: CaseSummary[]
  } | null>(null)

  useEffect(() => {
    let alive = true
    getCases(merchant).then((cases) => {
      if (alive) setResult({ merchant, refreshKey, cases })
    })
    return () => {
      alive = false
    }
  }, [merchant, refreshKey])
  const loading = result?.merchant !== merchant || result.refreshKey !== refreshKey
  const cases = useMemo(() => result?.cases ?? [], [result])

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase()
    return cases.filter((c) => {
      const matchStatus =
        status === 'ALL' || (status === 'OPEN_ONLY' ? c.status !== 'CLOSED' : c.status === status)
      const matchQuery = !q || [c.caseId, c.customerRef].some((v) => v.toLowerCase().includes(q))
      return matchStatus && matchQuery
    })
  }, [cases, search, status])

  const open = cases.filter((c) => c.status !== 'CLOSED')
  const columns: ColumnsType<CaseSummary> = [
    {
      title: 'Case ID',
      dataIndex: 'caseId',
      width: 120,
      render: (id: string) => (
        <Button
          type="link"
          style={{ padding: 0, fontWeight: 700 }}
          onClick={() => navigate(`/security/cases/${id}`)}
        >
          {id}
        </Button>
      ),
    },
    {
      title: '상태',
      dataIndex: 'status',
      width: 110,
      render: (s: CaseStatus) => <CodeTag value={caseStatusLabel[s]} />,
    },
    { title: '고객', dataIndex: 'customerRef', width: 120 },
    {
      title: '위험도',
      key: 'risk',
      width: 120,
      sorter: (a, b) => (a.maxRiskScore ?? -1) - (b.maxRiskScore ?? -1),
      defaultSortOrder: 'descend',
      render: (_, c) => (
        <span>
          <strong>{c.maxRiskScore ?? '-'}점</strong>
          <span style={{ color: '#64748b' }}> · {c.paymentCount}건</span>
        </span>
      ),
    },
    {
      title: '총액',
      dataIndex: 'totalAmount',
      align: 'right',
      width: 120,
      render: won,
    },
    {
      title: '생성 시각',
      dataIndex: 'createdAt',
      width: 150,
      sorter: (a, b) => Date.parse(a.createdAt) - Date.parse(b.createdAt),
      render: dateTime,
    },
    {
      title: '병합 마감',
      key: 'merge',
      width: 100,
      render: (_, c) => {
        const remaining = mergeRemainingMin(c.createdAt, c.decidedCount)
        return remaining === null ? (
          <span style={{ color: '#94a3b8' }}>마감</span>
        ) : (
          <span style={{ color: '#2563eb', fontWeight: 600 }}>{remaining}분 남음</span>
        )
      },
    },
    {
      title: 'SLA',
      key: 'sla',
      width: 110,
      render: (_, c) => {
        const sla = slaState(c.createdAt, c.status)
        if (!sla) return <span style={{ color: '#94a3b8' }}>-</span>
        return sla.exceeded ? (
          <span style={{ color: '#dc2626', fontWeight: 700 }}>{sla.hours}시간 초과</span>
        ) : (
          <span>{sla.hours}시간 남음</span>
        )
      },
    },
    {
      title: 'AI 보고서',
      dataIndex: 'latestReportStatus',
      width: 110,
      render: (s: CaseSummary['latestReportStatus']) =>
        s ? <CodeTag value={reportStatusLabel[s]} /> : '-',
    },
  ]

  return (
    <>
      <PageHeader
        title="Case 목록"
        description="HELD 결제로 생성된 조사 Case를 확인하고 판정할 Case를 고릅니다."
        actions={
          <>
            <span className="dw-demo-label">DEMO DATA</span>
            <Button icon={<ReloadOutlined />} onClick={() => setRefreshKey((k) => k + 1)}>
              새로고침
            </Button>
          </>
        }
      />
      <div className="dw-stat-grid">
        <StatCard title="열린 Case" value={open.length} foot="종료 전 Case" />
        <StatCard
          title="판정 대기"
          value={open.filter((c) => c.status === 'DECISION_PENDING').length}
          foot="직원 판정 필요"
          color="#B45309"
        />
        <StatCard
          title="인계 실패"
          value={open.filter((c) => c.status === 'ACTION_FAILED').length}
          foot="금융 조치 인계 확인 필요"
          color="#B91C1C"
        />
        <StatCard
          title="AI 보고서 실패"
          value={open.filter((c) => c.latestReportStatus === 'FAILED').length}
          foot="수동 조사·판정 필요"
          color="#B91C1C"
        />
      </div>
      <Card className="dw-table-card" style={{ borderColor: '#E2E8F0' }}>
        <div className="dw-table-toolbar">
          <div>
            <h3>Case 현황</h3>
            <p>위험도는 소속 결제의 FDS 최고 점수와 결제 건수입니다.</p>
          </div>
          <Space size={8}>
            <Input
              aria-label="Case 검색"
              placeholder="Case ID, 고객"
              prefix={<SearchOutlined style={{ color: '#94a3b8' }} />}
              style={{ width: 220 }}
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              allowClear
            />
            <Select
              aria-label="Case 상태 필터"
              style={{ width: 130 }}
              value={status}
              onChange={setStatus}
              options={statusOptions}
            />
          </Space>
        </div>
        <Table
          rowKey="caseId"
          columns={columns}
          dataSource={filtered}
          loading={loading}
          size="middle"
          scroll={{ x: 1060 }}
          locale={{
            emptyText: (
              <Typography.Text type="secondary" style={{ display: 'block', padding: 36 }}>
                {search || status !== 'OPEN_ONLY'
                  ? '조건에 맞는 Case가 없습니다.'
                  : '열린 Case가 없습니다.'}
              </Typography.Text>
            ),
          }}
          pagination={{
            pageSize: 20,
            showSizeChanger: false,
            showTotal: (total) => `총 ${total}건`,
          }}
        />
      </Card>
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
