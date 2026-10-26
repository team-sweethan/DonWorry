import { useCallback, useEffect, useState } from 'react'
import { ArrowLeftOutlined, ReloadOutlined } from '@ant-design/icons'
import { App, Button, Card, Descriptions, Space, Spin, Table, Tag } from 'antd'
import { useNavigate, useParams } from 'react-router-dom'
import {
  CaseVersionConflictError,
  demoMergePayment,
  getCase,
  recheckHandoff,
  requestReport,
  submitDecisions,
} from '../../api/cases'
import { ApiError } from '../../api/client'
import { useFeedback } from '../../components/common/Feedback'
import { PageHeader } from '../../components/common/PageHeader'
import { ScreenResult } from '../../components/common/ScreenResult'
import { useDemo } from '../../demo/DemoContext'
import type { CaseDetail, CasePayment, SubmitDecisionsRequest } from '../../types/case'
import { decisionMode, latestReport, mergeRemainingMin, slaState } from '../../utils/caseRules'
import { won } from '../../utils/format'
import { HandoffCard, HistoryCard } from './case/CaseActivity'
import { caseStatusLabel, dateTime, fdsResultLabel, paymentStatusLabel } from './case/caseLabels'
import { CodeTag } from './case/CodeTag'
import { DecisionPanel } from './case/DecisionPanel'
import { ReportCard } from './case/ReportCard'

/** 보고서 생성 중·금융 처리 대기 중이면 이 간격으로 다시 조회한다. */
const POLL_MS = 3_000

