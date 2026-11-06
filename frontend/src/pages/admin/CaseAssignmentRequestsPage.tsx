import { Alert, App, Button, Card, Input, Modal, Select, Space, Table, Tabs, Tag } from 'antd'
import { useCallback, useEffect, useRef, useState } from 'react'
import {
  approveAssignmentRequest,
  assignmentReasonLabel,
  getAssignmentOverview,
  getAssignmentRequests,
  rejectAssignmentRequest,
  suggestedStaff,
  type AssignmentRequest,
  type AssignmentRequestStatus,
  type StaffLoad,
} from '../../api/caseAssignments'
import { PageHeader } from '../../components/common/PageHeader'
import { refreshCaseAssignmentRequestBadge } from '../../hooks/useCaseAssignmentRequestBadge'
import { CodeTag } from '../security/case/CodeTag'
import { caseStatusLabel, dateTime } from '../security/case/caseLabels'
import { approveMemoPlaceholder, errorText } from './caseAssignmentForm'

const tabs: Array<{ key: AssignmentRequestStatus; label: string }> = [
  { key: 'PENDING', label: '처리 대기' },
  { key: 'APPROVED', label: '승인' },
  { key: 'REJECTED', label: '거절' },
]
const statusTag: Record<AssignmentRequestStatus, { label: string; color: string }> = {
  PENDING: { label: '처리 대기', color: 'gold' },
  APPROVED: { label: '승인', color: 'green' },
  REJECTED: { label: '거절', color: 'red' },
}

