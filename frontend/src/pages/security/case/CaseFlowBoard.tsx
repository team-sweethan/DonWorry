import {
  type CSSProperties,
  type ReactNode,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
} from 'react'
import {
  AimOutlined,
  ArrowRightOutlined,
  CreditCardOutlined,
  DownOutlined,
  FileSearchOutlined,
  UpOutlined,
  UserOutlined,
  WarningOutlined,
} from '@ant-design/icons'
import type { CaseSummary } from '../../../types/case'
import { customerPart, mergeRemainingMin, slaState, SLA_HOURS } from '../../../utils/caseRules'
import { riskBand } from '../../../utils/caseListQuery'
import {
  boardLanes,
  elapsedLabel,
  durationLabel,
  laneColumns,
  laneStats,
  priorityReasonLabel,
  type BoardLaneKey,
} from '../../../utils/caseListView'
import type { PriorityReason } from '../../../utils/securityHome'
import { won } from '../../../utils/format'
import { caseStatusLabel, reportStatusLabel } from './caseLabels'

type Props = {
  /** 지금 조건에 맞는 사건(단계 조건 제외). 종료된 사건은 그리지 않는다 */
  cases: CaseSummary[]
  selectedId: string | null
  hoverId: string | null
  newIds: string[]
  /** 확인 우선순 1위 — 열 수 있는 사건 중에서 고른다 */
  next: { caseId: string; reason: PriorityReason } | null
  /** 꺼져 있으면 단계 이동 날아가기를 하지 않는다 ([움직임 끄기] · 움직임 줄이기 설정) */
  motion: boolean
  onHover: (caseId: string | null) => void
  onSelect: (caseId: string) => void
  /** 레인 이름을 누르면 그 단계로 목록을 거른다 */
  onLane: (lane: BoardLaneKey) => void
  activeLane: BoardLaneKey | null
}

const lanes: Array<{ key: BoardLaneKey; label: string; hint: string; icon: ReactNode }> = [
  {
    key: 'prepare',
    label: '접수 · 보고서 준비',
    hint: '생성됨 · 조사 중',
    icon: <FileSearchOutlined />,
  },
  { key: 'decision', label: '판정 대기', hint: '직원 판정 필요', icon: <UserOutlined /> },
  {
    key: 'action',
    label: '금융 처리 대기',
    hint: '매입 · 승인 취소 완료 대기',
    icon: <CreditCardOutlined />,
  },
  {
    key: 'failed',
    label: '금융 처리 실패',
    hint: '실패 사유 확인 필요',
    icon: <WarningOutlined />,
  },
]

/** 레인마다 이만큼만 타일로 그리고 나머지는 "+N"으로 줄인다 */
const LANE_TILE_LIMIT = 36

// 접기는 이 브라우저에만 기억한다. 저장소를 못 쓰면(사생활 보호 창 등) 펼친 채로 연다
const COLLAPSE_KEY = 'dw.caseFlow.collapsed'
const readCollapsed = () => {
  try {
    return window.localStorage.getItem(COLLAPSE_KEY) === '1'
  } catch {
    return false
  }
}
const saveCollapsed = (collapsed: boolean) => {
  try {
    window.localStorage.setItem(COLLAPSE_KEY, collapsed ? '1' : '0')
  } catch {
    // 기억하지 못해도 화면 동작에는 문제가 없다
  }
}

/**
 * 사건 흐름 보드 (#759) — 진행 사건 하나가 타일 하나, 단계 레인을 따라 왼쪽에서 오른쪽으로 흐른다.
 * 타일 색 = 최고 위험 점수 구간, 진한 모서리 = 생성 후 24시간 경과, 점선 테두리 = 아직 결제가 합쳐질 수 있음,
 * 도는 테두리 = 보고서 생성 중. [목록에 반영]으로 단계가 바뀐 사건은 이전 자리에서 새 레인으로 날아간다.
 * 목록에 이미 있는 데이터만 쓴다. 타일을 눌러도 상세를 조회하지 않고 그 줄 미리보기를 연다.
 */
