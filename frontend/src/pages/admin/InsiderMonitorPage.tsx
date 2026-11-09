import { DownOutlined, ReloadOutlined } from '@ant-design/icons'
import { Alert, Button, Space, Tag, Timeline, Typography } from 'antd'
import { ListEmpty } from '../../components/common/ListEmpty'
import { ListLoading } from '../../components/common/ListLoading'
import dayjs from 'dayjs'
import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import {
  getInsiderMonitorStaff,
  getInsiderMonitorTimeline,
  type InsiderMonitorEvent,
  type InsiderMonitorStaff,
  type InsiderMonitorStage,
  type InsiderMonitorTimeline,
} from '../../api/insiderMonitor'
import { PageHeader } from '../../components/common/PageHeader'
import { pollStep } from '../../utils/casePolling'
import {
  INSIDER_MONITOR_POLL_LIMIT_MS,
  INSIDER_MONITOR_POLL_MS,
} from '../../utils/insiderMonitorPolling'
import { displayRuleCode, displayRuleText } from '../../utils/ruleDisplayCode'
import { InsiderCaseDrawer } from './InsiderCasesPage'
import { InsiderOffice } from './InsiderOffice'
import { SeverityMark } from './InsiderParts'
import {
  accessLabel,
  alertsPath,
  denyLabel,
  eventKey,
  fieldLabel,
  logFilters,
  matchesLogFilter,
  newLogCount,
  severityView,
  type LogFilter,
} from './insiderView'
import {
  incidentFor,
  stageClass,
  stageSummary,
  stageView,
  staffByUrgency,
} from './insiderOfficeView'
import './caseAssignmentShared.css'
import './InsiderMonitorPage.css'

// 타임라인 칸이 맨 아래에서 이만큼 안이면 새 줄을 따라 내려간다
const FOLLOW_GAP_PX = 40
const loginLabel: Record<string, string> = {
  LOGIN_SUCCESS: '로그인 성공',
  LOGIN_FAILURE: '로그인 실패',
  LOGOUT: '로그아웃',
}
const lockReasonLabel: Record<string, string> = {
  INSIDER_EXPORT_BLOCKED: '경보 중 내보내기 차단 · 세션 종료',
  INSIDER_CASE_DECISION: '내부자 사건 판정 중 정지',
}
const decisionLabel: Record<string, string> = {
  NORMAL_DUTY: '정상 업무',
  CAUTION_NOTICE: '주의 조치',
  DISCIPLINE_REQUEST: '징계 요청',
  DISCIPLINE_LOCK_RELEASE: '징계 뒤 정지 해제',
}
// "N건"은 거래 목록 · 내보내기에만 붙인다
const rowCountTypes = new Set(['TRANSACTION_LIST_VIEW', 'TRANSACTION_EXPORT'])

const clock = (iso: string) => dayjs(iso).format('HH:mm:ss')

// 한 줄 설명 — 원문 · 고객 번호는 응답에 없다
function EventText({ event }: { event: InsiderMonitorEvent }) {
  if (event.kind === 'LOGIN') {
    return (
      <Space wrap size={4}>
        <strong>{loginLabel[event.type] ?? event.type}</strong>
        {event.device && <span>{event.device}</span>}
        {event.clientIp && <Tag>{event.clientIp}</Tag>}
      </Space>
    )
  }
  if (event.kind === 'ACCESS') {
    return (
      <Space wrap size={4}>
        <strong>{accessLabel[event.type] ?? event.type}</strong>
        {event.merchantName && <span>{event.merchantName}</span>}
        {event.caseId !== null && <span>{`사건 #${event.caseId}`}</span>}
        {event.fieldType && <span>{fieldLabel[event.fieldType] ?? event.fieldType}</span>}
        {event.itemCount !== null && rowCountTypes.has(event.type) && (
          <span>{`${event.itemCount}건`}</span>
        )}
        {event.customerScope === 'UNRELATED' && <Tag color="gold">무관 고객 검색</Tag>}
        {event.customerScope === 'RELATED' && <Tag>담당 사건 고객</Tag>}
        {event.result === 'DENIED' && (
          <Tag color="red">{`거부 · ${denyLabel[event.denyReason ?? ''] ?? event.denyReason ?? ''}`}</Tag>
        )}
      </Space>
    )
  }
  if (event.kind === 'ALERT') {
    return (
      <Space wrap size={4}>
        <strong>{event.type === 'ALERT_CLOSED' ? '경보 닫힘' : '경보'}</strong>
        <span>{`${displayRuleCode(event.ruleCode ?? '')} ${event.ruleLabel}`}</span>
        {event.severity &&
          (event.type === 'ALERT_CLOSED' ? (
            <span className="dw-caption">{severityView[event.severity].label}</span>
          ) : (
            <SeverityMark value={event.severity} />
          ))}
        {event.type === 'ALERT_RAISED' && event.summary && (
          <Typography.Text type="secondary">{displayRuleText(event.summary)}</Typography.Text>
        )}
      </Space>
    )
  }
  if (event.type === 'RELEASED') {
    return (
      <Space wrap size={4}>
        <strong>정지 해제</strong>
        <span>
          {`내부자 사건 #${event.insiderCaseId ?? '—'} · ${decisionLabel[event.decision ?? ''] ?? event.decision ?? ''}`}
        </span>
      </Space>
    )
  }
  return (
    <Space wrap size={4}>
      <strong>계정 정지</strong>
      <span>{lockReasonLabel[event.lockReason ?? ''] ?? event.lockReason}</span>
    </Space>
  )
}

