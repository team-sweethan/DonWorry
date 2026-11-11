import { FinanceGuide } from './FinanceGuide'
import { FinanceMoney } from './FinanceMoney'
import { useCallback, useState } from 'react'
import { useLocation } from 'react-router-dom'
import dayjs from 'dayjs'
import { Alert, Button, Form, InputNumber } from 'antd'
import { useAuth } from '../../auth/useAuth'
import { PageHeader } from '../../components/common/PageHeader'
import {
  getCashFlowForecast,
  getCashFlowSettings,
  getPlannedExpenses,
  getRecurringOccurrences,
  getSettlementReport,
  saveCashFlowSettings,
} from '../../api/finance'
import type { CashFlowSettings } from '../../api/finance'
import { CashFlowOverview } from './CashFlowOverview'
import { SettlementPrototype } from './SettlementPrototype'
import { FinanceExpenseManager } from './FinanceExpenseManager'
import {
  FinanceFailure,
  FinanceLoading,
  FinancePeriodControl,
  FinanceRefresh,
  FinanceStaleNotice,
} from './FinanceControls'
import {
  MAX_FINANCE_AMOUNT,
  chartMonthPeriod,
  formatWonInput,
  parseWonInput,
  financeErrorMessage,
  financeWriteErrorMessage,
  forecastView,
  managementRows,
  money,
  movementLabel,
  settlementViewEntries,
  validFinancePeriod,
  validateSettlementReport,
  monthlyReportMismatch,
} from './financeView'
import type { FinancePeriod } from './financeView'
import { useFinanceResource, useKoreanToday } from './useFinanceResource'
import './finance-preview.css'
import './finance-connected.css'
import './finance-page-header.css'

