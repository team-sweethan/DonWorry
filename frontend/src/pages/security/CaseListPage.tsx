import { type MouseEvent, useEffect, useMemo, useState } from 'react'
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons'
import { Alert, App, Button, Card, Input, Select, Space, Table, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { demoCreateHeldPayment, getCases } from '../../api/cases'
import { useFeedback } from '../../components/common/Feedback'
import { PageHeader } from '../../components/common/PageHeader'
import { useDemo } from '../../demo/DemoContext'
import type { CaseStatus, CaseSummary } from '../../types/case'
import { mergeRemainingMin, slaState } from '../../utils/caseRules'
import {
  caseListSearch,
  matchesCaseListQuery,
  parseCaseListQuery,
  type CaseListQuery,
  type CaseListReturnState,
  type ReportFilter,
  type StatusFilter,
} from '../../utils/caseListQuery'
import { won } from '../../utils/format'
import { caseStatusLabel, dateTime, reportStatusLabel } from './case/caseLabels'
import { CodeTag } from './case/CodeTag'
import { StatButton } from './case/StatButton'

const statusOptions: Array<{ value: StatusFilter; label: string }> = [
  { value: 'OPEN_ONLY', label: '열린 Case' },
  { value: 'ALL', label: '전체' },
  ...(Object.keys(caseStatusLabel) as CaseStatus[]).map((s) => ({
    value: s,
    label: caseStatusLabel[s].label,
  })),
]

const slaOptions = [
  { value: 'ALL', label: 'SLA 전체' },
  { value: 'EXCEEDED', label: '24시간 초과' },
]

const reportOptions: Array<{ value: ReportFilter | 'ALL'; label: string }> = [
  { value: 'ALL', label: 'AI 보고서 전체' },
  { value: 'running', label: '생성 대기 · 생성 중' },
  { value: 'failed', label: '생성 실패' },
]

const PAGE_SIZE = 20

export function CaseListPage() {
  const navigate = useNavigate()
  const { merchant } = useDemo()
  const { toast } = useFeedback()
  // useFeedback()은 렌더마다 새 객체라 effect 의존성에는 App이 주는 message를 쓴다
  const { message } = App.useApp()
  const location = useLocation()
  // 목록 조건은 주소 쿼리에 둔다 (#243) — 상세에 갔다 와도 · 새로고침해도 같은 조건으로 열린다
  // 라우터로 들어온 주소(홈 카드 · 뒤로 가기 · 새로고침)가 바뀌면 그 주소의 조건을 다시 읽는다
  const [searchParams] = useSearchParams()
  const [edited, setEdited] = useState<{ routerSearch: string; query: CaseListQuery } | null>(null)
  const query =
    edited?.routerSearch === location.search ? edited.query : parseCaseListQuery(searchParams)
  // 조건을 바꾸면 1페이지로 간다. 방문 기록은 쌓지 않는다 — 뒤로 가기는 이전 화면으로 간다
  // 라우터로 주소를 바꾸면 ServerSession이 세션을 다시 확인하며 본문을 다시 그려 입력 중인 검색어 · 목록이 사라진다.
  // 그래서 브라우저 주소만 바꾸고, 라우터가 쓰는 history.state는 그대로 둔다
  const setQuery = (patch: Partial<CaseListQuery>) => {
    const next = { ...query, page: 1, ...patch }
    setEdited({ routerSearch: location.search, query: next })
    window.history.replaceState(window.history.state, '', location.pathname + caseListSearch(next))
  }
  // 상세의 "목록으로"가 지금 조건의 목록으로 돌아오게 주소를 넘긴다
  const openCase = (caseId: string) =>
    navigate(`/security/cases/${caseId}`, {
      state: {
        caseListReturn: location.pathname + caseListSearch(query),
      } satisfies CaseListReturnState,
    })
  const [refreshKey, setRefreshKey] = useState(0)
  // 어떤 요청(가맹점·새로고침)에 대한 응답인지 함께 저장해 늦게 온 응답이 덮어쓰지 않게 한다.
  // failed: 조회 실패. 빈 목록과 구분해 "처리할 Case 없음"으로 오인하지 않게 하고, 직전 목록은 유지한다.
  const [result, setResult] = useState<{
    merchant: string
    refreshKey: number
    cases: CaseSummary[]
    failed: boolean
  } | null>(null)

  useEffect(() => {
    let alive = true
    getCases(merchant)
      .then((cases) => {
        if (alive) setResult({ merchant, refreshKey, cases, failed: false })
      })
      .catch(() => {
        if (!alive) return
        message.error('Case 목록을 불러오지 못했습니다. 잠시 후 다시 시도하세요.')
        setResult((prev) => ({
          merchant,
          refreshKey,
          cases: prev?.merchant === merchant ? prev.cases : [],
          failed: true,
        }))
      })
    return () => {
      alive = false
    }
  }, [merchant, refreshKey, message])

  const handleDemoHeld = async () => {
    try {
      const caseId = await demoCreateHeldPayment()
      toast.info('시연: 65점 REVIEW 결제가 HELD되어 Case가 생성되었습니다.')
      navigate(`/security/cases/${caseId}`)
    } catch {
      toast.error('시연 결제를 만들지 못했습니다.')
    }
  }
  const loading = result?.merchant !== merchant || result.refreshKey !== refreshKey
  // 다른 가맹점의 응답은 표시하지 않는다. 같은 가맹점 새로고침 중에는 기존 목록을 유지한다.
  const cases = useMemo(
    () => (result?.merchant === merchant ? result.cases : []),
    [result, merchant],
  )

  const filtered = useMemo(
    () => cases.filter((c) => matchesCaseListQuery(c, query)),
    [cases, query],
  )
  // 목록이 줄어 주소의 페이지가 없어지면 마지막 페이지를 보여 준다
  const page = Math.min(query.page, Math.max(1, Math.ceil(filtered.length / PAGE_SIZE)))

  const open = cases.filter((c) => c.status !== 'CLOSED')
  // 카드는 상태 · 24시간 초과 · AI 보고서 조건을 한 번에 건다. 검색어는 유지한다
  const cards: Array<{
    title: string
    foot: string
    color?: string
    preset: Pick<CaseListQuery, 'status' | 'slaExceeded' | 'report'>
    value: number
  }> = [
    {
      title: '열린 Case',
      foot: '종료 전 Case',
      preset: { status: 'OPEN_ONLY', slaExceeded: false, report: null },
      value: open.length,
    },
    {
      title: '판정 대기',
      foot: '직원 판정 필요',
      color: '#B45309',
      preset: { status: 'DECISION_PENDING', slaExceeded: false, report: null },
      value: open.filter((c) => c.status === 'DECISION_PENDING').length,
    },
    {
      title: '금융 처리 대기',
      foot: '매입 · 승인 취소 완료 대기 중',
      color: '#1D4ED8',
      preset: { status: 'ACTION_PENDING', slaExceeded: false, report: null },
      value: open.filter((c) => c.status === 'ACTION_PENDING').length,
    },
    {
      title: '금융 조치 실패',
      foot: '실패 사유 확인 필요',
      color: '#B91C1C',
      preset: { status: 'ACTION_FAILED', slaExceeded: false, report: null },
      value: open.filter((c) => c.status === 'ACTION_FAILED').length,
    },
    {
      title: 'AI 보고서 실패',
      foot: '수동 조사 · 판정 필요',
      color: '#B91C1C',
      preset: { status: 'OPEN_ONLY', slaExceeded: false, report: 'failed' },
      value: open.filter((c) => c.latestReportStatus === 'FAILED').length,
    },
  ]
  const columns: ColumnsType<CaseSummary> = [
    {
      title: 'Case ID',
      dataIndex: 'caseId',
      width: 120,
      render: (id: string) => (
        <Button
          type="link"
          style={{ padding: 0, fontWeight: 700 }}
          // 행 클릭과 겹쳐 두 번 이동하지 않게 막는다. 버튼은 키보드로 여는 경로로 남긴다.
          onClick={(event: MouseEvent<HTMLElement>) => {
            event.stopPropagation()
            openCase(id)
          }}
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
      render: (v: CaseSummary['totalAmount']) => (v === null ? '-' : won(v)),
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
            <Button onClick={handleDemoHeld}>시연: HELD 결제 발생</Button>
            <Button icon={<ReloadOutlined />} onClick={() => setRefreshKey((k) => k + 1)}>
              새로고침
            </Button>
          </>
        }
      />
      {result?.merchant === merchant && result.failed && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          message="Case 목록을 불러오지 못했습니다. 아래 건수와 목록은 실제와 다를 수 있습니다."
          action={
            <Button size="small" onClick={() => setRefreshKey((k) => k + 1)}>
              다시 시도
            </Button>
          }
        />
      )}
      <div
        className="dw-stat-grid"
        style={{ gridTemplateColumns: 'repeat(5, minmax(0, 1fr))' }}
        aria-label="Case 조건별 건수"
      >
        {cards.map((card) => (
          <StatButton
            key={card.title}
            title={card.title}
            value={card.value}
            foot={card.foot}
            color={card.color}
            selected={
              query.status === card.preset.status &&
              query.slaExceeded === card.preset.slaExceeded &&
              query.report === card.preset.report
            }
            onClick={() => setQuery(card.preset)}
          />
        ))}
      </div>
      <Card className="dw-table-card" style={{ borderColor: '#E2E8F0' }}>
        <div className="dw-table-toolbar">
          <div>
            <h3>Case 현황</h3>
            <p>위험도는 소속 결제의 FDS 최고 점수와 결제 건수입니다.</p>
          </div>
          <Space size={8} wrap>
            <Input
              aria-label="Case 검색"
              placeholder="Case ID, 고객"
              prefix={<SearchOutlined style={{ color: '#94a3b8' }} />}
              style={{ width: 200 }}
              value={query.q}
              onChange={(e) => setQuery({ q: e.target.value })}
              allowClear
            />
            <Select
              aria-label="Case 상태 필터"
              style={{ width: 130 }}
              value={query.status}
              onChange={(status: StatusFilter) => setQuery({ status })}
              options={statusOptions}
            />
            <Select
              aria-label="SLA 필터"
              style={{ width: 120 }}
              value={query.slaExceeded ? 'EXCEEDED' : 'ALL'}
              onChange={(v: string) => setQuery({ slaExceeded: v === 'EXCEEDED' })}
              options={slaOptions}
            />
            <Select
              aria-label="AI 보고서 필터"
              style={{ width: 160 }}
              value={query.report ?? 'ALL'}
              onChange={(v: ReportFilter | 'ALL') => setQuery({ report: v === 'ALL' ? null : v })}
              options={reportOptions}
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
          onRow={(record) => ({
            onClick: () => openCase(record.caseId),
            style: { cursor: 'pointer' },
          })}
          locale={{
            emptyText: (
              <Typography.Text type="secondary" style={{ display: 'block', padding: 36 }}>
                {caseListSearch({ ...query, page: 1 })
                  ? '조건에 맞는 Case가 없습니다.'
                  : '열린 Case가 없습니다.'}
              </Typography.Text>
            ),
          }}
          pagination={{
            current: page,
            pageSize: PAGE_SIZE,
            onChange: (next) => setQuery({ page: next }),
            showSizeChanger: false,
            showTotal: (total) => `총 ${total}건`,
          }}
        />
      </Card>
    </>
  )
}
