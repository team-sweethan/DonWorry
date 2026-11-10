import { ReloadOutlined } from '@ant-design/icons'
import { Alert, Button, Tag } from 'antd'
import dayjs from 'dayjs'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  getLoginSecurity,
  type LoginAttemptAccount,
  type LoginSecurityAttempt,
  type LoginSecurityDashboard,
  type LoginSecurityState,
} from '../../api/loginSecurity'
import { ListEmpty } from '../../components/common/ListEmpty'
import { ListLoading } from '../../components/common/ListLoading'
import { PageHeader } from '../../components/common/PageHeader'
import { pollStep } from '../../utils/casePolling'
import { INSIDER_MONITOR_POLL_LIMIT_MS } from '../../utils/insiderMonitorPolling'
import { displayRuleCode, displayRuleText } from '../../utils/ruleDisplayCode'
import { InsiderCaseDrawer } from './InsiderCasesPage'
import { SeverityMark } from './InsiderParts'
import { LoginAttackRadar, type RadarNotice } from './LoginAttackRadar'
import {
  attemptKey,
  buildIncidents,
  type Incident,
  type IncidentKind,
  type Launch,
  REPLAY_DELAY_MS,
  scheduleReplay,
} from './loginSecurityTimeline'
import './caseAssignmentShared.css'
import './LoginSecurityPage.css'

// 공격 장면을 따라가도록 자주 읽는다. 멈춤 기준(5분 무조작)은 내부자 관제와 같다
const POLL_MS = 2_000
// 화면 시계(서버 시각 기준, 재생 지연만큼 뒤)를 움직이는 간격 — 실시간 시도 줄 · 사건 번호가 레이더 점과 같이 나타난다
const VIEW_TICK_MS = 250

const stateClass: Record<LoginSecurityState, string> = {
  NORMAL: 'is-normal',
  CAUTION: 'is-caution',
  UNDER_ATTACK: 'is-attack',
}

const accountLabel: Record<LoginAttemptAccount, string> = {
  UNKNOWN: '없는 아이디',
  SECURITY_STAFF: '보안 직원',
  ADMIN: '관리자 계정',
  OPERATOR: '가맹점 계정',
}

// 사건 종류마다 같은 색 — 그래프 표시선 · 사건 목록 · 실시간 시도 번호가 같은 색을 쓴다
const incidentColor: Record<IncidentKind, string> = {
  entry: '#b45309',
  takeover: '#dc2626',
  relogin: '#7c3aed',
  block: '#7f1d1d',
}
const circled = (n: number) => '①②③④⑤⑥⑦⑧⑨⑩'[n - 1] ?? `(${n})`

const time = (iso: string) => dayjs(iso).format('HH:mm:ss')
const ms = (iso: string) => new Date(iso).getTime()

function bannerText(d: LoginSecurityDashboard) {
  const recent = `최근 ${d.windowMinutes}분 실패율 ${d.recentFailureRatePercent}% · 시도 ${d.recentAttempts}건`
  if (d.state === 'UNDER_ATTACK')
    return {
      head: `로그인 경계 중 — ${recent}`,
      sub: '로그인은 막지 않습니다. 이 동안 처음 보는 브라우저로 들어온 보안 직원만 탈취 의심(SEC-INS-10)으로 올립니다.',
    }
  if (d.state === 'CAUTION')
    return {
      head: `주의 — ${recent}`,
      sub: `실패율은 높지만 시도가 ${d.minAttempts}건이 안 돼 경계 전입니다. 오타 몇 번으로 경계가 켜지지 않게 하는 장치예요.`,
    }
  return {
    head: `평소 — ${recent}`,
    sub: `시도 ${d.minAttempts}건 이상 · 실패율 ${d.failureRatePercentThreshold}% 이상이면 경계로 바뀝니다.`,
  }
}

