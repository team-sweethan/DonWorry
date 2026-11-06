import { Alert, App, Button, Card, Input, Modal, Space, Table, Tabs, Tag } from 'antd'
import { useCallback, useEffect, useRef, useState } from 'react'
import {
  getRawViewReviews,
  reviewRawView,
  type RawViewReview,
  type RawViewReviewStatus,
} from '../../api/rawViewReviews'
import { PageHeader } from '../../components/common/PageHeader'
import { refreshRawViewReviewBadge } from '../../hooks/useRawViewReviewBadge'
import { dateTime } from '../security/case/caseLabels'
import { approveMemoPlaceholder, errorText } from './caseAssignmentForm'

const tabs: Array<{ key: RawViewReviewStatus; label: string }> = [
  { key: 'PENDING', label: '검토 대기' },
  { key: 'APPROPRIATE', label: '적절' },
  { key: 'EXPLANATION_REQUESTED', label: '소명 요청' },
]
const statusTag: Record<RawViewReviewStatus, { label: string; color: string }> = {
  PENDING: { label: '검토 대기', color: 'gold' },
  APPROPRIATE: { label: '적절', color: 'green' },
  EXPLANATION_REQUESTED: { label: '소명 요청', color: 'red' },
}
type Decision = Exclude<RawViewReviewStatus, 'PENDING'>

const textCell = (value: string | null) => (
  <div style={{ minWidth: 160, whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
    {value ?? '-'}
  </div>
)
const oneLine = {
  onHeaderCell: () => ({ style: { whiteSpace: 'nowrap' as const } }),
  onCell: () => ({ style: { whiteSpace: 'nowrap' as const } }),
}

/**
 * 관리자 [직원 관리 › 보안 › 원문 열람 검토] (#619, ADR-0082).
 * 원문은 보이지 않고 누가 · 어느 사건 결제 · 무슨 근거로 봤는지만 보인다. 사건 상세도 열지 않는다 (ADR-0065).
 */
export function RawViewReviewsPage() {
  const { message } = App.useApp()
  const [status, setStatus] = useState<RawViewReviewStatus>('PENDING')
  const [rows, setRows] = useState<RawViewReview[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [target, setTarget] = useState<{ row: RawViewReview; decision: Decision } | null>(null)
  const [memo, setMemo] = useState('')
  const [saving, setSaving] = useState(false)
  // 탭을 빠르게 바꾸면 늦게 온 이전 탭 결과를 버린다
  const sequence = useRef(0)

  const reload = useCallback(() => {
    const current = ++sequence.current
    getRawViewReviews(status)
      .then((next) => {
        if (current !== sequence.current) return
        setRows(next)
        setError(null)
      })
      .catch(() => {
        if (current !== sequence.current) return
        setRows([])
        setError('원문 열람 기록을 불러오지 못했습니다. 잠시 후 다시 시도하세요.')
      })
  }, [status])
  useEffect(() => {
    reload()
  }, [reload])

  const open = (row: RawViewReview, decision: Decision) => {
    setMemo('')
    setTarget({ row, decision })
  }
  const submit = async () => {
    if (!target) return
    setSaving(true)
    try {
      await reviewRawView(target.row.reviewId, target.decision, memo)
      message.success(
        target.decision === 'APPROPRIATE' ? '적절로 표시했습니다.' : '소명을 요청했습니다.',
      )
      setTarget(null)
      reload()
      refreshRawViewReviewBadge()
    } catch (e) {
      message.error(errorText(e, '검토 결과를 저장하지 못했습니다. 잠시 후 다시 시도하세요.'))
    } finally {
      setSaving(false)
    }
  }

  return (
    <>
      <PageHeader
        title="원문 열람 검토"
        description="보안 직원이 담당 사건 결제의 주문자 전화번호 원문을 본 기록입니다. 원문은 여기 보이지 않습니다. 근거가 업무에 맞는지 보고 [적절] 또는 [소명 요청]을 누르세요."
      />
      {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 16 }} />}
      <Card>
        <Tabs
          activeKey={status}
          onChange={(key) => {
            setError(null)
            // 이전 탭의 행을 새 탭 결과처럼 잠깐 보여 주지 않는다
            sequence.current += 1
            setRows(null)
            setStatus(key as RawViewReviewStatus)
          }}
          items={tabs.map((tab) => ({ key: tab.key, label: tab.label }))}
        />
        <Table<RawViewReview>
          rowKey="reviewId"
          size="small"
          loading={rows === null}
          pagination={false}
          scroll={{ x: 'max-content' }}
          dataSource={rows ?? []}
          columns={[
            { ...oneLine, title: '열람 시각', dataIndex: 'viewedAt', render: dateTime },
            {
              ...oneLine,
              title: '직원',
              dataIndex: 'staffName',
              render: (v: string | null) => v ?? '-',
            },
            {
              ...oneLine,
              title: 'Case · 결제',
              render: (_, row) => `#${row.caseId} · 결제 ${row.paymentId}`,
            },
            {
              ...oneLine,
              title: '가맹점',
              render: (_, row) => row.merchantName ?? `ID ${row.merchantId}`,
            },
            { ...oneLine, title: '이의제기 참조', dataIndex: 'disputeRef' },
            { title: '가맹점 연락 시도', dataIndex: 'merchantContactAttempt', render: textCell },
            { title: '사유', dataIndex: 'reason', render: textCell },
            {
              ...oneLine,
              title: '상태',
              dataIndex: 'status',
              render: (value: RawViewReviewStatus) => (
                <Tag color={statusTag[value].color}>{statusTag[value].label}</Tag>
              ),
            },
            ...(status === 'PENDING'
              ? [
                  {
                    ...oneLine,
                    title: '검토',
                    render: (_: unknown, row: RawViewReview) => (
                      <Space size={6}>
                        <Button
                          size="small"
                          type="primary"
                          onClick={() => open(row, 'APPROPRIATE')}
                        >
                          적절
                        </Button>
                        <Button
                          size="small"
                          danger
                          onClick={() => open(row, 'EXPLANATION_REQUESTED')}
                        >
                          소명 요청
                        </Button>
                      </Space>
                    ),
                  },
                ]
              : [
                  {
                    ...oneLine,
                    title: '검토',
                    render: (_: unknown, row: RawViewReview) =>
                      `${row.reviewedByName ?? '-'} · ${row.reviewedAt ? dateTime(row.reviewedAt) : '-'}`,
                  },
                  { title: '검토 메모', dataIndex: 'reviewMemo', render: textCell },
                ]),
          ]}
        />
      </Card>
      <Modal
        title={target?.decision === 'APPROPRIATE' ? '적절로 표시' : '소명 요청'}
        open={target !== null}
        okText={target?.decision === 'APPROPRIATE' ? '적절' : '소명 요청'}
        okButtonProps={{ danger: target?.decision === 'EXPLANATION_REQUESTED' }}
        cancelText="닫기"
        confirmLoading={saving}
        onOk={() => void submit()}
        onCancel={() => setTarget(null)}
      >
        <p>
          {target?.decision === 'APPROPRIATE'
            ? '업무 근거가 맞으면 적절로 표시합니다.'
            : '근거가 부족하면 소명을 요청합니다. 소명 요청 기록은 내부자 탐지의 근거로 쓰입니다.'}
        </p>
        <Input.TextArea
          value={memo}
          maxLength={200}
          showCount
          rows={3}
          placeholder={approveMemoPlaceholder}
          onChange={(event) => setMemo(event.target.value)}
        />
      </Modal>
    </>
  )
}
