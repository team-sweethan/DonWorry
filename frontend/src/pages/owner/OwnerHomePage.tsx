import { ArrowRightOutlined, ReloadOutlined, SafetyCertificateOutlined } from '@ant-design/icons'
import { Button, Skeleton } from 'antd'
import { useCallback, useState } from 'react'
import type { CSSProperties, ReactNode } from 'react'
import { Link } from 'react-router-dom'
import dayjs from 'dayjs'
import { useAuth } from '../../auth/useAuth'
import { useOwnerIntegration } from '../../hooks/useOwnerIntegration'
import { getCashFlowForecast, getCashFlowSettings, getSettlementReport } from '../../api/finance'
import { getMerchantCases } from '../../api/merchantCases'
import type { MerchantCaseOverview } from '../../types/merchantCase'
import { sessionNavigation } from '../../config/navigation'
import { formatKstDateTime } from '../../utils/format'
import { useFinanceResource, useKoreanToday } from './useFinanceResource'
import type { FinanceResource } from './useFinanceResource'
import { OwnerWeekTiles } from './OwnerWeekTiles'
import { OwnerConnectSteps } from './OwnerConnectSteps'
import { StatusMark } from './StatusMark'
import {
  flowDays,
  hasBusinessHistory,
  homeAttentionStatus,
  homeTodos,
  nextIncomeDay,
  upcomingIncome,
} from './ownerHomeView'
import { monthDay, weekday } from './ownerHomeView'
import type { HomeAttentionStatus } from './ownerHomeView'
import './OwnerHomePage.css'
import '../../styles/ownerMotion.css'

const menuDescriptions: Record<string, string> = {
  '/owner/actions': '보류된 결제의 처리 결과',
  '/owner/settlements': '정산 내역과 실제 지급',
  '/owner/funds': '잔액 예측과 지출 관리',
  '/owner/loans': '운영자금 상품 안내',
}

/** 다시 조회하는 동안에도 마지막 결과를 남겨 화면이 비었다 채워지지 않게 한다 (#803). */
function shown<T>(resource: FinanceResource<T>) {
  return resource.status === 'success'
    ? resource.data
    : resource.status === 'loading'
      ? resource.stale
      : undefined
}

const delay = (ms: number) => ({ '--ow-delay': `${ms}ms` }) as CSSProperties

const todayLabel = (today: string) =>
  new Intl.DateTimeFormat('ko-KR', {
    month: 'long',
    day: 'numeric',
    weekday: 'long',
    timeZone: 'Asia/Seoul',
  }).format(new Date(`${today}T12:00:00+09:00`))

export function OwnerHomePage() {
  const { user } = useAuth()
  return user ? <OwnerHome key={`${user.userId}:${user.merchantId}`} user={user} /> : null
}

type User = NonNullable<ReturnType<typeof useAuth>['user']>

/**
 * 사장님 홈. 위에서 아래로 "할 일 → 돈 → 앞으로 7일 → 보안 · 연동" 순서로 읽힌다.
 * 쇼핑몰 연결 전에는 연결 단계 목록이 화면의 중심이 되고, 결제 기록이 없으면 돈 · 보안 영역은 숨긴다.
 */
