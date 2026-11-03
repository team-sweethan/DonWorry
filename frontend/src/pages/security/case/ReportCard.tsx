import { useEffect, useRef, useState } from 'react'
import { CheckCircleFilled } from '@ant-design/icons'
import { Alert, Button, Card, Empty, Space, Spin, Typography } from 'antd'
import { useLocation, useNavigate } from 'react-router-dom'
import { getReportProgress } from '../../../api/cases'
import type { AiReport, TraceStep } from '../../../types/case'
import { latestReport } from '../../../utils/caseRules'
import {
  durationText,
  elapsedText,
  hasReadableReport,
  liveCurrentTask,
  reportHeadline,
  topRiskScore,
} from '../../../utils/reportView'
import { dateTime, recommendationLabel, reportStatusLabel } from './caseLabels'
import { CodeTag } from './CodeTag'
import { ScoreGauge } from './ReportFull'

// Case 상세의 AI 보고서 요약 (P4 소유). 전문 · 버전 선택은 /security/cases/:id/report (#292)
export function ReportCard({
  caseId,
  reports,
  evidenceVersion,
}: {
  caseId: string
  reports: AiReport[]
  evidenceVersion: number
}) {
  const navigate = useNavigate()
  // 목록에서 받은 복귀 조건(#243)을 보고서 전문에 넘겨 돌아올 때 그대로 돌려받는다
  const listReturn = useLocation().state
  const report = latestReport(reports)
  const body = report?.status === 'SUCCEEDED' ? report.reportBody : null
  const running = report?.status === 'RUNNING' ? report : null
  // 끝난 단계 (#322). 사건 상세가 3초마다 다시 조회해 report가 새로 올 때마다 함께 가져온다
  // 자동 조회가 2분 뒤 멈추면 이것도 멈춘다 — 세션 무활동 만료(ADR-0014)를 막지 않는다
  const [progress, setProgress] = useState<{ reportId: string; steps: TraceStep[] } | null>(null)
  // 같은 보고서의 조회가 아직 끝나지 않았으면 새로 보내지 않는다 — 느릴 때 요청이 쌓이지 않게
  const inFlight = useRef<string | null>(null)
  useEffect(() => {
    if (!running || inFlight.current === running.reportId) return
    const reportId = running.reportId
    inFlight.current = reportId
    // 상세가 다시 조회돼도 응답을 버리지 않는다 — 보고서 번호로 맞는 응답인지 가린다 (아래 steps)
    getReportProgress(caseId, reportId)
      .then((p) => setProgress({ reportId, steps: p.steps }))
      // 진행 표시는 보조 정보라 실패해도 안내하지 않고 이전 단계를 그대로 둔다
      .catch(() => {})
      .finally(() => {
        if (inFlight.current === reportId) inFlight.current = null
      })
  }, [caseId, running])
  const steps = running && progress?.reportId === running.reportId ? progress.steps : []

  return (
    <Card
      title={<span className="dw-section-title">AI 조사 보고서</span>}
      extra={
        hasReadableReport(reports) && (
          <Button
            type="primary"
            size="small"
            onClick={() => navigate(`/security/cases/${caseId}/report`, { state: listReturn })}
          >
            보고서 전문 보기
          </Button>
        )
      }
      style={{ borderColor: '#E2E8F0' }}
    >
      {!report ? (
        <Empty description="요청된 보고서가 없습니다." />
      ) : (
        <Space direction="vertical" size={14} style={{ width: '100%' }}>
          <Space size={8} wrap>
            <CodeTag value={reportStatusLabel[report.status]} />
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              보고서 v{report.reportVersion} · 증거 v{report.evidenceVersion} · 요청{' '}
              {dateTime(report.createdAt)}
            </Typography.Text>
          </Space>
          {report.status === 'SUCCEEDED' && report.evidenceVersion !== evidenceVersion && (
            <Alert
              type="warning"
              showIcon
              message="현재 Case 증거 버전과 다른 보고서입니다."
              description={`Case는 증거 v${evidenceVersion}입니다. 이 보고서로 최종 판정할 수 없습니다.`}
            />
          )}
          {(report.status === 'PENDING' || report.status === 'RUNNING') && (
            <LiveSteps status={report.status} createdAt={report.createdAt} steps={steps} />
          )}
          {report.status === 'FAILED' && (
            <Alert
              type="error"
              showIcon
              message="AI 보고서 생성이 최종 실패했습니다 (총 3회 시도)."
              description="자동 판정은 하지 않습니다. 직접 근거를 조사한 뒤 오른쪽 판정 패널에서 수동 판정하고, 사유에 조사 근거를 기록하세요."
            />
          )}
          {body && (
            <div style={{ display: 'flex', gap: 18, alignItems: 'center' }}>
              <div style={{ flex: '0 0 auto' }}>
                <ScoreGauge score={topRiskScore(body.paymentAnalyses.map((p) => p.riskScore))} />
              </div>
              <div>
                <Typography.Paragraph strong style={{ fontSize: 16, marginBottom: 6 }}>
                  {reportHeadline(body) ??
                    '결론 항목이 생기기 전 보고서입니다. 전문에서 요약과 근거를 확인하세요.'}
                </Typography.Paragraph>
                {body.verdict && (
                  <Space size={6}>
                    <Typography.Text type="secondary" style={{ fontSize: 13 }}>
                      종합 권고
                    </Typography.Text>
                    <CodeTag value={recommendationLabel[body.verdict.overallRecommendation]} />
                  </Space>
                )}
              </div>
            </div>
          )}
        </Space>
      )}
    </Card>
  )
}

// 생성 중 끝난 단계 목록 + 지금 하는 일 줄 (#322, #355 A안) — 문구는 "조사 과정" 탭과 같은 요약이다
function LiveSteps({
  status,
  createdAt,
  steps,
}: {
  status: AiReport['status']
  createdAt: string
  steps: TraceStep[]
}) {
  // 지난 시간은 화면 안에서만 1초마다 센다 — 서버 요청이 아니라 세션 무활동 만료(ADR-0014)에 영향 없음
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(timer)
  }, [])
  const current = liveCurrentTask(status, steps)
  return (
    <div>
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        AI가 지금까지 한 일 — 3초마다 갱신 · 조사 시작 후 {elapsedText(createdAt, now)} (2분이 지나
        갱신이 멈추면 새로고침하세요)
      </Typography.Text>
      <ol style={liveList}>
        {steps.map((s, i) => (
          <li key={i} style={liveRow}>
            <CheckCircleFilled style={{ color: '#16A34A', marginTop: 3 }} />
            <span style={{ flex: 1 }}>
              <strong>{s.actor === 'AI' ? s.title : `${s.title} (시스템)`}</strong>
              {s.received && <span style={{ color: '#64748b' }}> — {s.received}</span>}
            </span>
            <span style={{ color: '#94A3B8', fontSize: 12 }}>{durationText(s.durationMs)}</span>
          </li>
        ))}
        <li style={{ ...liveRow, color: '#2563EB' }}>
          <Spin size="small" />
          <span>
            <strong>지금: {current.title}</strong>
            {current.note && <span> — {current.note}</span>}
          </span>
        </li>
      </ol>
    </div>
  )
}

const liveList = { listStyle: 'none', padding: 0, margin: '8px 0 0' }
const liveRow = {
  display: 'flex',
  alignItems: 'flex-start',
  gap: 8,
  padding: '6px 0',
  borderBottom: '1px solid #F1F5F9',
  fontSize: 13,
}
