import { useCallback, useEffect, useState } from 'react'
import { ArrowLeftOutlined, ReloadOutlined } from '@ant-design/icons'
import { Alert, App, Button, Card, Descriptions, Space, Spin, Table, Tag } from 'antd'
import { useLocation, useNavigate, useParams } from 'react-router-dom'
import {
  CaseVersionConflictError,
  getCase,
  isCaseAssigneeOnly,
  recheckHandoff,
  requestAssignmentChange,
  requestReport,
  submitDecisions,
} from '../../api/cases'
import type { AssignmentChangeReason } from '../../api/caseAssignments'
import { ApiError } from '../../api/client'
import { useAuth } from '../../auth/useAuth'
import { getMerchantDirectory } from '../../api/merchants'
import { useFeedback } from '../../components/common/Feedback'
import { PageHeader } from '../../components/common/PageHeader'
import { ScreenResult } from '../../components/common/ScreenResult'
import { refreshCaseBadge } from '../../hooks/useSecurityCaseBadge'
import type { CaseDetail, CasePayment, SubmitDecisionsRequest } from '../../types/case'
import {
  ASSIGNMENT_PENDING_MESSAGE,
  SLA_HOURS,
  canRequestAssignmentChange,
  canViewContacts,
  caseDecisionMode,
  customerPart,
  latestReport,
  mergeRemainingMin,
} from '../../utils/caseRules'
import { customerCanceledClosure, customerClosureNote } from '../../utils/caseHistoryText'
import { caseListReturnPath } from '../../utils/caseListQuery'
import { customerTransactionsPath } from '../../utils/transactionList'
import { POLL_LIMIT_MS, POLL_MS, pollStep } from '../../utils/casePolling'
import { won } from '../../utils/format'
import { AssignmentRequestModal } from './case/AssignmentRequestModal'
import { CaseForbidden } from './case/CaseForbidden'
import { ContactCard } from './case/ContactCard'
import { HandoffCard, HistoryCard } from './case/CaseActivity'
import { CaseBundle } from './case/CaseBundle'
import { CaseProgress } from './case/CaseProgress'
import {
  caseStatusLabel,
  caseTypeLabel,
  dateTime,
  fdsResultLabel,
  paymentStatusLabel,
} from './case/caseLabels'
import { DeadlineValue, HelpLabel, deadlineHelp, mergeHelp } from './case/CaseTimeLabels'
import { CodeTag } from './case/CodeTag'
import { DecisionPanel } from './case/DecisionPanel'
import { ReportCard } from './case/ReportCard'

// 제목 아래 한 줄: 가맹점 이름(ID) · 고객 · 유형 (#428)
// 고객 참조는 결제 쪽 내부 형식 "가맹점ID:고객"이라 고객 부분만 보인다
function caseHeaderLine(
  securityCase: CaseDetail['securityCase'],
  merchantName: string | undefined,
): string {
  const customer = customerPart(securityCase.customerRef)
  const merchant = merchantName
    ? `${merchantName} (ID ${securityCase.merchantId})`
    : `ID ${securityCase.merchantId}`
  const type = caseTypeLabel[securityCase.caseType] ?? securityCase.caseType
  return `가맹점 ${merchant} · 고객 ${customer} · ${type}`
}