export function CaseFlowBoard({
  cases,
  selectedId,
  hoverId,
  newIds,
  next,
  motion,
  onHover,
  onSelect,
  onLane,
  activeLane,
}: Props) {
  const grouped = boardLanes(cases)
  const [collapsed, setCollapsed] = useState(readCollapsed)
  // 화면을 켜 두는 동안 칩 바탕이 실제 경과만큼 차오르게 1분마다 기준 시각을 바꾼다
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 60_000)
    return () => window.clearInterval(timer)
  }, [])

  // 단계 이동 날아가기 (FLIP) — 지난 그림의 타일 위치를 기억했다가, 레인이 바뀐 타일을 이전 자리에서 출발시킨다
  const tileRefs = useRef(new Map<string, HTMLElement>())
  const lastRects = useRef(new Map<string, DOMRect>())
  const lastLane = useRef(new Map<string, BoardLaneKey>())
  useLayoutEffect(() => {
    const rects = new Map<string, DOMRect>()
    const laneNow = new Map<string, BoardLaneKey>()
    // 스크립트로 움직이므로 CSS의 움직임 줄이기 규칙이 닿지 않는다. 여기서 직접 확인한다
    const fly = motion && !window.matchMedia('(prefers-reduced-motion: reduce)').matches
    for (const lane of lanes) for (const c of grouped[lane.key]) laneNow.set(c.caseId, lane.key)
    tileRefs.current.forEach((el, id) => {
      const rect = el.getBoundingClientRect()
      rects.set(id, rect)
      const before = lastRects.current.get(id)
      // 레인이 바뀐 사건만 날린다 — 같은 레인 안 순서 변화나 스크롤로는 움직이지 않는다
      if (!fly || !before || lastLane.current.get(id) === laneNow.get(id)) return
      const dx = before.left - rect.left
      const dy = before.top - rect.top
      if (Math.abs(dx) + Math.abs(dy) < 2) return
      el.animate(
        [
          { transform: `translate(${dx}px, ${dy}px) scale(1.25)`, zIndex: 5 },
          { transform: 'translate(0, 0) scale(1)', zIndex: 5 },
        ],
        { duration: 900, easing: 'cubic-bezier(0.22, 1, 0.36, 1)' },
      )
    })
    lastRects.current = rects
    lastLane.current = laneNow
  })

  return (
    <section className={`case-flow${collapsed ? ' is-collapsed' : ''}`} aria-label="사건 흐름 보드">
      <header className="case-flow-head">
        <div className="case-flow-title">
          <strong>사건 흐름</strong>
          <span>
            {collapsed &&
              lanes.map((lane) => (
                <span key={lane.key} className={`case-flow-mini is-${lane.key}`}>
                  {lane.label} <b>{grouped[lane.key].length}</b>
                </span>
              ))}
          </span>
        </div>
        <div className="case-flow-actions">
          {next && (
            <button
              type="button"
              className="case-flow-next"
              onClick={() => onSelect(next.caseId)}
              title="확인 우선순 1위 사건의 줄을 엽니다"
            >
              <AimOutlined aria-hidden="true" />
              다음 확인 <b>Case {next.caseId}</b>
              <span>{priorityReasonLabel[next.reason]}</span>
              <ArrowRightOutlined aria-hidden="true" />
            </button>
          )}
          <button
            type="button"
            className="case-flow-toggle"
            aria-expanded={!collapsed}
            onClick={() => {
              setCollapsed(!collapsed)
              saveCollapsed(!collapsed)
            }}
          >
            {collapsed ? <DownOutlined /> : <UpOutlined />}
            {collapsed ? '펼치기' : '접기'}
          </button>
        </div>
      </header>
      {!collapsed && (
        <>
          {/* 범례 — 칩에 쓰는 모양을 그대로 작게 그려 읽는 법을 보인다 */}
          <ul className="case-flow-legend" aria-label="칩 읽는 법">
            <li>
              <span className="case-flow-sample is-fill" aria-hidden="true">
                <Signal band="review" />
                12
              </span>
              바탕 = 생성 후 경과 ({SLA_HOURS}시간이면 가득)
            </li>
            <li>
              <span className="case-flow-sample is-overdue" aria-hidden="true">
                <Signal band="review" />
                12
              </span>
              {SLA_HOURS}시간 경과
            </li>
            <li>
              <span className="case-flow-signal-set" aria-hidden="true">
                <Signal band="deny" />
                <Signal band="review" />
                <Signal band="low" />
              </span>
              최고 위험 점수 80↑ · 50~79 · 50↓
            </li>
            <li className="is-merge">결제 합쳐질 수 있음</li>
            <li className="is-report">보고서 생성 중</li>
            <li className="case-flow-legend-tip">레인 이름을 누르면 그 단계만 봅니다</li>
          </ul>
          <ol
            className="case-flow-lanes"
            // 사건이 많은 레인이 더 넓게 — 건수가 바뀌면 폭이 부드럽게 바뀐다
            style={
              {
                '--lane-cols': laneColumns(lanes.map((lane) => grouped[lane.key].length)),
              } as CSSProperties
            }
          >
            {lanes.map((lane, laneIndex) => {
              const items = grouped[lane.key]
              const shown = items.slice(0, LANE_TILE_LIMIT)
              const stats = laneStats(items, now)
              return (
                <li
                  key={lane.key}
                  className={`case-flow-lane is-${lane.key}${items.length === 0 ? ' is-empty' : ''}${
                    activeLane === lane.key ? ' is-active' : ''
                  }`}
                  style={{ '--lane': laneIndex } as CSSProperties}
                >
                  <button
                    type="button"
                    className="case-flow-lane-head"
                    aria-pressed={activeLane === lane.key}
                    title={
                      activeLane === lane.key
                        ? '단계 조건 풀기'
                        : `${lane.label}(${lane.hint})만 보기`
                    }
                    onClick={() => onLane(lane.key)}
                  >
                    <span className="case-flow-lane-icon" aria-hidden="true">
                      {lane.icon}
                    </span>
                    <span className="case-flow-lane-name">{lane.label}</span>
                    <b key={items.length}>{items.length}</b>
                  </button>
                  {/* 레인 대기 정보 — 최장 대기와 경과 구간(6시간 미만 · 24시간 미만 · 24시간 경과) 분포 */}
                  <div className="case-flow-lane-age">
                    <span className="case-flow-lane-age-text">
                      <span>
                        {stats.oldestHours === null
                          ? '대기 없음'
                          : `최장 ${durationLabel(stats.oldestHours)}`}
                      </span>
                      {stats.overdue > 0 && (
                        <em>
                          {SLA_HOURS}시간 경과 {stats.overdue}
                        </em>
                      )}
                    </span>
                    <span
                      className="case-flow-meter"
                      aria-label={`6시간 미만 ${stats.fresh}건, 6~${SLA_HOURS}시간 ${stats.waiting}건, ${SLA_HOURS}시간 경과 ${stats.overdue}건`}
                      role="img"
                    >
                      {items.length > 0 && (
                        <>
                          <i className="is-fresh" style={{ flexGrow: stats.fresh }} />
                          <i className="is-waiting" style={{ flexGrow: stats.waiting }} />
                          <i className="is-overdue" style={{ flexGrow: stats.overdue }} />
                        </>
                      )}
                    </span>
                  </div>
                  <LaneTiles
                    count={items.length}
                    shownCount={shown.length}
                    selectedIndex={shown.findIndex((c) => c.caseId === selectedId)}
                    label={lane.label}
                  >
                    {(hiddenFrom) =>
                      shown.map((c, index) => (
                        <FlowTile
                          key={c.caseId}
                          c={c}
                          index={index}
                          laneIndex={laneIndex}
                          overdue={slaState(c.createdAt, c.status, now)?.exceeded === true}
                          now={now}
                          hidden={index >= hiddenFrom}
                          selected={c.caseId === selectedId}
                          hovered={c.caseId === hoverId}
                          isNew={newIds.includes(c.caseId)}
                          isNext={c.caseId === next?.caseId}
                          tileRef={(el) => {
                            if (el) tileRefs.current.set(c.caseId, el)
                            else tileRefs.current.delete(c.caseId)
                          }}
                          onHover={onHover}
                          onSelect={onSelect}
                        />
                      ))
                    }
                  </LaneTiles>
                </li>
              )
            })}
          </ol>
        </>
      )}
    </section>
  )
}

