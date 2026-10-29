import { loanRequirement } from './loanForecast'
import { useRef, useState } from 'react'
import { Button, Empty } from 'antd'
import { useLocation, useNavigate } from 'react-router-dom'
import { SettingOutlined, RightOutlined } from '@ant-design/icons'
import { CashBalanceChart } from './FinanceCharts'
import './cashflow-overview.css'
export type ForecastDayView = {
  date: string
  opening: number
  completed: number
  scheduled: number
  expected: number
  expenses: number
  closing: number
}
export type ForecastSource = {
  id: string
  date: string
  title: string
  amount: number
  note: string
}
const money = (value: number) => `${value.toLocaleString('ko-KR')}원`
function Sources({ items, empty }: { items: ForecastSource[]; empty: string }) {
  const sorted = [...items].sort((a, b) => b.amount - a.amount)
  const row = (item: ForecastSource) => (
    <li key={item.id}>
      <div>
        <b>{item.title}</b>
        <small>{item.note}</small>
      </div>
      <strong>{money(item.amount)}</strong>
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
  onManage,
  onDetails,
  preview = false,
}: {
  days: ForecastDayView[]
  incomes: ForecastSource[]
  costs: ForecastSource[]
  preview?: boolean
  onManage: () => void
  onDetails: (date: string) => void
}) {
  const [selected, setSelected] = useState('')
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const evidenceRef = useRef<HTMLElement>(null)
  if (!days.length) return <Empty description="올바른 조회 기간을 선택해 주세요." />
  const firstShortage = days.find((d) => d.closing < 0)
  const requirement = loanRequirement(days)!
  const maximumShortage = requirement.required
  const active = days.find((d) => d.date === selected) ?? firstShortage ?? days[0]
  const dayIncome = incomes.filter((e) => e.date === active.date)
  const dayCosts = costs.filter((e) => e.date === active.date)
  const largest = [...dayCosts].sort((a, b) => b.amount - a.amount)[0]
  const incoming = active.completed + active.scheduled + active.expected
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
        <div className="cashflow-manage-action">
          <Button
            type="primary"
            size="large"
            icon={<SettingOutlined />}
            onClick={onManage}
            aria-describedby="cashflow-manage-hint"
          >
            잔액·지출 설정 <RightOutlined />
          </Button>
          <small id="cashflow-manage-hint">시작 잔액 · 일회성 지출 · 정기 지출 관리</small>
        </div>
      </div>
      <div className="cashflow-summary-grid">
        {[
          ['기간 시작 잔액', days[0].opening, '직접 설정한 잔액을 기준으로 계산'],
          [
            '기간 내 정산 유입',
            days.reduce((n, d) => n + d.completed + d.scheduled + d.expected, 0),
            '정산 완료 · 예정 · 픽업 전 예상 포함',
          ],
          [
            '기간 내 예정 지출',
            days.reduce((n, d) => n + d.expenses, 0),
            '일회성 지출 · 정기 지출 포함',
          ],
          [
            '최저 예상 잔액',
            Math.min(...days.map((d) => d.closing)),
            '일 마감 기준 · 실제 은행 잔액 아님',
          ],
        ].map(([label, amount, note], index) => (
          <section className={`cashflow-surface cashflow-stat-${index}`} key={label}>
            <span>{label}</span>
            <strong>{money(Number(amount))}</strong>
            <small>{note}</small>
          </section>
        ))}
      </div>
      <section
        className={`cashflow-verdict ${firstShortage ? 'cashflow-verdict-warning' : 'cashflow-verdict-safe'}`}
      >
        <span>
          {firstShortage ? '자금 준비가 필요해요' : '선택 기간에는 자금 부족이 예상되지 않아요'}
        </span>
        <h2>
          {firstShortage ? (
            <>
              {firstShortage.date}에<br />
              <em>{money(-firstShortage.closing)}</em>이 부족할 것으로 예상돼요.
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
            <b>{money(maximumShortage)}</b>
          </div>
          <div>
            <small>기간 말 예상 잔액</small>
            <b>{money(days.at(-1)!.closing)}</b>
          </div>
          <div>
            <small>기간 시작 잔액</small>
            <b>{money(days[0].opening)}</b>
          </div>
        </div>
        {firstShortage && (
          <button
            className="cashflow-reason-link"
            onClick={() => {
              setSelected(firstShortage.date)
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
              초록 막대는 정산 유입, 빨간 막대는 지출, 남색 선은 예상 잔액입니다. 날짜를 선택하면
              계산 근거를 확인할 수 있습니다.
            </p>
          </div>
          <Button size="small" onClick={() => onDetails(active.date)}>
            전체 계산 내역
          </Button>
        </div>
        <div className="cashflow-chart-legend">
          <span style={{ color: '#15803d' }}>■ 정산 유입</span>
          <span style={{ color: '#dc2626' }}>■ 예정 지출</span>
          <span style={{ color: '#172554' }}>━ 예상 잔액</span>
          <span>주황색 점 · 부족 예상일</span>
        </div>
        <CashBalanceChart
          points={days.map((d) => ({
            date: d.date,
            balance: d.closing,
            income: d.completed + d.scheduled + d.expected,
            expense: d.expenses,
          }))}
          selectedDate={active.date}
          onSelect={setSelected}
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
            {money(active.closing)}
          </strong>
        </div>
        <div className="cashflow-equation">
          <div>
            <small>전날에서 이어진 잔액</small>
            <b>{money(active.opening)}</b>
          </div>
          <span>＋</span>
          <div>
            <small>이날 정산 유입</small>
            <b>{money(incoming)}</b>
          </div>
          <span>−</span>
          <div>
            <small>이날 예정 지출</small>
            <b>{money(active.expenses)}</b>
          </div>
          <span>＝</span>
          <div>
            <small>예상 마감 잔액</small>
            <b className={active.closing < 0 ? 'cashflow-negative' : ''}>{money(active.closing)}</b>
          </div>
        </div>
        <p className="cashflow-explanation">
          {active.closing < 0
            ? active.opening < 0
              ? '이전 날짜에서 이어진 부족액에 이날의 유입과 지출을 반영한 결과입니다.'
              : `전날 잔액과 이날 유입을 합친 ${money(active.opening + incoming)}보다 예정 지출 ${money(active.expenses)}이 많습니다.`
            : '전날 잔액에 이날 정산 유입을 더하고 예정 지출을 차감했습니다.'}{' '}
          {largest &&
            costsMatch &&
            `이날 가장 큰 지출은 '${largest.title}' ${money(largest.amount)}입니다.`}
        </p>
        <div className="cashflow-evidence">
          <div>
            <h3>
              들어올 돈 <span>{money(incoming)}</span>
            </h3>
            {incomeMatches ? (
              <Sources items={dayIncome} empty="이날 반영할 정산 유입이 없습니다." />
            ) : (
              <p className="cashflow-negative">
                계산 결과와 상세 내역이 달라졌습니다. 새로 조회해 주세요.
              </p>
            )}
            <p className="cashflow-muted">
              완료 {money(active.completed)} · 픽업 완료 기준 예정 {money(active.scheduled)} · 픽업
              전 예상 {money(active.expected)}
            </p>
          </div>
          <div>
            <h3>
              나갈 돈 <span>{money(active.expenses)}</span>
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
          <h2>{maximumShortage ? money(maximumShortage) : '추가 필요 자금 없음'}</h2>
          <p>선택 기간의 최저 예상 잔액을 0원 이상으로 유지하는 데 필요한 금액입니다.</p>
          <small>
            등록된 정산·지출 기준의 단순 계산이며 실제 대출 필요액이나 승인 금액이 아닙니다.
          </small>
        </div>
        <Button
          size="large"
          onClick={() =>
            navigate(pathname.startsWith('/dev/') ? '/dev/funds/loans' : '/owner/loans', {
              state: {
                required: maximumShortage,
                minimum: Math.min(...days.map((d) => d.closing)),
                totalIn: days.reduce((n, d) => n + d.completed + d.scheduled + d.expected, 0),
                firstShortage: firstShortage?.date ?? null,
                minimumDate: requirement.minimumDate,
                from: days[0].date,
                to: days.at(-1)!.date,
                capturedAt: new Date().toISOString(),
                preview,
              },
            })
          }
        >
          연계 대출 상품 보기 →
        </Button>
      </section>

      <p className="cashflow-footnote">
        현재 확보된 주문·정산과 등록한 지출 기준의 예측입니다. 새로운 주문 매출, 실제 은행 잔액과
        하루 중 입출금 순서는 추정하지 않습니다.
      </p>
    </div>
  )
}
