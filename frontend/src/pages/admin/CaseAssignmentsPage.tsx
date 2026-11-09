import { Alert, App, Button, Modal, Popconfirm, Select, Space, Table, Tag } from 'antd'
import { ListEmpty } from '../../components/common/ListEmpty'
import { ListLoading } from '../../components/common/ListLoading'
import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import {
  backfillUnassigned,
  getAssignmentOverview,
  getAssignmentRequests,
  getStaffCases,
  handOverAll,
  reassignCase,
  staffInNameOrder,
  suggestedStaff,
  type AssignedCase,
  type AssignmentChangeReason,
  type AssignmentOverview,
  type StaffLoad,
} from '../../api/caseAssignments'
import { PageHeader } from '../../components/common/PageHeader'
import { refreshCaseAssignmentRequestBadge } from '../../hooks/useCaseAssignmentRequestBadge'
import { CodeTag } from '../security/case/CodeTag'
import { caseStatusLabel } from '../security/case/caseLabels'
import { errorText, reasonReady } from './caseAssignmentForm'
import { byOldest, canReceive, caseAge, needsHandover, staffState } from './caseAssignmentView'
import { AgeStamp } from './AgeStamp'
import { ReasonFields } from './ReasonFields'
import { StaffOffice } from './StaffOffice'
import { pendingByAssignee } from './assignmentAttention'
import './caseAssignmentShared.css'
import './CaseAssignmentsPage.css'

// 사유 · 메모는 사건 이력에 남아 넘겨받은 직원도 본다 — 조사 사실을 글로 남기지 않게 (#700, ADR-0082 조각 8 후속)
// 왜 넘겼는지는 [내부자 사건] 기록에 남는다
function InsiderMemoNotice() {
  return (
    <Alert
      type="warning"
      showIcon
      message="내부자 조사 중인 직원의 사건입니다"
      description="사유와 메모는 사건을 넘겨받는 직원도 사건 이력에서 봅니다. 조사 중이라는 사실은 적지 마세요. 조사 기록은 [내부자 사건]에 남습니다."
    />
  )
}

// 새 사건을 받는지 — 정상은 조용히, 받지 않는 이유만 글로. 빨강은 "정지인데 사건이 남음" 한 경우만
function AssignState({ staff }: { staff: StaffLoad }) {
  const state = staffState(staff)
  if (state === 'inactive')
    return needsHandover(staff) ? (
      <span className="ca-state is-danger">정지 · 남은 사건을 넘겨야 합니다</span>
    ) : (
      <span className="ca-state is-off">안 받음 · 정지</span>
    )
  if (state === 'insider') return <span className="ca-state is-off">안 받음 · 내부자 조사 중</span>
  return staff.assignOrder === 1 ? (
    <span className="ca-state is-next">받음 · 다음 차례</span>
  ) : (
    <span className="ca-state">받음</span>
  )
}

