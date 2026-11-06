import type { CSSProperties } from 'react'
import type { AiRecommendation, BasisSignal, ReportBody } from '../../../types/case'
import {
  caseNormals,
  caseSignalId,
  conclusionGuide,
  conclusionSignals,
  overviewText,
  topRiskScore,
  verdictLines,
  type ConclusionSignal,
  type RecommendationSource,
} from '../../../utils/reportView'
import { recommendationLabel } from './caseLabels'

// ① 결론 (#591) — 사건 개요 한 줄 · 기준표 판정 세 칸 · 징후 표(판정 전에 확인할 것)
// 개요 · 판정 · 확인할 것은 모두 코드가 쓴 칸이다. AI 문장은 아래 ③ 연결의 조사 요약에만 있다
// 체크 상태는 화면에서만 기억한다 — 새로고침하면 처음으로 (판정 기록이 아니다)

// 왼쪽(이상 쪽)부터 오른쪽(정상 쪽)으로
const STEPS: Array<{ rec: AiRecommendation; side: string; note: string }> = [
  { rec: 'REJECT_REVIEW', side: '이상 쪽', note: '고위험 징후 + 정상 사유 없는 다른 징후' },
  { rec: 'NEEDS_CHECK', side: '가운데', note: '정상 사유 없는 징후가 남음' },
  { rec: 'ACCEPT_REVIEW', side: '정상 쪽', note: '정상 사유 없는 징후 없음' },
]

const levelTag: Record<BasisSignal['level'], { text: string; style: CSSProperties }> = {
  HIGH_RISK: {
    text: '고위험',
    style: { background: '#FEE2E2', color: '#991B1B', border: '1px solid #FCA5A5' },
  },
  CAUTION: {
    text: '주의',
    style: { background: '#FEF3C7', color: '#92400E', border: '1px solid #FCD34D' },
  },
}

export function ReportConclusion({
  body,
  label,
  source,
  checked,
  onToggle,
  onDecide,
}: {
  body: ReportBody
  label: string
  source: RecommendationSource
  checked: string[]
  onToggle: (id: string) => void
  onDecide?: () => void
}) {
  const overview = overviewText(body)
  const signals = conclusionSignals(body)
  const overall = body.verdict?.overallRecommendation ?? null
  const payments = verdictLines(body).payments
  const score = topRiskScore(body.paymentAnalyses.map((p) => p.riskScore))
  const normals = caseNormals(body)
  return (
    <section style={sectionStyle}>
      <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 10 }}>
        <span style={{ fontSize: 13, fontWeight: 800, color: '#1E3A8A' }}>① 결론</span>
        <span style={{ fontSize: 13, color: '#475569' }}>{label}</span>
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 8 }}>
          <span style={{ fontSize: 13, fontWeight: 700, color: '#334155' }}>사건 개요</span>
          {overview.byCode && <span style={codeTagStyle}>코드가 채움</span>}
        </div>
        <p style={{ margin: 0, fontSize: 18, lineHeight: 1.7, color: '#0F172A' }}>
          {overview.text ?? '결론 항목이 생기기 전 보고서입니다. 아래 근거를 확인하세요.'}
        </p>
      </div>

      {overall && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          <div style={titleRowStyle}>
            <span style={{ fontSize: 14, fontWeight: 700, color: '#0F172A' }}>
              {source}{' '}
              <span style={{ fontWeight: 400, fontSize: 13, color: '#475569' }}>
                · 세 가지 중 하나 · 최종 판정은 보안 직원
              </span>
            </span>
            <span style={{ fontSize: 13, color: '#475569' }}>FDS {score ?? '-'}점 (확률 아님)</span>
          </div>
          <div style={stepsStyle}>
            {STEPS.map((step) => (
              <VerdictStep key={step.rec} {...step} current={step.rec === overall} />
            ))}
          </div>
          {payments.length > 0 && (
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, fontSize: 13 }}>
              {payments.map((p) => (
                <span key={p.paymentId} style={paymentChipStyle}>
                  결제 {p.paymentId} · {recommendationLabel[p.recommendation].label}
                </span>
              ))}
            </div>
          )}
          <div style={{ fontSize: 14, color: '#78350F', lineHeight: 1.6 }}>
            {conclusionGuide(body)}
          </div>
        </div>
      )}

      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        <div style={{ fontSize: 14, fontWeight: 700, color: '#0F172A' }}>
          징후와 정상 사유{' '}
          <span style={{ fontWeight: 400, fontSize: 13, color: '#475569' }}>
            · 기준표는 이 표로 판정합니다
          </span>
        </div>
        {signals === null ? (
          <div style={{ fontSize: 13.5, color: '#475569' }}>
            판정 근거가 생기기 전 보고서입니다. 아래 ④ 판정 근거 상세의 적중 규칙을 확인하세요.
          </div>
        ) : signals.length === 0 ? (
          <div style={{ fontSize: 13.5, color: '#475569' }}>적중한 징후가 없습니다.</div>
        ) : (
          <SignalTable signals={signals} checked={checked} onToggle={onToggle} />
        )}
        {body.additionalChecks.length > 0 && (
          <div style={{ fontSize: 13.5, color: '#334155', lineHeight: 1.6 }}>
            <b style={{ color: '#6D28D9' }}>AI 추가 제안</b> · {body.additionalChecks.join(' · ')}
          </div>
        )}
        <div style={{ fontSize: 13.5, color: '#334155', lineHeight: 1.6 }}>
          <b style={{ color: '#166534' }}>징후와 별개인 정상 거래 근거</b> ·{' '}
          {normals.length > 0 ? (
            normals.map((n) => n.label).join(' · ')
          ) : (
            <>
              없음{' '}
              <span style={{ color: '#64748B' }}>
                (예: 평소와 비슷한 금액의 결제가 여러 번 있으면 여기에 표시)
              </span>
            </>
          )}
        </div>
      </div>

      <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 8 }}>
        {onDecide && (
          <button type="button" onClick={onDecide} style={primaryButtonStyle}>
            판정하러 가기
          </button>
        )}
        <button type="button" onClick={() => scrollToEvidence()} style={secondaryButtonStyle}>
          근거 전체 보기
        </button>
        {onDecide && (
          <span style={{ fontSize: 12.5, color: '#475569' }}>
            Case 상세의 판정 패널로 이동 · 기준표 판정과 다르게 고르면 사유를 입력합니다
          </span>
        )}
      </div>
    </section>
  )
}