type TileProps = {
  c: CaseSummary
  index: number
  laneIndex: number
  overdue: boolean
  now: number
  /** 3줄 밖으로 접힌 칩 — 키보드 · 화면 낭독기에서도 건너뛴다 */
  hidden: boolean
  selected: boolean
  hovered: boolean
  isNew: boolean
  isNext: boolean
  tileRef: (el: HTMLButtonElement | null) => void
  onHover: (caseId: string | null) => void
  onSelect: (caseId: string) => void
}

/** 타일 하나 — 사건 번호, 모양으로 상태, 올리면 말풍선 카드 */
function FlowTile({
  c,
  index,
  laneIndex,
  overdue,
  now,
  hidden,
  selected,
  hovered,
  isNew,
  isNext,
  tileRef,
  onHover,
  onSelect,
}: TileProps) {
  const merge = mergeRemainingMin(c.createdAt, c.decidedCount, now)
  const running = c.latestReportStatus === 'PENDING' || c.latestReportStatus === 'RUNNING'
  const elapsed = elapsedLabel(c, now)
  const label = [
    `Case ${c.caseId}`,
    caseStatusLabel[c.status].label,
    c.maxRiskScore === null ? '점수 없음' : `${c.maxRiskScore}점`,
    overdue ? `${SLA_HOURS}시간 경과` : '',
    merge !== null ? `결제 병합 ${merge}분 남음` : '',
    running ? '보고서 생성 중' : '',
    isNext ? '다음 확인 사건' : '',
  ]
    .filter(Boolean)
    .join(' · ')
  const classes = [
    'case-flow-tile',
    overdue ? 'is-overdue' : '',
    merge !== null ? 'is-merge' : '',
    running ? 'is-report' : '',
    selected ? 'is-selected' : '',
    hovered ? 'is-hover' : '',
    isNew ? 'is-new' : '',
    isNext ? 'is-next' : '',
  ].join(' ')
  return (
    <>
      <button
        type="button"
        ref={tileRef}
        className={classes}
        style={
          {
            '--i': Math.min(index, 24),
            '--lane': laneIndex,
            // 칩 바탕이 생성 후 24시간까지 왼쪽부터 차오른다
            '--fill': `${Math.round((elapsed?.ratio ?? 0) * 100)}%`,
          } as CSSProperties
        }
        onMouseEnter={() => onHover(c.caseId)}
        onMouseLeave={() => onHover(null)}
        onFocus={() => onHover(c.caseId)}
        onBlur={() => onHover(null)}
        onClick={() => onSelect(c.caseId)}
        aria-pressed={selected}
        aria-label={label}
        tabIndex={hidden ? -1 : undefined}
        aria-hidden={hidden || undefined}
      >
        <Signal band={riskBand(c.maxRiskScore)} />
        <span className="case-flow-id">{c.caseId}</span>
        <span className="case-flow-fill" aria-hidden="true" />
        {isNext && (
          <span className="case-flow-next-flag" aria-hidden="true">
            다음
          </span>
        )}
        <span className="case-flow-tip" aria-hidden="true">
          <b>Case {c.caseId}</b>
          <span>
            {caseStatusLabel[c.status].label} ·{' '}
            {c.maxRiskScore === null ? '점수 없음' : `최고 ${c.maxRiskScore}점`}
          </span>
          <span>
            {c.totalAmount === null ? '총액 -' : won(c.totalAmount)} · 결제 {c.paymentCount}건
          </span>
          <span>
            고객 {customerPart(c.customerRef)} · {elapsed ? `생성 후 ${elapsed.text}` : '종료'}
          </span>
          {(merge !== null || running || c.latestReportStatus === 'FAILED') && (
            <span className="case-flow-tip-note">
              {merge !== null && `결제 병합 ${merge}분 남음`}
              {merge !== null && (running || c.latestReportStatus === 'FAILED') && ' · '}
              {c.latestReportStatus && (running || c.latestReportStatus === 'FAILED')
                ? `보고서 ${reportStatusLabel[c.latestReportStatus].label}`
                : ''}
            </span>
          )}
        </span>
      </button>
    </>
  )
}

