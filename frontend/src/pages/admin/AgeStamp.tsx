import { ClockCircleOutlined } from '@ant-design/icons'
import { dateTime } from '../security/case/caseLabels'
import './caseAssignmentShared.css'

/**
 * 지난 날 + 정확한 시각 (담당 현황 "생긴 날" · 변경 요청 "기다린 시간" 공통).
 * late는 기다림이 하루를 넘긴 요청에만 준다 — 사건이 오래된 것 자체는 경고가 아니다
 */
export function AgeStamp({
  text,
  at,
  late = false,
  alignEnd = false,
}: {
  text: string
  at: string
  late?: boolean
  alignEnd?: boolean
}) {
  return (
    <span className={`ca-age${alignEnd ? ' is-end' : ''}`}>
      <span className={`ca-age-main${late ? ' is-late' : ''}`}>
        <ClockCircleOutlined aria-hidden="true" />
        {text}
      </span>
      <span className="dw-caption">{dateTime(at)}</span>
    </span>
  )
}
