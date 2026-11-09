import { FinanceMoney } from './FinanceMoney'
import { financeTotal, koreanToday, koreanTime } from './financeView'
import './payout-ledger.css'
import { loanRequirement } from './loanForecast'
import type { CashFlowForecast } from '../../api/finance'
import { useRef, useState } from 'react'
import { Button, Empty } from 'antd'
import { InfoCircleFilled, WarningFilled } from '@ant-design/icons'
import { useLocation, useNavigate } from 'react-router-dom'
import { CashBalanceChart } from './FinanceCharts'
import './cashflow-overview.css'
export type ForecastDayView = {
  date: string
  opening: number
  completed: number
  scheduled: number
  expenses: number
  closing: number
}
export type ForecastSource = {
  id: string
  date: string
  title: string
  amount: number
  note: string
  settlementAmount?: number
  refundDeductionAmount?: number
}
const money = (value: number) => `${value.toLocaleString('ko-KR')}원`
function Sources({ items, empty }: { items: ForecastSource[]; empty: string }) {
  const sorted = [...items].sort((a, b) => b.amount - a.amount)
  const row = (item: ForecastSource) => (
    <li key={item.id}>
      <div>
        <b>{item.title}</b>
        <small>{item.note}</small>
        {(item.refundDeductionAmount ?? 0) > 0 && (
          <small className="cashflow-refund-note">
            원 정산금 <FinanceMoney value={item.settlementAmount ?? item.amount} /> − 환불 회수{' '}
            <FinanceMoney value={item.refundDeductionAmount!} /> = 유입{' '}
            <FinanceMoney value={item.amount} />
          </small>
        )}
      </div>
      <strong>
        <FinanceMoney value={item.amount} />
      </strong>
    </li>
  )
  return sorted.length ? (
    <>
      <ul className="cashflow-sources">{sorted.slice(0, 5).map(row)}</ul>
      {sorted.length > 5 && (
        <details>
          <summary>나머지 {sorted.length - 5}건 보기</summary>
          <ul className="cashflow-sources">{sorted.slice(5).map(row)}</ul>
        </details>
      )}
    </>
  ) : (
    <p className="cashflow-muted">{empty}</p>
  )
}
export function CashFlowOverview({
  days,
  incomes,
  costs,
  onDetails,
  preview = false,
  currentBalance,
  balanceBasis,
  summary,
  capturedAt,
  ownerScope,
  recovery,
  graphDate,
  onGraphDateChange,
  minGraphDate,
  maxGraphDate,
}: {
  graphDate?: string
  onGraphDateChange?: (date: string) => void
  minGraphDate?: string
  maxGraphDate?: string
  days: ForecastDayView[]
  incomes: ForecastSource[]
  costs: ForecastSource[]
  preview?: boolean
  currentBalance?: number
  balanceBasis?: { opening: number; income: number; expenses: number }
  summary?: CashFlowForecast
  capturedAt?: string
  ownerScope?: string
  recovery?: { outstanding: number; projected: number; remaining: number }
  onDetails: (date: string) => void
}) {
  const [selected, setSelected] = useState(graphDate ?? '')
  const selectDate = (date: string) => {
    setSelected(date)
    onGraphDateChange?.(date)
  }
  const navigate = useNavigate()
  const { pathname, search } = useLocation()
  const evidenceRef = useRef<HTMLElement>(null)
  if (!days.length) return <Empty description="올바른 조회 기간을 선택해 주세요." />
  const firstShortage = summary
    ? days.find((d) => d.date === summary.firstShortageDate)
    : days.find((d) => d.closing < 0)
  const requirement = loanRequirement(days)!
  const maximumShortage = summary?.maximumShortage ?? requirement.required
  const active =
    days.find((d) => d.date === selected) ??
    days.find((d) => d.date === graphDate) ??
    days.find((d) => d.date === koreanToday()) ??
    days[0]
  const dayIncome = incomes.filter((e) => e.date === active.date)
  const dayCosts = costs.filter((e) => e.date === active.date)
  const largest = [...dayCosts].sort((a, b) => b.amount - a.amount)[0]
  const incoming = financeTotal([active.completed, active.scheduled])
  const recoveryInfo = summary
    ? {
        outstanding: summary.outstandingRecoveryAmount,
        projected: summary.projectedRefundDeduction,
        remaining: summary.remainingRecoveryAmount,
      }
    : recovery
  const balanceCalculation = summary
    ? {
        opening: summary.openingBalance,
        income: summary.completedIncomeSinceBase,
        expenses: summary.paidExpensesSinceBase,
      }
    : balanceBasis
  const deduction = financeTotal(dayIncome.map((e) => e.refundDeductionAmount ?? 0))
  const originalIncome = financeTotal(dayIncome.map((e) => e.settlementAmount ?? e.amount))
  const incomeMatches = dayIncome.reduce((sum, e) => sum + e.amount, 0) === incoming
  const costsMatch = dayCosts.reduce((sum, e) => sum + e.amount, 0) === active.expenses
  return (
    <div className="cashflow-overview">
      <div className="cashflow-overview-toolbar">
        <div>
          <span className="cashflow-eyebrow">자금 흐름 미리보기</span>
          <p>
            {days[0].date} ~ {days.at(-1)!.date} · 일 마감 기준
          </p>
        </div>
      </div>
      <div className="cashflow-summary-grid">
        {[
          [
            currentBalance === undefined ? '기간 시작 잔액' : '오늘 계산 잔액',
            currentBalance ?? days[0].opening,
            currentBalance === undefined
              ? '직접 설정한 잔액을 기준으로 계산'
              : '시작 잔액 + 환불 차감 후 지급액 − 실제 지출 완료',
          ],
          [
            '기간 내 정산 예정',
            summary?.totalIncome ?? days.reduce((n, d) => n + d.completed + d.scheduled, 0),
            '실제 지급 + 환불 회수 차감 후 예상 지급',
          ],
          [
            '기간 내 지출 (완료·예정)',
            summary?.totalExpenses ?? days.reduce((n, d) => n + d.expenses, 0),
            '일회성 지출 · 정기 지출 포함',
          ],
          [
            '최저 예상 잔액',
            summary?.lowestBalance ?? Math.min(...days.map((d) => d.closing)),
            '일 마감 기준 · 실제 은행 잔액 아님',
          ],
        ].map(([label, amount, note], index) => (
          <section className={`cashflow-surface cashflow-stat-${index}`} key={label}>
            <span>{label}</span>
            <strong>
              <FinanceMoney value={Number(amount)} />
            </strong>
            <small>{note}</small>
            {index === 0 && balanceCalculation && (
              <details className="cashflow-balance-basis">
                <summary>계산 근거</summary>
                <p>
                  시작 잔액 <FinanceMoney value={balanceCalculation.opening} /> ＋ 실제 지급{' '}
                  <FinanceMoney value={balanceCalculation.income} /> − 실제 지출 완료{' '}
                  <FinanceMoney value={balanceCalculation.expenses} />
                </p>
                {summary && <small>계산 기준: {koreanTime(summary.asOf)}</small>}
              </details>
            )}
          </section>
        ))}
      </div>
      {recoveryInfo && (recoveryInfo.outstanding > 0 || recoveryInfo.projected > 0) && (
        <section className="recovery-notice" aria-label="환불 회수와 이월 안내">
          <h3>
            <InfoCircleFilled aria-hidden />
            환불 회수가 앞으로의 지급액에 반영돼요
          </h3>
          <p>
            이미 지급한 주문의 환불은 다음 정산에서 회수합니다. 현재 잔액에서 다시 빼지 않고, 아래
            예측에는 차감 후 들어올 금액만 반영했습니다.
          </p>
          <div className="recovery-values">
            <div>
              <span>현재 미회수액</span>
              <b>
                <FinanceMoney value={recoveryInfo.outstanding} />
              </b>
            </div>
            <div>
              <span>조회 종료일까지 예상 회수</span>
              <b>
                <FinanceMoney value={recoveryInfo.projected} />
              </b>
            </div>
            <div>
              <span>이후로 이월할 회수액</span>
              <b>
                <FinanceMoney value={recoveryInfo.remaining} />
              </b>
            </div>
          </div>
          <p>
            예상 회수에는 조회 시작 전 이월 구간도 포함됩니다. 새로운 매출과 추가 환불은 예측하지
            않습니다.
          </p>
        </section>
      )}
      <section
        className={`cashflow-verdict ${firstShortage ? 'cashflow-verdict-warning' : 'cashflow-verdict-safe'}`}
      >
        <span>
          {firstShortage && <WarningFilled aria-hidden />}
          {firstShortage ? '자금 준비가 필요해요' : '선택 기간에는 자금 부족이 예상되지 않아요'}
        </span>
        <h2>
          {firstShortage ? (
            <>
              {firstShortage.date}에<br />
              <em>
                <FinanceMoney value={-firstShortage.closing} />
              </em>
              이 부족할 것으로 예상돼요.
            </>
          ) : (
            <>
              등록한 유입과 지출 기준으로
              <br />
              운영자금을 유지할 수 있어요.
            </>
          )}
        </h2>
        <div className="cashflow-verdict-facts">
          <div>
            <small>기간 내 최대 부족액</small>
            <b>
              <FinanceMoney value={maximumShortage} />
            </b>
          </div>
          <div>
            <small>기간 말 예상 잔액</small>
            <b>
              <FinanceMoney value={days.at(-1)!.closing} />
            </b>
          </div>
          <div>
            <small>기간 시작 잔액</small>
            <b>
              <FinanceMoney value={days[0].opening} />
            </b>
          </div>
        </div>
        {firstShortage && (
          <button
            className="cashflow-reason-link"
            onClick={() => {
              selectDate(firstShortage.date)
              evidenceRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' })
            }}
          >
            처음 부족해지는 날의 원인 확인 ↓
          </button>
        )}
      </section>
      <section className="cashflow-surface">
        <div className="cashflow-section-head">
          <div>
            <h2>잔액이 어떻게 달라질까요?</h2>
            <p>
              초록 막대는 정산 유입, 빨간 막대는 지출, 갈색 선은 예상 잔액입니다. 날짜를 선택하면
              계산 근거를 확인할 수 있습니다.
            </p>
          </div>
          <Button size="small" onClick={() => onDetails(active.date)}>
            전체 계산 내역
          </Button>
        </div>
        <div className="cashflow-chart-legend">
          <span style={{ color: '#15803d' }}>■ 정산 유입</span>
          <span style={{ color: '#dc2626' }}>■ 지출 (완료·예정)</span>
          <span style={{ color: '#172554' }}>━ 예상 잔액</span>
          <span>주황색 점 · 부족 예상일</span>
        </div>
        <CashBalanceChart
          points={days.map((d) => ({
            date: d.date,
            balance: d.closing,
            income: d.completed + d.scheduled,
            expense: d.expenses,
          }))}
          selectedDate={active.date}
          minSelectableDate={minGraphDate}
          maxSelectableDate={maxGraphDate}
          onSelect={selectDate}
        />
      </section>
      <section
        ref={evidenceRef}
        className="cashflow-surface"
        aria-label="선택 날짜 자금 부족 계산 근거"
      >
        <div className="cashflow-section-head">
          <div>
            <span className="cashflow-eyebrow">{active.date}</span>
            <h2>{active.closing < 0 ? '왜 자금이 부족할까요?' : '이날 잔액은 이렇게 계산돼요'}</h2>
          </div>
          <strong className={active.closing < 0 ? 'cashflow-negative' : ''}>
            <FinanceMoney value={active.closing} />
          </strong>
        </div>
        <div className="cashflow-equation">
          <div>
            <small>전날에서 이어진 잔액</small>
            <b>
              <FinanceMoney value={active.opening} />
            </b>
          </div>
          <span>＋</span>
          <div>
            <small>이날 정산 유입</small>
            <b>
              <FinanceMoney value={incoming} />
            </b>
          </div>
          <span>−</span>
          <div>
            <small>이날 지출 (완료·예정)</small>
            <b>
              <FinanceMoney value={active.expenses} />
            </b>
          </div>
          <span>＝</span>
          <div>
            <small>예상 마감 잔액</small>
            <b className={active.closing < 0 ? 'cashflow-negative' : ''}>
              <FinanceMoney value={active.closing} />
            </b>
          </div>
        </div>
        {deduction > 0 && incomeMatches && (
          <p className="cashflow-deduction-equation">
            이날 원 정산금{' '}
            <b>
              <FinanceMoney value={originalIncome} />
            </b>{' '}
            − 환불 회수{' '}
            <b>
              <FinanceMoney value={deduction} />
            </b>{' '}
            = 실제·예상 유입{' '}
            <b>
              <FinanceMoney value={incoming} />
            </b>
            . 회수액을 지출에서 다시 차감하지 않습니다.
          </p>
        )}
        <p className="cashflow-explanation">
          {active.closing < 0
            ? active.opening < 0
              ? '이전 날짜에서 이어진 부족액에 이날의 유입과 지출을 반영한 결과입니다.'
              : `전날 잔액 ${money(active.opening)}에 유입 ${money(incoming)}과 지출 ${money(active.expenses)}을 반영한 결과입니다.`
            : '전날 잔액에 이날 정산 유입을 더하고 지출 (완료·예정)을 차감했습니다.'}{' '}
          {largest &&
            costsMatch &&
            `이날 가장 큰 지출은 '${largest.title}' ${money(largest.amount)}입니다.`}
        </p>
        <div className="cashflow-evidence">
          <div>
            <h3>
              들어올 돈{' '}
              <span>
                <FinanceMoney value={incoming} />
              </span>
            </h3>
            {incomeMatches ? (
              <Sources items={dayIncome} empty="이날 반영할 정산 유입이 없습니다." />
            ) : (
              <p className="cashflow-negative">
                계산 결과와 상세 내역이 달라졌습니다. 새로 조회해 주세요.
              </p>
            )}
            <p className="cashflow-muted">
              실제 지급 <FinanceMoney value={active.completed} /> · 매입일 기준 예상 지급{' '}
              <FinanceMoney value={active.scheduled} />
            </p>
          </div>
          <div>
            <h3>
              나갈 돈{' '}
              <span>
                <FinanceMoney value={active.expenses} />
              </span>
            </h3>
            {costsMatch ? (
              <Sources items={dayCosts} empty="이날 등록한 지출이 없습니다." />
            ) : (
              <p className="cashflow-negative">
                계산 결과와 상세 내역이 달라졌습니다. 새로 조회해 주세요.
              </p>
            )}
          </div>
        </div>
      </section>
      <section className="cashflow-loan-card">
        <div>
          <small>필요 자금 (예상)</small>
          <h2>
            {maximumShortage ? <FinanceMoney value={maximumShortage} /> : '추가 필요 자금 없음'}
          </h2>
          <p>선택 기간의 최저 예상 잔액을 0원 이상으로 유지하는 데 필요한 금액입니다.</p>
          <small>
            등록된 정산·지출 기준의 단순 계산이며 실제 대출 필요액이나 승인 금액이 아닙니다.
          </small>
        </div>
        <Button
          size="large"
          onClick={() =>
            navigate(
              pathname.startsWith('/dev/')
                ? `/dev/funds/loans${new URLSearchParams(search).get('sample') === '1' ? '?sample=1' : ''}`
                : '/owner/loans',
              {
                state: {
                  required: maximumShortage,
                  minimum: summary?.lowestBalance ?? Math.min(...days.map((d) => d.closing)),
                  totalIn:
                    summary?.totalIncome ?? days.reduce((n, d) => n + d.completed + d.scheduled, 0),
                  firstShortage: firstShortage?.date ?? null,
                  minimumDate: requirement.minimumDate,
                  from: days[0].date,
                  to: days.at(-1)!.date,
                  capturedAt: capturedAt ?? new Date().toISOString(),
                  ownerScope,
                  preview,
                },
              },
            )
          }
        >
          운영자금 상품 보기 →
        </Button>
      </section>

      <p className="cashflow-footnote">
        현재 확보된 주문·정산과 등록한 지출 기준의 예측입니다. 새로운 주문 매출, 실제 은행 잔액과
        하루 중 입출금 순서는 추정하지 않습니다.
      </p>
    </div>
  )
}