/** 위험 신호 막대 — 점수 구간을 3칸 막대로. 화면 낭독기는 타일의 aria-label에서 점수를 읽는다 */
function Signal({ band }: { band: ReturnType<typeof riskBand> }) {
  return (
    <span className={`case-flow-signal is-${band}`} aria-hidden="true">
      <i />
      <i />
      <i />
    </span>
  )
}

/** 레인에서 처음 보이는 줄 수. 넘치는 칩은 "+N건 더 보기"로 접는다 */
const VISIBLE_ROWS = 3

type LaneTilesProps = {
  count: number
  shownCount: number
  /** 선택한 칩 위치. 접힌 칸에 있으면 레인을 펼쳐 보인다 */
  selectedIndex: number
  label: string
  /** hiddenFrom: 이 순서부터는 접힌 칩 */
  children: (hiddenFrom: number) => ReactNode
}

/**
 * 레인 칩 칸 — 처음 3줄만 보이고 나머지는 접는다. 오래된 순이라 급한 칩(24시간 경과 · 다음 확인)이 위 3줄에 온다.
 * 몇 번째 칩부터 접히는지는 실제 그려진 줄 위치로 잰다(폭에 따라 한 줄 칩 수가 바뀐다).
 */
function LaneTiles({ count, shownCount, selectedIndex, label, children }: LaneTilesProps) {
  const [expanded, setExpanded] = useState(false)
  const [hiddenFrom, setHiddenFrom] = useState(Number.POSITIVE_INFINITY)
  const boxRef = useRef<HTMLDivElement>(null)
  const innerRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const box = boxRef.current
    const inner = innerRef.current
    if (!box || !inner) return
    const measure = () => {
      const tiles = Array.from(inner.querySelectorAll<HTMLElement>('.case-flow-tile'))
      if (tiles.length === 0) return setHiddenFrom(Number.POSITIVE_INFINITY)
      // 줄마다 다른 top 값을 모아 3번째 줄 아래에 놓인 첫 칩을 찾는다
      const rows = [...new Set(tiles.map((el) => el.offsetTop))].sort((a, b) => a - b)
      const limit = rows[VISIBLE_ROWS]
      setHiddenFrom(
        limit === undefined
          ? Number.POSITIVE_INFINITY
          : tiles.findIndex((el) => el.offsetTop >= limit),
      )
    }
    // 관찰을 시작하면 바로 한 번 불리므로 처음 잴 때도 여기서 잰다
    const observer = new ResizeObserver(measure)
    observer.observe(inner)
    return () => observer.disconnect()
  }, [shownCount])
  const overflow = Number.isFinite(hiddenFrom) ? shownCount - hiddenFrom : 0
  // 선택한 칩이 접힌 칸에 있으면 펼친다 — 표에서 고른 사건이 보드에서도 보이게
  const open = expanded || (selectedIndex >= 0 && selectedIndex >= hiddenFrom)
  return (
    <>
      <div
        ref={boxRef}
        className={`case-flow-tiles-box${open || overflow === 0 ? ' is-open' : ''}`}
      >
        <div ref={innerRef} className="case-flow-tiles">
          {count === 0 && <span className="case-flow-none">없음</span>}
          {children(open ? Number.POSITIVE_INFINITY : hiddenFrom)}
          {count > shownCount && <span className="case-flow-more">+{count - shownCount}</span>}
        </div>
      </div>
      {overflow > 0 && (
        <button
          type="button"
          className="case-flow-expand"
          aria-expanded={open}
          onClick={() => setExpanded(!open)}
        >
          {open ? `${label} 접기` : `+${overflow}건 더 보기`}
        </button>
      )}
    </>
  )
}
