import type { CSSProperties } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import type { LinkedCaseItem, ReportBody } from '../../../types/case'
import { linkedCaseGroups, linkedCaseOutcome, linkedKindsText } from '../../../utils/reportView'
import { dateTime } from './caseLabels'

// 같은 카드 · 기기 · IP로 열린 지난 사건 (#821) — 다른 고객의 끝난 사건이라 모든 보안 직원이 연다 (CaseAccessGuard)
// 지난 판정은 그 결제에 내린 것만 센 참고 자료다. 이번 사건 등급 · 정상 사유는 바꾸지 않는다

// "지난 사건과 비교" 칸 아래 묶음 — 기본 조회(카드 · 기기)와 AI가 고른 IP
export function LinkedCasesBlock({ body }: { body: ReportBody }) {
  const groups = linkedCaseGroups(body)
  if (groups.length === 0) return null
  return (
    <div style={blockStyle}>
      {groups.map((g) => (
        <div key={g.key} style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
          <b style={{ fontSize: 14, color: '#0F172A' }}>
            {g.title}
            <span style={{ fontSize: 12, fontWeight: 500, color: '#64748B' }}>
              {' '}
              · 다른 고객 · 끝난 사건
            </span>
          </b>
          {g.text && (
            <div
              style={{
                fontSize: 13,
                color: g.status === 'FAILED' ? '#B45309' : '#64748B',
                fontWeight: g.status === 'FAILED' ? 600 : 400,
              }}
            >
              {g.text}
            </div>
          )}
          {g.cases.length > 0 && <LinkedCaseList cases={g.cases} />}
          {g.notListed > 0 && (
            <div style={mutedText}>외 {g.notListed}건 (최근 끝난 사건만 보여 줍니다)</div>
          )}
          {g.missingText && <div style={mutedText}>{g.missingText}</div>}
        </div>
      ))}
      <div style={{ fontSize: 12, color: '#64748B' }}>
        판정은 겹친 결제에 내린 것만 셉니다. 지난 판정은 참고 자료이고 이번 판정을 대신하지
        않습니다.
      </div>
    </div>
  )
}

// 사건 줄 — [사건 20] 같은 기기 · 11/10 종료 · 승인 취소 1건
export function LinkedCaseList({ cases, compact }: { cases: LinkedCaseItem[]; compact?: boolean }) {
  const navigate = useNavigate()
  const listReturn = useLocation().state
  return (
    <ul style={{ margin: 0, paddingLeft: 18, fontSize: compact ? 13 : 13.5, lineHeight: 1.7 }}>
      {cases.map((c) => (
        <li key={c.caseId}>
          <button
            type="button"
            style={linkStyle}
            onClick={() => navigate(`/security/cases/${c.caseId}`, { state: listReturn })}
          >
            사건 {c.caseId}
          </button>{' '}
          같은 {linkedKindsText(c.matchedBy)} · {c.closedAt ? dateTime(c.closedAt) : '시각 모름'}{' '}
          종료 · 그 결제 {c.paymentCount}건 {linkedCaseOutcome(c)}
        </li>
      ))}
    </ul>
  )
}

const blockStyle: CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 12,
  borderTop: '1px solid #E2E8F0',
  paddingTop: 12,
}
const mutedText: CSSProperties = { fontSize: 13, color: '#64748B' }
const linkStyle: CSSProperties = {
  background: 'none',
  border: 'none',
  padding: 0,
  fontSize: 'inherit',
  fontWeight: 700,
  color: '#1D4ED8',
  cursor: 'pointer',
}
