import {
  type CSSProperties,
  type KeyboardEvent,
  type MouseEvent,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react'
import {
  ArrowRightOutlined,
  ClockCircleOutlined,
  FilterOutlined,
  LockOutlined,
  PauseCircleOutlined,
  PlayCircleOutlined,
  ReloadOutlined,
  SearchOutlined,
} from '@ant-design/icons'
import {
  Alert,
  App,
  Button,
  Card,
  Checkbox,
  Input,
  Popover,
  Segmented,
  Select,
  Table,
  Tabs,
  Tag,
  Typography,
  type InputRef,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { getCases } from '../../api/cases'
import { getMerchantDirectory } from '../../api/merchants'
import { useAuth } from '../../auth/useAuth'
import { PageHeader } from '../../components/common/PageHeader'
import { useCaseListWatch } from '../../hooks/useCaseListWatch'
import type { CaseStatus, CaseSummary } from '../../types/case'
import {
  SLA_HOURS,
  type CaseScope,
  casesInScope,
  customerPart,
  mergeRemainingMin,
} from '../../utils/caseRules'
import {
  CASE_LIST_ORDER_LIMIT,
  caseListSearch,
  caseListTab,
  caseListTabPreset,
  matchesCaseListQuery,
  parseCaseListQuery,
  riskBand,
  type CaseListQuery,
  type CaseListReturnState,
  type CaseListSort,
  type CaseListTab,
  type ReportFilter,
  type RiskFilter,
  type StatusFilter,
} from '../../utils/caseListQuery'
import { readCaseListScope, saveCaseListScope } from '../../utils/caseListScope'
import {
  type BoardLaneKey,
  boardLaneOf,
  casesInTab,
  clearConditions,
  elapsedLabel,
  isQuickConditionOn,
  isRestrictedCase,
  listPage,
  newCaseIds,
  nextCase,
  quickConditionCount,
  quickConditions,
  sortCases,
  toggleQuickCondition,
} from '../../utils/caseListView'
import { won } from '../../utils/format'
import { caseStatusLabel, dateTime, reportStatusLabel } from './case/caseLabels'
import { CaseWatchStoppedNotice } from './case/CaseWatchStoppedNotice'
import { CaseSummaryDrawer } from './case/CaseSummaryDrawer'
import { HomeCount } from './HomeCount'
import { CaseFlowBoard } from './case/CaseFlowBoard'
import { CodeTag } from './case/CodeTag'
import { DeadlineValue, HelpLabel, deadlineHelp, mergeHelp } from './case/CaseTimeLabels'
import './CaseListPage.css'

// 처리 중 탭에서 고를 수 있는 상태. 종료는 탭으로 고른다
const statusOptions: Array<{ value: StatusFilter; label: string }> = [
  { value: 'OPEN_ONLY', label: '진행 상태 전체' },
  { value: 'PREPARE', label: '접수 · 보고서 준비' },
  ...(Object.keys(caseStatusLabel) as CaseStatus[])
    .filter((s) => s !== 'CLOSED')
    .map((s) => ({ value: s, label: caseStatusLabel[s].label })),
]

// 흐름 보드 레인 ↔ 상태 조건 (#759)
const laneStatus: Record<BoardLaneKey, StatusFilter> = {
  prepare: 'PREPARE',
  decision: 'DECISION_PENDING',
  action: 'ACTION_PENDING',
  failed: 'ACTION_FAILED',
}
const laneOfStatus = (status: StatusFilter): BoardLaneKey | null =>
  (Object.keys(laneStatus) as BoardLaneKey[]).find((lane) => laneStatus[lane] === status) ?? null

const reportOptions: Array<{ value: ReportFilter | 'ALL'; label: string }> = [
  { value: 'ALL', label: '보고서 전체' },
  { value: 'running', label: '생성 대기 · 생성 중' },
  { value: 'failed', label: '생성 실패' },
]

// 구간은 FDS 판정 구간과 같다 (caseListQuery riskBand). 보안 홈 위험도 그래프에서 이 조건으로 들어온다
const riskLabel: Record<RiskFilter, string> = {
  deny: '80점 이상',
  review: '50~79점',
  low: '50점 미만',
  none: '점수 없음',
}
const riskOptions: Array<{ value: RiskFilter | 'ALL'; label: string }> = [
  { value: 'ALL', label: '위험 점수 전체' },
  ...(Object.keys(riskLabel) as RiskFilter[]).map((r) => ({ value: r, label: riskLabel[r] })),
]

const sortOptions: Array<{ value: CaseListSort; label: string }> = [
  { value: 'priority', label: '확인 우선순' },
  { value: 'risk', label: '위험 점수 높은 순' },
  { value: 'oldest', label: '오래된 순' },
  { value: 'newest', label: '최신순' },
]

const sortHelp = `확인 우선순: 금융 처리 실패 → 생성 후 ${SLA_HOURS}시간 경과 → 판정 대기 → 나머지, 같은 단계면 오래된 순입니다. 보안 홈의 먼저 확인할 사건과 같은 순서입니다. 종료된 사건은 최근 종료 순입니다.`

const PAGE_SIZE = 20

// 목록 위 탭 (#314) — 종료된 Case를 상태 필터 안에 숨기지 않고 바로 본다
const tabItems: Array<{ key: CaseListTab; label: string }> = [
  { key: 'ACTIVE', label: '처리 중' },
  { key: 'CLOSED', label: '종료' },
  { key: 'ALL', label: '전체' },
]

const tabNoun: Record<CaseListTab, string> = {
  ACTIVE: '진행 사건',
  CLOSED: '종료 사건',
  ALL: '사건',
}

type ListResult = {
  refreshKey: number
  cases: CaseSummary[]
  failed: boolean
  receivedAt: number | null
  /** 새 사건 안내를 반영해 다시 불러왔을 때 새로 생긴 사건. 그 줄을 한 번 강조한다 */
  newIds: string[]
}

export function CaseListPage() {
  const navigate = useNavigate()
  // effect 의존성에는 렌더마다 바뀌지 않는 App의 message를 쓴다
  const { message } = App.useApp()
  const location = useLocation()
  const { user } = useAuth()
  // [내 사건 / 전체 직원] (#612) — 상태 탭의 "전체"와 헷갈리지 않게 "전체 직원"이라고 쓴다
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
  // 미리보기를 연 줄은 주소에 남긴다. 페이지는 그대로 둔다
  const [refreshKey, setRefreshKey] = useState(0)
  // 어떤 요청(새로고침)에 대한 응답인지 함께 저장해 늦게 온 응답이 덮어쓰지 않게 한다.
  // failed: 조회 실패. 빈 목록과 구분해 "처리할 Case 없음"으로 오인하지 않게 하고, 직전 목록은 유지한다.
  const [result, setResult] = useState<ListResult | null>(null)
  // 감지한 변화 (#759). 읽는 중에 줄이 갑자기 바뀌지 않게 바로 다시 불러오지 않고 안내한 뒤 직원이 반영한다
  const [pendingChange, setPendingChange] = useState<{ added: number } | null>(null)
  // 이번 조회가 새 사건 안내를 반영한 것인지 — 그때만 새 줄을 강조한다
  const markNew = useRef(false)
  // 가맹점 이름 (#759) — 상세 · 경보 화면과 같은 가맹점 목록. 못 받으면 번호만 보인다
  const [merchantNames, setMerchantNames] = useState<Map<string, string>>(() => new Map())
  useEffect(() => {
    let alive = true
    getMerchantDirectory()
      .then((merchants) => {
        if (alive)
          setMerchantNames(new Map(merchants.map((m) => [String(m.merchantId), m.displayName])))
      })
      .catch(() => {})
    return () => {
      alive = false
    }
  }, [])

  // 새 Case · 상태 변화를 건수로 감지한다 (#553). 고른 범위의 건수만 본다 (#640)
  const watch = useCaseListWatch((added) => {
    // added는 마지막으로 반영한 목록 대비 전체 증가분이다. 더하지 않고 바꾼다 (#760 Codex 리뷰)
    setPendingChange({ added })
  }, scope)
  const { listLoaded } = watch
  const refresh = () => {
    setPendingChange(null)
    setRefreshKey((k) => k + 1)
    watch.restart()
  }
  // 안내한 변화를 반영한다. 감지 기준은 목록을 받은 뒤 listLoaded가 확정한다
  const applyChange = () => {
    setPendingChange(null)
    markNew.current = true
    setRefreshKey((k) => k + 1)
  }

  useEffect(() => {
    let alive = true
    getCases()
      .then((cases) => {
        if (!alive) return
        const highlight = markNew.current
        markNew.current = false
        setResult((prev) => ({
          refreshKey,
          cases,
          failed: false,
          receivedAt: Date.now(),
          newIds: highlight ? newCaseIds(prev?.receivedAt != null ? prev.cases : null, cases) : [],
        }))
        // 감지 기준 건수는 목록을 받은 뒤에만 확정한다 (#555 Codex 리뷰)
        listLoaded(true)
      })
      .catch(() => {
        if (!alive) return
        markNew.current = false
        message.error('Case 목록을 불러오지 못했습니다. 잠시 후 다시 시도하세요.')
        setResult((prev) => ({
          refreshKey,
          cases: prev?.cases ?? [],
          failed: true,
          receivedAt: prev?.receivedAt ?? null,
          newIds: [],
        }))
        listLoaded(false)
      })
    return () => {
      alive = false
    }
  }, [refreshKey, message, listLoaded])

  const loading = result?.refreshKey !== refreshKey
  // 새로고침 중에는 기존 목록을 유지한다.
  // [내 사건]이면 내가 담당인 사건만 — 위 건수도 같은 범위다 (#612)
  const myId = user?.userId ?? null
  const cases = useMemo(() => casesInScope(result?.cases ?? [], scope, myId), [result, scope, myId])
  const tab = caseListTab(query.status)
  const tabCases = useMemo(() => casesInTab(cases, tab), [cases, tab])
  // 자주 쓰는 조건 버튼의 비율 막대 기준 — 종료되지 않은 사건 수
  const openCount = useMemo(() => casesInTab(cases, 'ACTIVE').length, [cases])
  const filtered = useMemo(
    () =>
      sortCases(
        cases.filter((c) =>
          matchesCaseListQuery(c, query, undefined, merchantNames.get(c.merchantId)),
        ),
        query.sort,
      ),
    [cases, query, merchantNames],
  )
  const selectedId = query.selected
  // 흐름 보드는 단계(상태) 조건만 빼고 거른다 — 한 레인을 골라도 다른 레인이 보여야 흐름이 읽힌다
  const boardCases = useMemo(
    () =>
      cases.filter((c) =>
        matchesCaseListQuery(
          c,
          { ...query, status: 'OPEN_ONLY' },
          undefined,
          merchantNames.get(c.merchantId),
        ),
      ),
    [cases, query, merchantNames],
  )
  const activeLane = laneOfStatus(query.status)
  // 다음 확인 사건 — 열 수 있는 사건 중 확인 우선순 1위 (홈 "먼저 확인할 사건"과 같은 순서)
  const next = useMemo(
    () => nextCase(boardCases, (c) => !isRestrictedCase(c, myId)),
    [boardCases, myId],
  )
  const filteredIds = filtered.map((c) => c.caseId)
  const page = listPage(filteredIds, selectedId, query.page, PAGE_SIZE)
  // 고른 사건이 있는 페이지를 같이 남겨, 선택을 풀어도 보던 페이지에 머문다
  const select = (caseId: string | null) =>
    setQuery({
      selected: caseId,
      page: listPage(filteredIds, caseId, page, PAGE_SIZE),
    })
  // 보드에서 고른 사건이 표의 단계 조건 밖이면 그 사건 레인으로 단계 조건을 옮겨 표에도 보이게 한다.
  // 레인을 걸러 둔 채 다른 레인 칩이나 [다음 확인]을 눌러도 줄이 열린다 (#760 리뷰)
  const selectFromBoard = (caseId: string) => {
    if (filteredIds.includes(caseId)) return select(caseId)
    const c = boardCases.find((item) => item.caseId === caseId)
    if (!c || c.status === 'CLOSED') return
    setQuery({
      status: activeLane ? laneStatus[boardLaneOf[c.status]] : tab === 'ALL' ? 'ALL' : 'OPEN_ONLY',
      selected: caseId,
    })
  }
  // 상세 · 요약의 이전 · 다음 이동 순서 (#759) — 지금 조건 · 정렬 그대로, 열 수 없는 남의 진행 중 사건은 뺀다
  const queueOrder = useMemo(
    () =>
      filtered
        .filter((c) => !isRestrictedCase(c, myId))
        .map((c) => c.caseId)
        .slice(0, CASE_LIST_ORDER_LIMIT),
    [filtered, myId],
  )
  // 다른 페이지의 사건을 고르면 그 페이지가 열린다 (listPage)
  const selectCase = (caseId: string | null) => select(caseId)
  // 상세의 "목록으로"가 지금 조건 · 연 줄의 목록으로 돌아오게 주소와 순서를 넘긴다
  const openCase = (caseId: string) =>
    navigate(`/security/cases/${caseId}`, {
      state: {
        caseListReturn: location.pathname + caseListSearch({ ...query, selected: caseId }),
        caseListOrder: queueOrder,
      } satisfies CaseListReturnState,
    })
  // 움직임 끄기 (#759) — 보안 홈과 같다. 시스템의 움직임 줄이기 설정은 CSS가 따로 끈다
  const [motionPaused, setMotionPaused] = useState(false)
  // 오른쪽 요약 (#759). 직원이 [요약 보기]를 누를 때만 연다
  // 시간축 점과 표 줄을 함께 강조한다 (#759)
  const [hoverId, setHoverId] = useState<string | null>(null)
  const [summaryId, setSummaryId] = useState<string | null>(null)
  // [요약 보기]를 다시 누르면 같은 사건이어도 다시 조회한다
  const [summaryNonce, setSummaryNonce] = useState(0)
  const summaryIndex = summaryId ? queueOrder.indexOf(summaryId) : -1
  // 조건이 바뀌어 연 사건이 목록 순서에서 빠지면 요약을 닫는다 — 이전 · 다음이 막힌 채 남지 않게 (#760 리뷰)
  const summaryOpenId = summaryIndex >= 0 ? summaryId : null
  const openSummary = (caseId: string) => {
    setSummaryId(caseId)
    setSummaryNonce((n) => n + 1)
    selectCase(caseId)
  }
  const restrictedById = (caseId: string) => {
    const c = filtered.find((item) => item.caseId === caseId)
    return !c || isRestrictedCase(c, myId)
  }
  // 줄을 누르면 미리보기를 열고 닫는다. 요약이 열려 있으면 그 사건 요약으로 바꾼다(누른 것이 명시적 요청이다)
  const onRowClick = (caseId: string) => {
    if (summaryId !== null && !restrictedById(caseId)) {
      openSummary(caseId)
      return
    }
    select(selectedId === caseId ? null : caseId)
  }

  // "/"로 검색칸에 간다 (입력 중이 아닐 때)
  const searchRef = useRef<InputRef>(null)
  useEffect(() => {
    const onKey = (event: globalThis.KeyboardEvent) => {
      if (event.key !== '/' || event.metaKey || event.ctrlKey || event.altKey) return
      const target = event.target as HTMLElement | null
      if (target?.closest('input, textarea, select, [contenteditable="true"]')) return
      event.preventDefault()
      searchRef.current?.focus()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [])

  // 상세에서 돌아오면 연 줄이 보이게 한 번 스크롤한다
  const restored = useRef(false)
  useEffect(() => {
    if (restored.current || !result || !selectedId) return
    restored.current = true
    document
      .querySelector(`tr[data-row-key="${CSS.escape(selectedId)}"]`)
      ?.scrollIntoView({ block: 'nearest' })
  }, [result, selectedId])

  // 처리 중 탭에서만 뜻이 있는 조건. 종료 탭에서는 숨긴다
  const closedTab = tab === 'CLOSED'
  const specificStatus = tab === 'ACTIVE' && query.status !== 'OPEN_ONLY'
  const chips: Array<{ key: string; label: string; clear: Partial<CaseListQuery> }> = [
    ...(specificStatus
      ? [
          {
            key: 'status',
            label: `상태: ${statusOptions.find((o) => o.value === query.status)?.label ?? query.status}`,
            clear: { status: 'OPEN_ONLY' as const },
          },
        ]
      : []),
    ...(query.slaExceeded
      ? [{ key: 'sla', label: `생성 후 ${SLA_HOURS}시간 경과`, clear: { slaExceeded: false } }]
      : []),
    ...(query.report
      ? [
          {
            key: 'report',
            label: `보고서: ${query.report === 'failed' ? '생성 실패' : '생성 대기 · 생성 중'}`,
            clear: { report: null },
          },
        ]
      : []),
    ...(query.risk
      ? [{ key: 'risk', label: `위험 점수: ${riskLabel[query.risk]}`, clear: { risk: null } }]
      : []),
    ...(query.q.trim() ? [{ key: 'q', label: `검색: ${query.q.trim()}`, clear: { q: '' } }] : []),
  ]
  // 검색어는 입력란에 보이므로 필터 버튼의 개수에서 뺀다
  const filterCount = chips.filter((chip) => chip.key !== 'q').length

  const filterPanel = (
    <div className="case-queue-filter-panel">
      {tab === 'ACTIVE' && (
        <label>
          <span>현재 단계</span>
          <Select
            aria-label="Case 상태 필터"
            value={query.status}
            onChange={(status: StatusFilter) => setQuery({ status })}
            options={statusOptions}
          />
        </label>
      )}
      <label>
        <span>보고서</span>
        <Select
          aria-label="AI 보고서 필터"
          value={query.report ?? 'ALL'}
          onChange={(v: ReportFilter | 'ALL') => setQuery({ report: v === 'ALL' ? null : v })}
          options={reportOptions}
        />
      </label>
      <label>
        <span>최고 위험 점수</span>
        <Select
          aria-label="위험도 필터"
          value={query.risk ?? 'ALL'}
          onChange={(v: RiskFilter | 'ALL') => setQuery({ risk: v === 'ALL' ? null : v })}
          options={riskOptions}
        />
      </label>
      {!closedTab && (
        <Checkbox
          checked={query.slaExceeded}
          onChange={(e) => setQuery({ slaExceeded: e.target.checked })}
        >
          생성 후 {SLA_HOURS}시간 경과만
        </Checkbox>
      )}
    </div>
  )

  const showAssignee = scope === 'ALL'
  const columns: ColumnsType<CaseSummary> = [
    {
      title: '사건 · 고객',
      key: 'case',
      // 남는 폭은 이 열이 가져간다
      render: (_, c) => (
        <div className="case-queue-case">
          <strong>Case {c.caseId}</strong>
          <span title={`가맹점 ID ${c.merchantId}`}>
            {merchantNames.get(c.merchantId) ?? `가맹점 ${c.merchantId}`} · 고객{' '}
            {customerPart(c.customerRef)}
          </span>
        </div>
      ),
    },
    {
      title: '현재 단계',
      key: 'status',
      width: 160,
      render: (_, c) => <StageCell c={c} />,
    },
    {
      title: (
        <HelpLabel
          label="최고 위험 점수"
          help="사건에 묶인 결제의 FDS 점수 중 가장 높은 값입니다. 0~100점이며 확률이 아닙니다."
        />
      ),
      key: 'risk',
      width: 150,
      render: (_, c) => <RiskScore score={c.maxRiskScore} />,
    },
    {
      title: '총액',
      key: 'amount',
      align: 'right',
      width: 140,
      onHeaderCell: () => ({ className: 'case-queue-col-amount' }),
      onCell: () => ({ className: 'case-queue-col-amount' }),
      render: (_, c) => (
        <div className="case-queue-amount">
          <strong>{c.totalAmount === null ? '-' : won(c.totalAmount)}</strong>
          <span>결제 {c.paymentCount}건</span>
        </div>
      ),
    },
    closedTab
      ? {
          title: '종료 시각',
          key: 'time',
          width: 170,
          onHeaderCell: () => ({ className: 'case-queue-col-time' }),
          onCell: () => ({ className: 'case-queue-col-time' }),
          render: (_, c) => (c.closedAt ? dateTime(c.closedAt) : '-'),
        }
      : {
          title: (
            <HelpLabel
              label="생성 후 경과"
              help={`사건 생성 후 지난 시간입니다. ${SLA_HOURS}시간이 지나면 빨갛게 표시합니다. 시간이 지나도 자동으로 판정하거나 종료하지 않습니다.`}
            />
          ),
          key: 'time',
          width: 170,
          onHeaderCell: () => ({ className: 'case-queue-col-time' }),
          onCell: () => ({ className: 'case-queue-col-time' }),
          render: (_, c) => {
            const elapsed = elapsedLabel(c)
            if (!elapsed) return <span className="case-queue-muted">종료</span>
            return (
              <span className={`case-queue-elapsed${elapsed.exceeded ? ' is-overdue' : ''}`}>
                <span className="case-queue-elapsed-text">
                  {elapsed.exceeded && <ClockCircleOutlined aria-label="24시간 경과" />}
                  {elapsed.text}
                </span>
                <span className="case-queue-elapsed-bar" aria-hidden="true">
                  <i style={{ width: `${Math.round(elapsed.ratio * 100)}%` }} />
                </span>
              </span>
            )
          },
        },
    {
      title: '보고서',
      dataIndex: 'latestReportStatus',
      width: 110,
      render: (s: CaseSummary['latestReportStatus']) =>
        s ? (
          <span
            className={
              s === 'PENDING' || s === 'RUNNING' ? 'case-queue-report is-running' : undefined
            }
          >
            <CodeTag value={reportStatusLabel[s]} />
          </span>
        ) : (
          '-'
        ),
    },
    // 담당 보안 직원 (#608) — 내 사건에서는 모두 나라서 전체 직원에서만 보인다
    ...(showAssignee
      ? [
          {
            title: '담당',
            dataIndex: 'assigneeName',
            width: 110,
            onCell: () => ({ style: { whiteSpace: 'nowrap' as const } }),
            render: (name: CaseSummary['assigneeName']) => name ?? '미배정',
          },
        ]
      : []),
    {
      title: <span className="dw-input-sr-only">상세</span>,
      key: 'open',
      width: 110,
      align: 'right',
      render: (_, c) => <OpenAction c={c} restricted={isRestrictedCase(c, myId)} open={openCase} />,
    },
  ]

  // ↑ ↓로 줄을 옮기고 Enter · Space로 미리보기를 연다
  const onRowKeyDown = (event: KeyboardEvent<HTMLElement>, caseId: string) => {
    if (event.target !== event.currentTarget) return
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault()
      select(selectedId === caseId ? null : caseId)
      return
    }
    // O: 상세 열기, S: 오른쪽 요약 — 열 수 있는 사건만
    const key = event.key.toLowerCase()
    if ((key === 'o' || key === 's') && !event.metaKey && !event.ctrlKey && !event.altKey) {
      if (restrictedById(caseId)) return
      event.preventDefault()
      if (key === 'o') openCase(caseId)
      else openSummary(caseId)
      return
    }
    if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return
    event.preventDefault()
    const rows = Array.from(
      event.currentTarget.closest('tbody')?.querySelectorAll<HTMLElement>('tr.ant-table-row') ?? [],
    )
    const index = rows.indexOf(event.currentTarget)
    rows[index + (event.key === 'ArrowDown' ? 1 : -1)]?.focus()
  }

  const updatedAt = result?.receivedAt ?? null
  const scopeNoun = scope === 'MINE' ? '내' : '전체 직원'

  return (
    <div
      className={`case-queue${summaryOpenId ? ' has-drawer' : ''}${motionPaused ? ' is-motion-paused' : ''}`}
    >
      <PageHeader
        title="Case 목록"
        description="HELD 결제로 생성된 조사 Case를 비교하고 다음에 확인할 Case를 고릅니다."
        actions={
          <div className="case-queue-refresh">
            <Button
              type="text"
              size="small"
              icon={motionPaused ? <PlayCircleOutlined /> : <PauseCircleOutlined />}
              aria-pressed={!motionPaused}
              onClick={() => setMotionPaused((paused) => !paused)}
            >
              {motionPaused ? '움직임 켜기' : '움직임 끄기'}
            </Button>
            {updatedAt !== null && (
              <span title={dateTime(new Date(updatedAt).toISOString())}>
                {new Date(updatedAt).toLocaleTimeString('ko-KR', {
                  hour: '2-digit',
                  minute: '2-digit',
                  timeZone: 'Asia/Seoul',
                })}{' '}
                조회{result?.failed ? ' · 갱신 실패' : pendingChange ? ' · 변경 반영 전' : ''}
              </span>
            )}
            <Button icon={<ReloadOutlined spin={loading} />} onClick={refresh} disabled={loading}>
              새로고침
            </Button>
          </div>
        }
      />
      {watch.stopped && <CaseWatchStoppedNotice onRefresh={refresh} />}
      {result?.failed && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          message={
            updatedAt !== null
              ? 'Case 목록을 갱신하지 못했습니다. 아래는 마지막으로 조회한 목록이며 실제와 다를 수 있습니다.'
              : 'Case 목록을 불러오지 못했습니다.'
          }
          action={
            <Button size="small" onClick={refresh}>
              다시 시도
            </Button>
          }
        />
      )}
      {pendingChange && !loading && (
        <div className="case-queue-update" role="status">
          <span>
            {pendingChange.added > 0
              ? `새 Case ${pendingChange.added}건이 들어왔습니다.`
              : 'Case 상태가 바뀌었습니다.'}{' '}
            아래 목록은 반영 전입니다.
          </span>
          <Button size="small" type="primary" onClick={applyChange}>
            목록에 반영
          </Button>
        </div>
      )}
      <Card className="dw-table-card case-queue-card">
        <Tabs
          activeKey={tab}
          onChange={(key) => setQuery({ ...caseListTabPreset(key as CaseListTab), selected: null })}
          items={tabItems}
          className="case-queue-tabs"
          tabBarExtraContent={
            <Segmented<CaseScope>
              aria-label="Case 범위"
              value={scope}
              onChange={(next) => {
                setScope(next)
                saveCaseListScope(next)
                // 범위를 바꾸면 요약을 닫는다. 열린 채 두면 다시 조회해 누르지 않은 열람 기록이 남는다 (#760 리뷰)
                setSummaryId(null)
                setQuery({ selected: null })
                // 자동 갱신은 고른 범위의 건수만 본다(#640). [내 사건]일 때 놓친 남의 Case 변화가
                // 전환 뒤에도 남지 않게 목록을 다시 받고 감지 기준도 새로 잡는다 (#642 Codex 리뷰)
                refresh()
              }}
              options={[
                { value: 'MINE', label: '내 사건' },
                { value: 'ALL', label: '전체 직원' },
              ]}
            />
          }
        />
        <div className="case-queue-quick case-queue-enter" aria-label="자주 쓰는 조건">
          <div className="case-queue-quick-total" aria-live="polite">
            <span>
              {scopeNoun} {tabNoun[tab]}
            </span>
            <strong>{result ? <HomeCount value={tabCases.length} /> : '−'}</strong>
            <small>건</small>
          </div>
          <span className="case-queue-quick-divider" aria-hidden="true" />
          {quickConditions.map((item, index) => {
            const count = quickConditionCount(cases, item.key)
            const on = isQuickConditionOn(query, item.key)
            const ratio = openCount > 0 ? count / openCount : 0
            return (
              <button
                type="button"
                key={item.key}
                aria-pressed={on}
                className={`${on ? 'is-on ' : ''}${count > 0 ? `is-${item.tone}` : ''}`}
                style={{ '--i': index } as CSSProperties}
                onClick={() =>
                  setQuery({ ...toggleQuickCondition(query, item.key), selected: null })
                }
              >
                <span className="case-queue-quick-label">{item.label}</span>
                <strong key={count}>{result ? <HomeCount value={count} /> : '−'}</strong>
                <span className="case-queue-quick-track" aria-hidden="true">
                  <i style={{ width: `${Math.round(ratio * 100)}%` }} />
                </span>
              </button>
            )
          })}
        </div>
        <div className="case-queue-toolbar case-queue-enter">
          <Input
            ref={searchRef}
            aria-label="Case 검색"
            placeholder="사건 · 고객 · 가맹점 검색"
            suffix={<kbd className="case-queue-kbd">/</kbd>}
            prefix={<SearchOutlined style={{ color: '#94a3b8' }} />}
            className="case-queue-search"
            value={query.q}
            onChange={(e) => setQuery({ q: e.target.value })}
            allowClear
          />
          <Popover content={filterPanel} trigger="click" placement="bottomLeft" arrow={false}>
            <Button icon={<FilterOutlined />} aria-label={`추가 필터 ${filterCount}개 적용`}>
              필터
              {filterCount > 0 && <span className="case-queue-filter-count">{filterCount}</span>}
            </Button>
          </Popover>
          <Select<CaseListSort>
            aria-label="정렬"
            className="case-queue-sort"
            value={query.sort}
            onChange={(sort) => setQuery({ sort })}
            options={sortOptions}
            popupMatchSelectWidth={false}
          />
          <HelpLabel label="" help={sortHelp} />
          {chips.length > 0 && (
            <span className="case-queue-total" aria-live="polite">
              현재 조건{' '}
              <strong key={`${filtered.length}-${chips.length}`} className="case-queue-count">
                {filtered.length}건
              </strong>{' '}
              / {tabCases.length}건
            </span>
          )}
        </div>
        {chips.length > 0 && (
          <div className="case-queue-chips" aria-label="적용한 조건">
            {chips.map((chip) => (
              <Tag
                key={chip.key}
                closable
                closeIcon
                onClose={(event) => {
                  event.preventDefault()
                  setQuery(chip.clear)
                }}
              >
                {chip.label}
              </Tag>
            ))}
            <Button type="link" size="small" onClick={() => setQuery(clearConditions(tab))}>
              전체 초기화
            </Button>
          </div>
        )}
        {tab !== 'CLOSED' && (
          <CaseFlowBoard
            cases={boardCases}
            selectedId={selectedId}
            hoverId={hoverId}
            newIds={result?.newIds ?? []}
            next={next}
            motion={!motionPaused}
            onHover={setHoverId}
            onSelect={selectFromBoard}
            activeLane={activeLane}
            onLane={(lane) =>
              setQuery({
                status: activeLane === lane ? 'OPEN_ONLY' : laneStatus[lane],
                selected: null,
              })
            }
          />
        )}
        <Table
          // 탭 · 정렬 · 조건 · 페이지를 바꾸면 줄이 차례로 다시 들어온다 (검색어 입력 중에는 다시 그리지 않는다)
          key={[
            scope,
            tab,
            query.status,
            query.sort,
            query.report,
            query.risk,
            query.slaExceeded,
            page,
          ].join('|')}
          rowKey="caseId"
          className="case-queue-table case-queue-enter"
          columns={columns}
          dataSource={filtered}
          loading={loading}
          size="middle"
          scroll={{ x: showAssignee ? 1040 : 930 }}
          rowClassName={(record) =>
            [
              record.caseId === selectedId ? 'is-selected' : '',
              result?.newIds.includes(record.caseId) ? 'is-new' : '',
              record.status === 'ACTION_FAILED' ? 'is-urgent' : '',
              record.caseId === hoverId ? 'is-hover' : '',
            ].join(' ')
          }
          onRow={(record, index) => ({
            // 줄이 위에서부터 차례로 들어오는 순서. 너무 늦어지지 않게 12줄 뒤로는 같이 들어온다
            style: { '--i': Math.min(index ?? 0, 12) } as CSSProperties,
            onClick: () => onRowClick(record.caseId),
            onMouseEnter: () => setHoverId(record.caseId),
            onMouseLeave: () => setHoverId(null),
            onKeyDown: (event: KeyboardEvent<HTMLElement>) => onRowKeyDown(event, record.caseId),
            tabIndex: 0,
            'aria-expanded': record.caseId === selectedId,
            'aria-label': `Case ${record.caseId} 미리보기`,
          })}
          expandable={{
            showExpandColumn: false,
            expandedRowKeys: selectedId ? [selectedId] : [],
            expandedRowRender: (c) => (
              <CasePreview
                c={c}
                merchantName={merchantNames.get(c.merchantId)}
                restricted={isRestrictedCase(c, myId)}
                open={openCase}
                summarize={openSummary}
              />
            ),
          }}
          locale={{
            emptyText: (
              <Typography.Text type="secondary" style={{ display: 'block', padding: 36 }}>
                {result?.failed && updatedAt === null
                  ? 'Case 목록을 확인하지 못했습니다. 위의 다시 시도로 조회하세요.'
                  : scope === 'MINE' && cases.length === 0
                    ? '맡은 Case가 없습니다. 다른 직원의 사건은 [전체 직원]에서 볼 수 있어요.'
                    : chips.length > 0
                      ? '조건에 맞는 Case가 없습니다.'
                      : tab === 'CLOSED'
                        ? '종료된 Case가 없습니다.'
                        : '열린 Case가 없습니다.'}
              </Typography.Text>
            ),
          }}
          pagination={{
            current: page,
            pageSize: PAGE_SIZE,
            onChange: (next) => setQuery({ page: next, selected: null }),
            showSizeChanger: false,
            showTotal: (total) => (
              <span className="case-queue-keys">
                총 {total}건
                <span aria-hidden="true">
                  <kbd>↑</kbd>
                  <kbd>↓</kbd> 이동 · <kbd>Enter</kbd> 미리보기 · <kbd>S</kbd> 요약 · <kbd>O</kbd>{' '}
                  열기 · <kbd>/</kbd> 검색
                </span>
              </span>
            ),
          }}
        />
      </Card>
      <CaseSummaryDrawer
        caseId={summaryOpenId}
        // 새로고침 · [목록에 반영]처럼 직원이 목록을 갱신하면 열린 요약도 다시 조회한다 (#760 Codex 리뷰)
        reloadKey={`${refreshKey}-${summaryNonce}`}
        merchantName={
          summaryOpenId
            ? merchantNames.get(cases.find((c) => c.caseId === summaryOpenId)?.merchantId ?? '')
            : undefined
        }
        prev={summaryIndex > 0 ? queueOrder[summaryIndex - 1] : null}
        next={
          summaryIndex >= 0 && summaryIndex < queueOrder.length - 1
            ? queueOrder[summaryIndex + 1]
            : null
        }
        onMove={openSummary}
        onOpen={openCase}
        onClose={() => setSummaryId(null)}
      />
    </div>
  )
}

/** 현재 단계와 그 단계에서 볼 한 줄 — 판정 진행 · 결제 병합 남은 시간 */
function StageCell({ c }: { c: CaseSummary }) {
  const merge = c.status === 'CLOSED' ? null : mergeRemainingMin(c.createdAt, c.decidedCount)
  const sub =
    c.status === 'DECISION_PENDING' && c.decidedCount > 0
      ? `${c.paymentCount}건 중 ${c.decidedCount}건 판정`
      : merge !== null
        ? `병합 ${merge}분 남음`
        : null
  return (
    <div className={`case-queue-stage${c.status === 'ACTION_FAILED' ? ' is-urgent' : ''}`}>
      <CodeTag value={caseStatusLabel[c.status]} />
      {sub && (
        <span className={merge !== null && c.decidedCount === 0 ? 'is-merge' : undefined}>
          {sub}
        </span>
      )}
    </div>
  )
}

/** 최고 위험 점수 — 실제 FDS 점수와 0~100 막대. 색은 FDS 판정 구간(50 · 80)을 따른다 */
function RiskScore({ score }: { score: number | null }) {
  if (score === null) return <span className="case-queue-muted">점수 없음</span>
  const width = Math.min(100, Math.max(0, score))
  return (
    <span className={`case-queue-score is-${riskBand(score)}`}>
      <strong>{score}</strong>
      <span className="case-queue-score-bar" aria-hidden="true">
        <i style={{ width: `${width}%` }} />
      </span>
    </span>
  )
}

type CaseActionProps = {
  c: CaseSummary
  restricted: boolean
  open: (caseId: string) => void
}

type CasePreviewProps = CaseActionProps & {
  merchantName: string | undefined
  summarize: (caseId: string) => void
}

/** 상세 진입. 남이 맡은 진행 중 사건은 버튼 대신 안내를 보여 권한 오류로 이어지는 클릭을 줄인다 */
function OpenAction({ c, restricted, open }: CaseActionProps) {
  if (restricted)
    return (
      <span
        className="case-queue-restricted"
        title="진행 중인 사건은 담당 직원만 열람할 수 있습니다"
      >
        <LockOutlined /> 담당 직원만
      </span>
    )
  return (
    <Button
      type="text"
      size="small"
      className="case-queue-open"
      aria-label={`Case ${c.caseId} 상세 열기`}
      onClick={(event: MouseEvent<HTMLElement>) => {
        // 줄 클릭(미리보기)과 겹치지 않게 막는다
        event.stopPropagation()
        open(c.caseId)
      }}
    >
      열기 <ArrowRightOutlined />
    </Button>
  )
}

/**
 * 줄 미리보기 (#759) — 이미 받은 목록 데이터만 쓴다. 상세 조회는 열람 기록을 남기므로 여기서 부르지 않는다.
 * 판정 가능 여부는 현재 증거 버전과 사건 상태를 보는 상세에서 확인한다.
 */
function CasePreview({ c, merchantName, restricted, open, summarize }: CasePreviewProps) {
  const customer = customerPart(c.customerRef)
  const closed = c.status === 'CLOSED'
  const merge = mergeRemainingMin(c.createdAt, c.decidedCount)
  return (
    <div className="case-queue-preview">
      <dl>
        <div>
          <dt>고객</dt>
          <dd>
            <Typography.Text copyable={{ text: customer, tooltips: ['복사', '복사됨'] }}>
              {customer}
            </Typography.Text>
          </dd>
        </div>
        <div>
          <dt>가맹점</dt>
          <dd>{merchantName ? `${merchantName} (ID ${c.merchantId})` : `ID ${c.merchantId}`}</dd>
        </div>
        <div>
          <dt>생성 시각</dt>
          <dd>{dateTime(c.createdAt)}</dd>
        </div>
        {closed ? (
          <div>
            <dt>종료 시각</dt>
            <dd>{c.closedAt ? dateTime(c.closedAt) : '-'}</dd>
          </div>
        ) : (
          <>
            <div>
              <dt>
                <HelpLabel label="조사 기한" help={deadlineHelp} />
              </dt>
              <dd>
                <DeadlineValue createdAt={c.createdAt} status={c.status} />
              </dd>
            </div>
            <div>
              <dt>
                <HelpLabel label="결제 병합" help={mergeHelp} />
              </dt>
              <dd>
                {merge === null ? (
                  <span className="case-queue-muted">마감</span>
                ) : (
                  <span className="case-queue-merge">{merge}분 남음</span>
                )}
              </dd>
            </div>
          </>
        )}
        <div>
          <dt>판정</dt>
          <dd>
            결제 {c.paymentCount}건 중 {c.decidedCount}건 판정
          </dd>
        </div>
        <div>
          <dt>담당</dt>
          <dd>{c.assigneeName ?? '미배정'}</dd>
        </div>
      </dl>
      <div className="case-queue-preview-foot">
        <p>
          {restricted
            ? '진행 중인 사건은 담당 직원만 열람할 수 있습니다.'
            : c.latestReportStatus === 'FAILED'
              ? '보고서 생성에 실패했습니다. 판정 가능 여부는 상세에서 현재 증거 버전과 사건 상태로 확인하세요.'
              : '결제별 FDS 근거 · 보고서 · 판정은 상세에서 확인합니다.'}
        </p>
        {!restricted && (
          <div className="case-queue-preview-actions">
            <Button
              onClick={(event: MouseEvent<HTMLElement>) => {
                event.stopPropagation()
                summarize(c.caseId)
              }}
              title="상세를 조회해 오른쪽에 요약합니다. 열람 기록이 남습니다."
            >
              요약 보기
            </Button>
            <Button type="primary" onClick={() => open(c.caseId)}>
              사건 열기 <ArrowRightOutlined />
            </Button>
          </div>
        )}
      </div>
    </div>
  )
}