export function CaseDetailPage() {
  const { id = '' } = useParams()
  const navigate = useNavigate()
  // 목록에서 왔으면 그 조건의 목록으로 돌아간다 (#243)
  const listPath = caseListReturnPath(useLocation().state)
  const { toast } = useFeedback()
  // useFeedback()은 렌더마다 새 객체라 effect 의존성에는 App이 주는 message를 쓴다
  const { message } = App.useApp()
  const [refreshKey, setRefreshKey] = useState(0)
  const [recheckingId, setRecheckingId] = useState<string | null>(null)
  const { user } = useAuth()
  const [assignmentRequestOpen, setAssignmentRequestOpen] = useState(false)
  // 제목 아래 가맹점 이름 (#428). 경보 화면과 같은 가맹점 목록을 쓴다. 못 받으면 ID만 보인다
  const [merchantNames, setMerchantNames] = useState<Map<string, string>>(new Map())
  useEffect(() => {
    let alive = true
    getMerchantDirectory()
      .then((merchants) => {
        if (alive)
          setMerchantNames(new Map(merchants.map((m) => [String(m.merchantId), m.displayName])))
      })
      .catch(() => {})
    return () => {
      alive = false
    }
  }, [])
  // 응답을 조회 범위(Case)와 함께 저장해, 범위가 바뀌면 이전 Case를 즉시 숨기고
  // 늦게 도착한 이전 범위의 응답은 버린다.
  const scope = id
  // detail: null은 404(Case 없음), 'error'는 네트워크·5xx 등 일시 장애 — 404 화면으로 단정하지 않는다
  // 'forbidden'은 담당이 아닌 진행 중 사건 (#612). 판정 등이 담당 변경으로 거부된 뒤 다시 불러올 때도 여기로 온다
  const [loaded, setLoaded] = useState<{
    scope: string
    detail: CaseDetail | null | 'error' | 'forbidden'
  } | null>(null)
  useEffect(() => {
    let alive = true
    getCase(id)
      .then((result) => {
        if (alive) setLoaded({ scope: id, detail: result })
      })
      .catch((error: unknown) => {
        if (!alive) return
        if (isCaseAssigneeOnly(error)) {
          setLoaded({ scope: id, detail: 'forbidden' })
          return
        }
        message.error('Case를 불러오지 못했습니다. 잠시 후 다시 시도하세요.')
        setLoaded({ scope: id, detail: 'error' })
      })
    return () => {
      alive = false
    }
  }, [id, refreshKey, message])
  const result = loaded?.scope === scope ? loaded.detail : undefined
  const detail = result === 'error' || result === 'forbidden' ? null : result
  // 자동 조회 제한 시간의 시작점. null이면 다음 자동 조회부터 다시 센다 (#238)
  const [poll, setPoll] = useState<{ scope: string; from: number; stopped: boolean } | null>(null)
  // 사용자 동작으로 다시 조회할 때는 자동 조회 제한 시간도 처음부터 다시 센다
  const load = useCallback(() => {
    setPoll(null)
    setRefreshKey((k) => k + 1)
  }, [])
  const [visible, setVisible] = useState(() => document.visibilityState === 'visible')
  useEffect(() => {
    const onVisibility = () => {
      const now = document.visibilityState === 'visible'
      setVisible(now)
      // 탭으로 돌아오면 자리를 비운 사이 바뀐 상태를 한 번 반영한다
      if (now) load()
    }
    document.addEventListener('visibilitychange', onVisibility)
    return () => document.removeEventListener('visibilitychange', onVisibility)
  }, [load])

  const waiting =
    !!detail &&
    (detail.reports.some((r) => r.status === 'PENDING' || r.status === 'RUNNING') ||
      detail.handoffs.some((h) => h.status === 'REQUESTED'))
  const current = poll?.scope === scope ? poll : null
  const pollStopped = current?.stopped === true
  useEffect(() => {
    if (!waiting || !visible || pollStopped) return
    const timer = setTimeout(() => {
      const step = pollStep(current?.from ?? null, Date.now())
      setPoll({ scope, from: step.from, stopped: !step.poll })
      if (step.poll) setRefreshKey((k) => k + 1)
    }, POLL_MS)
    return () => clearTimeout(timer)
  }, [waiting, visible, pollStopped, current?.from, scope, detail])

  if (result === undefined) return <Spin style={{ display: 'block', margin: '120px auto' }} />
  if (result === 'forbidden') return <CaseForbidden listPath={listPath} />
  if (result === 'error')
    return (
      <ScreenResult
        kind="500"
        action={
          <Button type="primary" onClick={load}>
            다시 시도
          </Button>
        }
      />
    )
  if (detail === null || detail === undefined) return <ScreenResult kind="404" />

  const { securityCase, payments, reports, decisions, handoffs, history } = detail
  const customerClosedAt = customerCanceledClosure(history)
  // 고객이 승인을 취소한 결제는 판정 대상이 아니다 (#382). 기존 판정이 있으면 그대로 보인다
  const decidable = payments.filter((p) => !p.customerCanceledAt)
  const decidedCount = decisions.filter((d) =>
    decidable.some((p) => p.casePaymentId === d.casePaymentId),
  ).length
  const mergeRemaining = mergeRemainingMin(securityCase.createdAt, decisions.length)
  // 담당 변경 요청이 대기 중이면 판정 입력도 막는다 (#608)
  const mode = caseDecisionMode(securityCase, reports)
  const canRequestChange = canRequestAssignmentChange(user?.userId ?? null, securityCase)
  const contactsVisible = canViewContacts(user?.userId ?? null, securityCase)

  const handleSubmit = async (request: SubmitDecisionsRequest) => {
    try {
      await submitDecisions(securityCase.caseId, request)
      refreshCaseBadge()
      toast.success('판정을 확정했습니다. 실제 매입·취소 결과를 기다리고 있습니다.')
      load()
      return true
    } catch (error) {
      toast.error(
        error instanceof CaseVersionConflictError
          ? '검토 중 Case에 결제가 추가되었습니다. 변경 내용을 다시 확인한 뒤 판정하세요.'
          : error instanceof ApiError && error.detail
            ? error.detail
            : '판정을 저장하지 못했습니다. 잠시 후 다시 시도하세요.',
      )
      load()
      return false
    }
  }

  const handleRecheck = async (handoffId: string) => {
    setRecheckingId(handoffId)
    try {
      const after = await recheckHandoff(securityCase.caseId, handoffId)
      refreshCaseBadge()
      const handoff = after.handoffs.find((h) => h.handoffId === handoffId)
      if (handoff?.status === 'REQUESTED') {
        toast.info('아직 금융 처리 대기 중입니다. P5 확인이 필요하면 결제·조치 정보를 전달하세요.')
      } else {
        toast.success('금융 처리 결과를 반영했습니다.')
      }
    } catch (error) {
      toast.error(
        error instanceof ApiError && error.detail
          ? error.detail
          : '인계 상태를 확인하지 못했습니다. 잠시 후 다시 시도하세요.',
      )
    } finally {
      setRecheckingId(null)
    }
    load()
  }

  const handleAssignmentRequest = async (reason: AssignmentChangeReason, memo: string) => {
    await requestAssignmentChange(securityCase.caseId, reason, memo)
    setAssignmentRequestOpen(false)
    toast.success('담당 변경을 요청했습니다. 관리자가 처리할 때까지 이 사건의 판정은 멈춥니다.')
    load()
  }

  const handleRequestReport = async () => {
    try {
      await requestReport(securityCase.caseId)
      toast.info('AI 보고서를 다시 요청했습니다.')
    } catch {
      toast.error('AI 보고서를 요청하지 못했습니다. 잠시 후 다시 시도하세요.')
    }
    load()
  }
  // 현재 증거 버전 보고서가 없을 때만 다시 요청할 수 있다 — 백엔드 requestMissing과 같은 조건
  // (목 보고서가 서버 재시작으로 비었거나 커밋 뒤 보고서 요청이 실패한 경우)
  // 이전 버전 보고서가 생성 중이어도 현재 버전 요청이 없으면 복구할 수 있어야 한다
  const latest = latestReport(reports)
  const reportMissing =
    securityCase.status !== 'CLOSED' &&
    (!latest || latest.evidenceVersion < securityCase.evidenceVersion)
  // 사건 밖의 이 손님 결제까지 거래 내역에서 바로 본다 (#487)
  const customerPath = customerTransactionsPath(securityCase.merchantId, securityCase.customerRef)

  return (
    <>
      <PageHeader
        title={`Case #${securityCase.caseId}`}
        description={caseHeaderLine(securityCase, merchantNames.get(securityCase.merchantId))}
        actions={
          <Space>
            {reportMissing && <Button onClick={handleRequestReport}>AI 보고서 다시 요청</Button>}
            {canRequestChange && (
              <Button onClick={() => setAssignmentRequestOpen(true)}>담당 변경 요청</Button>
            )}
            {customerPath && (
              <Button onClick={() => navigate(customerPath)}>이 고객 거래 내역 보기</Button>
            )}
            <Button icon={<ReloadOutlined />} onClick={load}>
              새로고침
            </Button>
            <Button icon={<ArrowLeftOutlined />} onClick={() => navigate(listPath)}>
              목록으로
            </Button>
          </Space>
        }
      />
      {/* 고객이 모든 결제의 승인을 취소해 닫힌 Case (#382 · #443) — 이력까지 내려가지 않아도 이유가 보이게 한다 */}
      {customerClosedAt && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message={`고객이 모든 결제의 승인을 취소해 Case가 종료됐습니다 (${dateTime(customerClosedAt)}).`}
          description={customerClosureNote(decisions.length)}
        />
      )}
      {securityCase.assignmentChangePending && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          message={ASSIGNMENT_PENDING_MESSAGE}
          description="열람 · 조사는 그대로 할 수 있습니다. 관리자가 승인하면 새 담당이 판정하고, 거절하면 지금 담당이 다시 판정합니다."
        />
      )}
      <AssignmentRequestModal
        open={assignmentRequestOpen}
        caseId={securityCase.caseId}
        onClose={() => setAssignmentRequestOpen(false)}
        onSubmit={handleAssignmentRequest}
      />
      {waiting && pollStopped && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message={`자동 갱신을 멈췄습니다 (${POLL_LIMIT_MS / 60_000}분 경과). 최신 상태는 새로고침 또는 "인계 상태 다시 확인"으로 확인하세요.`}
          action={
            <Button size="small" icon={<ReloadOutlined />} onClick={load}>
              새로고침
            </Button>
          }
        />
      )}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
        <Card style={{ borderColor: '#E2E8F0' }}>
          <Descriptions column={4} colon={false} size="small" labelStyle={{ color: '#64748b' }}>
            <Descriptions.Item label="상태">
              <CodeTag value={caseStatusLabel[securityCase.status]} />
            </Descriptions.Item>
            <Descriptions.Item label="조사 유형">
              {caseTypeLabel[securityCase.caseType] ?? securityCase.caseType}
            </Descriptions.Item>
            <Descriptions.Item label="증거 버전">v{securityCase.evidenceVersion}</Descriptions.Item>
            <Descriptions.Item label="AI 보고서">
              {latestReport(reports) ? `v${latestReport(reports)?.reportVersion}` : '-'}
            </Descriptions.Item>
            <Descriptions.Item label="생성 시각">
              {dateTime(securityCase.createdAt)}
            </Descriptions.Item>
            <Descriptions.Item label={<HelpLabel label="결제 병합" help={mergeHelp} />}>
              {mergeRemaining === null ? '마감' : `${mergeRemaining}분 남음`}
            </Descriptions.Item>
            <Descriptions.Item
              label={
                <HelpLabel label={`조사 기한 (생성 후 ${SLA_HOURS}시간)`} help={deadlineHelp} />
              }
            >
              <DeadlineValue createdAt={securityCase.createdAt} status={securityCase.status} />
            </Descriptions.Item>
            <Descriptions.Item label="종료 시각">
              {securityCase.closedAt ? dateTime(securityCase.closedAt) : '-'}
            </Descriptions.Item>
            <Descriptions.Item label="담당">
              {securityCase.assigneeName ?? '미배정'}
            </Descriptions.Item>
            <Descriptions.Item label="배정 시각">
              {securityCase.assignedAt ? dateTime(securityCase.assignedAt) : '-'}
            </Descriptions.Item>
          </Descriptions>
        </Card>

        <CaseProgress
          status={securityCase.status}
          latestReportStatus={latest?.status ?? null}
          allDecided={decidable.length > 0 && decidedCount >= decidable.length}
        />

        <CaseBundle
          securityCase={securityCase}
          payments={payments}
          mergeRemaining={mergeRemaining}
        />

        <Card
          title={<span className="dw-section-title">조사 대상 결제 ({payments.length}건)</span>}
          style={{ borderColor: '#E2E8F0' }}
        >
          <Table<CasePayment>
            size="small"
            rowKey="casePaymentId"
            pagination={false}
            dataSource={payments}
            columns={[
              { title: '결제 ID', dataIndex: 'paymentId', render: (v) => <strong>{v}</strong> },
              {
                title: '금액',
                dataIndex: 'amount',
                align: 'right',
                render: (v: CasePayment['amount']) => (v === null ? '-' : won(v)),
              },
              {
                title: '결제 시각',
                dataIndex: 'requestedAt',
                render: (v: CasePayment['requestedAt']) => (v === null ? '-' : dateTime(v)),
              },
              {
                title: '결제 상태 (P5)',
                dataIndex: 'paymentStatus',
                render: (v: CasePayment['paymentStatus'], p) => (
                  <>
                    <CodeTag value={v ? paymentStatusLabel[v] : undefined} />
                    {p.customerCanceledAt && (
                      <Tag color="default" style={{ marginTop: 4 }}>
                        고객 취소 · {dateTime(p.customerCanceledAt)}
                      </Tag>
                    )}
                  </>
                ),
              },
              {
                title: 'FDS 점수',
                dataIndex: 'riskScore',
                align: 'right',
                render: (v: number | null) => <strong>{v ?? '-'}</strong>,
              },
              {
                title: 'FDS 판정',
                dataIndex: 'fdsResult',
                render: (v: CasePayment['fdsResult']) => (
                  <CodeTag value={v ? fdsResultLabel[v] : undefined} />
                ),
              },
              {
                title: '적중 규칙',
                dataIndex: 'ruleHits',
                render: (rules: CasePayment['ruleHits']) =>
                  rules.map((r) => (
                    <Tag key={r.code} title={r.code}>
                      {r.appliedScore === null ? r.name : `${r.name} +${r.appliedScore}`}
                    </Tag>
                  )),
              },
            ]}
          />
        </Card>

        {/* 주문자 정보 — 담당자 · 열린 사건에서만, 결제 1건씩 (#619) */}
        {contactsVisible && <ContactCard caseId={securityCase.caseId} payments={payments} />}

        <div className="dw-section-grid">
          <ReportCard
            caseId={securityCase.caseId}
            reports={reports}
            evidenceVersion={securityCase.evidenceVersion}
          />
          {/* 증거 버전이 바뀌면 이전 판정 초안을 버리고 새 내용으로 다시 판정하게 한다 (설계 3.5). */}
          <DecisionPanel
            key={securityCase.evidenceVersion}
            payments={payments}
            decisions={decisions}
            reports={reports}
            mode={mode}
            evidenceVersion={securityCase.evidenceVersion}
            onSubmit={handleSubmit}
          />
        </div>

        <div className="dw-section-grid">
          <HandoffCard
            payments={payments}
            decisions={decisions}
            handoffs={handoffs}
            recheckingId={recheckingId}
            onRecheck={handleRecheck}
          />
          <HistoryCard history={history} />
        </div>
      </div>
    </>
  )
}
