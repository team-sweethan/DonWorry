import { Alert, App, Button, Card, Empty, Modal, Select, Space, Table, Tag } from 'antd'
import { useCallback, useEffect, useRef, useState } from 'react'
import {
  backfillUnassigned,
  getAssignmentOverview,
  getStaffCases,
  handOverAll,
  reassignCase,
  suggestedStaff,
  type AssignedCase,
  type AssignmentChangeReason,
  type AssignmentOverview,
  type StaffLoad,
} from '../../api/caseAssignments'
import { PageHeader } from '../../components/common/PageHeader'
import { refreshCaseAssignmentRequestBadge } from '../../hooks/useCaseAssignmentRequestBadge'
import { CodeTag } from '../security/case/CodeTag'
import { caseStatusLabel, dateTime } from '../security/case/caseLabels'
import { errorText, reasonReady } from './caseAssignmentForm'
import { ReasonFields } from './ReasonFields'

function StaffCard({
  staff,
  selected,
  onSelect,
}: {
  staff: StaffLoad
  selected: boolean
  onSelect: () => void
}) {
  return (
    <Card
      hoverable
      size="small"
      onClick={onSelect}
      style={{
        width: 180,
        borderColor: selected ? '#2563EB' : '#E2E8F0',
        background: selected ? '#EFF6FF' : undefined,
        opacity: staff.active ? 1 : 0.6,
      }}
    >
      <div style={{ fontWeight: 600 }}>{staff.displayName}</div>
      <div style={{ fontSize: 24, fontWeight: 700, margin: '4px 0' }}>{staff.openCount}건</div>
      <div style={{ color: '#64748b', fontSize: 12 }}>맡은 미종결 사건</div>
      <div style={{ marginTop: 6 }}>
        {!staff.active && <Tag>정지</Tag>}
        {staff.assignOrder === 1 && <Tag color="blue">다음 배정 1순위</Tag>}
      </div>
    </Card>
  )
}

