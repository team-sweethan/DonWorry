import { ReloadOutlined } from '@ant-design/icons'
import { Alert, Button, Tag, Tooltip } from 'antd'
import dayjs from 'dayjs'
import { useCallback, useEffect, useRef, useState } from 'react'
import {
  getLoginSecurity,
  type LoginSecurityAlert,
  type LoginSecurityDashboard,
  type LoginSecurityMinute,
  type LoginSecurityStaffLogin,
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

// 공격 장면을 따라가도록 내부자 관제(2초)와 비슷하게 자주 읽는다. 멈춤 기준(5분 무조작)은 관제와 같다
const POLL_MS = 3_000
// 탈취 의심 경보는 로그인 몇 초 뒤 열린다 — 이 안이면 그 로그인 줄에 표시한다
const SUSPECT_MATCH_MS = 2 * 60_000

const stateView: Record<LoginSecurityState, { className: string; title: string }> = {
  NORMAL: { className: 'is-normal', title: '평소' },
  CAUTION: { className: 'is-caution', title: '주의' },
  UNDER_ATTACK: { className: 'is-attack', title: '로그인 경계 중' },
}

const time = (iso: string) => dayjs(iso).format('HH:mm:ss')
const minuteLabel = (iso: string) => dayjs(iso).format('HH:mm')
const rate = (m: LoginSecurityMinute) =>
  m.attempts === 0 ? 0 : Math.round((m.failures * 100) / m.attempts)

function bannerText(d: LoginSecurityDashboard) {
  const recent = `최근 ${d.windowMinutes}분 실패율 ${d.recentFailureRatePercent}% · 시도 ${d.recentAttempts}건`
  const criteria = `기준: 시도 ${d.minAttempts}건 이상 · 실패율 ${d.failureRatePercentThreshold}% 이상`
  if (d.state === 'UNDER_ATTACK')
    return {
      head: `로그인 경계 중 — ${recent}`,
      sub: `${criteria}. 로그인은 막지 않습니다. 이 동안 처음 보는 브라우저로 들어온 보안 직원만 탈취 의심(SEC-INS-10)으로 올립니다.`,
    }
  if (d.state === 'CAUTION')
    return {
      head: `주의 — ${recent}`,
      sub: `실패율은 높지만 시도가 ${d.minAttempts}건이 안 돼 경계 전입니다. 오타 몇 번으로 경계가 켜지지 않게 하는 장치예요.`,
    }
  return { head: `평소 — ${recent}`, sub: `${criteria}일 때 경계로 바뀝니다.` }
}

/** 1분 막대 — 분마다 실패율. 기준선 위 막대는 빨강, 숫자는 막대 위 */
function MinuteBars({ d }: { d: LoginSecurityDashboard }) {
  const threshold = d.failureRatePercentThreshold
  return (
    <section className="ls-chart ca-panel-box" aria-labelledby="ls-chart-heading">
      <header className="ls-panel-head">
        <h2 id="ls-chart-heading" className="dw-section-title">
          1분 단위 로그인 실패율
        </h2>
        <span className="ls-muted">경계 판단은 최근 {d.windowMinutes}분 기준</span>
      </header>
      <div className="ls-plot" role="list" aria-label="1분 단위 로그인 실패율">
        <div className="ls-threshold" style={{ bottom: `${threshold}%` }} aria-hidden="true">
          <span>경계 기준 {threshold}%</span>
        </div>
        {d.minutes.map((m) => {
          const value = rate(m)
          const hot = m.attempts > 0 && value >= threshold
          const label = `${minuteLabel(m.minuteStart)} 시도 ${m.attempts}건 · 실패 ${m.failures}건 (없는 아이디 ${m.unknownAccountFailures}건)`
          return (
            <Tooltip key={m.minuteStart} title={label}>
              <div className={`ls-col${hot ? ' is-hot' : ''}`} role="listitem" aria-label={label}>
                <i>{m.attempts > 0 ? `${value}%` : ''}</i>
                <div
                  className="ls-bar"
                  style={{ height: `${Math.max(value, m.attempts > 0 ? 2 : 0)}%` }}
                />
              </div>
            </Tooltip>
          )
        })}
      </div>
      <div className="ls-axis" aria-hidden="true">
        {d.minutes.map((m, i) => (
          <span key={m.minuteStart}>
            {i % 3 === 2 || i === d.minutes.length - 1 ? minuteLabel(m.minuteStart) : ''}
          </span>
        ))}
      </div>
    </section>
  )
}

/** 로그인 줄에 붙일 탈취 의심 경보 — 같은 직원 INS-10이 로그인 직후 열렸으면 */
function suspectOf(login: LoginSecurityStaffLogin, alerts: LoginSecurityAlert[]) {
  if (login.eventType !== 'LOGIN_SUCCESS') return null
  const at = new Date(login.occurredAt).getTime()
  return (
    alerts.find((a) => {
      if (a.staffId !== login.staffId || displayRuleCode(a.ruleCode) !== 'SEC-INS-10') return false
      const diff = new Date(a.createdAt).getTime() - at
      return diff >= 0 && diff <= SUSPECT_MATCH_MS
    }) ?? null
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
          <div className={`ls-banner ${stateView[data.state].className}`} role="status">
            <span className="ls-dot" aria-hidden="true" />
            <div>
              <strong>{banner?.head}</strong>
              <span>{banner?.sub}</span>
            </div>
            <span className="ls-muted ls-clock">{time(data.generatedAt)} 갱신</span>
          </div>

          <div className="ls-kpis">
            <div className="ls-kpi">
              <small>최근 {data.windowMinutes}분 시도</small>
              <strong>{data.recentAttempts}건</strong>
            </div>
            <div
              className={`ls-kpi${data.recentFailureRatePercent >= data.failureRatePercentThreshold && data.recentAttempts > 0 ? ' is-hot' : ''}`}
            >
              <small>최근 {data.windowMinutes}분 실패율</small>
              <strong>{data.recentFailureRatePercent}%</strong>
            </div>
            <div className="ls-kpi">
              <small>없는 아이디로 시도</small>
              <strong>{data.recentUnknownAccountFailures}건</strong>
            </div>
            <div className={`ls-kpi${data.suspectedTakeoverCount > 0 ? ' is-hot' : ''}`}>
              <small>탈취 의심 (24시간)</small>
              <strong>{data.suspectedTakeoverCount}건</strong>
            </div>
          </div>

          <MinuteBars d={data} />

          <div className="ls-columns">
            <section className="ca-panel-box" aria-labelledby="ls-logins-heading">
              <header className="ls-panel-head">
                <h2 id="ls-logins-heading" className="dw-section-title">
                  보안 직원 로그인
                </h2>
                <span className="ls-muted">최근 1시간 · 최신순</span>
              </header>
              {data.staffLogins.length === 0 ? (
                <ListEmpty title="최근 1시간 보안 직원 로그인이 없습니다." />
              ) : (
                <ul className="ls-rows">
                  {data.staffLogins.map((login) => {
                    const suspect = suspectOf(login, data.alerts)
                    const ok = login.eventType === 'LOGIN_SUCCESS'
                    return (
                      <li
                        key={`${login.staffId}-${login.occurredAt}-${login.eventType}`}
                        className={suspect ? 'is-bad' : ok ? 'is-good' : ''}
                      >
                        <span className="ls-time">{time(login.occurredAt)}</span>
                        <span className="ls-who">{login.staffName ?? `ID ${login.staffId}`}</span>
                        <span>{ok ? '로그인' : '로그인 실패'}</span>
                        <span className="ls-muted">{login.device}</span>
                        {suspect && <Tag color="red">탈취 의심</Tag>}
                      </li>
                    )
                  })}
                </ul>
              )}
            </section>

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
                <ul className="ls-rows">
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
          </div>
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
