import { CheckOutlined, DeleteOutlined, EditOutlined } from '@ant-design/icons'
import { FinanceMoney } from './FinanceMoney'
import { useCallback, useRef, useState } from 'react'
import dayjs from 'dayjs'
import {
  Alert,
  Button,
  Descriptions,
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
  saveCashFlowSettings,
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
  formatWonInput,
  parseWonInput,
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
  activeTab,
  onTabChange,
  open,
  onClose,
  settings,
  today,
  revision,
  onChanged,
}: {
  activeTab?: string
  onTabChange?: (tab: string) => void
  open: boolean
  onClose: () => void
  settings: CashFlowSettings | undefined
  today: string
  revision: number
  onChanged: () => void
}) {
  const [completion, setCompletion] = useState<{ id: number; title: string; date: string } | null>(
    null,
  )
  const [detail, setDetail] = useState<ExpenseRow | null>(null)
  const [internalTab, setTab] = useState('balance')
  const tab = activeTab ?? internalTab
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
    perform('실제 지출 기록', () => completeRecurringOccurrence(row.id, row.originalDate))
  const rows =
    state.status === 'success'
      ? state.data.rows.filter(
          (row) => filter === 'all' || (filter === 'paid' ? !!row.paidAt : !row.paidAt),
        )
      : []
  return (
    <Drawer
      className="finance-drawer"
      title="잔액·지출 설정"
      open={open}
      onClose={() => {
        setDetail(null)
        onClose()
      }}
      closable={!busy}
      maskClosable={!busy}
      width="min(1100px, 100vw)"
    >
      {saved && <Alert type="success" showIcon message={saved} />}
      {error !== null && <Alert type="error" showIcon message={financeErrorMessage(error)} />}
      <Tabs
        activeKey={tab}
        onChange={(value) => {
          setTab(value)
          onTabChange?.(value)
        }}
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
          <h2>
            처음 입력한 잔액 <FinanceMoney value={settings.openingBalance} />
          </h2>
          <p>기준일: {settings.baseDate} 하루 시작 시점</p>
          <Tag>서버 저장 완료 · 수정 불가</Tag>
          <Form<{ amount: number }>
            className="finance-minimum-funds-form"
            key={settings.minimumOperatingFunds ?? 0}
            layout="vertical"
            initialValues={{ amount: settings.minimumOperatingFunds ?? 0 }}
            onFinish={({ amount }) =>
              void perform('최소 운영 자금 저장', () =>
                saveCashFlowSettings({ ...settings, minimumOperatingFunds: amount }),
              )
            }
          >
            <Form.Item
              name="amount"
              label={<strong>최소 운영 자금 (원)</strong>}
              extra="지출 뒤에도 남겨두고 싶은 잔액이에요. 하루 끝 예상 잔액이 이 기준보다 낮으면 알려드려요. 이 기준은 언제든 바꿀 수 있어요."
              rules={[{ required: true, type: 'integer', min: 0, max: MAX_FINANCE_AMOUNT }]}
            >
              <InputNumber
                aria-label="최소 운영 자금"
                className="finance-money-input"
                suffix="원"
                min={0}
                max={MAX_FINANCE_AMOUNT}
                precision={0}
                formatter={formatWonInput}
                parser={parseWonInput}
              />
            </Form.Item>
            <Button type="primary" htmlType="submit" loading={busy}>
              최소 운영 자금 저장
            </Button>
          </Form>
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
                    {
                      title: '지출명',
                      render: (_, row) => (
                        <Button
                          type="link"
                          style={{
                            padding: 0,
                            height: 'auto',
                            whiteSpace: 'normal',
                            textAlign: 'left',
                          }}
                          onClick={() => setDetail(row)}
                        >
                          {row.title}
                        </Button>
                      ),
                    },
                    {
                      title: '종류',
                      render: (_, row) => (row.kind === 'ONEOFF' ? '일회성' : '정기'),
                    },
                    { title: '금액', render: (_, row) => <FinanceMoney value={row.amount} /> },
                    {
                      title: '상태',
                      render: (_, row) => (
                        <Tag
                          color={
                            row.paidAt ? 'green' : row.originalDate < today ? 'orange' : 'blue'
                          }
                        >
                          {row.paidAt
                            ? '지출 완료'
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
                            {row.kind === 'ONEOFF' ? (
                              <Button
                                type="primary"
                                size="small"
                                icon={<CheckOutlined />}
                                disabled={busy}
                                onClick={() => {
                                  setError(null)
                                  setCompletion({ id: row.id, title: row.title, date: today })
                                }}
                              >
                                지출 완료
                              </Button>
                            ) : (
                              <Popconfirm
                                title="오늘 실제 지출 완료로 기록할까요?"
                                description={`${row.title} ${money(row.amount)} · 미래 예정액에 다시 포함하지 않습니다.`}
                                onConfirm={() => complete(row)}
                              >
                                <Button
                                  type="primary"
                                  size="small"
                                  icon={<CheckOutlined />}
                                  disabled={busy}
                                >
                                  지출 완료
                                </Button>
                              </Popconfirm>
                            )}
                            {row.expense && (
                              <>
                                <Button
                                  size="small"
                                  type="text"
                                  icon={<EditOutlined />}
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
                                  <Button
                                    type="text"
                                    size="small"
                                    icon={<DeleteOutlined />}
                                    danger
                                    disabled={busy}
                                  >
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
        title="실제 지출일 기록"
        open={completion !== null && open}
        onCancel={() => setCompletion(null)}
        okText="지출 완료"
        cancelText="취소"
        confirmLoading={busy}
        closable={!busy}
        maskClosable={!busy}
        cancelButtonProps={{ disabled: busy }}
        okButtonProps={{
          disabled:
            !completion ||
            !base ||
            !completion.date ||
            completion.date < base ||
            completion.date > today,
        }}
        onOk={async () => {
          if (
            !completion ||
            !base ||
            !completion.date ||
            completion.date < base ||
            completion.date > today
          )
            return
          try {
            await mutate('실제 지출 기록', () =>
              completePlannedExpense(completion.id, completion.date),
            )
            setCompletion(null)
          } catch {
            // 저장 오류는 모달 안에서 표시한다.
          }
        }}
      >
        {completion && (
          <>
            <p>{completion.title}을 실제로 지출한 날짜를 선택해 주세요.</p>
            <label>
              실제 지출일
              <Input
                type="date"
                value={completion.date}
                min={base}
                max={today}
                disabled={busy}
                onChange={(event) => setCompletion({ ...completion, date: event.target.value })}
              />
            </label>
            {error !== null && (
              <Alert
                type="error"
                message={error instanceof Error ? error.message : String(error)}
              />
            )}
          </>
        )}
      </Modal>
      <Modal
        title="지출 상세"
        open={detail !== null && open}
        onCancel={() => setDetail(null)}
        footer={<Button onClick={() => setDetail(null)}>닫기</Button>}
      >
        {detail && (
          <Descriptions
            column={1}
            size="small"
            items={[
              { key: 'title', label: '지출명', children: detail.title },
              {
                key: 'kind',
                label: '종류',
                children: detail.kind === 'ONEOFF' ? '일회성 지출' : '정기 지출',
              },
              {
                key: 'amount',
                label: '금액',
                children: <FinanceMoney value={detail.amount} compact={false} />,
              },
              {
                key: 'category',
                label: '분류',
                children:
                  categories.find(({ value }) => value === detail.category)?.label ??
                  detail.category,
              },
              { key: 'planned', label: '지출 예정일', children: detail.originalDate },
              {
                key: 'status',
                label: '상태',
                children: detail.paidAt
                  ? '지출 완료'
                  : detail.originalDate < today
                    ? '연체 예정 · 예측 제외'
                    : '예정',
              },
              { key: 'paid', label: '완료 반영일', children: detail.paidAt ? detail.date : '—' },
              {
                key: 'memo',
                label: '메모',
                children: (
                  <span style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
                    {detail.memo || '—'}
                  </span>
                ),
              },
            ]}
          />
        )}
      </Modal>
      <Modal
        className="finance-dialog"
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
            label="지출명"
            rules={[{ required: true, whitespace: true, max: 100 }]}
          >
            <Input maxLength={100} />
          </Form.Item>
          <Form.Item
            name="expenseDate"
            label="지출 예정일"
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
            <InputNumber
              className="finance-money-input"
              formatter={formatWonInput}
              parser={parseWonInput}
              suffix="원"
              min={1}
              max={MAX_FINANCE_AMOUNT}
              precision={0}
            />
          </Form.Item>
          <Form.Item name="category" label="유형" rules={[{ required: true }]}>
            <Select options={categories} />
          </Form.Item>
          <Form.Item name="memo" label="메모" rules={[{ max: 500 }]}>
            <Input.TextArea maxLength={500} autoSize={{ minRows: 4, maxRows: 8 }} />
          </Form.Item>
        </Form>
      </Modal>
    </Drawer>
  )
}
