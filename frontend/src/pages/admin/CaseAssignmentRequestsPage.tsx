import { Alert, App, Button, Input, Modal, Select, Space, Table, Tabs, Tag } from 'antd'
import { CheckOutlined, ClockCircleOutlined, CloseOutlined } from '@ant-design/icons'
import { ListEmpty } from '../../components/common/ListEmpty'
import { ListLoading } from '../../components/common/ListLoading'
import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
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
import {
  refreshCaseAssignmentRequestBadge,
  useCaseAssignmentRequestCount,
} from '../../hooks/useCaseAssignmentRequestBadge'
import { CodeTag } from '../security/case/CodeTag'
import { caseStatusLabel, dateTime } from '../security/case/caseLabels'
import { approveMemoPlaceholder, errorText } from './caseAssignmentForm'
import { byRequestedOldest, canReceive, waitingDays, waitingText } from './caseAssignmentView'
import { AgeStamp } from './AgeStamp'
import './caseAssignmentShared.css'
import './CaseAssignmentRequestsPage.css'

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
const emptyTitle: Record<AssignmentRequestStatus, string> = {
  PENDING: '처리를 기다리는 요청이 없습니다.',
  APPROVED: '승인한 요청이 없습니다.',
  REJECTED: '거절한 요청이 없습니다.',
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

const merchantOf = (row: AssignmentRequest) => row.merchantName ?? `가맹점 ID ${row.merchantId}`

// 기다린 시간 — 대기 중에는 그 사건 판정이 멈춰 있어 하루를 넘기면 주황으로 보인다
function Waiting({ requestedAt }: { requestedAt: string }) {
  const days = waitingDays(requestedAt)
  return <AgeStamp text={waitingText(days)} at={requestedAt} late={days >= 1} alignEnd />
}

// 승인 · 거절 창 맨 위 — 누가 · 왜 요청했는지 보면서 정한다
function RequestSummary({ row }: { row: AssignmentRequest }) {
  return (
    <div className="car-summary">
      <dl>
        <div>
          <dt>요청 직원</dt>
          <dd>{row.requestedByName ?? '-'}</dd>
        </div>
        <div>
          <dt>지금 담당</dt>
          <dd>{row.currentAssigneeName ?? '-'}</dd>
        </div>
        <div>
          <dt>사건</dt>
          <dd>
            #{row.caseId} · {merchantOf(row)} · {caseStatusLabel[row.caseStatus]?.label ?? '-'}
          </dd>
        </div>
        <div>
          <dt>사유</dt>
          <dd>{assignmentReasonLabel[row.reason] ?? row.reason}</dd>
        </div>
        <div>
          <dt>기다린 시간</dt>
          <dd>{waitingText(waitingDays(row.requestedAt))}</dd>
        </div>
      </dl>
      {row.memo && <p className="car-summary-memo">{row.memo}</p>}
    </div>
  )
}

// 관리자 [사건 담당 › 변경 요청] (#608). 새 담당은 관리자가 고른다 — 요청한 직원은 지목하지 못한다
export function CaseAssignmentRequestsPage() {
  const { message } = App.useApp()
  // 사이드바 배지가 센 대기 건수 — 다른 탭에 있어도 처리 대기 탭에 숫자를 보인다
  const pendingCount = useCaseAssignmentRequestCount()
  // 담당 현황에서 오면 — ?case= 사건 요청 카드를 강조하고 가운데로, ?staff= 그 직원(지금 담당) 요청만 보인다
  // 주소는 들어올 때 한 번만 읽고, 이후 탭 · 거르기는 화면 상태로만 바꾼다.
  // 주소를 바꾸면 ServerSession이 로그인을 다시 확인하느라 본문을 다시 그려 탭이 [처리 대기]로 돌아간다
  const [searchParams] = useSearchParams()
  const focusCaseId = Number(searchParams.get('case')) || null
  const [staffFilterId, setStaffFilterId] = useState<number | null>(
    () => Number(searchParams.get('staff')) || null,
  )
  const clearStaffFilter = () => setStaffFilterId(null)
  const focusRef = useRef<HTMLLIElement>(null)
  const [status, setStatus] = useState<AssignmentRequestStatus>('PENDING')
  const [rows, setRows] = useState<AssignmentRequest[] | null>(null)
  const [staff, setStaff] = useState<StaffLoad[]>([])
  const [staffFailed, setStaffFailed] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [approving, setApproving] = useState<AssignmentRequest | null>(null)
  const [rejecting, setRejecting] = useState<AssignmentRequest | null>(null)
  const [newStaffId, setNewStaffId] = useState<number | null>(null)
  const [memo, setMemo] = useState('')
  const [saving, setSaving] = useState(false)
  // 방금 승인 · 거절한 요청 — 결과 탭에서 그 줄을 강조한다
  const [justHandled, setJustHandled] = useState<number | null>(null)
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
      .then((overview) => {
        setStaff(overview.staff)
        setStaffFailed(false)
      })
      .catch(() => {
        setStaff([])
        setStaffFailed(true)
      })
  }, [status])
  useEffect(() => {
    reload()
  }, [reload])

  useEffect(() => {
    if (status === 'PENDING' && rows && focusCaseId !== null)
      focusRef.current?.scrollIntoView({ block: 'center' })
  }, [status, rows, focusCaseId])

  const openApprove = (row: AssignmentRequest) => {
    setMemo('')
    setNewStaffId(suggestedStaff(staff, row.currentAssigneeStaffId)?.staffId ?? null)
    setApproving(row)
  }
  const openReject = (row: AssignmentRequest) => {
    setMemo('')
    setRejecting(row)
  }
  const switchTab = (next: AssignmentRequestStatus) => {
    setError(null)
    // 이전 탭의 행을 새 탭 결과처럼 잠깐 보여 주지 않는다
    sequence.current += 1
    setRows(null)
    setStatus(next)
    if (staffFilterId !== null) clearStaffFilter()
  }
  // 처리하면 결과 탭(승인 · 거절)으로 옮기고 방금 처리한 줄을 강조한다 — 처리가 어디로 갔는지 바로 보이게.
  // 남은 대기 요청은 [처리 대기] 탭 숫자로 보인다
  const done = (text: string, row: AssignmentRequest, next: AssignmentRequestStatus) => {
    message.success(text)
    setApproving(null)
    setRejecting(null)
    setJustHandled(row.requestId)
    if (status === next) reload()
    else switchTab(next)
    refreshCaseAssignmentRequestBadge()
  }
  const approve = async () => {
    if (!approving || newStaffId === null) return
    setSaving(true)
    try {
      await approveAssignmentRequest(approving.requestId, newStaffId, memo)
      done(`Case #${approving.caseId}의 담당을 바꿨습니다.`, approving, 'APPROVED')
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
      done(
        `요청을 거절했습니다. Case #${rejecting.caseId}는 지금 담당이 다시 판정할 수 있습니다.`,
        rejecting,
        'REJECTED',
      )
    } catch (e) {
      message.error(errorText(e, '요청을 거절하지 못했습니다. 잠시 후 다시 시도하세요.'))
    } finally {
      setSaving(false)
    }
  }

  const approveOptions = staff
    .filter((s) => canReceive(s, approving?.currentAssigneeStaffId ?? null))
    .map((s) => ({
      value: s.staffId,
      label: `${s.displayName} · 맡은 사건 ${s.openCount}건${s.assignOrder === 1 ? ' · 다음 차례' : ''}`,
    }))

  const loadingOrEmpty =
    rows === null ? (
      <ListLoading label="변경 요청을 불러오는 중" />
    ) : (
      <ListEmpty title={emptyTitle[status]} />
    )

  // 직원 거르기는 처리 대기 탭에만 — 책상의 "변경 요청 N건"과 같은 기준(지금 담당)으로 거른다
  const pendingRows =
    rows && staffFilterId !== null
      ? rows.filter((row) => row.currentAssigneeStaffId === staffFilterId)
      : rows
  const filterName =
    staff.find((s) => s.staffId === staffFilterId)?.displayName ??
    rows?.find((row) => row.currentAssigneeStaffId === staffFilterId)?.currentAssigneeName ??
    '이 직원'
  const staffFilterBar = staffFilterId !== null && (
    <div className="car-filter" role="status">
      <span>
        <b>{filterName}</b>의 요청만 보는 중
      </span>
      <Button size="small" onClick={clearStaffFilter}>
        전체 요청 보기
      </Button>
    </div>
  )
  const oldestDays = Math.max(0, ...(pendingRows ?? []).map((row) => waitingDays(row.requestedAt)))
  // 처리 대기 — 받은편지함처럼 한 줄 카드. 오래 기다린 요청이 위
  const pendingList =
    pendingRows === null ? (
      loadingOrEmpty
    ) : pendingRows.length === 0 ? (
      staffFilterId !== null ? (
        <ListEmpty
          title={`${filterName}의 처리할 요청이 없습니다.`}
          action={<Button onClick={clearStaffFilter}>전체 요청 보기</Button>}
        />
      ) : (
        loadingOrEmpty
      )
    ) : (
      <>
        <div className="car-pending-head ca-waiting-box" role="status">
          <ClockCircleOutlined aria-hidden="true" />
          <div>
            <strong>
              처리를 기다리는 요청 {pendingRows.length}건
              {oldestDays >= 1 && ` · 가장 오래된 요청 ${oldestDays}일째`}
            </strong>
            <span>대기 중에는 그 사건의 판정이 멈춰 있습니다. 승인하거나 거절해 주세요.</span>
          </div>
        </div>
        <ul className="car-inbox">
          {[...pendingRows].sort(byRequestedOldest).map((row) => (
            <li
              key={row.requestId}
              ref={row.caseId === focusCaseId ? focusRef : undefined}
              className={`car-item${waitingDays(row.requestedAt) >= 1 ? ' is-late' : ''}${
                row.caseId === focusCaseId ? ' is-focus' : ''
              }`}
            >
              <div className="car-main">
                <p className="car-title">
                  <b>{row.requestedByName ?? '보안 직원'}</b>이 <b>Case #{row.caseId}</b> 담당을
                  바꿔 달라고 요청했습니다
                </p>
                <div className="car-meta">
                  <span>{merchantOf(row)}</span>
                  <CodeTag value={caseStatusLabel[row.caseStatus]} />
                  <span>
                    사유 <b>{assignmentReasonLabel[row.reason] ?? row.reason}</b>
                  </span>
                  {/* 지금 담당 직원의 책상 — 다른 사건은 얼마나 맡았는지 보고 정할 수 있다 */}
                  {row.currentAssigneeStaffId !== null && (
                    <Link to={`/admin/case-assignments?staff=${row.currentAssigneeStaffId}`}>
                      {row.currentAssigneeName ?? '지금 담당'} 자리 보기
                    </Link>
                  )}
                </div>
                {row.memo && <p className="car-memo">{row.memo}</p>}
              </div>
              <div className="car-side">
                <Waiting requestedAt={row.requestedAt} />
                {/* 승인 = 초록 채움, 거절 = 옅은 빨강. 색 + 아이콘으로 한눈에 구분 */}
                <div className="car-actions">
                  <Button
                    className="car-btn is-reject"
                    icon={<CloseOutlined />}
                    onClick={() => openReject(row)}
                  >
                    거절
                  </Button>
                  <Button
                    className="car-btn is-approve"
                    icon={<CheckOutlined />}
                    type="primary"
                    onClick={() => openApprove(row)}
                  >
                    승인
                  </Button>
                </div>
              </div>
            </li>
          ))}
        </ul>
      </>
    )

  return (
    <div className="ca-page">
      <PageHeader
        title="담당 변경 요청"
        description="보안 직원이 맡은 사건의 담당을 바꿔 달라고 요청한 목록입니다. 처리를 기다리는 동안 그 사건은 판정만 멈추고, 열람 · 조사는 그대로 할 수 있습니다."
      />
      {error && <Alert type="error" showIcon message={error} className="dw-page-alert" />}
      <section className="car-board ca-panel-box">
        <Tabs
          activeKey={status}
          onChange={(key) => {
            setJustHandled(null)
            switchTab(key as AssignmentRequestStatus)
          }}
          items={tabs.map((tab) => ({
            key: tab.key,
            label:
              tab.key === 'PENDING' && pendingCount ? (
                <span className="car-tab">
                  {tab.label} <span className="car-tab-count">{pendingCount}</span>
                </span>
              ) : (
                tab.label
              ),
          }))}
        />
        {status === 'PENDING' ? (
          <>
            {staffFilterBar}
            {pendingList}
          </>
        ) : (
          // 승인 · 거절은 지난 기록 — 표로 훑어본다
          <Table<AssignmentRequest>
            rowKey="requestId"
            rowClassName={(row) => (row.requestId === justHandled ? 'car-row-handled' : '')}
            size="small"
            pagination={false}
            // 칸을 한 줄로 두는 대신 좁은 화면에서는 카드 안에서 가로로 넘긴다
            scroll={{ x: 'max-content' }}
            dataSource={rows ?? []}
            locale={{ emptyText: loadingOrEmpty }}
            columns={[
              { ...oneLine, title: 'Case', dataIndex: 'caseId', render: (id: number) => `#${id}` },
              { ...oneLine, title: '가맹점', render: (_, row) => merchantOf(row) },
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
                render: (value: AssignmentRequest['reason']) =>
                  assignmentReasonLabel[value] ?? value,
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
              // 거절은 담당이 그대로라 새 담당 칸을 두지 않는다
              ...(status === 'APPROVED'
                ? [
                    {
                      ...oneLine,
                      title: '새 담당',
                      render: (_: unknown, row: AssignmentRequest) => row.newAssigneeName ?? '-',
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
            ]}
          />
        )}
      </section>
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
          {approving && <RequestSummary row={approving} />}
          <div>
            <div style={{ marginBottom: 6, fontWeight: 600 }}>새 담당</div>
            {staffFailed && (
              <Alert
                type="error"
                showIcon
                style={{ marginBottom: 8 }}
                message="직원 목록을 불러오지 못했습니다. 창을 닫고 잠시 후 다시 시도하세요."
              />
            )}
            <Select
              style={{ width: '100%' }}
              value={newStaffId}
              options={approveOptions}
              disabled={approveOptions.length === 0}
              placeholder={
                approveOptions.length === 0
                  ? '넘겨받을 수 있는 직원이 없습니다'
                  : '넘겨받을 직원을 고르세요'
              }
              onChange={setNewStaffId}
            />
            <div className="dw-caption" style={{ marginTop: 4 }}>
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
        <Space direction="vertical" style={{ width: '100%' }} size={16}>
          {rejecting && <RequestSummary row={rejecting} />}
          <p style={{ margin: 0 }}>
            거절하면 담당은 그대로이고, 멈춰 있던 판정을 지금 담당이 다시 할 수 있습니다.
          </p>
          <Input.TextArea
            value={memo}
            maxLength={200}
            showCount
            rows={3}
            placeholder="거절 이유 (필수, 사건 이력에 남아 요청한 직원도 봅니다). 개인정보는 적지 마세요."
            onChange={(event) => setMemo(event.target.value)}
          />
        </Space>
      </Modal>
    </div>
  )
}
