import { useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Select,
  Space,
  Table,
  Tag,
} from 'antd'
import dayjs from 'dayjs'
import type { RecurringExpense, RecurringExpenseInput } from './financePreviewTypes'
import { MAX_PREVIEW_EXPENSE } from './recurringExpenses'

export function RecurringExpensePanel({
  rules,
  onCreate,
  onStop,
}: {
  rules: RecurringExpense[]
  onCreate: (input: RecurringExpenseInput) => Promise<void>
  onStop: (id: number) => Promise<void>
}) {
  const [open, setOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [form] = Form.useForm<RecurringExpenseInput>()
  const today = dayjs().format('YYYY-MM-DD')
  async function write(action: () => Promise<void>) {
    setBusy(true)
    setError(null)
    try {
      await action()
      setOpen(false)
    } catch (e) {
      setError(e)
    } finally {
      setBusy(false)
    }
  }
  return (
    <Card
      title="정기 지출"
      extra={
        <Button
          size="small"
          disabled={busy}
          onClick={() => {
            form.resetFields()
            form.setFieldsValue({ startDate: today, frequency: 'MONTHLY', category: 'OTHER' })
            setError(null)
            setOpen(true)
          }}
        >
          정기 지출 등록
        </Button>
      }
    >
      <p>
        매일은 하루마다, 매주는 시작일의 요일, 매달은 같은 날짜에 반영합니다. 매달 31일처럼 없는
        날짜는 말일로 계산합니다. 과거 시작일은 등록할 수 없습니다.
      </p>
      <p>
        금액 변경은 기존 규칙을 중단하고 새 규칙을 등록해 주세요. 중단은 오늘부터 적용하며, 오늘
        이전 내역은 유지합니다. 같은 지출을 일회성으로 다시 등록하면 이중 계산됩니다.
      </p>
      {error !== null && (
        <Alert
          type="error"
          message={error instanceof Error ? error.message : '입력값을 확인해 주세요.'}
        />
      )}
      <Table
        rowKey="recurringExpenseId"
        dataSource={rules}
        locale={{ emptyText: '등록한 정기 지출이 없습니다.' }}
        pagination={{ pageSize: 5 }}
        scroll={{ x: 700 }}
        columns={[
          { title: '항목', dataIndex: 'title' },
          {
            title: '주기',
            render: (_, r) =>
              r.frequency === 'DAILY'
                ? '매일'
                : r.frequency === 'WEEKLY'
                  ? `매주 ${['일', '월', '화', '수', '목', '금', '토'][dayjs(r.startDate).day()]}요일`
                  : `매달 ${dayjs(r.startDate).date()}일`,
          },
          {
            title: '금액',
            dataIndex: 'amount',
            render: (v: number) => `${v.toLocaleString('ko-KR')}원`,
          },
          { title: '적용 기간', render: (_, r) => `${r.startDate} ~ ${r.endDate ?? '종료 없음'}` },
          {
            title: '상태',
            render: (_, r) =>
              r.stoppedFrom ? (
                <Tag>중단 ({r.stoppedFrom}부터 제외)</Tag>
              ) : r.endDate && r.endDate < today ? (
                <Tag>기간 종료</Tag>
              ) : (
                <Popconfirm
                  title="오늘부터 정기 지출을 중단할까요?"
                  description="오늘 이전 내역은 보존됩니다. 중단 후에는 되돌릴 수 없습니다."
                  onConfirm={() => write(() => onStop(r.recurringExpenseId))}
                >
                  <Button size="small" disabled={busy}>
                    중단
                  </Button>
                </Popconfirm>
              ),
          },
        ]}
      />
      <Modal
        title="정기 지출 등록"
        okText="등록"
        cancelText="취소"
        open={open}
        onCancel={() => setOpen(false)}
        closable={!busy}
        maskClosable={!busy}
        cancelButtonProps={{ disabled: busy }}
        confirmLoading={busy}
        onOk={() => form.submit()}
      >
        {error !== null && (
          <Alert
            type="error"
            message={error instanceof Error ? error.message : '입력값을 확인해 주세요.'}
          />
        )}
        <Form
          form={form}
          layout="vertical"
          onFinish={(v) =>
            void write(() => onCreate({ ...v, memo: v.memo || null, endDate: v.endDate || null }))
          }
        >
          <Form.Item
            name="title"
            label="항목명"
            rules={[{ required: true, whitespace: true, max: 100 }]}
          >
            <Input maxLength={100} />
          </Form.Item>
          <Space>
            <Form.Item name="frequency" label="주기" rules={[{ required: true }]}>
              <Select
                style={{ width: 120 }}
                options={[
                  { value: 'DAILY', label: '매일' },
                  { value: 'WEEKLY', label: '매주' },
                  { value: 'MONTHLY', label: '매달' },
                ]}
              />
            </Form.Item>
            <Form.Item
              name="amount"
              label="금액 (원, 최대 10억)"
              rules={[{ required: true }, { type: 'integer', min: 1, max: MAX_PREVIEW_EXPENSE }]}
            >
              <InputNumber min={1} max={MAX_PREVIEW_EXPENSE} precision={0} />
            </Form.Item>
          </Space>
          <Form.Item
            name="startDate"
            label="첫 지출일 (주기 기준일)"
            rules={[
              { required: true },
              {
                validator: (_, v) =>
                  !v || v >= today
                    ? Promise.resolve()
                    : Promise.reject(new Error('오늘 이후 날짜를 선택해 주세요.')),
              },
            ]}
          >
            <Input type="date" min={today} />
          </Form.Item>
          <Form.Item
            name="endDate"
            label="마지막 적용일 (선택, 이 날짜 포함)"
            dependencies={['startDate']}
            rules={[
              {
                validator: (_, v) =>
                  !v || v >= form.getFieldValue('startDate')
                    ? Promise.resolve()
                    : Promise.reject(new Error('시작일 이후여야 합니다.')),
              },
            ]}
          >
            <Input type="date" />
          </Form.Item>
          <Form.Item name="category" label="유형" rules={[{ required: true }]}>
            <Select
              options={[
                { value: 'MATERIAL', label: '재료비' },
                { value: 'RENT', label: '임대료' },
                { value: 'PAYROLL', label: '급여' },
                { value: 'UTILITIES', label: '공과금' },
                { value: 'OTHER', label: '기타' },
              ]}
            />
          </Form.Item>
          <Form.Item name="memo" label="메모">
            <Input.TextArea maxLength={500} />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  )
}