function OwnerHome({ user }: { user: User }) {
  const today = useKoreanToday()
  // 권한이 없는 업무 API는 부르지 않는다. 사장님 계정의 403 반복은 차단 대상이다 (#515)
  const finance = user.permissions.includes('FINANCE_VIEW')
  const fds = user.permissions.includes('FDS_VIEW')
  const { state, refresh } = useOwnerIntegration(user.userId, user.merchantId)
  const [revision, setRevision] = useState(0)

  const settingsLoad = useCallback((signal: AbortSignal) => getCashFlowSettings(signal), [])
  const settings = useFinanceResource(`home-settings:${today}:${revision}`, settingsLoad, finance)
  const setting = shown(settings)
  const forecastLoad = useCallback(
    (signal: AbortSignal) =>
      getCashFlowForecast(today, dayjs(today).add(6, 'day').format('YYYY-MM-DD'), signal),
    [today],
  )
  const forecast = useFinanceResource(
    `home-forecast:${today}:${revision}`,
    forecastLoad,
    finance && !!setting && setting.baseDate <= today,
  )
  const monthLoad = useCallback(
    (signal: AbortSignal) =>
      getSettlementReport(dayjs(today).startOf('month').format('YYYY-MM-DD'), today, signal),
    [today],
  )
  const month = useFinanceResource(`home-month:${today}:${revision}`, monthLoad, finance)
  const casesLoad = useCallback(() => getMerchantCases(), [])
  const cases = useFinanceResource(`home-cases:${revision}`, casesLoad, fds)

  const data = state.kind === 'ready' ? state.data : null
  const connected = data?.status === 'CONNECTED'
  const integrationBusy = state.kind === 'loading' || (state.kind === 'ready' && state.refreshing)
  const busy =
    integrationBusy ||
    settings.status === 'loading' ||
    month.status === 'loading' ||
    forecast.status === 'loading' ||
    cases.status === 'loading'
  const refreshAll = () => {
    void refresh()
    setRevision((n) => n + 1)
  }

  const forecastData = shown(forecast)
  const monthData = shown(month)
  const caseData = shown(cases)
  const days = forecastData ? flowDays(forecastData) : null
  const needsOpeningBalance = finance && settings.status === 'success' && settings.data === null
  const todos = homeTodos({
    today,
    forecast: forecastData ?? null,
    needsOpeningBalance,
    cases: caseData?.overview ?? null,
  })
  const menus = sessionNavigation(user)
    .groups.flatMap((group) => group.items)
    .filter((item) => item.permission)

  // 연결 전이면 결제 기록이 있을 때만 돈 · 보안 영역을 보인다. 조회 중에는 판단을 미뤄 번쩍이지 않게 한다
  const showBusiness =
    connected ||
    (state.kind === 'ready' &&
      hasBusinessHistory({
        month: monthData,
        hasOpeningBalance: !!setting,
        cases: caseData?.overview,
      }))
  const attentionStatus = homeAttentionStatus({ settings, forecast, today })

  return (
    <div className="oh-home">
      <header className="oh-hero ow-rise" style={delay(0)}>
        <div>
          <p className="oh-date">{todayLabel(today)}</p>
          <h1>
            {data && !connected
              ? `${user.displayName}님, 반가워요`
              : `${user.displayName}님, 안녕하세요`}
          </h1>
          {data && !connected && (
            <p className="oh-hero-sub">
              쇼핑몰을 연결하면 이상한 결제를 돈워리가 먼저 멈추고 알려 드려요.
            </p>
          )}
        </div>
        {state.kind !== 'error' && (
          <Button icon={<ReloadOutlined />} loading={busy} onClick={refreshAll}>
            새로고침
          </Button>
        )}
      </header>

      {state.kind === 'loading' && (
        <section className="oh-panel" role="status" aria-label="연동 상태를 불러오는 중">
          <Skeleton active paragraph={{ rows: 3 }} />
        </section>
      )}
      {state.kind === 'error' && (
        <section className="oh-panel oh-error ow-rise" role="status">
          <h2>
            {state.inactive ? '가맹점이 사용 중지되어 있습니다' : '연동 상태를 확인하지 못했습니다'}
          </h2>
          <p>
            {state.inactive
              ? '돈워리 담당자에게 가맹점 상태를 확인해 주세요.'
              : '잠시 후 다시 시도해 주세요. 현재 연동 상태는 확인되지 않았습니다.'}
          </p>
          <Button onClick={() => void refresh()}>다시 시도</Button>
        </section>
      )}
      {data && !connected && (
        <OwnerConnectSteps data={data} busy={integrationBusy} onRefresh={() => void refresh()} />
      )}

      {showBusiness && finance && (
        <TodoStrip todos={todos} status={attentionStatus} onRetry={refreshAll} />
      )}

      {showBusiness && finance && (
        <section className="oh-money" aria-label="금액 요약">
          <MoneyCard
            dark
            style={delay(140)}
            label="이번 달 받은 돈"
            to="/owner/settlements"
            value={monthData?.paidAmount}
            failed={month.status === 'error'}
            onRetry={refreshAll}
            foot={
              monthData
                ? `${dayjs(today).month() + 1}월 1일부터 · 지급 ${monthData.payouts.length}회`
                : `${dayjs(today).month() + 1}월 1일부터 오늘까지`
            }
          />
          <MoneyCard
            style={delay(200)}
            label="앞으로 7일 정산 예정금"
            accent
            to="/owner/settlements"
            value={days ? upcomingIncome(days, today) : undefined}
            failed={forecast.status === 'error' || settings.status === 'error'}
            onRetry={refreshAll}
            setup={needsOpeningBalance}
            foot={(() => {
              const next = days ? nextIncomeDay(days, today) : null
              return next
                ? `다음 정산 예정 ${monthDay(next.date)}(${weekday(next.date)}) · 수수료 뺀 금액`
                : '정산 예정 · 수수료 뺀 금액'
            })()}
          />
          <MoneyCard
            style={delay(260)}
            label="계산된 현재 잔액"
            to="/owner/funds"
            value={forecastData?.currentBalance}
            failed={forecast.status === 'error' || settings.status === 'error'}
            onRetry={refreshAll}
            setup={needsOpeningBalance}
            foot="입력·기록 기준 · 은행 잔액이 아니에요"
          />
        </section>
      )}

      {showBusiness && finance && (
        <section className="oh-panel ow-rise" style={delay(320)} aria-labelledby="oh-week-title">
          <div className="oh-panel-head">
            <h2 id="oh-week-title">앞으로 7일 잔액</h2>
            <span>날짜를 누르면 계산 근거를 보여 줘요</span>
          </div>
          {days && forecastData ? (
            <OwnerWeekTiles
              key={`${forecastData.from}:${forecastData.to}`}
              days={days}
              minimum={forecastData.minimumOperatingFunds ?? 0}
              today={today}
            />
          ) : needsOpeningBalance ? (
            <div className="oh-empty">
              <p>시작 잔액을 한 번 입력하면 앞으로 7일 잔액을 계산해 드려요.</p>
              <Link to="/owner/funds">
                잔액 입력하러 가기 <ArrowRightOutlined />
              </Link>
            </div>
          ) : forecast.status === 'error' || settings.status === 'error' ? (
            <Failure onRetry={refreshAll} what="앞으로 7일 잔액" />
          ) : (
            <div role="status" aria-label="앞으로 7일 잔액을 불러오는 중">
              <Skeleton active paragraph={{ rows: 2 }} />
            </div>
          )}
        </section>
      )}

      {((showBusiness && fds) || connected) && (
        <div className={`oh-row${showBusiness && fds && connected ? '' : ' is-single'}`}>
          {showBusiness && fds && (
            <SecuritySummary
              resource={cases}
              data={caseData?.overview}
              onRetry={refreshAll}
              style={delay(400)}
            />
          )}
          {connected && data && (
            <section
              className="oh-panel oh-connected ow-rise"
              style={delay(460)}
              aria-labelledby="oh-connection-title"
            >
              <div className="oh-panel-head">
                <h2 id="oh-connection-title">쇼핑몰 연동</h2>
              </div>
              <p className="oh-connected-state">
                <span className="oh-live-dot ow-pop" style={delay(800)} aria-hidden="true" />
                쇼핑몰 연동이 완료되었습니다
              </p>
              <p className="oh-muted">
                가맹점 코드 <span className="oh-code">{data.merchantCode}</span>
                {data.connectedAt && ` · ${formatKstDateTime(data.connectedAt)} 연결 확인`}
              </p>
              <Link className="oh-more" to="/owner/integration">
                시크릿 키 관리 <ArrowRightOutlined />
              </Link>
            </section>
          )}
        </div>
      )}

      {menus.length ? (
        <nav className="oh-quick ow-rise" style={delay(520)} aria-label="바로 가기">
          {menus.map((item) => (
            <Link key={item.path} to={item.path}>
              <strong>{item.label}</strong>
              <span>{menuDescriptions[item.path]}</span>
            </Link>
          ))}
        </nav>
      ) : (
        <div className="oh-no-menus ow-rise" style={delay(200)}>
          <SafetyCertificateOutlined aria-hidden="true" />
          <div>
            <h3>업무 메뉴 권한이 아직 없습니다</h3>
            <p>
              쇼핑몰 연동과 시크릿 키 관리는 지금 이용할 수 있습니다. 업무 메뉴가 필요하면 돈워리
              담당자에게 문의해 주세요.
            </p>
          </div>
        </div>
      )}
      <p className="oh-security-note">
        <SafetyCertificateOutlined /> 시크릿 키는 쇼핑몰 서버에만 보관하고, 다른 사람에게 공유하지
        마세요.
      </p>
    </div>
  )
}