function noticeOf(i: Incident): RadarNotice {
  const head = circled(i.n)
  if (i.kind === 'entry') return { key: i.key, text: `${head} 경계 진입`, tone: 'alert' }
  if (i.kind === 'takeover')
    return { key: i.key, text: `${head} 탈취 의심 · ${i.tip[0]}`, tone: 'takeover' }
  if (i.kind === 'relogin') return { key: i.key, text: `${head} ${i.title}`, tone: 'takeover' }
  return { key: i.key, text: `${head} ${i.title}`, tone: 'block' }
}

function NumberMark({ incident }: { incident: Incident }) {
  return (
    <span
      className="ls-num"
      style={{ background: incidentColor[incident.kind] }}
      aria-label={`사건 ${incident.n}`}
    >
      {incident.n}
    </span>
  )
}

/** 경계까지 — 두 기준을 각각 채워 가는 막대. 둘 다 넘으면 경계 */
function Gauge({
  label,
  value,
  limit,
  unit,
}: {
  label: string
  value: number
  limit: number
  unit: string
}) {
  const reached = value >= limit
  const fill = limit === 0 ? 100 : Math.min(100, Math.round((value * 100) / limit))
  return (
    <div className={`ls-gauge${reached ? ' is-reached' : ''}`}>
      <div className="ls-gauge-text">
        <span>{label}</span>
        <strong>
          {value}
          {unit} <small>/ 기준 {`${limit}${unit}`}</small>
        </strong>
      </div>
      <div
        className="ls-gauge-track"
        role="meter"
        aria-label={label}
        aria-valuemin={0}
        aria-valuemax={limit}
        aria-valuenow={Math.min(value, limit)}
      >
        <div className="ls-gauge-fill" style={{ width: `${fill}%` }} />
      </div>
    </div>
  )
}

// 꺾은선 그림 크기(viewBox) — 실제 폭은 칸에 맞춰 늘어난다. 위쪽 여백은 사건 번호 자리
const W = 640
const H = 230
const PAD = { left: 34, right: 64, top: 40, bottom: 24 }

/**
 * 10초 단위 시도 · 실패 꺾은선 + 경계 구간 음영 + 사건 번호 표시선 (#788).
 * 칸에 마우스를 올리면 그 칸 숫자를, 번호 표시선 · 아래 사건 줄에 올리면 그 사건 설명을 보인다.
 */
