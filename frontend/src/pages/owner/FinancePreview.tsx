import { previewExpenses, previewInflows } from './loanForecast'
import { useLocation, Link } from 'react-router-dom'
import { CashFlowOverview } from './CashFlowOverview'
import dayjs from 'dayjs'
import { RecurringExpensePanel } from './RecurringExpensePanel'
import { expandRecurring } from './recurringExpenses'
import type { RecurringExpense } from './financePreviewTypes'
import { useState } from 'react'
import { ReloadOutlined } from '@ant-design/icons'
import type { Dispatch, SetStateAction, ReactNode } from 'react'
import { Alert, Button, Drawer, Input, InputNumber, Modal, Select, Tabs } from 'antd'
import { SettlementPrototype } from './SettlementPrototype'
import './finance-preview.css'
import './finance-page-header.css'
import { PageHeader } from '../../components/common/PageHeader'

type Expense = {
  id: number
  date: string
  title: string
  amount: number
  category: string
  memo: string
}
type Point = { date: string; scheduled: number; expected: number; expense: number; balance: number }
const money = (value: number) => `${value.toLocaleString('ko-KR')}원`
const iso = (date: Date) =>
  `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
const shift = (date: string, n: number) => {
  const d = new Date(`${date}T00:00:00`)
  d.setDate(d.getDate() + n)
  return iso(d)
}
const short = (date: string) => `${Number(date.slice(5, 7))}/${Number(date.slice(8, 10))}`

export function FinancePreview() {
  const { pathname } = useLocation()
  const tab = pathname.endsWith('/funds') ? 'forecast' : 'settlement'
  const [refreshedAt, setRefreshedAt] = useState(() => new Date())
  const [demoBase] = useState(() => iso(new Date()))
  const [expenses, setExpenses] = useState<Expense[]>(() => previewExpenses(demoBase))
  const [recurring, setRecurring] = useState<RecurringExpense[]>([])
  const calendarExpenses = [
    ...expenses.map((e) => ({ ...e, id: `oneoff-${e.id}`, note: '일회성 지출' })),
    ...expandRecurring(recurring, shift(demoBase, -31), shift(demoBase, 366)).map((e) => ({
      id: `recurring-${e.recurringExpenseId}-${e.expenseDate}`,
      date: e.expenseDate,
      title: e.title,
      amount: e.amount,
      note: '정기 지출',
    })),
  ]
  const headerActions = (
    <div className="finance-refresh-bar">
      <span>
        마지막 조회 (목 데이터):{' '}
        {refreshedAt.toLocaleTimeString('ko-KR', {
          hour: '2-digit',
          minute: '2-digit',
          second: '2-digit',
        })}
      </span>
      <Button
        size="small"
        icon={<ReloadOutlined />}
        onClick={() => setRefreshedAt(new Date())}
        title="입력한 설정을 유지하고 목 데이터 화면을 다시 표시합니다. API는 호출하지 않습니다."
      >
        새로고침
      </Button>
    </div>
  )
  return (
    <div className="finance-preview">
      {pathname.startsWith('/dev/') && (
        <nav className="finance-nav" aria-label="자금 화면 이동">
          <Link to="/dev/settlements" aria-current={tab === 'settlement' ? 'page' : undefined}>
            정산 내역
          </Link>
          <Link to="/dev/funds" aria-current={tab === 'forecast' ? 'page' : undefined}>
            자금 현황
          </Link>
        </nav>
      )}
      <div hidden={tab !== 'settlement'}>
        <SettlementPrototype expenses={calendarExpenses} headerActions={headerActions} />
      </div>
      <div hidden={tab !== 'forecast'}>
        <CashFlow
          expenses={expenses}
          setExpenses={setExpenses}
          recurring={recurring}
          setRecurring={setRecurring}
          headerActions={headerActions}
        />
      </div>
      <p className="finance-preview-status">
        목 데이터 시연 · 입력값은 현재 화면에서만 유지됩니다.
      </p>
    </div>
  )
}

function CashFlow({
  expenses,
  setExpenses,
  recurring,
  setRecurring,
  headerActions,
}: {
  expenses: Expense[]
  setExpenses: Dispatch<SetStateAction<Expense[]>>
  recurring: RecurringExpense[]
  setRecurring: Dispatch<SetStateAction<RecurringExpense[]>>
  headerActions?: ReactNode
}) {
  const [base, setBase] = useState(() => iso(new Date()))
  const [opening, setOpening] = useState(500000)
  const [start, setStart] = useState(base)
  const [end, setEnd] = useState(shift(base, 6))
  const [selected, setSelected] = useState(shift(base, 2))
  const recurringOccurrences = expandRecurring(recurring, base, shift(base, 89))
  // 은행 거래가 아닌 시연용 유입 가정이다. 실제 API 연결 전까지 메모리에서만 계산한다.
  const inflows = previewInflows(base)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [draftBase, setDraftBase] = useState(base)
  const [draftBalance, setDraftBalance] = useState<number | null>(opening)
  const [draft, setDraft] = useState<Expense | null>(null)
  const [error, setError] = useState('')
  const [subtab, setSubtab] = useState('daily')
  const [panel, setPanel] = useState<'manage' | 'daily' | null>(null)
  const [manageTab, setManageTab] = useState('oneoff')
  const valid = start >= base && end >= start && end <= shift(base, 89)
  let balance = opening
  const all: Point[] = Array.from({ length: 90 }, (_, i) => {
    const date = shift(base, i),
      income = inflows.find((x) => x.date === date)
    const expense =
      expenses.filter((x) => x.date === date).reduce((n, x) => n + x.amount, 0) +
      recurringOccurrences.filter((x) => x.expenseDate === date).reduce((n, x) => n + x.amount, 0)
    balance += (income?.scheduled ?? 0) + (income?.expected ?? 0) - expense
    return {
      date,
      scheduled: income?.scheduled ?? 0,
      expected: income?.expected ?? 0,
      expense,
      balance,
    }
  })
  const points = valid ? all.filter((x) => x.date >= start && x.date <= end) : []
  const day = points.find((x) => x.date === selected) ?? points[0]
  const periodExpenses = expenses
    .filter((x) => x.date >= start && x.date <= end)
    .toSorted((a, b) => a.date.localeCompare(b.date))
  const preset = (n: number) => {
    setStart(base)
    setEnd(shift(base, n - 1))
    setSelected(base)
  }
  const saveExpense = () => {
    if (
      !draft ||
      !draft.title.trim() ||
      !draft.date ||
      !Number.isSafeInteger(draft.amount) ||
      draft.amount <= 0
    ) {
      setError('항목명·지출일·양의 정수 금액을 입력해 주세요.')
      return
    }
    setExpenses((old) =>
      old.some((x) => x.id === draft.id)
        ? old.map((x) => (x.id === draft.id ? { ...draft, title: draft.title.trim() } : x))
        : [...old, { ...draft, title: draft.title.trim() }],
    )
    setDraft(null)
    setError('')
  }
  return (
    <>
      <PageHeader
        actions={headerActions}
        title="자금 현황"
        description="정산 유입과 예정 지출을 바탕으로 앞으로의 잔액과 필요한 자금을 확인합니다."
      />
      <Alert
        type="info"
        showIcon
        message="예시 데이터입니다. 입력은 현재 화면에서만 유지되며, 브라우저 페이지를 다시 불러오면 초기화됩니다."
      />
      <div className="finance-period">
        <Button onClick={() => preset(7)}>앞으로 7일</Button>
        <Button onClick={() => preset(30)}>앞으로 30일</Button>
        <Button
          onClick={() => {
            const next = dayjs().add(1, 'month')
            setStart(next.startOf('month').format('YYYY-MM-DD'))
            setEnd(next.endOf('month').format('YYYY-MM-DD'))
            setSelected(next.startOf('month').format('YYYY-MM-DD'))
          }}
        >
          다음 달 예측
        </Button>
        <label>
          조회 시작일
          <input type="date" value={start} min={base} onChange={(e) => setStart(e.target.value)} />
        </label>
        <span>~</span>
        <label>
          조회 종료일
          <input
            type="date"
            value={end}
            min={start}
            max={shift(base, 89)}
            onChange={(e) => setEnd(e.target.value)}
          />
        </label>
      </div>
      {!valid && (
        <Alert type="warning" message="기준일부터 90일 이내에서 시작일과 종료일을 선택해 주세요." />
      )}
      <CashFlowOverview
        preview
        days={points.map((p) => ({
          date: p.date,
          opening: p.balance - p.scheduled - p.expected + p.expense,
          completed: 0,
          scheduled: p.scheduled,
          expected: p.expected,
          expenses: p.expense,
          closing: p.balance,
        }))}
        incomes={points.flatMap((p) =>
          [
            {
              id: p.date + '-scheduled',
              date: p.date,
              title: '픽업 완료 기준 정산',
              amount: p.scheduled,
              note: '예시 정산 유입 합계',
            },
            {
              id: p.date + '-expected',
              date: p.date,
              title: '픽업 전 예상 정산',
              amount: p.expected,
              note: '예시 금액 · 일정 변경 가능',
            },
          ].filter((e) => e.amount > 0),
        )}
        costs={[
          ...expenses.map((e) => ({
            id: 'oneoff-' + e.id,
            date: e.date,
            title: e.title,
            amount: e.amount,
            note: '일회성 지출 · ' + e.category,
          })),
          ...recurringOccurrences.map((e) => ({
            id: 'rule-' + e.recurringExpenseId + '-' + e.expenseDate,
            date: e.expenseDate,
            title: e.title,
            amount: e.amount,
            note: '정기 지출',
          })),
        ]}
        onManage={() => {
          setPanel('manage')
          setManageTab('balance')
          setSubtab('expenses')
        }}
        onDetails={(date) => {
          setSelected(date)
          setSubtab('daily')
          setPanel('daily')
        }}
      />
      <Drawer
        title={panel === 'manage' ? '잔액·지출 설정' : '날짜별 계산 내역'}
        open={panel !== null}
        onClose={() => setPanel(null)}
        width="min(1100px, 100vw)"
      >
        {panel === 'manage' && (
          <p>
            일회성 지출 조회 기간: {start} ~ {end}. 반복 규칙은 전체 목록입니다.
          </p>
        )}
        {panel === 'manage' && (
          <Tabs
            activeKey={manageTab}
            onChange={setManageTab}
            items={[
              { key: 'balance', label: '기준 잔액' },
              { key: 'oneoff', label: '일회성 지출' },
              { key: 'recurring', label: '정기 지출' },
            ]}
          />
        )}
        {panel === 'manage' && manageTab === 'balance' ? (
          <section className="finance-card">
            <h2>기준 잔액 {money(opening)}</h2>
            <p>{base} 하루 시작 시점의 사용자 입력 잔액입니다.</p>
            <Button
              onClick={() => {
                setDraftBase(base)
                setDraftBalance(opening)
                setSettingsOpen(true)
              }}
            >
              기준 잔액 변경
            </Button>
          </section>
        ) : panel === 'manage' && manageTab === 'recurring' ? (
          <>
            {' '}
            <RecurringExpensePanel
              rules={recurring}
              onCreate={async (input) => {
                setRecurring((old) => [
                  ...old,
                  { ...input, recurringExpenseId: Date.now(), stoppedFrom: null },
                ])
              }}
              onStop={async (id) => {
                setRecurring((old) =>
                  old.map((r) =>
                    r.recurringExpenseId === id && !r.stoppedFrom
                      ? { ...r, stoppedFrom: dayjs().format('YYYY-MM-DD') }
                      : r,
                  ),
                )
              }}
            />
            <p>
              조회 기간 정기 지출{' '}
              {
                recurringOccurrences.filter((e) => e.expenseDate >= start && e.expenseDate <= end)
                  .length
              }
              건이 예상 잔액에 포함됩니다. 예시 유입은 최초 30일만 제공하므로 이후 매출을 추정하지
              않습니다.
            </p>
          </>
        ) : (
          <>
            {' '}
            <div
              className="finance-bottom-grid"
              style={{ gridTemplateColumns: panel === 'manage' ? '1fr' : undefined }}
            >
              <section className="finance-card">
                <div className="finance-section-head">
                  <h2>{subtab === 'daily' ? '일별 현금흐름' : '예정 지출 관리'}</h2>
                  <Button
                    style={{ display: panel === 'daily' ? 'none' : undefined }}
                    type="primary"
                    onClick={() => {
                      setError('')
                      setDraft({
                        id: Date.now(),
                        date: day?.date ?? base,
                        title: '',
                        amount: 0,
                        category: '재료비',
                        memo: '',
                      })
                    }}
                  >
                    + 지출 등록
                  </Button>
                </div>
                <div className="finance-table">
                  <table>
                    <thead>
                      <tr>
                        {(subtab === 'daily'
                          ? ['날짜', '예상 유입', '예정 지출', '예상 잔액', '상세']
                          : ['지출일', '항목', '유형', '금액', '관리']
                        ).map((x) => (
                          <th key={x}>{x}</th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {subtab === 'daily'
                        ? points.map((p) => (
                            <tr
                              key={p.date}
                              className={p.date === day?.date ? 'finance-selected' : ''}
                            >
                              <td>{short(p.date)}</td>
                              <td>{money(p.scheduled + p.expected)}</td>
                              <td>{money(p.expense)}</td>
                              <td className={p.balance < 0 ? 'finance-negative' : ''}>
                                <b>{money(p.balance)}</b>
                              </td>
                              <td>
                                <Button size="small" onClick={() => setSelected(p.date)}>
                                  보기
                                </Button>
                              </td>
                            </tr>
                          ))
                        : periodExpenses.map((e) => (
                            <tr key={e.id}>
                              <td>{short(e.date)}</td>
                              <td>{e.title}</td>
                              <td>{e.category}</td>
                              <td>{money(e.amount)}</td>
                              <td>
                                <Button
                                  size="small"
                                  onClick={() => {
                                    setError('')
                                    setDraft({ ...e })
                                  }}
                                >
                                  수정
                                </Button>{' '}
                                <Button
                                  size="small"
                                  danger
                                  onClick={() =>
                                    Modal.confirm({
                                      title: '예정 지출을 삭제할까요?',
                                      content: e.title,
                                      okText: '삭제',
                                      cancelText: '취소',
                                      onOk: () =>
                                        setExpenses((old) => old.filter((x) => x.id !== e.id)),
                                    })
                                  }
                                >
                                  삭제
                                </Button>
                              </td>
                            </tr>
                          ))}
                      {((subtab === 'daily' && !points.length) ||
                        (subtab === 'expenses' && !periodExpenses.length)) && (
                        <tr>
                          <td colSpan={5}>선택 기간에 표시할 내역이 없습니다.</td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                </div>
              </section>
              <aside className="finance-card" hidden={panel === 'manage'}>
                <h2>{day ? `${short(day.date)} 자금 상세` : '날짜를 선택하세요'}</h2>
                {day && (
                  <>
                    <dl className="finance-breakdown">
                      <dt>전일 예상 잔액</dt>
                      <dd>{money(day.balance - day.scheduled - day.expected + day.expense)}</dd>
                      <dt>픽업 완료 기준 유입</dt>
                      <dd>+{money(day.scheduled)}</dd>
                      <dt>픽업 전 예상 유입</dt>
                      <dd>+{money(day.expected)}</dd>
                      <dt>예정 지출</dt>
                      <dd>−{money(day.expense)}</dd>
                      <dt>
                        <b>당일 예상 잔액</b>
                      </dt>
                      <dd className={day.balance < 0 ? 'finance-negative' : ''}>
                        <b>{money(day.balance)}</b>
                      </dd>
                    </dl>
                    <h3>이날의 지출</h3>
                    {expenses
                      .filter((e) => e.date === day.date)
                      .map((e) => (
                        <p key={e.id}>
                          {e.title}
                          <b className="finance-right">{money(e.amount)}</b>
                        </p>
                      ))}
                    {recurringOccurrences
                      .filter((e) => e.expenseDate === day.date)
                      .map((e) => (
                        <p key={`recurring-${e.recurringExpenseId}-${e.expenseDate}`}>
                          {e.title} <small>반복</small>
                          <b className="finance-right">{money(e.amount)}</b>
                        </p>
                      ))}
                    {!expenses.some((e) => e.date === day.date) &&
                      !recurringOccurrences.some((e) => e.expenseDate === day.date) && (
                        <p>등록한 지출이 없습니다.</p>
                      )}
                  </>
                )}
                <div className="finance-footnote">
                  일정 미정 정산 예시 97,000원은 날짜별 예측에서 제외했습니다. 일별 잔액은 실제
                  입금과 당일 거래 순서를 보장하지 않습니다.
                </div>
              </aside>
            </div>
          </>
        )}
      </Drawer>
      <Modal
        title="시작 잔액 설정"
        open={settingsOpen}
        onCancel={() => setSettingsOpen(false)}
        onOk={() => {
          if (!draftBase || draftBalance === null || !Number.isSafeInteger(draftBalance)) return
          setBase(draftBase)
          setOpening(draftBalance)
          setStart(draftBase)
          setEnd(shift(draftBase, 6))
          setSelected(draftBase)
          setSettingsOpen(false)
        }}
        okText="적용"
        cancelText="취소"
      >
        <p>
          기준일 하루 시작 시점의 사용 가능한 금액을 입력하세요. 은행 잔액을 자동 조회하지 않습니다.
        </p>
        <label className="finance-field">
          기준일
          <input
            type="date"
            value={draftBase}
            onChange={(e) => setDraftBase(e.target.value)}
            required
          />
        </label>
        <label className="finance-field">
          시작 잔액 (원)
          <InputNumber
            value={draftBalance}
            precision={0}
            onChange={setDraftBalance}
            style={{ width: '100%' }}
          />
        </label>
        <p>
          시연에서는 기준일을 바꾸면 예시 유입 일정도 함께 이동합니다. 기존 지출 날짜는 유지됩니다.
        </p>
      </Modal>
      <Modal
        title={
          draft && expenses.some((x) => x.id === draft.id) ? '예정 지출 수정' : '예정 지출 등록'
        }
        open={draft !== null}
        onCancel={() => setDraft(null)}
        onOk={saveExpense}
        okText="저장"
        cancelText="취소"
      >
        {draft && (
          <>
            {error && <Alert type="error" message={error} />}
            <label className="finance-field">
              항목명
              <Input
                value={draft.title}
                maxLength={100}
                onChange={(e) => setDraft({ ...draft, title: e.target.value })}
              />
            </label>
            <label className="finance-field">
              지출 예정일
              <input
                type="date"
                value={draft.date}
                onChange={(e) => setDraft({ ...draft, date: e.target.value })}
              />
            </label>
            <label className="finance-field">
              금액 (원)
              <InputNumber
                min={1}
                precision={0}
                value={draft.amount}
                onChange={(v) => setDraft({ ...draft, amount: v ?? 0 })}
                style={{ width: '100%' }}
              />
            </label>
            <label className="finance-field">
              유형
              <Select
                value={draft.category}
                options={['재료비', '임대료', '인건비', '공과금', '기타'].map((value) => ({
                  value,
                  label: value,
                }))}
                onChange={(v) => setDraft({ ...draft, category: v })}
              />
            </label>
            <label className="finance-field">
              메모
              <Input.TextArea
                value={draft.memo}
                maxLength={500}
                onChange={(e) => setDraft({ ...draft, memo: e.target.value })}
              />
            </label>
          </>
        )}
      </Modal>
    </>
  )
}
