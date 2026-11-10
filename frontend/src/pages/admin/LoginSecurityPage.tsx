import { ReloadOutlined } from '@ant-design/icons'
import { Alert, Button, Tag } from 'antd'
import dayjs from 'dayjs'
import { useCallback, useEffect, useRef, useState } from 'react'
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
import './caseAssignmentShared.css'
import './LoginSecurityPage.css'

// 공격 장면을 따라가도록 자주 읽는다. 멈춤 기준(5분 무조작)은 내부자 관제와 같다
const POLL_MS = 2_000

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

const time = (iso: string) => dayjs(iso).format('HH:mm:ss')
const attemptKey = (a: LoginSecurityAttempt) =>
  `${a.occurredAt}-${a.account}-${a.staffId ?? ''}-${a.success}`

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

// 꺾은선 그림 크기(viewBox) — 실제 폭은 칸에 맞춰 늘어난다
const W = 640
const H = 200
const PAD = { left: 34, right: 64, top: 12, bottom: 24 }

/** 10초 단위 시도 · 실패 꺾은선 — 같은 단위(건)라 축 하나. 마우스를 올린 칸의 숫자를 위에 보인다 */
function AttemptLine({ d }: { d: LoginSecurityDashboard }) {
  const [hover, setHover] = useState<number | null>(null)
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
  return (
    <section className="ls-chart ca-panel-box" aria-labelledby="ls-chart-heading">
      <header className="ls-panel-head">
        <h2 id="ls-chart-heading" className="dw-section-title">
          로그인 시도 ({d.bucketSeconds}초 단위)
        </h2>
        <div className="ls-legend" aria-hidden="true">
          <span className="ls-key is-attempts">시도</span>
          <span className="ls-key is-failures">실패</span>
        </div>
      </header>
      {at && (
        <p className="ls-readout" aria-live="off">
          {`${time(at.start)}부터 ${d.bucketSeconds}초 · 시도 ${at.attempts}건 · 실패 ${at.failures}건`}
          {at.unknownAccountFailures > 0 && ` (없는 아이디 ${at.unknownAccountFailures}건)`}
          {hover === null && ' · 가장 최근'}
        </p>
      )}
      <svg
        className="ls-svg"
        viewBox={`0 0 ${W} ${H}`}
        role="img"
        aria-label={`최근 ${d.windowMinutes}분 ${d.bucketSeconds}초 단위 로그인 시도와 실패. 가장 최근 칸 시도 ${points[last]?.attempts ?? 0}건, 실패 ${points[last]?.failures ?? 0}건`}
        onMouseLeave={() => setHover(null)}
      >
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
            onMouseEnter={() => setHover(i)}
          />
        ))}
      </svg>
    </section>
  )
}

function attemptText(a: LoginSecurityAttempt) {
  const who =
    a.account === 'SECURITY_STAFF' ? (a.staffName ?? '보안 직원') : accountLabel[a.account]
  if (a.account === 'UNKNOWN') return `${who}로 로그인 실패`
  return `${who} ${a.success ? '로그인' : '로그인 실패'}`
}

