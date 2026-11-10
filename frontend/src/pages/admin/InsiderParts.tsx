import { Alert } from 'antd'
import dayjs from 'dayjs'
import { useEffect, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import {
  getInsiderAlertDetail,
  type InsiderAlertEvidence,
  type InsiderAlertSeverity,
} from '../../api/insiderAlerts'
import { ListEmpty } from '../../components/common/ListEmpty'
import { ListLoading } from '../../components/common/ListLoading'
import { accessLabel, denyLabel, fieldLabel, monitorPath, severityView } from './insiderView'
import './InsiderParts.css'

/** 등급 — 바탕 없는 원 아이콘 + 글자. 주의 노랑(테두리) · 높음 주황(테두리) · 위험 빨강(채움) */
/** 경보 표의 규칙 칸 — 코드는 한 줄, 이름은 다음 줄에서 줄바꿈된다. 긴 이름이 내용 칸을 밀어내지 않게 (#770) */
export function RuleCell({ code, label }: { code: string; label: string }) {
  return (
    <span className="ip-rule">
      <strong>{code}</strong>
      <span>{label}</span>
    </span>
  )
}

export function SeverityMark({ value }: { value: InsiderAlertSeverity }) {
  return (
    <span className={`ip-severity is-${value.toLowerCase()}`}>
      <span className="ip-severity-icon" aria-hidden="true" />
      {severityView[value].label}
    </span>
  )
}

/**
 * 상태 한 마디 — 아이콘 + 글자. antd 태그 대신 쓴다.
 * 판정 창(Drawer)은 관리 화면 틀 밖(포털)에 그려져 공통 태그 규칙이 닿지 않는다 — 어디서든 같은 모양이게
 */
export function StatusMark({ tone, children }: { tone: 'danger' | 'quiet'; children: ReactNode }) {
  return (
    <span className={`ip-status is-${tone}`}>
      <span className="ip-status-icon" aria-hidden="true" />
      {children}
    </span>
  )
}

/** 직원 이름 — 누르면 관제 탭에서 그 직원 기록이 열린다 */
export function StaffLink({ staffId, name }: { staffId: number; name: string | null }) {
  return (
    <Link className="ip-staff-link" to={monitorPath(staffId)}>
      {name ?? `ID ${staffId}`}
    </Link>
  )
}

/** 근거 접근 기록 — 펼칠 때 불러온다. 관제 기록과 같은 모양(시각 + 한 줄). 원문 값은 없고 무엇을 · 어디서 · 결과만 */
export function EvidenceLog({ alertId }: { alertId: number }) {
  const [rows, setRows] = useState<InsiderAlertEvidence[] | null>(null)
  const [error, setError] = useState(false)
  useEffect(() => {
    let alive = true
    getInsiderAlertDetail(alertId)
      .then((detail) => {
        if (alive) setRows(detail.evidence)
      })
      .catch(() => {
        if (alive) setError(true)
      })
    return () => {
      alive = false
    }
  }, [alertId])
  if (error) return <Alert type="error" showIcon message="근거 기록을 불러오지 못했습니다." />
  if (rows === null) return <ListLoading label="근거 기록을 불러오는 중" rows={3} columns={2} />
  if (rows.length === 0) return <ListEmpty title="근거 기록이 없어요." />
  return (
    <ol className="ip-log" aria-label="근거 접근 기록">
      {rows.map((row) => (
        <li key={row.eventId} className="ip-log-row">
          <time className="ip-log-time" dateTime={row.occurredAt}>
            {dayjs(row.occurredAt).format('MM.DD HH:mm:ss')}
          </time>
          <span className="ip-log-text">
            <strong>{accessLabel[row.accessType] ?? row.accessType}</strong>
            {row.merchantName ? (
              <span>{row.merchantName}</span>
            ) : (
              row.merchantId !== null && <span>{`가맹점 ID ${row.merchantId}`}</span>
            )}
            {row.caseId !== null && <span>{`사건 #${row.caseId}`}</span>}
            {row.paymentId !== null && <span>{`결제 ${row.paymentId}`}</span>}
            {row.fieldType && <span>{fieldLabel[row.fieldType] ?? row.fieldType}</span>}
            {row.result === 'ALLOWED' ? (
              <span className="ip-log-allowed">허용</span>
            ) : (
              <StatusMark tone="danger">
                {`거부${row.denyReason ? ` · ${denyLabel[row.denyReason] ?? row.denyReason}` : ''}`}
              </StatusMark>
            )}
          </span>
        </li>
      ))}
    </ol>
  )
}
