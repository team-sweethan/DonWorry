import { Steps } from 'antd'
import type { CaseStatus, ReportStatus } from '../../../types/case'
import { caseProgress } from '../../../utils/caseDetailView'

// 사건 진행 단계 (#320) — 지금 어느 단계이고 어디서 막혔는지 한 줄로 보인다
// 상세 머리 칸(사건 정보) 안에 들어가므로 카드 테두리는 두지 않는다 (#648)
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
    <Steps
      size="small"
      responsive={false}
      items={steps.map((s) => ({ title: s.title, status: s.state, description: s.note }))}
    />
  )
}