// 관리자 [사건 담당 › 담당 현황] (#608). 사건은 번호 · 가맹점 · 상태 · 생성 시각만 — 사건 상세로 가는 링크는 두지 않는다 (ADR-0065)
export function CaseAssignmentsPage() {
  const { message } = App.useApp()
  const [overview, setOverview] = useState<AssignmentOverview | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [selected, setSelected] = useState<StaffLoad | null>(null)
  const [cases, setCases] = useState<AssignedCase[] | null>(null)
  const [target, setTarget] = useState<AssignedCase | null>(null)
  const [handover, setHandover] = useState(false)
  const [newStaffId, setNewStaffId] = useState<number | null>(null)
  const [reason, setReason] = useState<AssignmentChangeReason | null>(null)
  const [memo, setMemo] = useState('')
  const [saving, setSaving] = useState(false)
  // 직원을 빠르게 바꿔 누르면 늦게 온 이전 직원의 사건 목록을 버린다
  const casesSequence = useRef(0)

  const loadOverview = useCallback(() => {
    getAssignmentOverview()
      .then((next) => {
        setOverview(next)
        setError(null)
      })
      .catch(() => setError('담당 현황을 불러오지 못했습니다. 잠시 후 다시 시도하세요.'))
  }, [])
  const loadCases = useCallback((staffId: number) => {
    const current = ++casesSequence.current
    setCases(null)
    getStaffCases(staffId)
      .then((next) => {
        if (current === casesSequence.current) setCases(next)
      })
      .catch(() => {
        if (current !== casesSequence.current) return
        setCases([])
        setError('직원이 맡은 사건을 불러오지 못했습니다. 잠시 후 다시 시도하세요.')
      })
  }, [])
  useEffect(() => {
    loadOverview()
  }, [loadOverview])

  const select = (staff: StaffLoad) => {
    setSelected(staff)
    loadCases(staff.staffId)
  }
  const reload = () => {
    loadOverview()
    if (selected) loadCases(selected.staffId)
    // 담당 변경 · 넘기기는 그 사건의 대기 요청을 함께 승인하므로 배지도 다시 센다
    refreshCaseAssignmentRequestBadge()
  }
  const resetForm = () => {
    setReason(null)
    setMemo('')
  }
  const openReassign = (row: AssignedCase) => {
    resetForm()
    setTarget(row)
    setNewStaffId(suggestedStaff(overview?.staff ?? [], selected?.staffId ?? null)?.staffId ?? null)
  }
  const openHandover = () => {
    resetForm()
    setHandover(true)
  }

  const submitReassign = async () => {
    if (!target || newStaffId === null || reason === null) return
    setSaving(true)
    try {
      await reassignCase(target.caseId, newStaffId, reason, memo)
      message.success(`Case #${target.caseId}의 담당을 바꿨습니다.`)
      setTarget(null)
      reload()
    } catch (e) {
      message.error(errorText(e, '담당을 바꾸지 못했습니다. 잠시 후 다시 시도하세요.'))
    } finally {
      setSaving(false)
    }
  }
  const submitHandover = async () => {
    if (!selected || reason === null) return
    setSaving(true)
    try {
      const { moved } = await handOverAll(selected.staffId, reason, memo)
      message.success(`${selected.displayName}의 사건 ${moved}건을 다른 직원에게 넘겼습니다.`)
      setHandover(false)
      reload()
    } catch (e) {
      message.error(errorText(e, '사건을 넘기지 못했습니다. 잠시 후 다시 시도하세요.'))
    } finally {
      setSaving(false)
    }
  }
  const backfill = async () => {
    try {
      const { assigned } = await backfillUnassigned()
      message.success(`담당자가 없던 사건 ${assigned}건을 배정했습니다.`)
      reload()
    } catch (e) {
      message.error(errorText(e, '미배정 사건을 배정하지 못했습니다. 잠시 후 다시 시도하세요.'))
    }
  }

  const staffOptions = (overview?.staff ?? [])
    .filter((s) => s.active && s.staffId !== selected?.staffId)
    .map((s) => ({ value: s.staffId, label: `${s.displayName} (맡은 사건 ${s.openCount}건)` }))

  return (
    <>
      <PageHeader
        title="사건 담당 현황"
        description="보안 직원마다 맡고 있는 미종결 FDS 사건 수입니다. 직원을 누르면 맡은 사건을 보고 담당을 바꿀 수 있습니다. 새 사건은 맡은 사건이 적은 직원에게 자동으로 배정됩니다."
      />
      {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 16 }} />}
      {overview && overview.unassignedOpenCount > 0 && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          message={`담당자가 없는 미종결 사건이 ${overview.unassignedOpenCount}건 있습니다.`}
          description="자동 배정을 켜기 전에 만들어졌거나, 만들 때 활성 보안 직원이 없던 사건입니다. 자동 배정과 같은 규칙으로 한 번에 배정합니다."
          action={<Button onClick={() => void backfill()}>미배정 사건 배정</Button>}
        />
      )}
      <Card
        title={<span className="dw-section-title">보안 직원</span>}
        style={{ marginBottom: 20 }}
      >
        {overview && overview.staff.length === 0 ? (
          <Empty description="보안 직원 계정이 없습니다." />
        ) : (
          <Space wrap size={12}>
            {(overview?.staff ?? []).map((staff) => (
              <StaffCard
                key={staff.staffId}
                staff={staff}
                selected={selected?.staffId === staff.staffId}
                onSelect={() => select(staff)}
              />
            ))}
          </Space>
        )}
      </Card>
      {selected && (
        <Card
          title={
            <span className="dw-section-title">
              {selected.displayName}이(가) 맡은 미종결 사건 ({cases?.length ?? 0}건)
            </span>
          }
          extra={
            <Button danger disabled={!cases || cases.length === 0} onClick={openHandover}>
              모두 다른 직원에게 넘기기
            </Button>
          }
        >
          <Table<AssignedCase>
            rowKey="caseId"
            size="small"
            loading={cases === null}
            pagination={false}
            dataSource={cases ?? []}
            locale={{ emptyText: <Empty description="맡은 미종결 사건이 없습니다." /> }}
            columns={[
              { title: 'Case', dataIndex: 'caseId', render: (id: number) => `#${id}` },
              {
                title: '가맹점',
                render: (_, row) => row.merchantName ?? `ID ${row.merchantId}`,
              },
              {
                title: '상태',
                dataIndex: 'status',
                render: (status: AssignedCase['status']) => (
                  <CodeTag value={caseStatusLabel[status]} />
                ),
              },
              { title: '생성 시각', dataIndex: 'createdAt', render: dateTime },
              {
                title: '변경 요청',
                dataIndex: 'assignmentRequestPending',
                render: (pending: boolean) => (pending ? <Tag color="gold">처리 대기</Tag> : '-'),
              },
              {
                title: '',
                render: (_, row) => (
                  <Button size="small" onClick={() => openReassign(row)}>
                    담당 변경
                  </Button>
                ),
              },
            ]}
          />
        </Card>
      )}
      <Modal
        title={target ? `Case #${target.caseId} 담당 변경` : ''}
        open={target !== null}
        okText="담당 변경"
        cancelText="닫기"
        confirmLoading={saving}
        okButtonProps={{ disabled: newStaffId === null || !reasonReady(reason, memo) }}
        onOk={() => void submitReassign()}
        onCancel={() => setTarget(null)}
      >
        <Space direction="vertical" style={{ width: '100%' }} size={16}>
          <div>
            <div style={{ marginBottom: 6, fontWeight: 600 }}>새 담당</div>
            <Select
              style={{ width: '100%' }}
              value={newStaffId}
              options={staffOptions}
              placeholder="넘겨받을 활성 직원이 없습니다"
              onChange={setNewStaffId}
            />
            <div style={{ color: '#64748b', fontSize: 12, marginTop: 4 }}>
              자동 배정 순서(맡은 사건이 적은 직원 먼저)의 첫 후보를 미리 골라 뒀습니다.
              {target?.assignmentRequestPending && ' 처리 대기 중인 변경 요청은 함께 승인됩니다.'}
            </div>
          </div>
          <ReasonFields reason={reason} memo={memo} onReason={setReason} onMemo={setMemo} />
        </Space>
      </Modal>
      <Modal
        title={selected ? `${selected.displayName}의 사건 모두 넘기기` : ''}
        open={handover}
        okText="모두 넘기기"
        okButtonProps={{ danger: true, disabled: !reasonReady(reason, memo) }}
        cancelText="닫기"
        confirmLoading={saving}
        onOk={() => void submitHandover()}
        onCancel={() => setHandover(false)}
      >
        <p>
          미종결 사건 {cases?.length ?? 0}건을 자동 배정 순서(맡은 사건이 적은 직원 먼저)로 나머지
          직원에게 나눠 넘깁니다. 처리 대기 중인 변경 요청은 함께 승인됩니다.
        </p>
        <ReasonFields reason={reason} memo={memo} onReason={setReason} onMemo={setMemo} />
      </Modal>
    </>
  )
}
