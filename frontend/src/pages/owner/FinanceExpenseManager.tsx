import { useCallback, useRef, useState } from 'react'
import dayjs from 'dayjs'
import {
  Alert,
  Button,
  Drawer,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Select,
  Table,
  Tabs,
  Tag,
} from 'antd'
import type {
  CashFlowSettings,
  Expense,
  ExpenseInput,
  RecurringExpenseInput,
} from '../../api/finance'
import {
  completePlannedExpense,
  completeRecurringOccurrence,
  createPlannedExpense,
  createRecurringExpense,
  deletePlannedExpense,
  getPlannedExpenses,
  getRecurringExpenses,
  getRecurringOccurrences,
  stopRecurringExpense,
  updatePlannedExpense,
} from '../../api/finance'
import {
  FinanceFailure,
  FinanceLoading,
  FinancePeriodControl,
  FinanceRefresh,
} from './FinanceControls'
import { RecurringExpensePanel } from './RecurringExpensePanel'
import {
  MAX_FINANCE_AMOUNT,
  financeErrorMessage,
  financeWriteErrorMessage,
  managementRows,
  money,
  validFinancePeriod,
} from './financeView'
import type { ExpenseRow } from './financeView'
import { useFinanceResource } from './useFinanceResource'
const categories = [
  { value: 'MATERIAL', label: '재료비' },
  { value: 'RENT', label: '임대료' },
  { value: 'PAYROLL', label: '인건비' },
  { value: 'UTILITIES', label: '공과금' },
  { value: 'OTHER', label: '기타' },
]
export function FinanceExpenseManager({
  open,
  onClose,
  settings,
  today,
  revision,
  onChanged,
}: {
  open: boolean
  onClose: () => void
  settings: CashFlowSettings | undefined
  today: string
  revision: number
  onChanged: () => void
}) {
  const [tab, setTab] = useState('balance')
  const [period, setPeriod] = useState(() => ({
    from: dayjs(today).subtract(30, 'day').format('YYYY-MM-DD'),
    to: dayjs(today).add(30, 'day').format('YYYY-MM-DD'),
  }))
  const [filter, setFilter] = useState('all')
  const [busy, setBusy] = useState(false)
  const writing = useRef(false)
  const [error, setError] = useState<unknown>(null)
  const [saved, setSaved] = useState('')
  const [draft, setDraft] = useState<'new' | Expense | null>(null)
  const [form] = Form.useForm<ExpenseInput>()
  const base = settings?.baseDate
  const from = base && period.from < base ? base : period.from
  const valid = validFinancePeriod({ from, to: period.to })
  const load = useCallback(
    async (signal: AbortSignal) => {
      const [expenses, rules, occurrences] = await Promise.all([
        getPlannedExpenses(from, period.to, signal),
        getRecurringExpenses(signal),
        getRecurringOccurrences(from, period.to, signal),
      ])
      return { rules, rows: managementRows(expenses, occurrences) }
    },
    [from, period.to],
  )
  const state = useFinanceResource(
    `manage:${open}:${base}:${from}:${period.to}:${revision}`,
    load,
    open && !!base && valid,
  )
  async function mutate(name: string, action: () => Promise<unknown>) {
    if (writing.current || !settings) throw new Error('설정 정보를 확인한 뒤 다시 시도해 주세요.')
    writing.current = true
    setBusy(true)
    setError(null)
    setSaved('')
    try {
      await action()
      setSaved(`${name} 완료. 최신 내역을 다시 조회합니다.`)
      setDraft(null)
      onChanged()
    } catch (e) {
      const failure = new Error(financeWriteErrorMessage(e))
      setError(failure)
      throw failure
    } finally {
      writing.current = false
      setBusy(false)
    }
  }
  const perform = (name: string, action: () => Promise<unknown>) =>
    mutate(name, action).catch(() => undefined)
  const edit = (expense: Expense | 'new') => {
    setError(null)
    setDraft(expense)
    form.resetFields()
    form.setFieldsValue(
      expense === 'new'
        ? { title: '', expenseDate: today, amount: undefined, category: 'MATERIAL', memo: null }
        : expense,
    )
  }
  const complete = (row: ExpenseRow) =>
    perform('실제 지출 기록', () =>
      row.kind === 'ONEOFF'
        ? completePlannedExpense(row.id)
        : completeRecurringOccurrence(row.id, row.originalDate),
    )
  const rows =
    state.status === 'success'
      ? state.data.rows.filter(
          (row) => filter === 'all' || (filter === 'paid' ? !!row.paidAt : !row.paidAt),
        )
      : []
  return (
    <Drawer
      title="잔액·지출 설정"
      open={open}
      onClose={onClose}
      closable={!busy}
      maskClosable={!busy}
      width="min(1100px, 100vw)"
    >
      {saved && <Alert type="success" showIcon message={saved} />}
      {error !== null && <Alert type="error" showIcon message={financeErrorMessage(error)} />}
      <Tabs
        activeKey={tab}
        onChange={setTab}
        items={[
          { key: 'balance', label: '최초 잔액' },
          { key: 'expenses', label: '지출 내역·완료 처리' },
          { key: 'recurring', label: '정기 지출' },
        ]}
      />
      {!settings ? (
        <FinanceLoading />
      ) : tab === 'balance' ? (
        <section className="finance-card">
          <h2>최초 시작 잔액 {money(settings.openingBalance)}</h2>
          <p>기준일: {settings.baseDate} 하루 시작 시점</p>
          <Tag>서버 저장 완료 · 수정 불가</Tag>
          <p>
            현재 잔액은 실제 완료 정산과 지출로 계산합니다. 은행 실잔액이 아니며 최초 설정은
            재로드해도 유지됩니다.
          </p>
        </section>
      ) : (
        <>
          <FinanceRefresh
            at={state.lastSuccess}
            loading={state.status === 'loading'}
            onRefresh={onChanged}
          />
          <FinancePeriodControl value={{ from, to: period.to }} onChange={setPeriod} min={base} />
          <p>
            선택 기간의 미완료 예정 지출과 실제 완료일 기준 내역입니다. 원래 예정일이 과거인 미완료
            지출은 예측에서 제외되며, 실제 지출 완료 시 오늘 기록에 반영됩니다. 한 번에 최대 366일을
            조회합니다.
          </p>
          {!valid && (
            <Alert type="warning" message="기준일 이후 최대 366일의 기간을 선택해 주세요." />
          )}
          {state.status === 'loading' && <FinanceLoading />}
          {state.status === 'error' && <FinanceFailure error={state.error} onRetry={onChanged} />}
          {state.status === 'success' &&
            (tab === 'recurring' ? (
              <RecurringExpensePanel
                rules={state.data.rules}
                todayValue={today}
                maxAmount={MAX_FINANCE_AMOUNT}
                onCreate={(input: RecurringExpenseInput) =>
                  mutate('정기 지출 등록', () => createRecurringExpense(input))
                }
                onStop={(id) => mutate('정기 지출 중단', () => stopRecurringExpense(id))}
              />
            ) : (
              <>
                <div className="finance-section-head">
                  <h2>지출 내역</h2>
                  <Button type="primary" disabled={busy} onClick={() => edit('new')}>
                    지출 등록
                  </Button>
                </div>
                <Select
                  aria-label="지출 상태 필터"
                  value={filter}
                  onChange={setFilter}
                  options={[
                    { value: 'all', label: '전체' },
                    { value: 'planned', label: '지출 예정' },
                    { value: 'paid', label: '지출 완료' },
                  ]}
                />
                <Table<ExpenseRow>
                  rowKey="key"
                  dataSource={rows}
                  pagination={{ pageSize: 10 }}
                  scroll={{ x: 850 }}
                  locale={{ emptyText: '선택 기간에 조회된 지출이 없습니다.' }}
                  columns={[
                    {
                      title: '반영일 / 원래 예정일',
                      render: (_, row) => (
                        <>
                          {row.date}
                          <small style={{ display: 'block' }}>예정 {row.originalDate}</small>
                        </>
                      ),
                    },
                    { title: '항목', dataIndex: 'title' },
                    {
                      title: '종류',
                      render: (_, row) => (row.kind === 'ONEOFF' ? '일회성' : '정기'),
                    },
                    { title: '금액', render: (_, row) => money(row.amount) },
                    {
                      title: '상태',
                      render: (_, row) => (
                        <Tag
                          color={
                            row.paidAt ? 'green' : row.originalDate < today ? 'orange' : 'blue'
                          }
                        >
                          {row.paidAt
                            ? '실제 완료'
                            : row.originalDate < today
                              ? '연체 예정 · 예측 제외'
                              : '예정'}
                        </Tag>
                      ),
                    },
                    {
                      title: '처리',
                      render: (_, row) =>
                        row.paidAt ? (
                          <span>완료 기록 · 수정 불가</span>
                        ) : (
                          <div className="finance-row-actions">
                            <Popconfirm
                              title="오늘 실제 지출 완료로 기록할까요?"
                              description={`${row.title} ${money(row.amount)} · 미래 예정액에 다시 포함하지 않습니다.`}
                              onConfirm={() => complete(row)}
                            >
                              <Button size="small" disabled={busy}>
                                지출 완료
                              </Button>
                            </Popconfirm>
                            {row.expense && (
                              <>
                                <Button
                                  size="small"
                                  disabled={busy}
                                  onClick={() => edit(row.expense!)}
                                >
                                  수정
                                </Button>
                                <Popconfirm
                                  title="이 미완료 일회성 지출을 삭제할까요?"
                                  onConfirm={() =>
                                    perform('지출 삭제', () => deletePlannedExpense(row.id))
                                  }
                                >
                                  <Button size="small" danger disabled={busy}>
                                    삭제
                                  </Button>
                                </Popconfirm>
                              </>
                            )}
                          </div>
                        ),
                    },
                  ]}
                />
              </>
            ))}
        </>
      )}
      <Modal
        title={draft === 'new' ? '일회성 지출 등록' : '일회성 지출 수정'}
        open={draft !== null}
        onCancel={() => setDraft(null)}
        closable={!busy}
        maskClosable={!busy}
        cancelButtonProps={{ disabled: busy }}
        confirmLoading={busy}
        okButtonProps={{ disabled: !settings }}
        onOk={() => form.submit()}
        okText="저장"
        cancelText="취소"
      >
        {error !== null && <Alert type="error" message={financeErrorMessage(error)} />}
        <Form
          form={form}
          layout="vertical"
          disabled={busy}
          onFinish={(input) => {
            if (!draft) return
            const payload: ExpenseInput = {
              title: input.title.trim(),
              expenseDate: input.expenseDate,
              amount: input.amount,
              category: input.category,
              memo: input.memo?.trim() || null,
            }
            void perform('일회성 지출 저장', () =>
              draft === 'new'
                ? createPlannedExpense(payload)
                : updatePlannedExpense(draft.expenseId, payload),
            )
          }}
        >
          <Form.Item
            name="title"
            label="항목명"
            rules={[{ required: true, whitespace: true, max: 100 }]}
          >
            <Input maxLength={100} />
          </Form.Item>
          <Form.Item
            name="expenseDate"
            label="원래 지출 예정일"
            rules={[
              { required: true },
              {
                validator: (_, value: string) =>
                  value && base && value < base
                    ? Promise.reject(new Error('잔액 기준일 이후여야 합니다.'))
                    : Promise.resolve(),
              },
            ]}
          >
            <Input type="date" min={base} />
          </Form.Item>
          <Form.Item
            name="amount"
            label="금액 (원)"
            rules={[{ required: true }, { type: 'integer', min: 1, max: MAX_FINANCE_AMOUNT }]}
          >
            <InputNumber min={1} max={MAX_FINANCE_AMOUNT} precision={0} />
          </Form.Item>
          <Form.Item name="category" label="유형" rules={[{ required: true }]}>
            <Select options={categories} />
          </Form.Item>
          <Form.Item name="memo" label="메모" rules={[{ max: 500 }]}>
            <Input.TextArea maxLength={500} />
          </Form.Item>
        </Form>
      </Modal>
    </Drawer>
  )
}