/** 실시간 시도 — 최신이 위. 응답 시각 기준 한 번 갱신 간격 안에 들어온 줄은 잠깐 강조한다 */
function AttemptFeed({
  attempts,
  generatedAt,
}: {
  attempts: LoginSecurityAttempt[]
  generatedAt: string
}) {
  const now = new Date(generatedAt).getTime()
  const isFresh = (a: LoginSecurityAttempt) =>
    now - new Date(a.occurredAt).getTime() <= POLL_MS + 1_000
  return (
    <section className="ls-feed ca-panel-box" aria-labelledby="ls-feed-heading">
      <header className="ls-panel-head">
        <h2 id="ls-feed-heading" className="dw-section-title">
          실시간 로그인 시도
        </h2>
        <span className="ls-muted">최근 10분 · 최신이 위</span>
      </header>
      {attempts.length === 0 ? (
        <ListEmpty title="최근 10분 로그인 시도가 없습니다." />
      ) : (
        <ol className="ls-rows" aria-live="polite">
          {attempts.map((a) => {
            const key = attemptKey(a)
            const tone = a.suspected ? 'is-bad' : a.success ? 'is-good' : 'is-fail'
            return (
              <li key={key} className={`${tone}${isFresh(a) ? ' is-fresh' : ''}`}>
                <span className="ls-time">{time(a.occurredAt)}</span>
                <span className="ls-mark" aria-hidden="true">
                  {a.success ? '✔' : '✖'}
                </span>
                <span className="ls-who">{attemptText(a)}</span>
                {a.account !== 'UNKNOWN' && <span className="ls-muted">{a.device}</span>}
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
  const [data, setData] = useState<LoginSecurityDashboard | null>(null)
  const [error, setError] = useState(false)
  const [stopped, setStopped] = useState(false)
  const [caseId, setCaseId] = useState<number | null>(null)
  const pollFrom = useRef<number | null>(null)
  const busy = useRef(false)

  const refresh = useCallback(async () => {
    if (busy.current) return
    busy.current = true
    try {
      setData(await getLoginSecurity())
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

  // 사건을 여는 것도 조작이다 — 멈춤 시계를 다시 센다
  const openCase = useCallback((id: number) => {
    pollFrom.current = Date.now()
    setCaseId(id)
  }, [])

  const banner = data ? bannerText(data) : null
  return (
    <div className="ls-page">
      <PageHeader
        title="로그인 보안"
        description={`전체 로그인 실패율(계정 · IP 구분 없음)과 탈취 의심 로그인을 봐요. 화면을 보는 동안 ${POLL_MS / 1000}초마다 새로 고치고, ${INSIDER_MONITOR_POLL_LIMIT_MS / 60_000}분 동안 조작이 없으면 멈춰요.`}
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
      {!data ? (
        !error && <ListLoading label="로그인 현황을 불러오는 중" rows={4} columns={1} />
      ) : (
        <>
          <div className={`ls-banner ${stateClass[data.state]}`} role="status">
            <span className="ls-dot-state" aria-hidden="true" />
            <div>
              <strong>{banner?.head}</strong>
              <span>{banner?.sub}</span>
            </div>
            <span className="ls-muted ls-clock">{time(data.generatedAt)} 갱신</span>
          </div>

          <div className="ls-top">
            <section className="ls-progress ca-panel-box" aria-labelledby="ls-progress-heading">
              <header className="ls-panel-head">
                <h2 id="ls-progress-heading" className="dw-section-title">
                  경계까지
                </h2>
                <span className="ls-muted">
                  최근 {data.windowMinutes}분 · 두 기준을 모두 넘으면 경계
                </span>
              </header>
              <div className="ls-gauges">
                <Gauge
                  label="로그인 시도"
                  value={data.recentAttempts}
                  limit={data.minAttempts}
                  unit="건"
                />
                <Gauge
                  label="실패율"
                  value={data.recentFailureRatePercent}
                  limit={data.failureRatePercentThreshold}
                  unit="%"
                />
              </div>
            </section>
            <div className="ls-kpi">
              <small>없는 아이디로 시도 (최근 {data.windowMinutes}분)</small>
              <strong>{data.recentUnknownAccountFailures}건</strong>
            </div>
            <div className={`ls-kpi${data.suspectedTakeoverCount > 0 ? ' is-hot' : ''}`}>
              <small>탈취 의심 (24시간)</small>
              <strong>{data.suspectedTakeoverCount}건</strong>
            </div>
          </div>

          <div className="ls-columns">
            <AttemptLine d={data} />
            <AttemptFeed attempts={data.attempts} generatedAt={data.generatedAt} />
          </div>

          <section className="ca-panel-box" aria-labelledby="ls-alerts-heading">
            <header className="ls-panel-head">
              <h2 id="ls-alerts-heading" className="dw-section-title">
                탈취 의심 · 반출 차단
              </h2>
              <span className="ls-muted">최근 24시간</span>
            </header>
            {data.alerts.length === 0 ? (
              <ListEmpty title="최근 24시간 탈취 의심 경보가 없습니다." />
            ) : (
              <ul className="ls-rows ls-alert-rows">
                {data.alerts.map((alert) => {
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
                      {linkedCase !== null ? (
                        <Button size="small" onClick={() => openCase(linkedCase)}>
                          사건 보기
                        </Button>
                      ) : (
                        !alert.open && <Tag>닫힘</Tag>
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
