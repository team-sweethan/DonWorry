import { Card, Steps } from 'antd'
import type { CaseStatus, ReportStatus } from '../../../types/case'
import { caseProgress } from '../../../utils/caseDetailView'

// 사건 진행 단계 (#320) — 지금 어느 단계이고 어디서 막혔는지 한 줄로 보인다
export function CaseProgress({
  status,
  latestReportStatus,
  allDecided,
}: {
  status: CaseStatus
  latestReportStatus: ReportStatus | null
  allDecided: boolean
}) {
  const steps = caseProgress({ status, latestReportStatus, allDecided })
  return (
    <Card size="small" style={{ borderColor: '#E2E8F0' }}>
      <Steps
        size="small"
        responsive={false}
        items={steps.map((s) => ({ title: s.title, status: s.state, description: s.note }))}
      />
    </Card>
  )
}