// 관리자 [사건 담당 › 담당 현황] (#608). 사건은 번호 · 가맹점 · 상태 · 생성 시각만 — 사건 상세로 가는 링크는 두지 않는다 (ADR-0065)
// 화면은 보안팀 사무실(직원 = 책상) 하나. 책상을 누르면 그 직원이 맡은 사건이 아래에 열리고, 거기서 넘긴다
export function CaseAssignmentsPage() {
  const { message } = App.useApp()
  const [searchParams] = useSearchParams()
  const [overview, setOverview] = useState<AssignmentOverview | null>(null)
  const [pendingCounts, setPendingCounts] = useState<Map<number, number> | null>(null)
  const [requestsFailed, setRequestsFailed] = useState(false)
  const detailRef = useRef<HTMLElement>(null)
  const [error, setError] = useState<string | null>(null)
  // 사건 목록은 어느 직원 것인지와 함께 둔다 — 펼친 직원과 다르면 불러오는 중으로 본다
  const [casesOf, setCasesOf] = useState<{
    staffId: number
    rows: AssignedCase[]
    failed: boolean
  } | null>(null)
  const [target, setTarget] = useState<AssignedCase | null>(null)
  const [handover, setHandover] = useState(false)
  const [newStaffId, setNewStaffId] = useState<number | null>(null)
  const [reason, setReason] = useState<AssignmentChangeReason | null>(null)
  const [memo, setMemo] = useState('')
  const [saving, setSaving] = useState(false)
  const [backfilling, setBackfilling] = useState(false)
  // 직원을 빠르게 바꿔 누르면 늦게 온 이전 직원의 사건 목록을 버린다
  const casesSequence = useRef(0)

  // 열린 책상은 화면 상태로 둔다. 주소(?staff=)는 들어올 때 한 번만 읽는다 — 변경 요청 탭의 "자리 보기"로 오는 길.
  // 주소를 바꾸면 ServerSession이 로그인을 다시 확인하느라 본문을 내렸다 다시 그려 화면 상태가 처음으로 돌아간다
  const [selectedId, setSelectedId] = useState<number | null>(() => {
    const id = Number(searchParams.get('staff'))
    return Number.isInteger(id) && id > 0 ? id : null
  })
  // 주소로 책상이 열린 채 들어오면(자리 보기) 그 자리로 스크롤한다 — 사무실 아래라 안 보일 수 있다
  const focusDetail = useRef(selectedId !== null)
  const staffList = overview?.staff ?? []
  const selected = staffList.find((s) => s.staffId === selectedId) ?? null
  const cases = casesOf && casesOf.staffId === selectedId ? casesOf.rows : null
  const casesFailed = cases !== null && casesOf?.failed === true

  const requestsSequence = useRef(0)
  const loadRequests = useCallback(() => {
    const current = ++requestsSequence.current
    getAssignmentRequests('PENDING')
      .then((rows) => {
        if (current !== requestsSequence.current) return
        setPendingCounts(pendingByAssignee(rows))
        setRequestsFailed(false)
      })
      .catch(() => {
        if (current !== requestsSequence.current) return
        setPendingCounts(null)
        setRequestsFailed(true)
      })
  }, [])
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
    getStaffCases(staffId)
      .then((rows) => {
        if (current === casesSequence.current) setCasesOf({ staffId, rows, failed: false })
      })
      .catch(() => {
        if (current === casesSequence.current) setCasesOf({ staffId, rows: [], failed: true })
      })
  }, [])
  useEffect(() => {
    loadOverview()
    loadRequests()
  }, [loadOverview, loadRequests])
  const selectedExists = selected !== null
  useEffect(() => {
    if (selectedId !== null && selectedExists) loadCases(selectedId)
  }, [selectedId, selectedExists, loadCases])

  useEffect(() => {
    if (!selectedExists || !focusDetail.current) return
    focusDetail.current = false
    detailRef.current?.focus({ preventScroll: true })
    detailRef.current?.scrollIntoView({
      behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches
        ? 'instant'
        : 'smooth',
      block: 'start',
    })
  }, [selectedId, selectedExists])

  // 같은 책상을 다시 누르면 닫는다
  const toggle = (staff: StaffLoad) => {
    focusDetail.current = selectedId !== staff.staffId
    setSelectedId(selectedId === staff.staffId ? null : staff.staffId)
  }
  const reload = () => {
    loadOverview()
    loadRequests()
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
    setNewStaffId(suggestedStaff(staffList, selected?.staffId ?? null)?.staffId ?? null)
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
    setBackfilling(true)
    try {
      const { assigned } = await backfillUnassigned()
      message.success(`담당자가 없던 사건 ${assigned}건을 배정했습니다.`)
      reload()
    } catch (e) {
      message.error(errorText(e, '미배정 사건을 배정하지 못했습니다. 잠시 후 다시 시도하세요.'))
    } finally {
      setBackfilling(false)
    }
  }

  const excludedCount = staffList.filter(
    (s) => s.staffId !== selected?.staffId && !canReceive(s, null),
  ).length
  const staffOptions = staffList
    .filter((s) => canReceive(s, selected?.staffId ?? null))
    .map((s) => ({
      value: s.staffId,
      label: `${s.displayName} · 맡은 사건 ${s.openCount}건${s.assignOrder === 1 ? ' · 다음 차례' : ''}`,
    }))
  const totalOpen = staffList.reduce((sum, s) => sum + s.openCount, 0)
  const unassigned = overview?.unassignedOpenCount ?? 0

  // 열린 책상 — 그 직원이 맡은 사건. 오래된 사건이 위
  const renderCases = (staff: StaffLoad) => (
    <div className="ca-cases">
      <div className="ca-cases-head">
        <span>
          맡은 미종결 사건 {cases === null ? '' : <b className="ca-num">{cases.length}건</b>}
          <span className="dw-caption"> · 오래된 순</span>
        </span>
        <Button
          danger
          type={needsHandover(staff) ? 'primary' : 'default'}
          size="small"
          disabled={!cases || cases.length === 0}
          onClick={openHandover}
        >
          모두 다른 직원에게 넘기기
        </Button>
      </div>
      <Table<AssignedCase>
        rowKey="caseId"
        size="small"
        pagination={false}
        scroll={{ x: 640 }}
        dataSource={cases ? [...cases].sort(byOldest) : []}
        locale={{
          emptyText:
            cases === null ? (
              <ListLoading label="맡은 사건을 불러오는 중" rows={2} columns={4} />
            ) : casesFailed ? (
              <ListEmpty
                title="맡은 사건을 불러오지 못했습니다."
                action={
                  <Button
                    onClick={() => {
                      setCasesOf(null)
                      loadCases(staff.staffId)
                    }}
                  >
                    다시 시도
                  </Button>
                }
              />
            ) : (
              <ListEmpty title="맡은 미종결 사건이 없습니다." />
            ),
        }}
        columns={[
          {
            title: 'Case',
            dataIndex: 'caseId',
            width: 90,
            render: (id: number) => <span className="ca-num">#{id}</span>,
          },
          { title: '가맹점', render: (_, row) => row.merchantName ?? `ID ${row.merchantId}` },
          {
            title: '상태',
            dataIndex: 'status',
            render: (status: AssignedCase['status']) => <CodeTag value={caseStatusLabel[status]} />,
          },
          {
            title: '생긴 날',
            dataIndex: 'createdAt',
            render: (createdAt: string) => <AgeStamp text={caseAge(createdAt)} at={createdAt} />,
          },
          {
            title: '직원 요청',
            dataIndex: 'assignmentRequestPending',
            render: (pending: boolean, row: AssignedCase) =>
              pending ? (
                <span className="ca-request-cell">
                  <Tag color="gold">변경 요청 대기</Tag>
                  {/* 거절하거나 사유를 보려면 변경 요청 탭에서 이 사건 요청을 바로 연다 */}
                  <Link to={`/admin/case-assignment-requests?case=${row.caseId}`}>요청 보기</Link>
                </span>
              ) : (
                <span className="dw-caption">—</span>
              ),
          },
          {
            title: <span className="dw-input-sr-only">작업</span>,
            align: 'right',
            width: 120,
            render: (_, row) => (
              <Button size="small" onClick={() => openReassign(row)}>
                담당 변경
              </Button>
            ),
          },
        ]}
      />
    </div>
  )

  return (
    <div className="ca-page">
      <PageHeader
        title="사건 담당 현황"
        description="보안 직원이 FDS 사건을 몇 건씩 맡고 있는지 봅니다. 새 사건은 맡은 사건이 적은 직원에게 자동으로 갑니다. 직원을 누르면 맡은 사건을 보고 다른 직원에게 넘길 수 있습니다."
      />
      {error && <Alert type="error" showIcon message={error} className="dw-page-alert" />}
      {requestsFailed && (
        <Alert
          className="dw-page-alert"
          type="warning"
          showIcon
          message="직원별 변경 요청 건수를 확인하지 못했습니다."
          action={
            <Button size="small" onClick={loadRequests}>
              다시 시도
            </Button>
          }
        />
      )}
      <section className="ca-board ca-panel-box" aria-labelledby="ca-board-heading">
        <header className="ca-board-head">
          <h2 id="ca-board-heading" className="dw-section-title">
            보안팀 사무실
          </h2>
          {overview && (
            <span className="dw-caption">
              보안 직원 {staffList.length}명 · 책상 위 서류 = 맡은 미종결 사건 (합계 {totalOpen}건)
              · 이름순
            </span>
          )}
        </header>
        {!overview ? (
          error ? (
            <ListEmpty title="보안 직원 목록을 불러오지 못했습니다." />
          ) : (
            <ListLoading label="보안 직원을 불러오는 중" rows={2} columns={4} />
          )
        ) : staffList.length === 0 ? (
          <ListEmpty title="보안 직원 계정이 없습니다." mascot />
        ) : (
          <StaffOffice
            staff={staffInNameOrder(staffList)}
            pendingCounts={pendingCounts}
            openStaffId={selected?.staffId ?? null}
            onToggle={toggle}
            unassigned={unassigned}
            unassignedAction={
              <Popconfirm
                title={`담당자가 없는 사건 ${unassigned}건을 배정할까요?`}
                description="자동 배정과 같은 규칙(맡은 사건이 적은 직원 먼저)으로 나눠 줍니다."
                okText="배정"
                cancelText="닫기"
                onConfirm={() => void backfill()}
              >
                <Button type="primary" loading={backfilling}>
                  한 번에 배정
                </Button>
              </Popconfirm>
            }
          />
        )}
      </section>

      {selected ? (
        <section
          ref={detailRef}
          tabIndex={-1}
          className="ca-board ca-panel-box ca-desk-detail"
          aria-labelledby="ca-detail-heading"
        >
          <header className="ca-board-head">
            <h2 id="ca-detail-heading" className="dw-section-title">
              {selected.displayName} 자리
            </h2>
            <AssignState staff={selected} />
          </header>
          {renderCases(selected)}
        </section>
      ) : (
        overview &&
        staffList.length > 0 && (
          <p className="ca-hint dw-caption">
            책상을 누르면 그 직원이 맡은 사건이 여기에 열립니다. 한 건씩 또는 모두 다른 직원에게
            넘길 수 있습니다.
          </p>
        )
      )}

      <Modal
        title={target ? `Case #${target.caseId} 다른 직원에게 넘기기` : ''}
        open={target !== null}
        okText="넘기기"
        cancelText="닫기"
        confirmLoading={saving}
        okButtonProps={{ disabled: newStaffId === null || !reasonReady(reason, memo) }}
        onOk={() => void submitReassign()}
        onCancel={() => setTarget(null)}
      >
        <Space direction="vertical" style={{ width: '100%' }} size={16}>
          {target && (
            <div className="ca-modal-from">
              <span>
                지금 담당 <b>{selected?.displayName}</b>
              </span>
              <span className="dw-caption">
                {target.merchantName ?? `가맹점 ID ${target.merchantId}`} ·{' '}
                {caseStatusLabel[target.status]?.label ?? target.status}
              </span>
            </div>
          )}
          <div>
            <div style={{ marginBottom: 6, fontWeight: 600 }}>넘겨받을 직원</div>
            <Select
              style={{ width: '100%' }}
              value={newStaffId}
              options={staffOptions}
              disabled={staffOptions.length === 0}
              placeholder={
                staffOptions.length === 0
                  ? '넘겨받을 수 있는 직원이 없습니다'
                  : '넘겨받을 직원을 고르세요'
              }
              onChange={setNewStaffId}
            />
            <div className="dw-caption" style={{ marginTop: 4 }}>
              자동 배정 순서(맡은 사건이 적은 직원 먼저)의 첫 후보를 미리 골라 뒀습니다.
              {excludedCount > 0 &&
                ` 정지 · 내부자 조사 중인 직원 ${excludedCount}명은 받을 수 없어 뺐습니다.`}
              {target?.assignmentRequestPending && ' 처리 대기 중인 변경 요청은 함께 승인됩니다.'}
            </div>
          </div>
          {selected?.insiderCaseOpen && <InsiderMemoNotice />}
          <ReasonFields reason={reason} memo={memo} onReason={setReason} onMemo={setMemo} />
        </Space>
      </Modal>
      <Modal
        title={selected ? `${selected.displayName}의 사건 모두 넘기기` : ''}
        open={handover}
        okText={`${cases?.length ?? 0}건 모두 넘기기`}
        okButtonProps={{ danger: true, disabled: !reasonReady(reason, memo) }}
        cancelText="닫기"
        confirmLoading={saving}
        onOk={() => void submitHandover()}
        onCancel={() => setHandover(false)}
      >
        <p>
          미종결 사건 {cases?.length ?? 0}건을 자동 배정 순서(맡은 사건이 적은 직원 먼저)로 받을 수
          있는 다른 직원에게 나눠 넘깁니다. 처리 대기 중인 변경 요청은 함께 승인됩니다.
        </p>
        {selected?.insiderCaseOpen && (
          <div style={{ marginBottom: 16 }}>
            <InsiderMemoNotice />
          </div>
        )}
        <ReasonFields reason={reason} memo={memo} onReason={setReason} onMemo={setMemo} />
      </Modal>
    </div>
  )
}
