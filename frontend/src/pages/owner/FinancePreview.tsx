import { useMemo, useState, useSyncExternalStore } from 'react'
import { Link, useLocation } from 'react-router-dom'
import dayjs from 'dayjs'
import { App, Alert, Button, Drawer, Input, InputNumber, Modal, Select, Tabs, Tag } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import { useAuth } from '../../auth/useAuth'
import { PageHeader } from '../../components/common/PageHeader'
import { SettlementPrototype } from './SettlementPrototype'
import { CashFlowOverview } from './CashFlowOverview'
import { RecurringExpensePanel } from './RecurringExpensePanel'
import {
  calculatePreviewCashFlow,
  completePreviewExpense,
  expenseRows,
  getPreviewStore,
  normalizeFinancePath,
  setInitialBalance,
  previewExpenseHorizon,
} from './financePreviewState'
import type { ExpenseRow, PreviewExpense } from './financePreviewState'
import './finance-preview.css'
import './finance-page-header.css'

const money = (value: number) => `${value.toLocaleString('ko-KR')}원`
export function FinancePreview() {
  const { modal } = App.useApp()
  const { pathname } = useLocation()
  const { user } = useAuth()
  const today = dayjs().format('YYYY-MM-DD')
  const normalized = normalizeFinancePath(pathname)
  const isFunds = normalized.endsWith('/funds')
  const scope = normalized.startsWith('/dev/') ? 'dev' : `owner:${user?.userId}:${user?.merchantId}`
  const store = useMemo(() => getPreviewStore(scope, today), [scope, today])
  const state = useSyncExternalStore(store.subscribe, store.getSnapshot)
  const [refreshedAt, setRefreshedAt] = useState(() => new Date())
  const [start, setStart] = useState(today)
  const [end, setEnd] = useState(dayjs(today).add(6, 'day').format('YYYY-MM-DD'))
  const [openingInput, setOpeningInput] = useState<number | null>(500000)
  const [panel, setPanel] = useState<'manage' | 'daily' | null>(null)
  const [manageTab, setManageTab] = useState('balance')
  const [draft, setDraft] = useState<PreviewExpense | null>(null)
  const [error, setError] = useState('')
  const [selected, setSelected] = useState(today)
  const [expenseFilter, setExpenseFilter] = useState('all')
  const [calendarPeriod, setCalendarPeriod] = useState(() => ({
    from: dayjs(today).startOf('month').format('YYYY-MM-DD'),
    to: dayjs(today).endOf('month').format('YYYY-MM-DD'),
  }))
  const result = calculatePreviewCashFlow(state, today, start, end)
  const managementRows = expenseRows(
    state,
    previewExpenseHorizon(state, today, end, calendarPeriod.to),
  )
  const calendarExpenses = expenseRows(
    state,
    previewExpenseHorizon(state, today, calendarPeriod.to),
  ).map((e) => ({
    id: e.key,
    date: e.date,
    title: e.title,
    amount: e.amount,
    note: `${e.kind === 'RECURRING' ? '정기' : '일회성'} 지출 · ${e.paidAt ? '지출 완료' : '지출 예정'}`,
  }))
  const headerActions = (
    <div className="finance-refresh-bar">
      <span>마지막 조회 (목 데이터): {refreshedAt.toLocaleTimeString('ko-KR')}</span>
      <Button
        size="small"
        icon={<ReloadOutlined />}
        onClick={() => setRefreshedAt(new Date())}
        title="입력값을 유지하고 목 화면을 갱신합니다. API는 호출하지 않습니다."
      >
        새로고침
      </Button>
    </div>
  )
  const preset = (days: number) => {
    setStart(today)
    setEnd(
      dayjs(today)
        .add(days - 1, 'day')
        .format('YYYY-MM-DD'),
    )
  }
  const manage = (tab = 'balance') => {
    setManageTab(tab)
    setPanel('manage')
  }
  const rows = managementRows
    .filter((e) => expenseFilter === 'all' || (expenseFilter === 'paid' ? !!e.paidAt : !e.paidAt))
    .toSorted((a, b) => b.date.localeCompare(a.date))
  const active = result.days.find((d) => d.date === selected) ?? result.days[0]
  const pay = (row: ExpenseRow) =>
    modal.confirm({
      title: '오늘 지출 완료로 기록할까요?',
      content: `${row.title} ${money(row.amount)}을 ${today} 실제 지출로 반영합니다. 같은 예정 지출은 예측에서 제외합니다.`,
      okText: '지출 완료',
      cancelText: '취소',
      onOk: () => store.update((previous) => completePreviewExpense(previous, row, today)),
    })
  const save = () => {
    if (
      !draft ||
      !draft.title.trim() ||
      !draft.date ||
      draft.date < state.base ||
      !Number.isSafeInteger(draft.amount) ||
      draft.amount <= 0
    ) {
      setError('항목명·기준일 이후 날짜·양의 정수 금액을 확인해 주세요.')
      return
    }
    store.update((previous) => ({
      ...previous,
      expenses: previous.expenses.some((e) => e.id === draft.id)
        ? previous.expenses.map((e) =>
            e.id === draft.id && !e.paidAt ? { ...draft, title: draft.title.trim() } : e,
          )
        : [...previous.expenses, { ...draft, title: draft.title.trim() }],
    }))
    setDraft(null)
    setError('')
  }
  return (
    <div className="finance-preview">
      {normalized.startsWith('/dev/') && (
        <nav className="finance-nav" aria-label="자금 화면 이동">
          <Link to="/dev/settlements" aria-current={!isFunds ? 'page' : undefined}>
            정산 내역
          </Link>
          <Link to="/dev/funds" aria-current={isFunds ? 'page' : undefined}>
            자금 현황
          </Link>
        </nav>
      )}
      {!isFunds ? (
        <SettlementPrototype
          expenses={calendarExpenses}
          headerActions={headerActions}
          period={calendarPeriod}
          onPeriodChange={setCalendarPeriod}
        />
      ) : (
        <>
          <PageHeader
            title="자금 현황"
            description="오늘 계산 잔액을 확인하고, 예정 정산과 미래 지출로 앞으로의 잔액을 예측합니다."
            actions={headerActions}
          />
          <Alert
            type="info"
            showIcon
            message="목 데이터 시연 · 정산 완료는 입금된 것으로 간주합니다."
            description="최초 잔액과 입력은 메뉴 이동 시 유지되며 브라우저 재로드 시 초기화됩니다. 실제 은행 잔액을 조회하지 않습니다."
          />
          {state.opening === null ? (
            <section className="finance-card finance-opening-setup">
              <h2>최초 시작 잔액 설정</h2>
              <p>
                오늘 하루 시작 시점의 금액을 한 번 입력하세요. 저장 후 기준일과 시작 잔액은 변경할
                수 없습니다.
              </p>
              <p>
                시연 데이터에는 오늘 완료된 정산과 재료비 지출 1건이 있습니다. 이 금액들은 시작
                잔액에 중복 포함하지 마세요.
              </p>
              <label className="finance-field">
                최초 기준일
                <input type="date" value={state.base} disabled />
              </label>
              <label className="finance-field">
                시작 잔액 (원)
                <InputNumber
                  aria-label="최초 시작 잔액"
                  min={0}
                  max={1000000000000}
                  precision={0}
                  value={openingInput}
                  onChange={setOpeningInput}
                />
              </label>
              <Button
                type="primary"
                disabled={openingInput === null || openingInput < 0}
                onClick={() => {
                  if (openingInput !== null)
                    store.update((previous) => setInitialBalance(previous, openingInput))
                }}
              >
                최초 잔액 저장
              </Button>
            </section>
          ) : (
            <>
              <div className="finance-current-balance">
                <div>
                  <small>{today} 오늘 계산 잔액</small>
                  <strong>{money(result.currentBalance)}</strong>
                  <p>
                    시작 잔액 {money(state.opening)} ＋ 정산 완료 {money(result.completedIncome)} −
                    지출 완료 {money(result.paidExpenses)}
                  </p>
                </div>
                <Button onClick={() => manage('balance')}>최초 설정 확인</Button>
              </div>
              {result.overdue.length > 0 && (
                <Alert
                  type="warning"
                  showIcon
                  message={`지출 확인이 필요한 지난 예정 내역 ${result.overdue.length}건`}
                  description="예정일이 지났다고 실제 지출로 처리하지 않습니다. 오늘 계산 잔액과 미래 예측에서 제외했으니 완료 여부나 예정일을 확인해 주세요."
                  action={<Button onClick={() => manage('expenses')}>확인하기</Button>}
                />
              )}
              <div className="finance-period">
                <Button onClick={() => preset(7)}>앞으로 7일</Button>
                <Button onClick={() => preset(30)}>앞으로 30일</Button>
                <Button
                  onClick={() => {
                    const next = dayjs(today).add(1, 'month')
                    setStart(next.startOf('month').format('YYYY-MM-DD'))
                    setEnd(next.endOf('month').format('YYYY-MM-DD'))
                  }}
                >
                  다음 달 예측
                </Button>
                <label>
                  조회 시작일
                  <input
                    type="date"
                    value={start}
                    min={today}
                    max={end}
                    onChange={(e) => setStart(e.target.value)}
                  />
                </label>
                <span>~</span>
                <label>
                  조회 종료일
                  <input
                    type="date"
                    value={end}
                    min={start}
                    max={dayjs(start).add(365, 'day').format('YYYY-MM-DD')}
                    onChange={(e) => setEnd(e.target.value)}
                  />
                </label>
              </div>
              {!result.valid && (
                <Alert
                  type="warning"
                  message="오늘 이후 날짜로 최대 366일의 조회 기간을 선택해 주세요."
                />
              )}
              <CashFlowOverview
                preview
                currentBalance={result.currentBalance}
                days={result.days}
                incomes={result.projected.map((e) => ({
                  id: e.id,
                  date: e.date,
                  title: e.status === 'sch' ? '픽업 완료 기준 정산 예정' : '픽업 전 예상 정산',
                  amount: e.amount,
                  note: '정산 완료 시 입금으로 반영 · 예시 데이터',
                }))}
                costs={result.pending.map((e) => ({
                  id: e.key,
                  date: e.plannedDate,
                  title: e.title,
                  amount: e.amount,
                  note: e.kind === 'RECURRING' ? '정기 지출 예정' : '일회성 지출 예정',
                }))}
                onManage={() => manage()}
                onDetails={(date) => {
                  setSelected(date)
                  setPanel('daily')
                }}
              />
            </>
          )}
        </>
      )}
      <p className="finance-preview-status">
        목 데이터 시연 · 실제 저장이나 은행 조회 없이 같은 사용자·가맹점의 메뉴 이동 동안 입력을
        유지합니다.
      </p>
      <Drawer
        title={panel === 'manage' ? '잔액·지출 설정' : '날짜별 예상 잔액 계산'}
        open={panel !== null}
        onClose={() => setPanel(null)}
        width="min(1100px, 100vw)"
      >
        {panel === 'manage' ? (
          <>
            <Tabs
              activeKey={manageTab}
              onChange={setManageTab}
              items={[
                { key: 'balance', label: '최초 잔액' },
                { key: 'expenses', label: '지출 내역·완료 처리' },
                { key: 'recurring', label: '정기 지출' },
              ]}
            />
            {manageTab === 'balance' ? (
              <section className="finance-card">
                <h2>최초 시작 잔액 {money(state.opening ?? 0)}</h2>
                <p>기준일: {state.base} 하루 시작 시점</p>
                <Tag>최초 설정 완료 · 수정 불가</Tag>
                <p>
                  오늘 계산 잔액은 정산 완료와 실제 지출 완료만 반영합니다. 예정 금액은 미래
                  예측에만 반영합니다.
                </p>
                <p>시연에서 다시 설정하려면 브라우저 페이지를 다시 불러오세요.</p>
              </section>
            ) : manageTab === 'recurring' ? (
              <RecurringExpensePanel
                rules={state.recurring}
                onCreate={async (input) =>
                  store.update((previous) => ({
                    ...previous,
                    recurring: [
                      ...previous.recurring,
                      { ...input, recurringExpenseId: Date.now(), stoppedFrom: null },
                    ],
                  }))
                }
                onStop={async (id) =>
                  store.update((previous) => ({
                    ...previous,
                    recurring: previous.recurring.map((rule) =>
                      rule.recurringExpenseId === id && !rule.stoppedFrom
                        ? { ...rule, stoppedFrom: today }
                        : rule,
                    ),
                  }))
                }
              />
            ) : (
              <section className="finance-card">
                <div className="finance-section-head">
                  <h2>지출 내역</h2>
                  <Button
                    type="primary"
                    onClick={() => {
                      setError('')
                      setDraft({
                        id: Date.now(),
                        date: today,
                        title: '',
                        amount: 0,
                        category: '재료비',
                        memo: '',
                        paidAt: null,
                      })
                    }}
                  >
                    + 지출 등록
                  </Button>
                </div>
                <p>
                  예정 지출은 예측에만 반영합니다. 실제 지급했다면 ‘지출 완료’를 눌러 오늘의 실제
                  차감을 기록하세요. 완료 기록은 수정·삭제하지 않습니다. 일회성 지출은 예측 조회
                  기간과 무관하게 모두 표시합니다. 정기 지출 회차는
                  {previewExpenseHorizon(state, today, end, calendarPeriod.to)}까지 표시합니다.
                </p>
                <Select
                  aria-label="지출 상태 필터"
                  value={expenseFilter}
                  onChange={setExpenseFilter}
                  options={[
                    { value: 'all', label: '전체' },
                    { value: 'planned', label: '지출 예정' },
                    { value: 'paid', label: '지출 완료' },
                  ]}
                />
                <div className="finance-table">
                  <table>
                    <thead>
                      <tr>
                        <th>반영일 / 예정일</th>
                        <th>항목</th>
                        <th>금액</th>
                        <th>상태</th>
                        <th>관리</th>
                      </tr>
                    </thead>
                    <tbody>
                      {rows.map((row) => (
                        <tr key={row.key}>
                          <td>
                            {row.date}
                            <small>
                              {row.paidAt ? '실제 지출일' : '예정일'}
                              {row.paidAt && row.plannedDate !== row.paidAt
                                ? ` · 원 예정 ${row.plannedDate}`
                                : ''}
                            </small>
                          </td>
                          <td>
                            {row.title}
                            <small>{row.kind === 'RECURRING' ? '정기 지출' : '일회성 지출'}</small>
                          </td>
                          <td>{money(row.amount)}</td>
                          <td>
                            <Tag color={row.paidAt ? 'green' : 'orange'}>
                              {row.paidAt
                                ? '지출 완료'
                                : row.date < today
                                  ? '확인 필요'
                                  : '지출 예정'}
                            </Tag>
                          </td>
                          <td>
                            {!row.paidAt ? (
                              <>
                                <Button size="small" onClick={() => pay(row)}>
                                  지출 완료
                                </Button>
                                {row.kind === 'ONEOFF' && (
                                  <>
                                    <Button
                                      size="small"
                                      onClick={() => {
                                        const e = state.expenses.find((x) => x.id === row.expenseId)
                                        if (e) {
                                          setError('')
                                          setDraft({ ...e })
                                        }
                                      }}
                                    >
                                      수정
                                    </Button>
                                    <Button
                                      size="small"
                                      danger
                                      onClick={() =>
                                        modal.confirm({
                                          title: '예정 지출을 삭제할까요?',
                                          content: row.title,
                                          okText: '삭제',
                                          cancelText: '취소',
                                          onOk: () =>
                                            store.update((previous) => ({
                                              ...previous,
                                              expenses: previous.expenses.filter(
                                                (e) => e.id !== row.expenseId,
                                              ),
                                            })),
                                        })
                                      }
                                    >
                                      삭제
                                    </Button>
                                  </>
                                )}
                              </>
                            ) : (
                              <span>실제 잔액 반영됨</span>
                            )}
                          </td>
                        </tr>
                      ))}
                      {!rows.length && (
                        <tr>
                          <td colSpan={5}>표시할 지출 내역이 없습니다.</td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                </div>
              </section>
            )}
          </>
        ) : (
          <section className="finance-card">
            <h2>날짜별 미래 예상 잔액</h2>
            <p>
              오늘 계산 잔액에서 시작해, 날짜별 미완료 정산 예정과 미완료 지출 예정을 이어
              계산합니다.
            </p>
            <div className="finance-table">
              <table>
                <thead>
                  <tr>
                    <th>날짜</th>
                    <th>시작 예상 잔액</th>
                    <th>예정 유입</th>
                    <th>예정 지출</th>
                    <th>예상 마감 잔액</th>
                  </tr>
                </thead>
                <tbody>
                  {result.days.map((d) => (
                    <tr
                      key={d.date}
                      className={active?.date === d.date ? 'finance-selected' : ''}
                      onClick={() => setSelected(d.date)}
                    >
                      <td>{d.date}</td>
                      <td>{money(d.opening)}</td>
                      <td>{money(d.scheduled + d.expected)}</td>
                      <td>{money(d.expenses)}</td>
                      <td>{money(d.closing)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
        )}
      </Drawer>
      <Modal
        title={
          draft && state.expenses.some((e) => e.id === draft.id)
            ? '예정 지출 수정'
            : '예정 지출 등록'
        }
        open={draft !== null}
        onCancel={() => setDraft(null)}
        onOk={save}
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
                min={state.base}
                value={draft.date}
                onChange={(e) => setDraft({ ...draft, date: e.target.value })}
              />
            </label>
            <label className="finance-field">
              금액 (원)
              <InputNumber
                min={1}
                max={1000000000000}
                precision={0}
                value={draft.amount}
                onChange={(amount) => setDraft({ ...draft, amount: amount ?? 0 })}
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
                onChange={(category) => setDraft({ ...draft, category })}
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
    </div>
  )
}