export function CaseDetailPage() {
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const { merchant } = useDemo()
  const { toast } = useFeedback()
  // useFeedback()은 렌더마다 새 객체라 effect 의존성에는 App이 주는 message를 쓴다
  const { message } = App.useApp()
  const [refreshKey, setRefreshKey] = useState(0)
  const [recheckingId, setRecheckingId] = useState<string | null>(null)
  // 응답을 조회 범위(가맹점·Case)와 함께 저장해, 범위가 바뀌면 이전 Case를 즉시 숨기고
  // 늦게 도착한 이전 범위의 응답은 버린다.
  const scope = `${merchant}/${id}`
  // detail: null은 404(Case 없음), 'error'는 네트워크·5xx 등 일시 장애 — 404 화면으로 단정하지 않는다
  const [loaded, setLoaded] = useState<{
    scope: string
    detail: CaseDetail | null | 'error'
  } | null>(null)
  useEffect(() => {
    let alive = true
    getCase(id, merchant)
      .then((result) => {
        if (alive) setLoaded({ scope: `${merchant}/${id}`, detail: result })
      })
      .catch(() => {
        if (!alive) return
        message.error('Case를 불러오지 못했습니다. 잠시 후 다시 시도하세요.')
        setLoaded({ scope: `${merchant}/${id}`, detail: 'error' })
      })
    return () => {
      alive = false
    }
  }, [id, merchant, refreshKey, message])
  const result = loaded?.scope === scope ? loaded.detail : undefined
  const detail = result === 'error' ? null : result
  const load = useCallback(() => setRefreshKey((k) => k + 1), [])

  const waiting =
    !!detail &&
    (detail.reports.some((r) => r.status === 'PENDING' || r.status === 'RUNNING') ||
      detail.handoffs.some((h) => h.status === 'REQUESTED'))
  useEffect(() => {
    if (!waiting) return
    const timer = setTimeout(load, POLL_MS)
    return () => clearTimeout(timer)
  }, [waiting, detail, load])

  if (result === undefined) return <Spin style={{ display: 'block', margin: '120px auto' }} />
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
  const mergeRemaining = mergeRemainingMin(securityCase.createdAt, decisions.length)
  const sla = slaState(securityCase.createdAt, securityCase.status)
  const mode = decisionMode(securityCase.evidenceVersion, reports)

  const handleSubmit = async (request: SubmitDecisionsRequest) => {
    try {
      await submitDecisions(securityCase.caseId, request)
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

  const handleDemoMerge = async () => {
    try {
      // 판정 중 병합 충돌을 시연하려고 화면은 일부러 갱신하지 않는다.
      const result = await demoMergePayment(securityCase.caseId)
      if (result.merged) {
        toast.info('시연: 같은 고객의 결제가 병합되었습니다. 화면은 아직 이전 상태입니다.')
      } else {
        // 병합되지 않은 결제는 새 Case가 된다. 숨기지 않고 새 Case로 안내한다
        toast.info(
          `병합 기한(30분)이 지났거나 판정이 시작되어 새 Case ${result.caseId}가 생성되었습니다.`,
        )
        navigate(`/security/cases/${result.caseId}`)
      }
    } catch {
      toast.error('시연 결제를 병합하지 못했습니다. 잠시 후 다시 시도하세요.')
      load()
    }
  }

  const handleRecheck = async (handoffId: string) => {
    setRecheckingId(handoffId)
    try {
      const after = await recheckHandoff(securityCase.caseId, handoffId)
      const handoff = after.handoffs.find((h) => h.handoffId === handoffId)
      if (handoff?.status === 'REQUESTED') {
        toast.info('아직 금융 처리 중입니다. P5 확인이 필요하면 결제·조치 정보를 전달하세요.')
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

  return (
    <>
      <PageHeader
        title="Case 상세"
        description={`${securityCase.caseId} · ${securityCase.merchantId} · ${securityCase.customerRef}`}
        actions={
          <Space>
            <span className="dw-demo-label">DEMO DATA</span>
            {reportMissing && <Button onClick={handleRequestReport}>AI 보고서 다시 요청</Button>}
            {mergeRemaining !== null && <Button onClick={handleDemoMerge}>시연: 결제 병합</Button>}
            <Button icon={<ReloadOutlined />} onClick={load}>
              새로고침
            </Button>
            <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/security/cases')}>
              목록으로
            </Button>
          </Space>
        }
      />
      <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
        <Card style={{ borderColor: '#E2E8F0' }}>
          <Descriptions column={4} colon={false} size="small" labelStyle={{ color: '#64748b' }}>
            <Descriptions.Item label="상태">
              <CodeTag value={caseStatusLabel[securityCase.status]} />
            </Descriptions.Item>
            <Descriptions.Item label="조사 유형">{securityCase.caseType}</Descriptions.Item>
            <Descriptions.Item label="증거 버전">v{securityCase.evidenceVersion}</Descriptions.Item>
            <Descriptions.Item label="AI 보고서">
              {latestReport(reports) ? `v${latestReport(reports)?.reportVersion}` : '-'}
            </Descriptions.Item>
            <Descriptions.Item label="생성 시각">
              {dateTime(securityCase.createdAt)}
            </Descriptions.Item>
            <Descriptions.Item label="병합 마감">
              {mergeRemaining === null ? '마감' : `${mergeRemaining}분 남음`}
            </Descriptions.Item>
            <Descriptions.Item label="SLA (권장 24h)">
              {!sla ? (
                '-'
              ) : sla.exceeded ? (
                <span style={{ color: '#dc2626', fontWeight: 700 }}>{sla.hours}시간 초과</span>
              ) : (
                `${sla.hours}시간 남음`
              )}
            </Descriptions.Item>
            <Descriptions.Item label="종료 시각">
              {securityCase.closedAt ? dateTime(securityCase.closedAt) : '-'}
            </Descriptions.Item>
          </Descriptions>
        </Card>

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
                render: (v: CasePayment['paymentStatus']) => (
                  <CodeTag value={v ? paymentStatusLabel[v] : undefined} />
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
                render: (rules: string[]) => rules.map((r) => <Tag key={r}>{r}</Tag>),
              },
            ]}
          />
        </Card>

        <div className="dw-section-grid">
          {/* 보고서 버전이 늘면 최신 버전을 다시 기본 선택한다. */}
          <ReportCard
            key={latestReport(reports)?.reportVersion}
            reports={reports}
            evidenceVersion={securityCase.evidenceVersion}
          />
          {/* 증거 버전이 바뀌면 이전 판정 초안을 버리고 새 내용으로 다시 판정하게 한다 (설계 3.5). */}
          <DecisionPanel
            key={securityCase.evidenceVersion}
            payments={payments}
            decisions={decisions}
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