// 확인된 경고를 유지하면서 조회 중·실패·정상 완료를 구분한다.
function TodoStrip({
  todos,
  status,
  onRetry,
}: {
  todos: ReturnType<typeof homeTodos>
  status: HomeAttentionStatus
  onRetry: () => void
}) {
  if (status === 'loading' && todos.length === 0)
    return (
      <section className="oh-todos" role="status" aria-label="확인할 것을 불러오는 중">
        <Skeleton active paragraph={{ rows: 1 }} title={false} />
      </section>
    )
  if (status === 'ready' && todos.length === 0)
    return (
      <section className="oh-todos is-clear" aria-label="지금 확인할 것">
        <StatusMark tone="success" />
        <span>앞으로 7일 예측에는 최소 운영 자금 미달일이 없어요</span>
      </section>
    )
  return (
    <section className="oh-todos" aria-label="지금 확인할 것">
      {status === 'error' && (
        <div className="oh-attention-status" role="status">
          <span>잔액 정보를 확인하지 못했어요. 확인할 사항이 없는지는 아직 알 수 없어요.</span>
          <Button size="small" onClick={onRetry}>
            다시 시도
          </Button>
        </div>
      )}
      {status === 'loading' && (
        <p className="oh-attention-status" role="status">
          잔액 정보를 다시 확인하고 있어요.
        </p>
      )}
      {status === 'future' && (
        <div className="oh-attention-status" role="status">
          <span>시작 잔액의 기준일이 아직 오지 않아 현재 잔액을 예측할 수 없어요.</span>
          <Link className="oh-more" to="/owner/funds">
            잔액 설정 확인 <ArrowRightOutlined />
          </Link>
        </div>
      )}
      <ul>
        {todos.map((todo) => (
          <li key={todo.key} className={`is-${todo.tone}`}>
            <Link to={todo.to}>
              <StatusMark tone={todo.tone} />
              <span className="oh-todo-copy">
                <strong>{todo.title}</strong>
                <span>{todo.detail}</span>
              </span>
              <ArrowRightOutlined className="oh-todo-arrow" aria-hidden="true" />
            </Link>
          </li>
        ))}
      </ul>
    </section>
  )
}

