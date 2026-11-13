import {
  ArrowRightOutlined,
  CheckOutlined,
  FileSearchOutlined,
  LinkOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
  WalletOutlined,
} from '@ant-design/icons'
import { Button, Skeleton } from 'antd'
import { useCallback, useState } from 'react'
import type { CSSProperties, ReactNode } from 'react'
import { Link } from 'react-router-dom'
import dayjs from 'dayjs'
import { useAuth } from '../../auth/useAuth'
import { useCountUp } from '../../hooks/useCountUp'
import type { SettlementReport } from '../../api/finance'
import { PageHeader } from '../../components/common/PageHeader'
import { useOwnerIntegration } from '../../hooks/useOwnerIntegration'
import { getCashFlowForecast, getCashFlowSettings, getSettlementReport } from '../../api/finance'
import { getMerchantCases } from '../../api/merchantCases'
import type { MerchantCaseOverview } from '../../types/merchantCase'
import { sessionNavigation } from '../../config/navigation'
import { formatKstDateTime } from '../../utils/format'
import { useFinanceResource, useKoreanToday } from './useFinanceResource'
import type { FinanceResource } from './useFinanceResource'
import { OwnerConnectSteps } from './OwnerConnectSteps'
import { StatusMark } from './StatusMark'
import { hasBusinessHistory, homeAttentionStatus, homeTodos } from './ownerHomeView'
import type { HomeAttentionStatus } from './ownerHomeView'
import './OwnerHomePage.css'
import '../../styles/ownerMotion.css'

