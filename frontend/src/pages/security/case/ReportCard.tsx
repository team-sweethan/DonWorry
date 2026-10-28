import { Alert, Button, Card, Empty, Space, Spin, Typography } from 'antd'
import { useLocation, useNavigate } from 'react-router-dom'
import type { AiReport } from '../../../types/case'
import { latestReport } from '../../../utils/caseRules'
import { hasReadableReport, reportHeadline, topRiskScore } from '../../../utils/reportView'
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
            <div style={{ padding: '20px 0', textAlign: 'center' }}>
              <Spin />
              <Typography.Paragraph type="secondary" style={{ marginTop: 12 }}>
                AI가 Case를 조사하고 있습니다. 완료되면 새로고침으로 확인하세요.
              </Typography.Paragraph>
            </div>
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