function Failure({ onRetry, what }: { onRetry: () => void; what: string }) {
  return (
    <div className="oh-failure" role="status">
      <span>{what}을 불러오지 못했어요. 0원이 아니라 확인되지 않은 상태예요.</span>
      <Button size="small" onClick={onRetry}>
        다시 시도
      </Button>
    </div>
  )
}

function MoneyCard({
  label,
  value,
  foot,
  to,
  failed,
  setup,
  onRetry,
  dark,
  accent,
  style,
}: {
  label: string
  value: number | undefined
  foot: string
  to: string
  failed: boolean
  setup?: boolean
  onRetry: () => void
  dark?: boolean
  accent?: boolean
  style: CSSProperties
}) {
  const className = `oh-money-card ow-rise${dark ? ' is-dark' : ''}${accent ? ' is-accent' : ''}`
  if (failed && value === undefined)
    return (
      <div className={className} style={style}>
        <span className="oh-money-label">{label}</span>
        <div className="oh-money-failed">
          불러오지 못했어요
          <Button size="small" onClick={onRetry}>
            다시 시도
          </Button>
        </div>
        <span className="oh-money-foot">확인되지 않은 금액은 0원으로 보여 드리지 않아요</span>
      </div>
    )
  if (setup)
    return (
      <Link className={className} style={style} to="/owner/funds">
        <span className="oh-money-label">{label}</span>
        <div className="oh-money-setup">시작 잔액을 입력하면 계산돼요</div>
        <span className="oh-money-foot">
          잔액 입력하러 가기 <ArrowRightOutlined />
        </span>
      </Link>
    )
  return (
    <Link
      className={className}
      style={style}
      to={to}
      aria-label={value === undefined ? label : `${label} ${value.toLocaleString('ko-KR')}원`}
    >
      <span className="oh-money-label">{label}</span>
      {value === undefined ? (
        <Skeleton.Input active size="large" className="oh-money-skeleton" />
      ) : (
        <div className="oh-money-value">
          {value.toLocaleString('ko-KR')}
          <span>원</span>
        </div>
      )}
      <span className="oh-money-foot">{foot}</span>
    </Link>
  )
}

