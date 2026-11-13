import { Mascot } from '../../components/brand/BrandMark'
import { FinanceMoney } from './FinanceMoney'
import { financeTotal, koreanToday, koreanTime } from './financeView'
import './payout-ledger.css'
import { loanRequirement } from './loanForecast'
import type { CashFlowForecast } from '../../api/finance'
import { useRef, useState } from 'react'
import { Button, Empty } from 'antd'
import {
  ArrowDownOutlined,
  ArrowUpOutlined,
  CheckCircleOutlined,
  InfoCircleFilled,
  WalletOutlined,
  WarningFilled,
} from '@ant-design/icons'
import { useLocation, useNavigate } from 'react-router-dom'
import { CashBalanceChart } from './FinanceCharts'
import './cashflow-overview.css'
import './funds-experience.css'
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
            차감 전 정산금 <FinanceMoney value={item.settlementAmount ?? item.amount} /> − 정산 후
            환불 차감 <FinanceMoney value={item.refundDeductionAmount!} /> = 유입{' '}
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
  onManageExpenses,
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
  onManageExpenses?: () => void
  onDetails: (date: string) => void
}) {
  const [selected, setSelected] = useState(graphDate ?? '')
  const selectDate = (date: string) => {
    setSelected(date)
    onGraphDateChange?.(date)
  }
  const navigate = useNavigate()
  const { pathname, search } = useLocation()
  const evidenceRef = useRef<HTMLDetailsElement>(null)
  if (!days.length) return <Empty description="올바른 조회 기간을 선택해 주세요." />
  const minimumOperatingFunds = summary?.minimumOperatingFunds ?? 0
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
    <div className="cashflow-overview funds-experience">
      <section className="cashflow-surface">
        <div className="cashflow-section-head">
          <div>
            <h2>우리 가게 자금 예보</h2>
            <p>
              맑은 날도, 자금 준비가 필요한 날도 미리 살펴보세요. 날짜를 누르면 그날의 금액을 확인할
              수 있어요.
            </p>
          </div>
        </div>
        <CashBalanceChart
          minimumOperatingFunds={minimumOperatingFunds}
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
        className={`funds-quick-summary ${firstShortage ? 'cashflow-verdict-warning' : 'cashflow-verdict-safe'}`}
        aria-label="자금 현황 빠른 요약"
      >
        <div
          className={`funds-quick-cell funds-quick-status${firstShortage ? ' is-shortage' : ''}`}
        >
          <div className="funds-decision-icon" aria-hidden="true">
            <Mascot size={76} className="dw-mascot-float" />
            <span className="funds-mascot-check">
              {firstShortage ? <WarningFilled /> : <CheckCircleOutlined />}
            </span>
          </div>
          <div className="funds-decision-copy">
            <span>{firstShortage ? '자금 준비가 필요해요' : '선택 기간에 부족 예상 없음'}</span>
            {firstShortage ? (
              <>
                <b>
                  {Number(firstShortage.date.slice(5, 7))}월 {Number(firstShortage.date.slice(8))}
                  일, 자금이 부족할 수 있어요
                </b>
                <small>
                  기간 내 최대 부족액 <FinanceMoney value={maximumShortage} compact={false} />
                </small>
                <details className="funds-quick-basis">
                  <summary>첫 부족일의 금액 확인</summary>
                  <p>
                    이날 마감 잔액이 설정한 기준보다{' '}
                    <FinanceMoney
                      value={minimumOperatingFunds - firstShortage.closing}
                      compact={false}
                    />{' '}
                    낮아질 것으로 예상돼요.
                  </p>
                </details>
                <button
                  className="funds-quick-reason"
                  onClick={() => {
                    selectDate(firstShortage.date)
                    if (evidenceRef.current) evidenceRef.current.open = true
                    evidenceRef.current?.focus({ preventScroll: true })
                    evidenceRef.current?.scrollIntoView({
                      behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches
                        ? 'instant'
                        : 'smooth',
                      block: 'start',
                    })
                  }}
                >
                  부족한 이유 확인 →
                </button>
              </>
            ) : (
              <>
                <b>등록한 지출을 감당할 수 있어요</b>
                <small>
                  남겨둘 잔액 기준 <FinanceMoney value={minimumOperatingFunds} compact={false} />
                </small>
              </>
            )}
            <div className="funds-quick-actions">
              {onManageExpenses && (
                <Button type={firstShortage ? 'primary' : 'default'} onClick={onManageExpenses}>
                  지출 계획 확인
                </Button>
              )}
              <Button
                className="funds-loan-action"
                type="default"
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
                          summary?.totalIncome ??
                          days.reduce((n, d) => n + d.completed + d.scheduled, 0),
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
            </div>
          </div>
        </div>
        <div className="funds-quick-cell">
          <span>
            <WalletOutlined aria-hidden />{' '}
            {currentBalance === undefined ? '기간 시작 잔액' : '오늘의 계산 잔액'}
          </span>
          <strong>
            <FinanceMoney value={currentBalance ?? days[0].opening} compact={false} />
          </strong>
          {balanceCalculation && (
            <details className="funds-quick-basis">
              <summary>계산 기준</summary>
              <p>
                시작 잔액 <FinanceMoney value={balanceCalculation.opening} compact={false} /> ＋
                실제 지급 <FinanceMoney value={balanceCalculation.income} compact={false} /> − 지출
                완료 <FinanceMoney value={balanceCalculation.expenses} compact={false} />
              </p>
              {summary && <small>계산 기준: {koreanTime(summary.asOf)}</small>}
            </details>
          )}
        </div>
        <div className="funds-quick-cell">
          <span>선택 기간에 등록된 지출 합계</span>
          <strong>
            <FinanceMoney
              value={summary?.totalExpenses ?? financeTotal(days.map((d) => d.expenses))}
              compact={false}
            />
          </strong>
          <small>
            {days[0].date} ~ {days.at(-1)!.date}
          </small>
        </div>
      </section>
      <details className="funds-period-summary">
        <summary>
          <span>선택 기간의 유입·지출 합계</span>
          <small>기간 전체 요약 펼치기</small>
        </summary>
        <div className="funds-period-metrics" aria-label="조회 기간 자금 요약">
          <section>
            <span>
              <ArrowDownOutlined aria-hidden /> 기간 내 정산 유입
            </span>
            <strong>
              <FinanceMoney
                value={
                  summary?.totalIncome ??
                  financeTotal(days.map((d) => financeTotal([d.completed, d.scheduled])))
                }
                compact={false}
                countUp
              />
            </strong>
            <small>지급 완료 + 지급 예상 · 정산 후 환불 차감 반영</small>
          </section>
          <section>
            <span>
              <ArrowUpOutlined aria-hidden /> 기간 내 지출
            </span>
            <strong>
              <FinanceMoney
                value={summary?.totalExpenses ?? financeTotal(days.map((d) => d.expenses))}
                compact={false}
                countUp
              />
            </strong>
            <small>완료·예정 지출 + 정기 지출</small>
          </section>
          <section>
            <span>기간 마지막 날의 예상 잔액</span>
            <strong>
              <FinanceMoney value={days.at(-1)!.closing} compact={false} countUp />
            </strong>
            <small>{days.at(-1)!.date} 마감 기준</small>
            <details className="funds-period-basis">
              <summary>최저·시작 잔액 보기</summary>
              <p>
                기간 중 최저{' '}
                <FinanceMoney
                  value={summary?.lowestBalance ?? Math.min(...days.map((d) => d.closing))}
                  compact={false}
                />
                <br />
                기간 시작 <FinanceMoney value={days[0].opening} compact={false} />
              </p>
            </details>
          </section>
        </div>
      </details>
      {(!incomeMatches || !costsMatch) && (
        <p className="funds-reconcile-note" role="status">
          선택한 날짜의 합계와 거래 내역이 달라졌어요. 새로고침 후 확인해 주세요.
        </p>
      )}
      <details
        ref={evidenceRef}
        className="cashflow-surface funds-day-details"
        id="funds-day-evidence"
        tabIndex={-1}
        aria-label="선택 날짜 잔액 계산 근거"
      >
        <summary className="funds-day-summary">
          <span>
            <b>{active.date}</b>의 돈 흐름이 궁금하다면
          </span>
          <span className="funds-disclosure-closed">계산 내역 펼치기</span>
          <span className="funds-disclosure-open">계산 내역 접기</span>
        </summary>
        <div className="cashflow-section-head">
          <div>
            <span className="cashflow-eyebrow">{active.date}</span>
            <h2>
              {active.closing < minimumOperatingFunds
                ? '왜 자금이 부족할까요?'
                : '이날 잔액은 이렇게 계산돼요'}
            </h2>
          </div>
          <strong className={active.closing < minimumOperatingFunds ? 'cashflow-negative' : ''}>
            <FinanceMoney value={active.closing} />
          </strong>
          <Button size="small" onClick={() => onDetails(active.date)}>
            이날 전체 거래 보기
          </Button>
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
            <small>하루 끝 예상 잔액</small>
            <b className={active.closing < minimumOperatingFunds ? 'cashflow-negative' : ''}>
              <FinanceMoney value={active.closing} />
            </b>
          </div>
        </div>
        {deduction > 0 && incomeMatches && (
          <p className="cashflow-deduction-equation">
            이날 차감 전 정산금{' '}
            <b>
              <FinanceMoney value={originalIncome} />
            </b>{' '}
            − 정산 후 환불 차감{' '}
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
          {active.closing < minimumOperatingFunds
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
              정산 유입{' '}
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
              지출 내역{' '}
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
      </details>
      {recoveryInfo && (recoveryInfo.outstanding > 0 || recoveryInfo.projected > 0) && (
        <details
          className="recovery-notice funds-refund-details"
          aria-label="환불 회수와 이월 안내"
        >
          <summary>
            <span>
              <InfoCircleFilled aria-hidden /> 정산된 주문의 환불 차감을 반영했어요
            </span>
            <span>
              앞으로 차감할 금액 <FinanceMoney value={recoveryInfo.outstanding} compact={false} /> ·
              설명 보기
            </span>
          </summary>
          <h3>
            <InfoCircleFilled aria-hidden />
            이미 정산된 주문의 환불은 다음 지급에서 차감돼요
          </h3>
          <p>
            이미 정산한 주문이 고객에게 환불되면, 그 주문의 정산금을 다음 지급에서 차감해요. 현재
            계산 잔액에서 다시 빼지 않고, 이 예측에는 차감 후 들어올 금액을 반영했어요.
          </p>
          <div className="recovery-values">
            <div>
              <span>앞으로 차감할 금액</span>
              <b>
                <FinanceMoney value={recoveryInfo.outstanding} />
              </b>
            </div>
            <div>
              <span>조회 기간 말까지 차감 예상</span>
              <b>
                <FinanceMoney value={recoveryInfo.projected} />
              </b>
            </div>
            <div>
              <span>기간 이후에 남을 차감액</span>
              <b>
                <FinanceMoney value={recoveryInfo.remaining} />
              </b>
            </div>
          </div>
          <p>
            차감 예상액에는 선택 기간이 시작되기 전에 차감될 금액도 포함돼요. 새로운 매출과 추가
            환불은 예측하지 않습니다.
          </p>
        </details>
      )}
      <p className="cashflow-footnote">
        등록된 정산·지출로 하루가 끝날 때의 잔액을 계산해요. 아직 발생하지 않은 매출, 등록하지 않은
        지출, 하루 중 입출금 순서는 반영하지 않아요. 실제 은행 잔액과 다를 수 있어요.
      </p>
    </div>
  )
}
