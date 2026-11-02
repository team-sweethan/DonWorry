import { QuestionCircleOutlined } from '@ant-design/icons'
import { Space, Tooltip } from 'antd'
import type { CaseStatus } from '../../../types/case'
import { SLA_HOURS, slaState } from '../../../utils/caseRules'

/** 조사 기한 이름과 도움말 (#302) — 목록 · 상세 · 홈이 같은 문구를 쓴다. */
export const deadlineHelp = `Case가 생긴 뒤 ${SLA_HOURS}시간 안에 판정하도록 권장하는 기한입니다. 기한이 지나도 자동으로 판정하거나 실패 처리하지 않고 경고만 표시합니다.`

/** 이름 옆 ⓘ에 마우스를 올리거나 키보드로 가면 도움말을 보인다. */
export function HelpLabel({ label, help }: { label: string; help: string }) {
  return (
    <Space size={4}>
      {label}
      <Tooltip title={help} trigger={['hover', 'focus']}>
        <QuestionCircleOutlined
          tabIndex={0}
          aria-label={`${label} 설명: ${help}`}
          style={{ color: '#94a3b8', cursor: 'help' }}
        />
      </Tooltip>
    </Space>
  )
}

/** 조사 기한 값 — 남은 시간, 또는 기한을 넘긴 시간. 종료된 Case는 '-'. */
export function DeadlineValue({ createdAt, status }: { createdAt: string; status: CaseStatus }) {
  const sla = slaState(createdAt, status)
  if (!sla) return <span style={{ color: '#94a3b8' }}>-</span>
  return sla.exceeded ? (
    <span style={{ color: '#dc2626', fontWeight: 700 }}>기한 {sla.hours}시간 지남</span>
  ) : (
    <span>{sla.hours}시간 남음</span>
  )
}