const menuDescriptions: Record<string, string> = {
  '/owner/actions': '보류된 결제의 처리 결과를 확인하세요.',
  '/owner/settlements': '정산 내역과 실제 지급을 확인하세요.',
  '/owner/funds': '앞으로의 잔액과 예정된 지출을 살펴보세요.',
  '/owner/loans': '운영에 필요한 자금 상품을 살펴보세요.',
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
      <PageHeader
        title="홈"
        description={
          data && !connected
            ? `${user.displayName}님, 반가워요. 쇼핑몰을 연결하면 이상한 결제를 돈워리가 먼저 멈추고 알려 드려요.`
            : `${user.displayName}님, ${todayLabel(today)} · 오늘 확인할 것과 돈 흐름을 정리했어요.`
        }
        // 오류일 때는 상자 안의 다시 시도 버튼 하나만 둔다
        actions={
          state.kind !== 'error' && (
            <Button icon={<ReloadOutlined />} loading={busy} onClick={refreshAll}>
              새로고침
            </Button>
          )
        }
      />

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
      {connected && data && (
        <section
          className="oh-connection ow-rise"
          style={delay(60)}
          aria-labelledby="oh-connection-title"
        >
          <span className="oh-connection-icon" aria-hidden="true">
            <CheckOutlined />
          </span>
          <div className="oh-connection-copy">
            <span className="oh-eyebrow">
              <LinkOutlined aria-hidden="true" /> 쇼핑몰 연동
              <span className="oh-live" aria-hidden="true" />
              <span className="oh-live-text">연결됨</span>
            </span>
            <h2 id="oh-connection-title">쇼핑몰 연동이 완료되었습니다</h2>
            <dl className="oh-details">
              <div>
                <dt>가맹점 코드</dt>
                <dd className="oh-code">{data.merchantCode}</dd>
              </div>
              {data.connectedAt && (
                <div>
                  <dt>연결 확인 (한국 시간)</dt>
                  <dd>{formatKstDateTime(data.connectedAt)}</dd>
                </div>
              )}
            </dl>
          </div>
          <Link className="oh-secondary-link" to="/owner/integration">
            시크릿 키 관리 <ArrowRightOutlined />
          </Link>
        </section>
      )}

      {showBusiness && finance && (
        <TodoStrip todos={todos} status={attentionStatus} onRetry={refreshAll} />
      )}

      {/* 홈 요약은 두 개만 — 이번 달 받은 돈(정산)과 보류된 결제(보안). 잔액 예측은 자금 현황 메뉴에서 본다 */}
      {showBusiness && (finance || fds) && (
        <section className="oh-money" aria-label="요약">
          {finance && (
            <MoneyCard
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
              aside={monthData && <PayoutBars payouts={monthData.payouts} today={today} />}
            />
          )}
          {fds && (
            <SecurityCard
              resource={cases}
              data={caseData?.overview}
              onRetry={refreshAll}
              style={delay(200)}
            />
          )}
        </section>
      )}

      {menus.length ? (
        <section
          className="oh-menu-section ow-rise"
          style={delay(520)}
          aria-labelledby="oh-menu-title"
        >
          <div className="oh-section-heading">
            <h2 id="oh-menu-title">이용 메뉴</h2>
            <span>내 계정에서 이용할 수 있는 메뉴</span>
          </div>
          <div className="oh-menu-grid">
            {menus.map((item) => (
              <Link key={item.path} className="oh-menu-card" to={item.path}>
                <span className="oh-menu-icon" aria-hidden="true">
                  {item.path === '/owner/actions' ? <FileSearchOutlined /> : <WalletOutlined />}
                </span>
                <h3>{item.label}</h3>
                <p>{menuDescriptions[item.path]}</p>
                <ArrowRightOutlined className="oh-menu-arrow" aria-hidden="true" />
              </Link>
            ))}
          </div>
        </section>
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

function MoneyCard({
  label,
  value,
  foot,
  to,
  failed,
  setup,
  onRetry,
  style,
  aside,
}: {
  label: string
  value: number | undefined
  foot: string
  to: string
  failed: boolean
  setup?: boolean
  onRetry: () => void
  style: CSSProperties
  /** 오른쪽 작은 그림 (실제 데이터로만 그린다) */
  aside?: ReactNode
}) {
  const className = 'oh-money-card ow-rise'
  const counted = useCountUp(value, 900, 150)
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
      className={`${className} is-split`}
      style={style}
      to={to}
      aria-label={value === undefined ? label : `${label} ${value.toLocaleString('ko-KR')}원`}
    >
      <span className="oh-money-main">
        <span className="oh-money-label">{label}</span>
        {value === undefined ? (
          <Skeleton.Input active size="large" className="oh-money-skeleton" />
        ) : (
          <span className="oh-money-value" aria-hidden="true">
            {(counted ?? value).toLocaleString('ko-KR')}
            <span>원</span>
          </span>
        )}
        <span className="oh-money-foot">{foot}</span>
      </span>
      {aside}
    </Link>
  )
}

/** 보류된 결제 한 장 — 지금 조사 중인 사건 수를 크게, 지금까지의 결정 건수는 작게 */
function SecurityCard({
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
  const label = '보류된 결제'
  if (!data)
    return resource.status === 'error' ? (
      <div className="oh-money-card ow-rise" style={style}>
        <span className="oh-money-label">{label}</span>
        <div className="oh-money-failed">
          불러오지 못했어요
          <Button size="small" onClick={onRetry}>
            다시 시도
          </Button>
        </div>
        <span className="oh-money-foot">확인되지 않은 건수는 0건으로 보여 드리지 않아요</span>
      </div>
    ) : (
      <div
        className="oh-money-card ow-rise"
        style={style}
        role="status"
        aria-label={`${label}을 불러오는 중`}
      >
        <span className="oh-money-label">{label}</span>
        <Skeleton.Input active size="large" className="oh-money-skeleton" />
      </div>
    )
  return <SecurityCardReady data={data} label={label} style={style} />
}

function SecurityCardReady({
  data,
  label,
  style,
}: {
  data: MerchantCaseOverview
  label: string
  style: CSSProperties
}) {
  const counted = useCountUp(data.investigatingCount, 700, 150)
  const checking = data.investigatingCount > 0
  return (
    <Link
      className="oh-money-card is-split ow-rise"
      style={style}
      to="/owner/actions"
      aria-label={`${label}, 담당자 확인 중 ${data.investigatingCount}건, 정상 처리 결정 ${data.acceptDecisionCount}건, 취소 결정 ${data.rejectDecisionCount}건`}
    >
      <span className="oh-money-main">
        <span className="oh-money-label">
          {label} · {checking ? '담당자 확인 중' : '확인 중인 결제 없음'}
          {checking && <span className="oh-live is-checking" aria-hidden="true" />}
        </span>
        <span className="oh-money-value" aria-hidden="true">
          {(counted ?? data.investigatingCount).toLocaleString('ko-KR')}
          <span>건</span>
        </span>
        <span className="oh-money-foot">
          지금까지 정상 처리 {data.acceptDecisionCount.toLocaleString('ko-KR')}건 · 취소{' '}
          {data.rejectDecisionCount.toLocaleString('ko-KR')}건
        </span>
      </span>
      <DecisionRing accept={data.acceptDecisionCount} reject={data.rejectDecisionCount} />
    </Link>
  )
}

/**
 * 이번 달 1일부터 오늘까지 날짜별 실제 지급 막대. 지급이 없는 날은 바닥 선만 남긴다.
 * 금액은 카드 숫자와 접근성 이름이 말해 주므로 그림은 읽기 프로그램에서 숨긴다.
 */
function PayoutBars({ payouts, today }: { payouts: SettlementReport['payouts']; today: string }) {
  const start = dayjs(today).startOf('month')
  const count = dayjs(today).date()
  const byDate = new Map<string, number>()
  for (const p of payouts)
    byDate.set(p.payoutDate, (byDate.get(p.payoutDate) ?? 0) + p.payoutAmount)
  const days = Array.from({ length: count }, (_, i) => {
    const date = start.add(i, 'day').format('YYYY-MM-DD')
    return { date, amount: Math.max(0, byDate.get(date) ?? 0) }
  })
  const max = Math.max(1, ...days.map((d) => d.amount))
  return (
    <span className="oh-bars" aria-hidden="true">
      {days.map((d, i) => (
        <span
          key={d.date}
          className={`oh-bar ow-grow-y${d.date === today ? ' is-today' : ''}${d.amount ? '' : ' is-empty'}`}
          style={
            {
              height: d.amount ? `${Math.max(8, (d.amount / max) * 100)}%` : undefined,
              '--ow-delay': `${300 + i * 22}ms`,
            } as CSSProperties
          }
        />
      ))}
    </span>
  )
}

/** 지금까지의 담당자 결정 비율 — 정상(초록) · 취소(빨강). 결정이 없으면 회색 고리만 */
function DecisionRing({ accept, reject }: { accept: number; reject: number }) {
  const total = accept + reject
  const acceptLength = total ? (accept / total) * 100 : 0
  return (
    <span className="oh-ring" aria-hidden="true">
      <svg viewBox="0 0 64 64">
        <circle className="oh-ring-track" cx="32" cy="32" r="26" pathLength="100" />
        {total > 0 && (
          <>
            <circle
              className="oh-ring-seg is-accept"
              cx="32"
              cy="32"
              r="26"
              pathLength="100"
              style={{ strokeDasharray: `${acceptLength} 100` }}
            />
            <circle
              className="oh-ring-seg is-reject"
              cx="32"
              cy="32"
              r="26"
              pathLength="100"
              style={{
                strokeDasharray: `${100 - acceptLength} 100`,
                strokeDashoffset: -acceptLength,
              }}
            />
          </>
        )}
      </svg>
      <SafetyCertificateOutlined className="oh-ring-icon" />
    </span>
  )
}
