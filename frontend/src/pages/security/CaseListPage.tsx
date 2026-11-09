import { type MouseEvent, useEffect, useMemo, useState } from 'react'
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons'
import {
  Alert,
  App,
  Button,
  Card,
  Input,
  Segmented,
  Select,
  Space,
  Table,
  Tabs,
  Typography,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { getCases } from '../../api/cases'
import { useAuth } from '../../auth/useAuth'
import { PageHeader } from '../../components/common/PageHeader'
import { useCaseListWatch } from '../../hooks/useCaseListWatch'
import type { CaseStatus, CaseSummary } from '../../types/case'
import {
  type CaseScope,
  casesInScope,
  customerPart,
  mergeRemainingMin,
} from '../../utils/caseRules'
import {
  caseListSearch,
  caseListTab,
  caseListTabPreset,
  matchesCaseListQuery,
  parseCaseListQuery,
  type CaseListQuery,
  type CaseListReturnState,
  type CaseListTab,
  type ReportFilter,
  type RiskFilter,
  type StatusFilter,
} from '../../utils/caseListQuery'
import { readCaseListScope, saveCaseListScope } from '../../utils/caseListScope'
import { won } from '../../utils/format'
import { caseStatusLabel, dateTime, reportStatusLabel } from './case/caseLabels'
import { CaseWatchStoppedNotice } from './case/CaseWatchStoppedNotice'
import { CodeTag } from './case/CodeTag'
import { DeadlineValue, HelpLabel, deadlineHelp, mergeHelp } from './case/CaseTimeLabels'
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
  { value: 'ALL', label: '조사 기한 전체' },
  { value: 'EXCEEDED', label: '기한 지남' },
]

const reportOptions: Array<{ value: ReportFilter | 'ALL'; label: string }> = [
  { value: 'ALL', label: 'AI 보고서 전체' },
  { value: 'running', label: '생성 대기 · 생성 중' },
  { value: 'failed', label: '생성 실패' },
]

// 구간은 FDS 판정 구간과 같다 (caseListQuery riskBand). 보안 홈 위험도 그래프에서 이 조건으로 들어온다
const riskOptions: Array<{ value: RiskFilter | 'ALL'; label: string }> = [
  { value: 'ALL', label: '위험도 전체' },
  { value: 'deny', label: '80점 이상' },
  { value: 'review', label: '50~79점' },
  { value: 'low', label: '50점 미만' },
  { value: 'none', label: '점수 없음' },
]

const PAGE_SIZE = 20

// 목록 위 탭 (#314) — 종료된 Case를 상태 필터 안에 숨기지 않고 바로 본다
const tabItems: Array<{ key: CaseListTab; label: string }> = [
  { key: 'ACTIVE', label: '처리 중' },
  { key: 'CLOSED', label: '종료' },
  { key: 'ALL', label: '전체' },
]