// 메모만 줄을 바꾸고, 이름 · 시각 같은 짧은 칸과 머리글은 한 줄로 둔다 (좁은 칸이 "보안 / 직원 / B"로 쪼개지지 않게)
const textCell = (value: string | null) => (
  <div style={{ minWidth: 160, whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
    {value ?? '-'}
  </div>
)
const oneLine = {
  onHeaderCell: () => ({ style: { whiteSpace: 'nowrap' as const } }),
  onCell: () => ({ style: { whiteSpace: 'nowrap' as const } }),
}

// 관리자 [사건 담당 › 변경 요청] (#608). 새 담당은 관리자가 고른다 — 요청한 직원은 지목하지 못한다
export function CaseAssignmentRequestsPage() {
  const { message } = App.useApp()
  const [status, setStatus] = useState<AssignmentRequestStatus>('PENDING')
  const [rows, setRows] = useState<AssignmentRequest[] | null>(null)
  const [staff, setStaff] = useState<StaffLoad[]>([])
  const [error, setError] = useState<string | null>(null)
  const [approving, setApproving] = useState<AssignmentRequest | null>(null)
  const [rejecting, setRejecting] = useState<AssignmentRequest | null>(null)
  const [newStaffId, setNewStaffId] = useState<number | null>(null)
  const [memo, setMemo] = useState('')
  const [saving, setSaving] = useState(false)
  // 탭을 빠르게 바꾸면 늦게 온 이전 탭 결과를 버린다
  const sequence = useRef(0)

  const reload = useCallback(() => {
    const current = ++sequence.current
    getAssignmentRequests(status)
      .then((next) => {
        if (current !== sequence.current) return
        setRows(next)
        setError(null)
      })
      .catch(() => {
        if (current !== sequence.current) return
        setRows([])
        setError('담당 변경 요청을 불러오지 못했습니다. 잠시 후 다시 시도하세요.')
      })
    // 승인 창의 새 담당 후보(자동 배정 순서)도 최신 건수로 다시 받는다
    getAssignmentOverview()
      .then((overview) => setStaff(overview.staff))
      .catch(() => setStaff([]))
  }, [status])
  useEffect(() => {
    reload()
  }, [reload])

  const openApprove = (row: AssignmentRequest) => {
    setMemo('')
    setNewStaffId(suggestedStaff(staff, row.currentAssigneeStaffId)?.staffId ?? null)
    setApproving(row)
  }
  const openReject = (row: AssignmentRequest) => {
    setMemo('')
    setRejecting(row)
  }
  const done = (text: string) => {
    message.success(text)
    setApproving(null)
    setRejecting(null)
    reload()
    refreshCaseAssignmentRequestBadge()
  }
  const approve = async () => {
    if (!approving || newStaffId === null) return
    setSaving(true)
    try {
      await approveAssignmentRequest(approving.requestId, newStaffId, memo)
      done(`Case #${approving.caseId}의 담당을 바꿨습니다.`)
    } catch (e) {
      message.error(errorText(e, '요청을 승인하지 못했습니다. 잠시 후 다시 시도하세요.'))
    } finally {
      setSaving(false)
    }
  }
  const reject = async () => {
    if (!rejecting) return
    setSaving(true)
    try {
      await rejectAssignmentRequest(rejecting.requestId, memo)
      done(`요청을 거절했습니다. Case #${rejecting.caseId}는 지금 담당이 다시 판정할 수 있습니다.`)
    } catch (e) {
      message.error(errorText(e, '요청을 거절하지 못했습니다. 잠시 후 다시 시도하세요.'))
    } finally {
      setSaving(false)
    }
  }

  const staffOptions = (exclude: number | null) =>
    staff
      .filter((s) => s.active && s.staffId !== exclude)
      .map((s) => ({ value: s.staffId, label: `${s.displayName} (맡은 사건 ${s.openCount}건)` }))

  return (
    <>
      <PageHeader
        title="담당 변경 요청"
        description="보안 직원이 맡은 사건의 담당을 바꿔 달라고 요청한 목록입니다. 처리를 기다리는 동안 그 사건은 판정만 멈추고, 열람 · 조사는 그대로 할 수 있습니다."
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
            setStatus(key as AssignmentRequestStatus)
          }}
          items={tabs.map((tab) => ({ key: tab.key, label: tab.label }))}
        />
        <Table<AssignmentRequest>
          rowKey="requestId"
          size="small"
          loading={rows === null}
          pagination={false}
          // 칸을 한 줄로 두는 대신 좁은 화면에서는 카드 안에서 가로로 넘긴다
          scroll={{ x: 'max-content' }}
          dataSource={rows ?? []}
          columns={[
            { ...oneLine, title: 'Case', dataIndex: 'caseId', render: (id: number) => `#${id}` },
            {
              ...oneLine,
              title: '가맹점',
              render: (_, row) => row.merchantName ?? `ID ${row.merchantId}`,
            },
            {
              ...oneLine,
              title: '사건 상태',
              dataIndex: 'caseStatus',
              render: (value: AssignmentRequest['caseStatus']) => (
                <CodeTag value={caseStatusLabel[value]} />
              ),
            },
            {
              ...oneLine,
              title: '요청 직원',
              dataIndex: 'requestedByName',
              render: (v) => v ?? '-',
            },
            {
              ...oneLine,
              title: '사유',
              dataIndex: 'reason',
              render: (value: AssignmentRequest['reason']) => assignmentReasonLabel[value] ?? value,
            },
            { title: '메모', dataIndex: 'memo', render: textCell },
            { ...oneLine, title: '요청 시각', dataIndex: 'requestedAt', render: dateTime },
            {
              ...oneLine,
              title: '상태',
              dataIndex: 'status',
              render: (value: AssignmentRequestStatus) => (
                <Tag color={statusTag[value].color}>{statusTag[value].label}</Tag>
              ),
            },
            ...(status === 'PENDING'
              ? [
                  {
                    ...oneLine,
                    title: '처리',
                    render: (_: unknown, row: AssignmentRequest) => (
                      <Space size={6}>
                        <Button size="small" type="primary" onClick={() => openApprove(row)}>
                          승인
                        </Button>
                        <Button size="small" danger onClick={() => openReject(row)}>
                          거절
                        </Button>
                      </Space>
                    ),
                  },
                ]
              : [
                  // 거절은 담당이 그대로라 새 담당 칸을 두지 않는다
                  ...(status === 'APPROVED'
                    ? [
                        {
                          ...oneLine,
                          title: '새 담당',
                          render: (_: unknown, row: AssignmentRequest) =>
                            row.newAssigneeName ?? '-',
                        },
                      ]
                    : []),
                  {
                    ...oneLine,
                    title: '처리',
                    render: (_: unknown, row: AssignmentRequest) =>
                      `${row.reviewedByName ?? '-'} · ${row.reviewedAt ? dateTime(row.reviewedAt) : '-'}`,
                  },
                  { title: '처리 메모', dataIndex: 'reviewMemo', render: textCell },
                ]),
          ]}
        />
      </Card>
      <Modal
        title={approving ? `Case #${approving.caseId} 담당 변경 승인` : ''}
        open={approving !== null}
        okText="승인하고 담당 변경"
        cancelText="닫기"
        confirmLoading={saving}
        okButtonProps={{ disabled: newStaffId === null }}
        onOk={() => void approve()}
        onCancel={() => setApproving(null)}
      >
        <Space direction="vertical" style={{ width: '100%' }} size={16}>
          <div>
            <div style={{ marginBottom: 6, fontWeight: 600 }}>새 담당</div>
            <Select
              style={{ width: '100%' }}
              value={newStaffId}
              options={staffOptions(approving?.currentAssigneeStaffId ?? null)}
              placeholder="넘겨받을 활성 직원이 없습니다"
              onChange={setNewStaffId}
            />
            <div style={{ color: '#64748b', fontSize: 12, marginTop: 4 }}>
              자동 배정 순서(맡은 사건이 적은 직원 먼저)의 첫 후보를 미리 골라 뒀습니다. 승인하면 새
              담당이 판정합니다.
            </div>
          </div>
          <Input.TextArea
            value={memo}
            maxLength={200}
            showCount
            rows={3}
            placeholder={approveMemoPlaceholder}
            onChange={(event) => setMemo(event.target.value)}
          />
        </Space>
      </Modal>
      <Modal
        title={rejecting ? `Case #${rejecting.caseId} 담당 변경 거절` : ''}
        open={rejecting !== null}
        okText="거절"
        cancelText="닫기"
        confirmLoading={saving}
        okButtonProps={{ danger: true, disabled: memo.trim().length === 0 }}
        onOk={() => void reject()}
        onCancel={() => setRejecting(null)}
      >
        <p>거절하면 담당은 그대로이고, 멈춰 있던 판정을 지금 담당이 다시 할 수 있습니다.</p>
        <Input.TextArea
          value={memo}
          maxLength={200}
          showCount
          rows={3}
          placeholder="거절 이유 (필수, 사건 이력에 남아 요청한 직원도 봅니다). 개인정보는 적지 마세요."
          onChange={(event) => setMemo(event.target.value)}
        />
      </Modal>
    </>
  )
}