function SecuritySummary({
  resource,
  data,
  onRetry,
  style,
}: {
  resource: FinanceResource<unknown>
  data: MerchantCaseOverview | undefined
  onRetry: () => void
  style: CSSProperties
}) {
  const decided = data ? data.acceptDecisionCount + data.rejectDecisionCount : 0
  let body: ReactNode
  if (!data)
    body =
      resource.status === 'error' ? (
        <Failure onRetry={onRetry} what="결제 보안 현황" />
      ) : (
        <div role="status" aria-label="결제 보안 현황을 불러오는 중">
          <Skeleton active paragraph={{ rows: 2 }} title={false} />
        </div>
      )
  else
    body = (
      <>
        <div className="oh-decision-bar" aria-hidden="true">
          {decided === 0 ? (
            <span className="oh-decision-empty" />
          ) : (
            <>
              <span
                className="is-accept ow-grow-x"
                style={{ flex: data.acceptDecisionCount, ...delay(700) }}
              />
              <span
                className="is-reject ow-grow-x"
                style={{ flex: data.rejectDecisionCount, ...delay(820) }}
              />
            </>
          )}
        </div>
        <dl className="oh-decisions">
          <div>
            <dt>
              <StatusMark tone="success" size={14} />
              정상 처리 결정
            </dt>
            <dd>
              {data.acceptDecisionCount.toLocaleString('ko-KR')}
              <span>건</span>
            </dd>
          </div>
          <div>
            <dt>
              <StatusMark tone="danger" size={14} />
              취소 결정
            </dt>
            <dd>
              {data.rejectDecisionCount.toLocaleString('ko-KR')}
              <span>건</span>
            </dd>
          </div>
          <div>
            <dt>
              <StatusMark tone="progress" size={14} />
              조사 중 사건
            </dt>
            <dd>
              {data.investigatingCount.toLocaleString('ko-KR')}
              <span>건</span>
            </dd>
          </div>
        </dl>
        {data.investigatingCount > 0 && (
          <p className="oh-security-context">담당자가 확인 중이에요. 사장님이 하실 일은 없어요.</p>
        )}
      </>
    )
  return (
    <section className="oh-panel oh-security ow-rise" style={style} aria-labelledby="oh-sec-title">
      <div className="oh-panel-head">
        <h2 id="oh-sec-title">보류된 결제 처리 결과</h2>
        <Link className="oh-more" to="/owner/actions">
          조치 결과 보기 <ArrowRightOutlined />
        </Link>
      </div>
      {body}
    </section>
  )
}