export function FinanceConnected() {
  const { pathname } = useLocation()
  const { user } = useAuth()
  const scope = `${user?.userId}:${user?.merchantId}`
  return pathname.replace(/\/+$/, '').endsWith('/funds') ? (
    <ConnectedFunds key={scope} ownerScope={scope} />
  ) : (
    <ConnectedSettlements key={scope} />
  )
}
function InitialBalance({
  today,
  onSaved,
}: {
  today: string
  onSaved: (settings: CashFlowSettings) => void
}) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  return (
    <section className="finance-card finance-opening-setup">
      <h2>최초 시작 잔액 설정</h2>
      <p>
        {today} 하루 시작 시점의 금액을 한 번 입력하세요. 저장한 기준일과 잔액은 변경할 수 없습니다.
        오늘 지급된 정산·완료 지출을 시작 잔액에 중복 포함하지 마세요.
      </p>
      {error !== null && <Alert type="error" message={financeErrorMessage(error)} />}
      <Form<{ openingBalance: number; minimumOperatingFunds: number }>
        layout="vertical"
        initialValues={{ minimumOperatingFunds: 0 }}
        disabled={busy}
        onFinish={async (value) => {
          if (busy) return
          setBusy(true)
          setError(null)
          try {
            const saved = await saveCashFlowSettings({
              openingBalance: value.openingBalance,
              minimumOperatingFunds: value.minimumOperatingFunds,
              baseDate: today,
            })
            onSaved(saved)
          } catch (e) {
            setError(new Error(financeWriteErrorMessage(e)))
          } finally {
            setBusy(false)
          }
        }}
      >
        <Form.Item label="최초 기준일">
          <input aria-label="최초 기준일" type="date" value={today} disabled />
        </Form.Item>
        <Form.Item
          name="openingBalance"
          label="시작 잔액 (원)"
          rules={[{ required: true }, { type: 'integer', min: 0, max: MAX_FINANCE_AMOUNT }]}
        >
          <InputNumber
            aria-label="최초 시작 잔액"
            className="finance-money-input"
            formatter={formatWonInput}
            parser={parseWonInput}
            suffix="원"
            min={0}
            max={MAX_FINANCE_AMOUNT}
            precision={0}
            disabled={busy}
          />
        </Form.Item>
        <Form.Item
          name="minimumOperatingFunds"
          label="최소 운영 자금 (원)"
          extra="예상 마감 잔액이 이 금액보다 낮으면 경고해요. 잔액·지출 설정에서 변경할 수 있어요."
          rules={[{ required: true }, { type: 'integer', min: 0, max: MAX_FINANCE_AMOUNT }]}
        >
          <InputNumber
            aria-label="최소 운영 자금"
            className="finance-money-input"
            formatter={formatWonInput}
            parser={parseWonInput}
            suffix="원"
            min={0}
            max={MAX_FINANCE_AMOUNT}
            precision={0}
          />
        </Form.Item>
        <Button type="primary" htmlType="submit" loading={busy}>
          잔액 설정 저장
        </Button>
      </Form>
    </section>
  )
}
function ConnectedFunds({ ownerScope }: { ownerScope: string }) {
  const today = useKoreanToday()
  const [graphDate, setGraphDate] = useState('')
  const [period, setPeriod] = useState<FinancePeriod>(() => ({
    from: today,
    to: dayjs(today).add(6, 'day').format('YYYY-MM-DD'),
  }))
  const [revision, setRevision] = useState(0)
  const [savedInitialBalance, setSavedInitialBalance] = useState<CashFlowSettings | null>(null)
  const [manage, setManage] = useState(false)
  const [details, setDetails] = useState<string | null>(null)
  const refresh = useCallback(() => setRevision((n) => n + 1), [])
  const settingsLoad = useCallback((signal: AbortSignal) => getCashFlowSettings(signal), [])
  const settings = useFinanceResource(`settings:${today}:${revision}`, settingsLoad)
  // 새로고침 동안에도 마지막 설정을 유지해 예측 화면이 비었다 다시 그려지지 않게 한다 (#803)
  const setting =
    settings.status === 'success'
      ? settings.data
      : settings.status === 'loading'
        ? settings.stale
        : undefined
  // 설정을 바꾸는 창과 그 버튼은 지금처럼 새로 받은 설정으로만 연다
  const freshSetting = settings.status === 'success' ? settings.data : undefined
  const valid =
    validFinancePeriod(period) &&
    period.to <= dayjs(today).add(365, 'day').format('YYYY-MM-DD') &&
    (!setting || period.from >= setting.baseDate)
  const load = useCallback(
    async (signal: AbortSignal) => {
      const forecast = await getCashFlowForecast(period.from, period.to, signal)
      return { forecast, view: forecastView(forecast) }
    },
    [period.from, period.to],
  )
  const forecast = useFinanceResource(
    `forecast:${today}:${period.from}:${period.to}:${revision}`,
    load,
    valid && !!setting,
  )
  // 다시 조회하는 동안 마지막 예측을 남긴다. 기간이 바뀐 조회면 흐리게 하고 조작을 막는다.
  const shownForecast =
    forecast.status === 'success'
      ? forecast.data
      : forecast.status === 'loading'
        ? forecast.stale
        : undefined
  const changingPeriod =
    forecast.status === 'loading' &&
    !!shownForecast &&
    (shownForecast.forecast.from !== period.from || shownForecast.forecast.to !== period.to)
  const result = shownForecast?.forecast ?? null
  const excludedIncome = result
    ? [
        { label: '일정 미정', amount: result.undatedIncome },
        { label: '지난 배치 미완료', amount: result.overdueIncome },
        { label: '취소', amount: result.canceledIncome },
      ]
        .filter(({ amount }) => amount !== 0)
        .map(({ label, amount }) => `${label} ${money(amount)}`)
    : []
  const view = shownForecast?.view ?? null
  // 계산 내역 창은 기존(dev)처럼 조회가 끝난 결과에서만 연다. 다시 조회하는 동안에는 잠시 닫혔다가 결과가 오면 다시 열린다
  const active =
    forecast.status === 'success' ? result?.days.find((d) => d.date === details) : undefined
  const preset = (days: number) =>
    setPeriod({
      from: today,
      to: dayjs(today)
        .add(days - 1, 'day')
        .format('YYYY-MM-DD'),
    })
  return (
    <div className="finance-preview finance-connected">
      <PageHeader
        title="자금 현황"
        description="실제 완료 기록과 등록한 정산·지출로 앞으로의 잔액을 확인합니다."
        actions={
          <div className="finance-header-actions">
            <FinanceGuide page="funds" />
            <FinanceRefresh
              at={forecast.lastSuccess ?? settings.lastSuccess}
              loading={settings.status === 'loading' || forecast.status === 'loading'}
              onRefresh={refresh}
            />
          </div>
        }
      />
      <Alert
        type="info"
        showIcon
        message="지급·지출 기록으로 계산한 잔액"
        className="finance-intro-alert"
        description="은행 계좌를 조회하는 기능이 아닙니다. 환불 회수를 차감한 실제 지급액만 입금으로 간주하고, 실제 지출 완료 기록을 현재 잔액에서 차감합니다."
      />
      <div className="finance-period" data-guide="finance-period">
        <Button onClick={() => preset(7)}>앞으로 7일</Button>
        <Button onClick={() => preset(30)}>앞으로 30일</Button>
        <Button
          onClick={() => {
            const next = dayjs(today).add(1, 'month')
            setPeriod({
              from: next.startOf('month').format('YYYY-MM-DD'),
              to: next.endOf('month').format('YYYY-MM-DD'),
            })
          }}
        >
          다음 달 예측
        </Button>
      </div>
      <FinancePeriodControl
        value={period}
        onChange={setPeriod}
        min={setting?.baseDate}
        max={dayjs(today).add(365, 'day').format('YYYY-MM-DD')}
        actions={
          <Button
            data-guide="finance-settings"
            type="primary"
            disabled={!freshSetting}
            onClick={() => setManage(true)}
          >
            잔액·지출 설정
          </Button>
        }
      />
      {!valid && (
        <Alert
          type="warning"
          message="잔액 기준일 이후 최대 366일, 종료일은 오늘+365일 이내로 선택해 주세요."
        />
      )}
      {savedInitialBalance && (
        <Alert
          type="success"
          showIcon
          message="최초 잔액 저장이 완료되었습니다. 다시 저장할 필요가 없습니다."
          description={`기준일 ${savedInitialBalance.baseDate} · 시작 잔액 ${money(savedInitialBalance.openingBalance)}. 이후 조회에 실패하면 새로고침해 주세요. 저장한 값은 변경할 수 없습니다.`}
        />
      )}
      {settings.status === 'error' && <FinanceFailure error={settings.error} onRetry={refresh} />}
      {settings.status === 'loading' && !setting && <FinanceLoading />}
      {settings.status === 'success' && setting === null && !savedInitialBalance && (
        <InitialBalance
          today={today}
          onSaved={(saved) => {
            setSavedInitialBalance(saved)
            refresh()
          }}
        />
      )}
      {forecast.status === 'error' && <FinanceFailure error={forecast.error} onRetry={refresh} />}
      {forecast.status === 'loading' && !shownForecast && <FinanceLoading />}
      {changingPeriod && <FinanceStaleNotice />}
      {result && view && (
        <div
          // 새 결과가 도착할 때마다 기존(dev)처럼 안쪽 상태를 새로 시작한다. 기다리는 동안에는 이전 결과를 그대로 둔다
          key={forecast.lastSuccess ?? 'first'}
          className={`finance-result${forecast.status === 'loading' ? ' is-busy' : ''}${changingPeriod ? ' is-stale' : ''}`}
          aria-busy={forecast.status === 'loading'}
        >
          {!result.batchEnabled && (
            <Alert
              type="warning"
              message="자동 정산 배치가 비활성 상태입니다"
              description="예상 유입은 서버에 설정된 배치가 실행된다는 가정입니다. 실제 지급을 보장하지 않습니다."
            />
          )}
          {excludedIncome.length > 0 && (
            <Alert
              type="info"
              message="예측에서 제외한 정산"
              description={excludedIncome.join(' · ')}
            />
          )}
          <CashFlowOverview
            {...view}
            graphDate={graphDate}
            minGraphDate={setting?.baseDate}
            maxGraphDate={dayjs(today).add(365, 'day').format('YYYY-MM-DD')}
            onGraphDateChange={(date) => {
              setGraphDate(date)
              if (date < period.from || date > period.to)
                setPeriod(
                  chartMonthPeriod(
                    date,
                    setting?.baseDate,
                    dayjs(today).add(365, 'day').format('YYYY-MM-DD'),
                  ),
                )
            }}
            currentBalance={result.currentBalance}
            summary={result}
            capturedAt={result.asOf}
            ownerScope={ownerScope}
            onDetails={setDetails}
          />
          {result.carryForwardMovements.length > 0 && (
            <details className="finance-card">
              <summary>
                기간 시작 전 이월 근거 {result.carryForwardMovements.length}건 · 시작 잔액{' '}
                <FinanceMoney value={result.periodOpeningBalance} />
              </summary>
              <ul>
                {result.carryForwardMovements.map((m) => (
                  <li key={`${m.source}:${m.referenceId}:${m.occurrenceDate ?? ''}`}>
                    {m.date} · {m.title} · <FinanceMoney value={m.amount} /> ·{' '}
                    {movementLabel(m.classification)} #{m.referenceId}
                    {m.refundDeductionAmount > 0 &&
                      ` · 원 정산금 ${money(m.settlementAmount)} − 환불 회수 ${money(m.refundDeductionAmount)}`}
                  </li>
                ))}
              </ul>
            </details>
          )}
        </div>
      )}
      <FinanceExpenseManager
        open={manage}
        onClose={() => setManage(false)}
        settings={freshSetting ?? undefined}
        today={today}
        revision={revision}
        onChanged={refresh}
      />
      {active && (
        <div className="finance-card" role="dialog" aria-label="날짜별 예상 잔액 계산">
          <Button onClick={() => setDetails(null)}>계산 내역 닫기</Button>
          <h2>{active.date} 전체 계산 내역</h2>
          <p>
            전날 잔액 <FinanceMoney value={active.openingBalance} /> · 마감 잔액{' '}
            <FinanceMoney value={active.closingBalance} />
          </p>
          <ul>
            {active.movements.map((m) => (
              <li key={`${m.source}:${m.referenceId}:${m.occurrenceDate ?? ''}`}>
                {m.title} · <FinanceMoney value={m.amount} /> · {movementLabel(m.classification)} ·
                #{m.referenceId}
                {m.occurrenceDate && ` · 원래 예정일 ${m.occurrenceDate}`}
                {m.refundDeductionAmount > 0 && (
                  <small className="payout-deduction">
                    {' '}
                    · 원 정산금 <FinanceMoney value={m.settlementAmount} /> − 환불 회수{' '}
                    <FinanceMoney value={m.refundDeductionAmount} /> = 유입{' '}
                    <FinanceMoney value={m.amount} />
                  </small>
                )}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}
function ConnectedSettlements() {
  const today = useKoreanToday()
  const [graphDate, setGraphDate] = useState('')
  const [period, setPeriod] = useState<FinancePeriod>(() => ({
    from: dayjs(today).startOf('month').format('YYYY-MM-DD'),
    to: dayjs(today).endOf('month').format('YYYY-MM-DD'),
  }))
  const [revision, setRevision] = useState(0)
  const refresh = () => setRevision((n) => n + 1)
  const valid = validFinancePeriod(period)
  const load = useCallback(
    async (signal: AbortSignal) => {
      const [report, expenses, occurrences] = await Promise.all([
        getSettlementReport(period.from, period.to, signal),
        getPlannedExpenses(period.from, period.to, signal),
        getRecurringOccurrences(period.from, period.to, signal),
      ])
      return {
        report,
        entries: settlementViewEntries(report),
        expenses: managementRows(expenses, occurrences),
      }
    },
    [period.from, period.to],
  )
  const resource = useFinanceResource(
    `report:${period.from}:${period.to}:${today}:${revision}`,
    load,
    valid,
  )
  const monthFrom = dayjs(today).startOf('month').format('YYYY-MM-DD')
  const monthLoad = useCallback(
    (signal: AbortSignal) =>
      getSettlementReport(monthFrom, today, signal).then(validateSettlementReport),
    [monthFrom, today],
  )
  const month = useFinanceResource(`month:${today}:${revision}`, monthLoad)
  // 다시 조회하는 동안에는 마지막 결과를 그대로 두어 화면이 비었다 다시 그려지지 않게 한다 (#803).
  // 기간이 바뀐 조회라면 이전 기간의 숫자이므로 흐리게 하고 조작을 막는다. 같은 기간 새로고침은 버튼 로딩만 보인다.
  const shown =
    resource.status === 'success'
      ? resource.data
      : resource.status === 'loading'
        ? resource.stale
        : undefined
  const changingPeriod =
    resource.status === 'loading' &&
    !!shown &&
    (shown.report.from !== period.from || shown.report.to !== period.to)
  const mismatch =
    resource.status === 'success' &&
    month.status === 'success' &&
    monthlyReportMismatch(resource.data.report, month.data, today)
  const actions = (
    <FinanceRefresh
      at={resource.lastSuccess}
      loading={resource.status === 'loading'}
      onRefresh={refresh}
    />
  )
  const periodControls = (
    <FinancePeriodControl
      value={period}
      onChange={setPeriod}
      presets={
        <>
          <Button onClick={() => setPeriod({ from: today, to: today })}>오늘</Button>
          <Button
            onClick={() =>
              setPeriod({
                from: dayjs(today).startOf('month').format('YYYY-MM-DD'),
                to: dayjs(today).endOf('month').format('YYYY-MM-DD'),
              })
            }
          >
            이번 달
          </Button>
        </>
      }
    />
  )
  return (
    <div className="finance-preview finance-connected">
      {!shown && (
        <>
          <PageHeader
            title="정산 내역"
            description="매입일 기준 영업일 D+3(주말·한국 공휴일 제외) 정산과 환불 차감 후 실제 지급액을 확인합니다."
            actions={actions}
          />
          {periodControls}
        </>
      )}
      {!valid && (
        <>
          <Alert type="warning" message="올바른 날짜로 최대 366일을 선택해 주세요." />
        </>
      )}
      {resource.status === 'loading' && !shown && <FinanceLoading />}
      {resource.status === 'error' && <FinanceFailure error={resource.error} onRetry={refresh} />}
      {shown && (
        <div
          // 새 결과가 도착할 때마다 기존(dev)처럼 필터 · 검색 · 페이지 · 보기 · 상세 창을 새로 시작한다.
          // 기다리는 동안에는 이전 결과를 그대로 두어 화면이 비지 않게만 한다
          key={resource.lastSuccess ?? 'first'}
          className={`finance-result${resource.status === 'loading' ? ' is-busy' : ''}${changingPeriod ? ' is-stale' : ''}`}
          aria-busy={resource.status === 'loading'}
        >
          <SettlementPrototype
            todayValue={today}
            period={period}
            onPeriodChange={setPeriod}
            graphDate={graphDate}
            onGraphDateChange={setGraphDate}
            headerActions={actions}
            actualEntries={shown.entries}
            payoutReport={shown.report}
            periodControls={periodControls}
            notice={
              <>
                {changingPeriod && <FinanceStaleNotice />}
                {shown.report.undatedAmount > 0 && (
                  <Alert
                    type="info"
                    message={`일정이 없는 예정 정산 ${money(shown.report.undatedAmount)}`}
                    description="날짜 미정 금액은 달력과 기간 합계에 임의로 포함하지 않습니다."
                  />
                )}
              </>
            }
            expenses={shown.expenses.map((e) => ({
              id: e.key,
              date: e.date,
              title: e.title,
              amount: e.amount,
              memo: e.memo,
              originalDate: e.originalDate,
              paidAt: e.paidAt,
              updatedAt: e.expense?.updatedAt,
              note: `${e.kind === 'ONEOFF' ? '일회성' : '정기'} 지출 · ${e.paidAt ? '지출 완료' : '예정'}`,
            }))}
            monthSummary={
              <>
                <div>이번 달 누적 실제 지급액</div>
                {mismatch ? (
                  <span>내역 조회와 이번 달 지급액의 금액이 달라졌습니다. 새로고침해 주세요.</span>
                ) : month.status === 'success' ? (
                  <strong>
                    <FinanceMoney value={month.data.paidAmount} />
                  </strong>
                ) : month.status === 'error' ? (
                  <span>이번 달 지급액 조회 실패 · 새로고침해 주세요.</span>
                ) : (
                  <span className="dw-loading-reveal">이번 달 지급액 조회 중</span>
                )}
              </>
            }
          />
        </div>
      )}
    </div>
  )
}