function AttemptLine({
  d,
  incidents,
  onOpenCase,
}: {
  d: LoginSecurityDashboard
  incidents: Incident[]
  onOpenCase: (id: number) => void
}) {
  const [hover, setHover] = useState<number | null>(null)
  const [active, setActive] = useState<string | null>(null)
  const points = d.buckets
  const peak = Math.max(5, ...points.map((p) => p.attempts))
  const yMax = Math.ceil(peak / 5) * 5
  const plotW = W - PAD.left - PAD.right
  const plotH = H - PAD.top - PAD.bottom
  const x = (i: number) => PAD.left + (points.length <= 1 ? 0 : (i * plotW) / (points.length - 1))
  const y = (v: number) => PAD.top + plotH - (v * plotH) / yMax
  const path = (pick: (i: number) => number) =>
    points
      .map((_, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(1)},${y(pick(i)).toFixed(1)}`)
      .join(' ')
  const last = points.length - 1
  const shown = hover ?? last
  const at = points[shown]
  const step = points.length > 1 ? plotW / (points.length - 1) : plotW
  const firstStart = points[0] ? ms(points[0].start) : 0
  const bucketMs = d.bucketSeconds * 1_000
  const xt = (iso: string) => x(Math.max(0, Math.min(last, (ms(iso) - firstStart) / bucketMs)))

  // 같은 칸에 몰린 번호는 동그라미만 옆으로 비킨다
  const markers: { incident: Incident; lineX: number; dotX: number }[] = []
  for (const incident of incidents) {
    const lineX = xt(incident.at)
    const prev = markers.at(-1)
    const dotX = prev && lineX - prev.lineX < 22 ? prev.dotX + 22 : lineX
    markers.push({ incident, lineX, dotX: Math.min(W - PAD.right + 20, dotX) })
  }
  const tip = markers.find((m) => m.incident.key === active)

  return (
    <section className="ls-chart ca-panel-box" aria-labelledby="ls-chart-heading">
      <header className="ls-panel-head">
        <h2 id="ls-chart-heading" className="dw-section-title">
          로그인 시도 · 실패 ({d.bucketSeconds}초 단위)
        </h2>
        <div className="ls-legend" aria-hidden="true">
          <span className="ls-key is-attempts">시도</span>
          <span className="ls-key is-failures">실패</span>
          <span className="ls-key is-zone">경계 구간</span>
          <span className="ls-key is-mark">사건 표시</span>
        </div>
      </header>
      {at && (
        <p className="ls-readout" aria-live="off">
          {`${time(at.start)}부터 ${d.bucketSeconds}초 · 시도 ${at.attempts}건 · 실패 ${at.failures}건`}
          {at.unknownAccountFailures > 0 && ` (없는 아이디 ${at.unknownAccountFailures}건)`}
          {hover === null && ' · 가장 최근'}
        </p>
      )}
      <div className="ls-chart-body">
        <svg
          className="ls-svg"
          viewBox={`0 0 ${W} ${H}`}
          role="img"
          aria-label={`최근 ${d.windowMinutes}분 ${d.bucketSeconds}초 단위 로그인 시도와 실패. 가장 최근 칸 시도 ${points[last]?.attempts ?? 0}건, 실패 ${points[last]?.failures ?? 0}건`}
          onMouseLeave={() => {
            setHover(null)
            setActive(null)
          }}
        >
          {d.attackPeriods.map((p) => {
            const x1 = p.start === null ? x(0) : xt(p.start)
            const x2 = p.end === null ? x(last) : xt(p.end)
            return (
              <rect
                key={`${p.start ?? 'before'}-${p.end ?? 'now'}`}
                className="ls-zone"
                x={x1}
                y={PAD.top}
                width={Math.max(0, x2 - x1)}
                height={plotH}
              />
            )
          })}
          {[0, 0.5, 1].map((r) => (
            <g key={r}>
              <line
                className="ls-grid"
                x1={PAD.left}
                x2={W - PAD.right}
                y1={y(yMax * r)}
                y2={y(yMax * r)}
              />
              <text className="ls-tick" x={PAD.left - 6} y={y(yMax * r) + 4} textAnchor="end">
                {Math.round(yMax * r)}
              </text>
            </g>
          ))}
          {points.map((p, i) =>
            i % 6 === 0 ? (
              <text key={p.start} className="ls-tick" x={x(i)} y={H - 6} textAnchor="middle">
                {dayjs(p.start).format('HH:mm')}
              </text>
            ) : null,
          )}
          <path className="ls-line is-attempts" d={path((i) => points[i].attempts)} />
          <path className="ls-line is-failures" d={path((i) => points[i].failures)} />
          {at && (
            <>
              <line
                className="ls-cursor"
                x1={x(shown)}
                x2={x(shown)}
                y1={PAD.top}
                y2={PAD.top + plotH}
              />
              <circle className="ls-dot is-attempts" cx={x(shown)} cy={y(at.attempts)} r={4} />
              <circle className="ls-dot is-failures" cx={x(shown)} cy={y(at.failures)} r={4} />
            </>
          )}
          {points[last] && (
            <>
              <text className="ls-end" x={x(last) + 8} y={y(points[last].attempts) + 4}>
                시도 {points[last].attempts}
              </text>
              <text className="ls-end" x={x(last) + 8} y={y(points[last].failures) + 16}>
                실패 {points[last].failures}
              </text>
            </>
          )}
          {points.map((p, i) => (
            <rect
              key={p.start}
              className="ls-hit"
              x={x(i) - step / 2}
              y={PAD.top}
              width={step}
              height={plotH}
              onMouseEnter={() => {
                setHover(i)
                setActive(null)
              }}
            />
          ))}
          {markers.map(({ incident, lineX, dotX }) => {
            const color = incidentColor[incident.kind]
            const on = active === incident.key
            return (
              <g
                key={incident.key}
                className="ls-mark-group"
                onMouseEnter={() => setActive(incident.key)}
              >
                <rect
                  className="ls-hit"
                  x={lineX - 8}
                  y={PAD.top - 32}
                  width={16}
                  height={plotH + 32}
                />
                <line
                  x1={lineX}
                  x2={lineX}
                  y1={PAD.top - 6}
                  y2={PAD.top + plotH}
                  stroke={color}
                  strokeWidth={on ? 2.4 : 1.6}
                  strokeDasharray="4 3"
                />
                {dotX !== lineX && (
                  <line
                    x1={lineX}
                    x2={dotX}
                    y1={PAD.top - 6}
                    y2={PAD.top - 18}
                    stroke={color}
                    strokeWidth={1.2}
                  />
                )}
                <circle
                  cx={dotX}
                  cy={PAD.top - 20}
                  r={on ? 11 : 10}
                  fill={color}
                  stroke={on ? '#0f172a' : 'none'}
                  strokeWidth={2}
                />
                <text className="ls-mark-num" x={dotX} y={PAD.top - 16} textAnchor="middle">
                  {incident.n}
                </text>
              </g>
            )
          })}
        </svg>
        {tip && (
          <div
            className="ls-tip"
            style={{ left: `${Math.min(82, Math.max(18, (tip.lineX / W) * 100))}%` }}
          >
            <strong>
              {circled(tip.incident.n)} {time(tip.incident.at)} {tip.incident.title}
            </strong>
            {tip.incident.tip.map((line) => (
              <span key={line}>{line}</span>
            ))}
          </div>
        )}
      </div>
      {incidents.length === 0 ? (
        <p className="ls-incident-empty ls-muted">
          아직 표시할 사건이 없어요. 경계 진입 · 탈취 의심 · 반출 차단이 생기면 번호가 쌓여요.
        </p>
      ) : (
        <ol className="ls-incidents">
          {incidents.map((incident) => {
            const caseId = incident.caseId
            return (
              <li
                key={incident.key}
                className={active === incident.key ? 'is-active' : ''}
                tabIndex={0}
                onMouseEnter={() => setActive(incident.key)}
                onMouseLeave={() => setActive(null)}
                onFocus={() => setActive(incident.key)}
                onBlur={() => setActive(null)}
              >
                <NumberMark incident={incident} />
                <span className="ls-time">{time(incident.at)}</span>
                <span className="ls-incident-text">
                  <strong>{incident.title}</strong>
                  <span className="ls-muted">{incident.detail}</span>
                </span>
                {caseId !== null && (incident.kind === 'takeover' || incident.kind === 'block') && (
                  <Button size="small" onClick={() => onOpenCase(caseId)}>
                    사건 보기
                  </Button>
                )}
              </li>
            )
          })}
        </ol>
      )}
      <p className="ls-caption">
        그래프 한 장으로 "공격 → 경계 → 뚫림 → 막음"을 읽어요. 번호 표시선이나 아래 줄에 마우스를
        올리면 내용이 뜨고, 아래 목록에서 바로 사건으로 가요.
      </p>
    </section>
  )
}

function attemptText(a: LoginSecurityAttempt) {
  const who =
    a.account === 'SECURITY_STAFF' ? (a.staffName ?? '보안 직원') : accountLabel[a.account]
  if (a.account === 'UNKNOWN') return `${who}로 로그인 실패`
  return `${who} ${a.success ? '로그인' : '로그인 실패'}`
}

/** 실시간 시도 — 최신이 위. 화면 시계까지 일어난 시도만, 막 들어온 줄은 잠깐 강조한다 */
function AttemptFeed({
  attempts,
  viewNow,
  incidentByAttempt,
}: {
  attempts: LoginSecurityAttempt[]
  viewNow: number
  incidentByAttempt: Map<string, Incident>
}) {
  const visible = attempts.filter((a) => ms(a.occurredAt) <= viewNow)
  const isFresh = (a: LoginSecurityAttempt) => viewNow - ms(a.occurredAt) <= 1_500
  return (
    <section className="ls-feed ca-panel-box" aria-labelledby="ls-feed-heading">
      <header className="ls-panel-head">
        <h2 id="ls-feed-heading" className="dw-section-title">
          실시간 로그인 시도
        </h2>
        <span className="ls-muted">최근 10분 · 최신이 위</span>
      </header>
      {visible.length === 0 ? (
        <ListEmpty title="최근 10분 로그인 시도가 없습니다." />
      ) : (
        <ol className="ls-rows">
          {visible.map((a) => {
            const key = attemptKey(a)
            const tone = a.suspected ? 'is-bad' : a.success ? 'is-good' : 'is-fail'
            const incident = incidentByAttempt.get(key)
            return (
              <li key={key} className={`${tone}${isFresh(a) ? ' is-fresh' : ''}`}>
                <span className="ls-time">{time(a.occurredAt)}</span>
                <span className="ls-mark" aria-hidden="true">
                  {a.success ? '✔' : '✖'}
                </span>
                <span className="ls-who">{attemptText(a)}</span>
                {a.account !== 'UNKNOWN' && <span className="ls-muted">{a.device}</span>}
                {incident && <NumberMark incident={incident} />}
                {a.suspected && <Tag color="red">탈취 의심</Tag>}
              </li>
            )
          })}
        </ol>
      )}
    </section>
  )
}

export function LoginSecurityPage() {
  // latest = 마지막으로 받은 응답, shown = 화면 시계가 따라잡은 응답(상태 띠 · 막대 · 그래프 · 경보)
  const [latest, setLatest] = useState<LoginSecurityDashboard | null>(null)
  const [shown, setShown] = useState<LoginSecurityDashboard | null>(null)
  const [viewNow, setViewNow] = useState(0)
  const [launches, setLaunches] = useState<Launch[]>([])
  const [notices, setNotices] = useState<RadarNotice[]>([])
  const [error, setError] = useState(false)
  const [stopped, setStopped] = useState(false)
  const [caseId, setCaseId] = useState<number | null>(null)
  const pollFrom = useRef<number | null>(null)
  const busy = useRef(false)
  // 브라우저 시계 - 서버 시계, 아직 화면 시계가 못 따라잡은 응답, 이미 날린 시도, 이미 레이더에 띄운 사건
  const clockOffset = useRef(0)
  const pending = useRef<LoginSecurityDashboard[]>([])
  const latestRef = useRef<LoginSecurityDashboard | null>(null)
  const launched = useRef<Set<string> | null>(null)
  const noticed = useRef<Set<string> | null>(null)
  const viewFloor = useRef(0)

  const refresh = useCallback(async () => {
    if (busy.current) return
    busy.current = true
    try {
      const d = await getLoginSecurity()
      const nowMs = Date.now()
      clockOffset.current = nowMs - ms(d.generatedAt)
      latestRef.current = d
      if (launched.current === null) {
        // 처음 열었을 때 이미 있던 시도 · 사건은 날리거나 띄우지 않는다
        launched.current = new Set(d.attempts.map(attemptKey))
        noticed.current = new Set(buildIncidents(d).map((i) => i.key))
        // 처음 받은 응답까지는 바로 보인다 — 화면 시계가 이 시각 앞으로 돌아가 줄이 잠깐 사라지지 않게
        viewFloor.current = ms(d.generatedAt)
        setShown(d)
        setViewNow(viewFloor.current)
      } else {
        const next = scheduleReplay(d.attempts, launched.current, {
          clockOffsetMs: clockOffset.current,
          nowMs,
          serverNowMs: ms(d.generatedAt),
          maxAgeMs: POLL_MS * 3,
        })
        launched.current = new Set(d.attempts.map(attemptKey))
        if (next.length > 0) setLaunches(next)
        pending.current.push(d)
      }
      setLatest(d)
      setError(false)
    } catch {
      setError(true)
    } finally {
      busy.current = false
    }
  }, [])

  // 사용자가 직접 조작했다 — 멈춤 시계를 다시 0부터 센다
  const restart = useCallback(() => {
    pollFrom.current = Date.now()
    setStopped(false)
    void refresh()
  }, [refresh])

  useEffect(() => {
    const tick = () => {
      if (document.visibilityState !== 'visible') return
      const step = pollStep(pollFrom.current, Date.now(), INSIDER_MONITOR_POLL_LIMIT_MS)
      pollFrom.current = step.from
      if (step.poll) void refresh()
      else setStopped(true)
    }
    const onVisibility = () => {
      if (document.visibilityState === 'visible') restart()
    }
    tick()
    const timer = window.setInterval(tick, POLL_MS)
    document.addEventListener('visibilitychange', onVisibility)
    return () => {
      window.clearInterval(timer)
      document.removeEventListener('visibilitychange', onVisibility)
    }
  }, [refresh, restart])

  // 화면 시계 — 서버 시각 기준으로 재생 지연만큼 뒤. 따라잡은 응답을 보이고, 새로 보인 사건을 레이더에 띄운다
  useEffect(() => {
    const timer = window.setInterval(() => {
      const view = Math.max(viewFloor.current, Date.now() - clockOffset.current - REPLAY_DELAY_MS)
      setViewNow(view)
      let caught: LoginSecurityDashboard | undefined
      while (pending.current[0] && ms(pending.current[0].generatedAt) <= view) {
        caught = pending.current.shift()
      }
      if (caught) setShown(caught)
      const d = latestRef.current
      const seen = noticed.current
      if (!d || !seen) return
      const fresh = buildIncidents(d).filter((i) => ms(i.at) <= view && !seen.has(i.key))
      if (fresh.length === 0) return
      fresh.forEach((i) => seen.add(i.key))
      setNotices(fresh.map(noticeOf))
    }, VIEW_TICK_MS)
    return () => window.clearInterval(timer)
  }, [])

  // 사건을 여는 것도 조작이다 — 멈춤 시계를 다시 센다
  const openCase = useCallback((id: number) => {
    pollFrom.current = Date.now()
    setCaseId(id)
  }, [])

  const incidents = useMemo(
    () => (latest ? buildIncidents(latest).filter((i) => ms(i.at) <= viewNow) : []),
    [latest, viewNow],
  )
  const incidentByAttempt = useMemo(() => {
    const map = new Map<string, Incident>()
    for (const i of incidents) if (i.attemptKey) map.set(i.attemptKey, i)
    return map
  }, [incidents])

  const banner = shown ? bannerText(shown) : null
  return (
    <div className="ls-page">
      <PageHeader
        title="로그인 보안"
        description={`전체 로그인 실패율(계정 · IP 구분 없음)과 탈취 의심 로그인을 봐요. 화면을 보는 동안 ${POLL_MS / 1000}초마다 새로 고치고, 새 시도는 일어난 간격대로 약 ${Math.round(REPLAY_DELAY_MS / 1000)}초 늦게 보여요. ${INSIDER_MONITOR_POLL_LIMIT_MS / 60_000}분 동안 조작이 없으면 멈춰요.`}
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
          className="dw-page-alert"
          message="로그인 보안 정보를 불러오지 못했습니다. 잠시 뒤 다시 시도해요."
        />
      )}
      {!shown || !latest ? (
        !error && <ListLoading label="로그인 현황을 불러오는 중" rows={4} columns={1} />
      ) : (
        <>
          <div className={`ls-banner ${stateClass[shown.state]}`} role="status">
            <span className="ls-dot-state" aria-hidden="true" />
            <div>
              <strong>{banner?.head}</strong>
              <span>{banner?.sub}</span>
            </div>
            <span className="ls-muted ls-clock">{time(shown.generatedAt)} 기준</span>
          </div>

          <div className="ls-live">
            <LoginAttackRadar state={shown.state} launches={launches} notices={notices} />
            <div className="ls-live-side">
              <section className="ls-progress ca-panel-box" aria-labelledby="ls-progress-heading">
                <header className="ls-panel-head">
                  <h2 id="ls-progress-heading" className="dw-section-title">
                    경계까지
                  </h2>
                  <span className="ls-muted">
                    최근 {shown.windowMinutes}분 · 두 기준을 모두 넘으면 경계
                  </span>
                </header>
                <div className="ls-gauges">
                  <Gauge
                    label="로그인 시도"
                    value={shown.recentAttempts}
                    limit={shown.minAttempts}
                    unit="건"
                  />
                  <Gauge
                    label="실패율"
                    value={shown.recentFailureRatePercent}
                    limit={shown.failureRatePercentThreshold}
                    unit="%"
                  />
                </div>
              </section>
              <div className="ls-kpis">
                <div className="ls-kpi">
                  <small>없는 아이디로 시도 (최근 {shown.windowMinutes}분)</small>
                  <strong>{shown.recentUnknownAccountFailures}건</strong>
                </div>
                <div className={`ls-kpi${shown.suspectedTakeoverCount > 0 ? ' is-hot' : ''}`}>
                  <small>탈취 의심 (24시간)</small>
                  <strong>{shown.suspectedTakeoverCount}건</strong>
                </div>
              </div>
            </div>
          </div>

          <div className="ls-columns">
            <AttemptLine d={shown} incidents={incidents} onOpenCase={openCase} />
            <AttemptFeed
              attempts={latest.attempts}
              viewNow={viewNow}
              incidentByAttempt={incidentByAttempt}
            />
          </div>

          <section className="ca-panel-box" aria-labelledby="ls-alerts-heading">
            <header className="ls-panel-head">
              <h2 id="ls-alerts-heading" className="dw-section-title">
                탈취 의심 · 반출 차단
              </h2>
              <span className="ls-muted">최근 24시간</span>
            </header>
            {shown.alerts.length === 0 ? (
              <ListEmpty title="최근 24시간 탈취 의심 경보가 없습니다." />
            ) : (
              <ul className="ls-rows ls-alert-rows">
                {shown.alerts.map((alert) => {
                  const linkedCase = alert.insiderCaseId
                  return (
                    <li key={alert.alertId} className={alert.open ? 'is-bad' : ''}>
                      <span className="ls-time">{time(alert.createdAt)}</span>
                      <span className="ls-who">{alert.staffName ?? `ID ${alert.staffId}`}</span>
                      <SeverityMark value={alert.severity} />
                      <span className="ls-alert-text">
                        <strong>
                          {displayRuleCode(alert.ruleCode)} {alert.ruleLabel}
                        </strong>
                        <span className="ls-muted">{displayRuleText(alert.summary)}</span>
                      </span>
                      {!alert.open && <Tag>닫힘</Tag>}
                      {linkedCase !== null && (
                        <Button size="small" onClick={() => openCase(linkedCase)}>
                          사건 보기
                        </Button>
                      )}
                    </li>
                  )
                })}
              </ul>
            )}
          </section>
        </>
      )}
      {caseId !== null && (
        <InsiderCaseDrawer
          insiderCaseId={caseId}
          onClose={() => setCaseId(null)}
          onChanged={() => void refresh()}
        />
      )}
    </div>
  )
}