function eventColor(event: InsiderMonitorEvent) {
  if (event.kind === 'LOCK') return event.type === 'RELEASED' ? 'green' : 'red'
  if (event.kind === 'ALERT') {
    if (event.type === 'ALERT_CLOSED') return 'gray'
    return event.severity === 'CAUTION' ? 'gold' : event.severity === 'HIGH' ? 'orange' : 'red'
  }
  if (event.kind === 'LOGIN') return event.type === 'LOGIN_FAILURE' ? 'red' : 'blue'
  if (event.result === 'DENIED') return 'red'
  if (event.customerScope === 'UNRELATED' || event.type === 'CONTACT_RAW_VIEW') return 'gold'
  return 'gray'
}

// 닮은 실제 사고 — "사례 출처 › 공식"(회사 공지 · 판결) 묶음의 사실만, 짧게 (ADR-0082 조각 10 D4)
const incidents: Partial<
  Record<InsiderMonitorStage, { type: 'warning' | 'error'; title: string; lines: string[] }>
> = {
  INSIDER_CASE: {
    type: 'warning',
    title: '닮은 실제 사고 — 신한카드 가맹점 대표자 정보 (회사 공식 발표)',
    lines: [
      '영업소 직원 12명이 2022년 3월~2025년 5월 가맹점 대표자 정보 19만 2,088건을 신규 카드 모집에 썼어요.',
      '조회한 화면을 촬영해 넘겼고, 회사는 3년여 뒤 공익 제보로 알게 됐어요.',
      '돈워리는 하나씩은 허용된 조회라도 겹치면 사건으로 묶어 관리자에게 넘겨요.',
    ],
  },
  BLOCKED: {
    type: 'error',
    title: '닮은 실제 사고 — 2014년 카드 3사 정보 유출 (판결)',
    lines: [
      '신용평가사 직원(FDS 개발 총괄)이 외부 저장장치로 1억여 건을 빼냈어요.',
      '징역 3년이 선고됐고, 2심은 USB 반출입 통제가 미흡했다고 봤어요.',
      '돈워리는 경보 중 내보내기를 그 순간 막고 계정을 정지해요.',
    ],
  },
}

// 접으면 제목 한 줄만 남아 타임라인이 길어진다. 처음엔 펼친 채로(시연 · 처음 보는 사람), 접은 뒤엔 다른 직원을 골라도 접힌 채로
function IncidentCard({
  stage,
  open,
  onToggle,
}: {
  stage: InsiderMonitorStage
  open: boolean
  onToggle: () => void
}) {
  const incident = incidents[stage]
  if (!incident) return null
  return (
    <Alert
      type={incident.type}
      showIcon
      className="im-incident"
      message={
        <span className="im-incident-head">
          <span>{incident.title}</span>
          <button
            type="button"
            className="im-incident-toggle"
            aria-expanded={open}
            aria-controls="im-incident-body"
            onClick={onToggle}
          >
            {open ? '접기' : '펼치기'}
            <DownOutlined aria-hidden="true" rotate={open ? 180 : 0} />
          </button>
        </span>
      }
      description={
        open ? (
          <ul id="im-incident-body" className="im-incident-body">
            {incident.lines.map((line) => (
              <li key={line}>{line}</li>
            ))}
          </ul>
        ) : undefined
      }
    />
  )
}