function VerdictStep({
  rec,
  side,
  note,
  current,
}: {
  rec: AiRecommendation
  side: string
  note: string
  current: boolean
}) {
  return (
    <div style={current ? currentStepStyle : stepStyle} aria-current={current ? 'true' : undefined}>
      <span style={{ fontSize: 12, fontWeight: current ? 800 : 500 }}>
        {current ? '▼ 이 사건' : side}
      </span>
      <span style={{ fontSize: current ? 22 : 17, fontWeight: current ? 800 : 700 }}>
        {recommendationLabel[rec].label}
      </span>
      <span style={{ fontSize: 12.5, lineHeight: 1.5 }}>{note}</span>
    </div>
  )
}

// 짧은 칸은 줄바꿈하지 않고, 확인할 것만 줄바꿈한다
// 체크박스는 판정에 센 징후(정상 사유 없음)에만 — 설명된 징후는 확인할 것을 흐리게 보여 준다
function SignalTable({
  signals,
  checked,
  onToggle,
}: {
  signals: ConclusionSignal[]
  checked: string[]
  onToggle: (id: string) => void
}) {
  return (
    <div style={{ overflowX: 'auto', border: '1px solid #E2E8F0', borderRadius: 10 }}>
      <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 14 }}>
        <thead>
          <tr style={{ background: '#F8FAFC', color: '#334155', textAlign: 'left' }}>
            <th style={{ ...thStyle, width: 40 }}>#</th>
            <th style={thStyle}>징후</th>
            <th style={thStyle}>기준 · 이번 값</th>
            <th style={thStyle}>정상 사유</th>
            <th style={{ ...thStyle, background: '#EFF6FF', color: '#1E3A8A' }}>
              판정 전에 확인할 것
            </th>
          </tr>
        </thead>
        <tbody>
          {signals.map((s, i) => {
            const id = caseSignalId(s)
            const isOpen = !s.normalReason && !s.countedIn
            return (
              <tr key={id} style={{ borderTop: '1px solid #E2E8F0' }}>
                <td style={{ ...tdStyle, color: '#475569' }}>{i + 1}</td>
                <td style={{ ...tdStyle, whiteSpace: 'nowrap' }}>
                  <b>{s.label}</b>{' '}
                  <span style={{ ...tagStyle, ...levelTag[s.level].style }}>
                    {levelTag[s.level].text}
                  </span>
                </td>
                <td style={{ ...tdStyle, whiteSpace: 'nowrap' }}>{s.detail ?? '—'}</td>
                <td
                  style={{
                    ...tdStyle,
                    whiteSpace: 'nowrap',
                    fontWeight: isOpen ? 700 : 500,
                    color: isOpen ? '#92400E' : '#166534',
                  }}
                >
                  {s.countedIn ? `${s.countedIn}에 포함` : (s.normalReason ?? '없음')}
                  {s.mixed && (
                    <div style={{ fontSize: 12, fontWeight: 500, whiteSpace: 'normal' }}>
                      결제 {s.openPaymentIds.join(' · ')}만 · 다른 결제는 정상 사유 있음
                    </div>
                  )}
                </td>
                <td style={{ ...tdStyle, background: '#F8FBFF', lineHeight: 1.55 }}>
                  {!s.checkBefore ? (
                    <span style={{ color: '#94A3B8' }}>—</span>
                  ) : isOpen ? (
                    <label style={{ display: 'flex', gap: 8, alignItems: 'flex-start' }}>
                      <input
                        type="checkbox"
                        checked={checked.includes(id)}
                        onChange={() => onToggle(id)}
                        style={{ width: 18, height: 18, marginTop: 2, flex: 'none' }}
                      />
                      <span>{s.checkBefore}</span>
                    </label>
                  ) : (
                    <span style={{ color: '#64748B' }}>{s.checkBefore}</span>
                  )}
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

function scrollToEvidence() {
  document.getElementById('report-evidence')?.scrollIntoView({ behavior: 'smooth' })
}

const sectionStyle: CSSProperties = {
  background: '#fff',
  border: '1px solid #CBD5E1',
  borderRadius: 14,
  padding: '24px 28px',
  display: 'flex',
  flexDirection: 'column',
  gap: 18,
}
const codeTagStyle: CSSProperties = {
  padding: '1px 7px',
  borderRadius: 4,
  fontSize: 12,
  background: '#F1F5F9',
  color: '#334155',
  border: '1px solid #CBD5E1',
}
const titleRowStyle: CSSProperties = {
  display: 'flex',
  flexWrap: 'wrap',
  justifyContent: 'space-between',
  alignItems: 'baseline',
  gap: 8,
}
const stepsStyle: CSSProperties = {
  display: 'grid',
  gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))',
  gap: 8,
}
const stepStyle: CSSProperties = {
  border: '1px solid #E2E8F0',
  background: '#F8FAFC',
  borderRadius: 10,
  padding: '12px 14px',
  color: '#475569',
  display: 'flex',
  flexDirection: 'column',
  gap: 4,
}
const currentStepStyle: CSSProperties = {
  ...stepStyle,
  border: '2px solid #F59E0B',
  background: '#FFFBEB',
  color: '#78350F',
}
const paymentChipStyle: CSSProperties = {
  padding: '3px 10px',
  borderRadius: 6,
  background: '#F1F5F9',
  color: '#334155',
  border: '1px solid #CBD5E1',
}
const thStyle: CSSProperties = { padding: '10px 12px', fontWeight: 700, whiteSpace: 'nowrap' }
const tdStyle: CSSProperties = { padding: 12, verticalAlign: 'top' }
const tagStyle: CSSProperties = { padding: '2px 8px', borderRadius: 4, fontSize: 12 }
const primaryButtonStyle: CSSProperties = {
  height: 44,
  padding: '0 18px',
  borderRadius: 8,
  border: 'none',
  background: '#2563EB',
  color: '#fff',
  fontSize: 14,
  fontWeight: 700,
  cursor: 'pointer',
}
const secondaryButtonStyle: CSSProperties = {
  height: 44,
  padding: '0 18px',
  borderRadius: 8,
  border: '1px solid #CBD5E1',
  background: '#fff',
  color: '#1E3A8A',
  fontSize: 14,
  cursor: 'pointer',
}
