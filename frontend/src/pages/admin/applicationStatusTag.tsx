import { Tag } from 'antd'
import { applicationLabels, mailStatusLabels } from '../../api/adminApplications'
import type { ApplicationStatus } from '../../api/adminApplications'
import type { MailStatus } from '../../api/merchantApplications.ts'

// 신청 상태는 관리 화면 공통 태그(global.css)를 따른다: 아이콘 + 글자.
// 안내 메일은 신청 상태보다 한 단계 조용하게 글자만 쓰고, 문제 있을 때만 색을 준다 (AdminApplicationsPage.css .ar-mail).
const statusColors: Record<ApplicationStatus, string> = {
  EMAIL_VERIFICATION_PENDING: 'default',
  REVIEW_PENDING: 'gold',
  APPROVED: 'green',
  REJECTED: 'red',
}
const mailTone: Partial<Record<MailStatus, string>> = {
  FAILED: 'is-failed',
  UNKNOWN: 'is-unknown',
}

export const statusTag = (status: ApplicationStatus) => (
  <Tag color={statusColors[status]}>{applicationLabels[status]}</Tag>
)

export const mailTag = (status: MailStatus) => (
  <span className={`ar-mail ${mailTone[status] ?? ''}`}>{mailStatusLabels[status]}</span>
)