/** 내부자 관제 한 화면 (#679). 왼쪽 보안팀 사무실(직원 = 책상, 급한 순) · 오른쪽 고른 직원의 조치 · 사고 카드 · 로그인부터 기록 */
export function InsiderMonitorPage() {
  const [searchParams] = useSearchParams()
  // 경보 · 사건 탭의 직원 이름에서 오면(?staff=) 그 직원을 고른 채로 연다. 주소는 들어올 때 한 번만 읽는다
  const [initialStaff] = useState<number | null>(() => {
    const id = Number(searchParams.get('staff'))
    return Number.isInteger(id) && id > 0 ? id : null
  })
  const [staff, setStaff] = useState<InsiderMonitorStaff[] | null>(null)
  const [selected, setSelected] = useState<number | null>(initialStaff)
  const [timeline, setTimeline] = useState<InsiderMonitorTimeline | null>(null)
  const [error, setError] = useState(false)
  const [caseId, setCaseId] = useState<number | null>(null)
  const [incidentOpen, setIncidentOpen] = useState(true)
  const selectedRef = useRef<number | null>(initialStaff)
  const busy = useRef(false)
  // 자동 갱신 시계: 마지막 사용자 조작 시각(null이면 다음 갱신부터 센다). 멈춤 시간이 지나면 stopped (#691)
  const pollFrom = useRef<number | null>(null)
  const [stopped, setStopped] = useState(false)
  const scrollBox = useRef<HTMLDivElement>(null)
  // 관리자가 위로 올려 지난 줄을 보는 중이면 false — 자동으로 끌어내리지 않는다
  const followBottom = useRef(true)
  // 맨 아래를 떠날 때 받아 둔 줄 — 그 밖의 줄을 "새 기록 N줄"로 센다. 맨 아래에 있으면 null
  const [seen, setSeen] = useState<ReadonlySet<string> | null>(null)
  // 기록 거르기 — 직원을 바꿔도 유지한다
  const [logFilter, setLogFilter] = useState<LogFilter>('ALL')
  const focusDetail = useRef(false)
  const detailRef = useRef<HTMLElement>(null)
  // 처음 열 때 가장 급한 직원을 한 번만 자동으로 고른다 — 오른쪽 칸이 비어 있지 않게
  const autoPicked = useRef(initialStaff !== null)
  // 사무실 목록을 마지막으로 받은 시각
  const [lastAt, setLastAt] = useState<string | null>(null)

  const refresh = useCallback(async () => {
    if (busy.current) return
    busy.current = true
    const target = selectedRef.current
    try {
      // 고른 직원 기록이 실패해도(undefined) 직원 목록은 반영한다 — 한쪽 실패로 사무실 전체가 멈추지 않게
      const [list, next] = await Promise.all([
        getInsiderMonitorStaff(),
        target === null
          ? Promise.resolve(null)
          : getInsiderMonitorTimeline(target).catch(() => undefined),
      ])
      setStaff(list)
      setLastAt(new Date().toISOString())
      const timelineFailed = next === undefined
      // 그사이 다른 직원을 골랐으면 버린다
      if (selectedRef.current === target) {
        if (!timelineFailed) setTimeline(next)
        else if (!list.some((s) => s.staffId === target)) {
          // 사무실에도 없고 기록도 없는 직원(잘못된 ?staff= 주소 등) — 고른 것을 풀고 아래에서 가장 급한 직원을 고른다
          selectedRef.current = null
          setSelected(null)
          setTimeline(null)
          followBottom.current = true
          setSeen(null)
          autoPicked.current = false
        }
      }
      if (!autoPicked.current && selectedRef.current === null && list.length > 0) {
        autoPicked.current = true
        const first = staffByUrgency(list)[0].staffId
        selectedRef.current = first
        setSelected(first)
        void getInsiderMonitorTimeline(first)
          .then((picked) => {
            if (selectedRef.current === first) setTimeline(picked)
          })
          .catch(() => setError(true))
      }
      // 오류 안내는 지금 고른 직원 기록이 안 올 때만 — 선택을 푼 경우는 대신 고른 직원이 보인다
      setError(timelineFailed && selectedRef.current === target)
    } catch {
      setError(true)
    } finally {
      busy.current = false
    }
  }, [])

  // 사용자가 직접 조작했다 — 멈춤 시계를 다시 0부터 센다
  const resetPoll = useCallback(() => {
    pollFrom.current = Date.now()
    setStopped(false)
  }, [])

  const restart = useCallback(() => {
    resetPoll()
    void refresh()
  }, [resetPoll, refresh])

  useEffect(() => {
    const tick = () => {
      if (document.visibilityState !== 'visible') return
      const step = pollStep(pollFrom.current, Date.now(), INSIDER_MONITOR_POLL_LIMIT_MS)
      pollFrom.current = step.from
      if (step.poll) void refresh()
      else setStopped(true)
    }
    // 탭으로 돌아오면 자리를 비운 사이 바뀐 상태를 반영하고 시계를 다시 센다
    const onVisibility = () => {
      if (document.visibilityState === 'visible') restart()
    }
    tick()
    const timer = window.setInterval(tick, INSIDER_MONITOR_POLL_MS)
    document.addEventListener('visibilitychange', onVisibility)
    return () => {
      window.clearInterval(timer)
      document.removeEventListener('visibilitychange', onVisibility)
    }
  }, [refresh, restart])

  // 새 줄이 오면 타임라인 칸을 맨 아래로 (화면 전체가 아니라 칸 안에서만)
  useEffect(() => {
    const box = scrollBox.current
    if (box && followBottom.current) box.scrollTop = box.scrollHeight
  }, [timeline, logFilter])

  // 새 기록 버튼 — 맨 아래로 내려가 다시 따라간다
  const jumpToLatest = () => {
    const box = scrollBox.current
    followBottom.current = true
    setSeen(null)
    if (box) box.scrollTop = box.scrollHeight
  }

  // 책상을 누르면 오른쪽 기록이 바뀐다. 좁은 화면(위아래로 쌓임)에서만 기록 칸으로 내려간다
  const select = (staffId: number) => {
    resetPoll()
    autoPicked.current = true
    if (selectedRef.current === staffId) return
    selectedRef.current = staffId
    followBottom.current = true
    setSeen(null)
    focusDetail.current = true
    setSelected(staffId)
    setTimeline(null)
    getInsiderMonitorTimeline(staffId)
      .then((next) => {
        if (selectedRef.current === staffId) setTimeline(next)
      })
      .catch(() => setError(true))
  }

  useEffect(() => {
    if (selected === null || !focusDetail.current) return
    focusDetail.current = false
    if (!window.matchMedia('(max-width: 900px)').matches) return
    detailRef.current?.focus({ preventScroll: true })
    detailRef.current?.scrollIntoView({
      behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches
        ? 'instant'
        : 'smooth',
      block: 'start',
    })
  }, [selected])

  const sorted = staff ? staffByUrgency(staff) : null
  const current = timeline?.staff ?? null
  // 판정할 사건이 열린 직원만 사고 카드 (#740)
  const incident = current ? incidentFor(current) : null
  const shownEvents = timeline
    ? timeline.events.filter((event) => matchesLogFilter(event, logFilter))
    : []
  // 새 기록은 지금 거르기에 맞는 줄만 센다 — 버튼을 눌러 내려가면 그만큼 보이게
  const newCount = timeline ? newLogCount(timeline.events, seen, timeline.truncated, logFilter) : 0
  // 타임라인이 오기 전에도 이름 · 단계는 목록에서 바로 보인다
  const selectedStaff = current ?? staff?.find((s) => s.staffId === selected) ?? null
  return (
    <div className="im-page">
      <PageHeader
        title="내부자 관제"
        description={`직원을 고르면 마지막 로그인부터 지금까지 활동 기록과 열린 경보를 오른쪽에서 확인하고, 내부자 사건이면 바로 판정할 수 있어요. 원문 값과 고객 번호는 보이지 않아요. 화면을 보는 동안 ${INSIDER_MONITOR_POLL_MS / 1000}초마다 새로 고치고, ${INSIDER_MONITOR_POLL_LIMIT_MS / 60_000}분 동안 조작이 없으면 멈춰요.`}
      />
      {stopped && (
        <Alert
          type="info"
          showIcon
          className="dw-page-alert"
          message={`${INSIDER_MONITOR_POLL_LIMIT_MS / 60_000}분 동안 조작이 없어 자동 갱신을 멈췄습니다. 자리를 비운 동안 로그인 유지 시간이 늘어나지 않게 하기 위해서예요.`}
          action={
            <Button size="small" icon={<ReloadOutlined />} onClick={restart}>
              다시 시작
            </Button>
          }
        />
      )}
      {error && (
        <Alert
          type="error"
          showIcon
          message="관제 정보를 불러오지 못했습니다. 잠시 뒤 다시 시도해요."
          className="dw-page-alert"
        />
      )}
      <div className="im-console">
        <section className="im-office ca-panel-box" aria-labelledby="im-office-heading">
          <header className="im-panel-head">
            <h2 id="im-office-heading" className="dw-section-title">
              보안팀 사무실
            </h2>
            {lastAt && <span className="im-clock">{`${clock(lastAt)} 갱신`}</span>}
          </header>
          {sorted && sorted.length > 0 && (
            <div className="im-summary">
              <span>{`${sorted.length}명`}</span>
              {stageSummary(sorted).map(({ stage, count }) => (
                <span key={stage} className={`im-summary-item ${stageClass[stage]}`}>
                  <span className="im-stage-icon" aria-hidden="true" />
                  {stageView[stage].label} <b>{count}</b>
                </span>
              ))}
            </div>
          )}
          {sorted && sorted.length > 0 && (
            <p className="im-legend">모니터 색 = 단계 · 노란 쪽지 = 열린 경보 · 급한 직원이 위</p>
          )}
          {sorted === null ? (
            error ? (
              <ListEmpty title="보안 직원 목록을 불러오지 못했습니다." />
            ) : (
              <ListLoading label="보안 직원을 불러오는 중" rows={4} columns={1} />
            )
          ) : sorted.length === 0 ? (
            <ListEmpty title="사용 중이거나 정지된 보안 직원이 없어요." mascot />
          ) : (
            <InsiderOffice staff={sorted} openStaffId={selected} onSelect={select} />
          )}
        </section>

        <section
          ref={detailRef}
          tabIndex={-1}
          className="im-detail ca-panel-box"
          aria-labelledby="im-detail-heading"
        >
          {selectedStaff === null && selected !== null && !error ? (
            // 주소(?staff=)로 온 직원이 사무실에 없으면 목록이 아니라 기록이 와야 이름을 안다
            <ListLoading label="불러오는 중이에요." rows={6} columns={2} />
          ) : selectedStaff === null ? (
            <div className="im-detail-empty">
              <ListEmpty
                title={
                  sorted && sorted.length > 0
                    ? '왼쪽에서 책상을 골라 주세요.'
                    : '볼 직원 기록이 없어요.'
                }
              />
            </div>
          ) : (
            <>
              <header className="im-detail-head">
                <div className="im-detail-who">
                  <h2 id="im-detail-heading" className="dw-section-title">
                    {`${selectedStaff.staffName} 자리 기록`}
                  </h2>
                  <span className={`im-summary-item ${stageClass[selectedStaff.stage]}`}>
                    <span className="im-stage-icon" aria-hidden="true" />
                    {stageView[selectedStaff.stage].label}
                    {selectedStaff.openAlertCount === 0 &&
                      ` · ${stageView[selectedStaff.stage].help}`}
                  </span>
                  {selectedStaff.openAlertCount > 0 && (
                    <Link className="im-detail-alerts" to={alertsPath(selectedStaff.staffId)}>
                      {`열린 경보 ${selectedStaff.openAlertCount}건 보기 →`}
                    </Link>
                  )}
                </div>
                {/* 관리자가 할 일은 맨 위 오른쪽 한 곳 — 판정 창은 이 버튼으로만 연다 */}
                {selectedStaff.openInsiderCaseId != null && (
                  <div className="im-detail-action">
                    <Button
                      type="primary"
                      onClick={() => setCaseId(selectedStaff.openInsiderCaseId)}
                    >
                      {`내부자 사건 #${selectedStaff.openInsiderCaseId} 판정하기`}
                    </Button>
                    <span className="dw-caption">
                      해제 검토 보고서 확인과 판정을 이 창에서 해요.
                    </span>
                  </div>
                )}
              </header>
              <div className="im-detail-body">
                {staff && !staff.some((s) => s.staffId === selectedStaff.staffId) && (
                  <Alert
                    type="info"
                    showIcon
                    message="사용을 멈춘 계정이라 사무실에 책상이 없어요. 남은 기록만 보여 줘요."
                  />
                )}
                {timeline === null ? (
                  <ListLoading label="불러오는 중이에요." rows={6} columns={2} />
                ) : (
                  <>
                    <div className="im-detail-meta">
                      <span className="dw-caption">
                        {`${dayjs(timeline.from).format('MM.DD HH:mm:ss')}부터(마지막 로그인 30분 전) · 마지막 갱신 ${clock(timeline.to)}`}
                      </span>
                      <span className="dw-caption">{`${timeline.events.length}줄`}</span>
                    </div>
                    {timeline.truncated && (
                      <Alert type="info" showIcon message="줄이 많아 최근 200줄만 보여 줘요." />
                    )}
                    {incident && (
                      <IncidentCard
                        stage={incident}
                        open={incidentOpen}
                        onToggle={() => setIncidentOpen((v) => !v)}
                      />
                    )}
                    {timeline.events.length === 0 ? (
                      <div className="im-detail-empty">
                        <ListEmpty title="이 구간에 기록이 없어요." />
                      </div>
                    ) : (
                      <>
                        <div className="im-log-filters" role="group" aria-label="기록 거르기">
                          {logFilters.map((filter) => (
                            <button
                              key={filter.key}
                              type="button"
                              className={`im-log-filter${logFilter === filter.key ? ' is-active' : ''}`}
                              aria-pressed={logFilter === filter.key}
                              onClick={() => {
                                resetPoll()
                                followBottom.current = true
                                setSeen(null)
                                setLogFilter(filter.key)
                              }}
                            >
                              {filter.label}
                              <span className="im-log-filter-count">
                                {
                                  timeline.events.filter((event) =>
                                    matchesLogFilter(event, filter.key),
                                  ).length
                                }
                              </span>
                            </button>
                          ))}
                        </div>
                        <div
                          ref={scrollBox}
                          className="im-log"
                          onScroll={(event) => {
                            const box = event.currentTarget
                            const atBottom =
                              box.scrollHeight - box.scrollTop - box.clientHeight < FOLLOW_GAP_PX
                            if (atBottom === followBottom.current) return
                            followBottom.current = atBottom
                            // 맨 아래를 떠나는 순간 받아 둔 줄 전부 — 그 밖의 줄을 새 기록으로 센다
                            setSeen(atBottom ? null : new Set(timeline.events.map(eventKey)))
                          }}
                        >
                          {shownEvents.length === 0 ? (
                            <ListEmpty title="이 조건에 맞는 기록이 없어요." />
                          ) : (
                            <Timeline
                              items={shownEvents.map((event, index) => ({
                                key: `${event.kind}-${event.occurredAt}-${index}`,
                                color: eventColor(event),
                                children: (
                                  // 시각 칸은 줄바꿈 · 줄어듦 없이, 긴 요약은 오른쪽 칸에서만 줄바꿈
                                  <div className="im-log-row">
                                    <span className="im-log-time">{clock(event.occurredAt)}</span>
                                    <div className="im-log-text">
                                      <EventText event={event} />
                                    </div>
                                  </div>
                                ),
                              }))}
                            />
                          )}
                          {newCount !== 0 && (
                            <button type="button" className="im-log-new" onClick={jumpToLatest}>
                              {newCount === 'many' ? '새 기록이 많아요' : `새 기록 ${newCount}줄`}
                              <DownOutlined aria-hidden="true" />
                            </button>
                          )}
                        </div>
                      </>
                    )}
                  </>
                )}
              </div>
            </>
          )}
        </section>
      </div>
      {caseId !== null && (
        <InsiderCaseDrawer
          key={caseId}
          insiderCaseId={caseId}
          onClose={() => setCaseId(null)}
          onChanged={restart}
        />
      )}
    </div>
  )
}