export function CaseListPage() {
  const navigate = useNavigate()
  // effect 의존성에는 렌더마다 바뀌지 않는 App의 message를 쓴다
  const { message } = App.useApp()
  const location = useLocation()
  const { user } = useAuth()
  // [내 사건만 / 전체 직원] (#612) — 상태 탭의 "전체"와 헷갈리지 않게 "전체 직원"이라고 쓴다
  const [scope, setScope] = useState<CaseScope>(readCaseListScope)
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
  // 어떤 요청(새로고침)에 대한 응답인지 함께 저장해 늦게 온 응답이 덮어쓰지 않게 한다.
  // failed: 조회 실패. 빈 목록과 구분해 "처리할 Case 없음"으로 오인하지 않게 하고, 직전 목록은 유지한다.
  const [result, setResult] = useState<{
    refreshKey: number
    cases: CaseSummary[]
    failed: boolean
  } | null>(null)

  // 새 Case · 상태 변화를 건수로 감지해 바뀌었을 때만 다시 조회한다 (#553).
  // 조건(탭 · 검색 · 페이지)은 주소에 있어 다시 조회해도 그대로다. 고른 범위의 건수만 본다 (#640)
  const watch = useCaseListWatch((added) => {
    setRefreshKey((k) => k + 1)
    if (added > 0) message.info(`새 Case ${added}건이 들어왔습니다. 목록을 다시 불러옵니다.`)
  }, scope)
  const { listLoaded } = watch
  const refresh = () => {
    setRefreshKey((k) => k + 1)
    watch.restart()
  }

  useEffect(() => {
    let alive = true
    getCases()
      .then((cases) => {
        if (!alive) return
        setResult({ refreshKey, cases, failed: false })
        // 감지 기준 건수는 목록을 받은 뒤에만 확정한다 (#555 Codex 리뷰)
        listLoaded(true)
      })
      .catch(() => {
        if (!alive) return
        message.error('Case 목록을 불러오지 못했습니다. 잠시 후 다시 시도하세요.')
        setResult((prev) => ({
          refreshKey,
          cases: prev?.cases ?? [],
          failed: true,
        }))
        listLoaded(false)
      })
    return () => {
      alive = false
    }
  }, [refreshKey, message, listLoaded])

  const loading = result?.refreshKey !== refreshKey
  // 새로고침 중에는 기존 목록을 유지한다.
  // [내 사건만]이면 내가 담당인 사건만 — 위 카드 건수도 같은 범위다 (#612)
  const myId = user?.userId ?? null
  const cases = useMemo(() => casesInScope(result?.cases ?? [], scope, myId), [result, scope, myId])

  const filtered = useMemo(
    () => cases.filter((c) => matchesCaseListQuery(c, query)),
    [cases, query],
  )
  // 목록이 줄어 주소의 페이지가 없어지면 마지막 페이지를 보여 준다
  const page = Math.min(query.page, Math.max(1, Math.ceil(filtered.length / PAGE_SIZE)))

  const open = cases.filter((c) => c.status !== 'CLOSED')
  // 카드는 상태 · 조사 기한 지남 · AI 보고서 조건을 한 번에 건다. 검색어는 유지한다
  const cards: Array<{
    title: string
    foot: string
    color?: string
    preset: Pick<CaseListQuery, 'status' | 'slaExceeded' | 'report' | 'risk'>
    value: number
  }> = [
    {
      title: '열린 Case',
      foot: '종료 전 Case',
      preset: { status: 'OPEN_ONLY', slaExceeded: false, report: null, risk: null },
      value: open.length,
    },
    {
      title: '판정 대기',
      foot: '직원 판정 필요',
      color: '#B45309',
      preset: { status: 'DECISION_PENDING', slaExceeded: false, report: null, risk: null },
      value: open.filter((c) => c.status === 'DECISION_PENDING').length,
    },
    {
      title: '금융 처리 대기',
      foot: '매입 · 승인 취소 완료 대기 중',
      color: '#1D4ED8',
      preset: { status: 'ACTION_PENDING', slaExceeded: false, report: null, risk: null },
      value: open.filter((c) => c.status === 'ACTION_PENDING').length,
    },
    {
      title: '금융 처리 실패',
      foot: '실패 사유 확인 필요',
      color: '#B91C1C',
      preset: { status: 'ACTION_FAILED', slaExceeded: false, report: null, risk: null },
      value: open.filter((c) => c.status === 'ACTION_FAILED').length,
    },
    {
      title: 'AI 보고서 실패',
      foot: '수동 조사 · 판정 필요',
      color: '#B91C1C',
      preset: { status: 'OPEN_ONLY', slaExceeded: false, report: 'failed', risk: null },
      value: open.filter((c) => c.latestReportStatus === 'FAILED').length,
    },
  ]
  const tab = caseListTab(query.status)
  const allColumns: ColumnsType<CaseSummary> = [
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
    // 저장값 "가맹점ID:고객"에서 고객 부분만 보이고 복사된다 (#462 · #726)
    {
      title: '고객',
      dataIndex: 'customerRef',
      width: 140,
      // 12자리 번호여도 복사 버튼이 다음 줄로 떨어지지 않게 한 줄로 둔다
      onCell: () => ({ style: { whiteSpace: 'nowrap' as const } }),
      render: (value: string) => {
        const customer = customerPart(value)
        return (
          <Space size={2}>
            {customer}
            {/* 줄을 누르면 사건이 열려서, 복사 버튼 클릭은 줄까지 올라가지 않게 막는다 */}
            <span onClick={(event: MouseEvent<HTMLElement>) => event.stopPropagation()}>
              <Typography.Text copyable={{ text: customer, tooltips: ['복사', '복사됨'] }} />
            </span>
          </Space>
        )
      },
    },
    // 담당 보안 직원 (#608) — 미배정 사건은 관리자 [담당 현황]에서 배정한다
    {
      title: '담당',
      dataIndex: 'assigneeName',
      width: 110,
      // "보안직원 12"처럼 이름 뒤 번호가 다음 줄로 넘어가지 않게 한 줄로 둔다
      onCell: () => ({ style: { whiteSpace: 'nowrap' as const } }),
      render: (name: CaseSummary['assigneeName']) => name ?? '미배정',
    },
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
      title: '종료 시각',
      key: 'closedAt',
      width: 150,
      render: (_, c) => (c.closedAt ? dateTime(c.closedAt) : '-'),
    },
    {
      title: <HelpLabel label="결제 병합" help={mergeHelp} />,
      key: 'merge',
      width: 110,
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
      title: <HelpLabel label="조사 기한" help={deadlineHelp} />,
      key: 'sla',
      width: 130,
      render: (_, c) => <DeadlineValue createdAt={c.createdAt} status={c.status} />,
    },
    {
      title: 'AI 보고서',
      dataIndex: 'latestReportStatus',
      width: 110,
      render: (s: CaseSummary['latestReportStatus']) =>
        s ? <CodeTag value={reportStatusLabel[s]} /> : '-',
    },
  ]

  // 종료 탭은 진행용 열(결제 병합 · 조사 기한)을 빼고 종료 시각을 보인다. 그 밖의 탭은 종료 시각을 뺀다
  const columns = allColumns.filter((col) =>
    tab === 'CLOSED' ? col.key !== 'merge' && col.key !== 'sla' : col.key !== 'closedAt',
  )

  return (
    <>
      <PageHeader
        title="Case 목록"
        description="HELD 결제로 생성된 조사 Case를 확인하고 판정할 Case를 고릅니다."
        actions={
          <Button icon={<ReloadOutlined />} onClick={refresh}>
            새로고침
          </Button>
        }
      />
      {watch.stopped && <CaseWatchStoppedNotice onRefresh={refresh} />}
      {result?.failed && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          message="Case 목록을 불러오지 못했습니다. 아래 건수와 목록은 실제와 다를 수 있습니다."
          action={
            <Button size="small" onClick={refresh}>
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
              query.report === card.preset.report &&
              query.risk === card.preset.risk
            }
            onClick={() => setQuery(card.preset)}
          />
        ))}
      </div>
      <Card className="dw-table-card" style={{ borderColor: '#E2E8F0' }}>
        <Tabs
          activeKey={tab}
          onChange={(key) => setQuery(caseListTabPreset(key as CaseListTab))}
          items={tabItems}
          style={{ padding: '0 20px' }}
        />
        <div className="dw-table-toolbar">
          <div>
            <h3>Case 현황</h3>
            <p>위험도는 소속 결제의 FDS 최고 점수와 결제 건수입니다.</p>
          </div>
          <Space size={8} wrap>
            <Segmented<CaseScope>
              aria-label="Case 범위"
              value={scope}
              onChange={(next) => {
                setScope(next)
                saveCaseListScope(next)
                setQuery({})
                // 자동 갱신은 고른 범위의 건수만 본다(#640). [내 사건만]일 때 놓친 남의 Case 변화가
                // 전환 뒤에도 남지 않게 목록을 다시 받고 감지 기준도 새로 잡는다 (#642 Codex 리뷰)
                refresh()
              }}
              options={[
                { value: 'MINE', label: '내 사건만' },
                { value: 'ALL', label: '전체 직원' },
              ]}
            />
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
              aria-label="조사 기한 필터"
              style={{ width: 130 }}
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
            <Select
              aria-label="위험도 필터"
              style={{ width: 130 }}
              value={query.risk ?? 'ALL'}
              onChange={(v: RiskFilter | 'ALL') => setQuery({ risk: v === 'ALL' ? null : v })}
              options={riskOptions}
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
                {scope === 'MINE' && cases.length === 0
                  ? '맡은 Case가 없습니다. 다른 직원의 사건은 [전체 직원]에서 볼 수 있어요.'
                  : tab === 'CLOSED'
                    ? '종료된 Case가 없습니다.'
                    : caseListSearch({ ...query, page: 1 })
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
