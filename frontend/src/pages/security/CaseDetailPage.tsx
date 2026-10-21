import { useCallback, useEffect, useState } from 'react'
import { ArrowLeftOutlined, ReloadOutlined } from '@ant-design/icons'
import { Button, Card, Descriptions, Space, Spin, Table, Tag } from 'antd'
import { useNavigate, useParams } from 'react-router-dom'
import {
  CaseVersionConflictError,
  demoMergePayment,
  getCase,
  submitDecisions,
} from '../../api/cases'
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

/** 보고서 생성 중·인계 접수 확인 중이면 이 간격으로 다시 조회한다. */
const POLL_MS = 3_000

export function CaseDetailPage() {
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const { merchant } = useDemo()
  const { toast } = useFeedback()
  const [detail, setDetail] = useState<CaseDetail | null | undefined>(undefined)

  const load = useCallback(() => {
    getCase(id, merchant).then(setDetail)
  }, [id, merchant])
  useEffect(load, [load])

  const waiting =
    !!detail &&
    (detail.reports.some((r) => r.status === 'PENDING' || r.status === 'RUNNING') ||
      detail.handoffs.some((h) => h.status === 'REQUESTED'))
  useEffect(() => {
    if (!waiting) return
    const timer = setTimeout(load, POLL_MS)
    return () => clearTimeout(timer)
  }, [waiting, detail, load])

  if (detail === undefined) return <Spin style={{ display: 'block', margin: '120px auto' }} />
  if (detail === null) return <ScreenResult kind="404" />

  const { securityCase, payments, reports, decisions, handoffs, history } = detail
  const mergeRemaining = mergeRemainingMin(securityCase.createdAt, decisions.length)
  const sla = slaState(securityCase.createdAt, securityCase.status)
  const mode = decisionMode(securityCase.evidenceVersion, reports)

  const handleSubmit = async (request: SubmitDecisionsRequest) => {
    try {
      await submitDecisions(securityCase.caseId, request)
      toast.success('판정을 확정했습니다. 금융 조치 인계 접수를 확인하고 있습니다.')
      load()
      return true
    } catch (error) {
      toast.error(
        error instanceof CaseVersionConflictError
          ? '검토 중 Case에 결제가 추가되었습니다. 변경 내용을 다시 확인한 뒤 판정하세요.'
          : '판정을 저장하지 못했습니다. 잠시 후 다시 시도하세요.',
      )
      load()
      return false
    }
  }

  const handleDemoMerge = async () => {
    await demoMergePayment(securityCase.caseId)
    toast.info('시연: 같은 고객의 결제가 병합되었습니다. 화면은 아직 이전 상태입니다.')
  }

  return (
    <>
      <PageHeader
        title="Case 상세"
        description={`${securityCase.caseId} · ${securityCase.merchantId} · ${securityCase.customerRef}`}
        actions={
          <Space>
            <span className="dw-demo-label">DEMO DATA</span>
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
              { title: '금액', dataIndex: 'amount', align: 'right', render: won },
              { title: '결제 시각', dataIndex: 'requestedAt', render: dateTime },
              {
                title: '결제 상태 (P5)',
                dataIndex: 'paymentStatus',
                render: (v: CasePayment['paymentStatus']) => (
                  <CodeTag value={paymentStatusLabel[v]} />
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
                render: (v: CasePayment['fdsResult']) => <CodeTag value={fdsResultLabel[v]} />,
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
          <DecisionPanel
            payments={payments}
            decisions={decisions}
            mode={mode}
            evidenceVersion={securityCase.evidenceVersion}
            onSubmit={handleSubmit}
          />
        </div>

        <div className="dw-section-grid">
          <HandoffCard payments={payments} decisions={decisions} handoffs={handoffs} />
          <HistoryCard history={history} />
        </div>
      </div>
    </>
  )
}
