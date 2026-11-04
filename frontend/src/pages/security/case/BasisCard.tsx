import type { CSSProperties, ReactNode } from 'react'
import { Tag } from 'antd'
import type {
  BasisSignal,
  DecisionBasis,
  NormalBasis,
  ReportRuleHitDetail,
} from '../../../types/case'
import {
  BASIS_RULE_ROWS,
  basisGroups,
  markSignals,
  normalNote,
  signalRecordText,
  signalRuleRecord,
  signalTag,
  type MarkKind,
} from '../../../utils/reportView'

// 판정 근거 화면 (#501) — 조사 요약(AI 해석)과 "왜 이 판정인가요?"(기준표 판정 근거)
// 코드가 쓴 판정 이유는 조사 요약이 아니라 "왜 이 판정인가요?"에 둔다 (#543)

// 고위험 빨강 · 주의 노랑 · 정상 초록 — 밑줄과 근거 칸이 같은 색을 쓴다
const levelTone: Record<MarkKind, { bg: string; border: string; text: string; line: string }> = {
  HIGH_RISK: { bg: '#FEF2F2', border: '#FECACA', text: '#7F1D1D', line: '#DC2626' },
  CAUTION: { bg: '#FFFBEB', border: '#FDE68A', text: '#78350F', line: '#F59E0B' },
  NORMAL: { bg: '#F0FDF4', border: '#BBF7D0', text: '#14532D', line: '#16A34A' },
}

// 조사 요약 — AI가 근거를 이어 설명한 문장. 징후 이름이 그대로 나오면 같은 색 밑줄
export function SummaryBox({ lines, basis }: { lines: string[]; basis: DecisionBasis }) {
  if (lines.length === 0) return null
  return (
    <div style={summaryStyle}>
      <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 10 }}>
        <h3 style={{ margin: 0, fontSize: 16, fontWeight: 700, color: '#581C87' }}>조사 요약</h3>
        <Tag color="purple" style={{ marginInlineEnd: 0 }}>
          AI 해석 · 근거를 이어 설명한 것이며 판정이 아닙니다
        </Tag>
      </div>
      <p style={{ margin: 0, fontSize: 16, lineHeight: 1.85, color: '#1E1B4B' }}>
        {lines.map((line, i) => (
          <span key={i}>
            {i > 0 && ' '}
            {markSignals(line, basis).map((part, j) =>
              part.mark ? (
                <span key={j} style={{ borderBottom: `3px solid ${levelTone[part.mark].line}` }}>
                  {part.text}
                </span>
              ) : (
                <span key={j}>{part.text}</span>
              ),
            )}
          </span>
        ))}
      </p>
      <div style={{ fontSize: 12, color: '#6B21A8' }}>
        밑줄 색은 아래 근거와 같은 색입니다. 빨강 고위험 징후 · 노랑 주의 징후 · 초록 정상 거래 근거
      </div>
    </div>
  )
}

// 왜 이 판정인가요? — 판정 이유 · 고위험 징후 · 주의 징후 · 정상 거래 근거 · 판정 기준표
// reason: 코드가 쓴 판정 이유 (#532), details: FDS 규칙 기록 — 규칙 징후 줄에 점수 · 증거 번호를 붙인다 (#543)
export function WhyCard({
  basis,
  reason,
  details,
}: {
  basis: DecisionBasis
  reason?: string
  details?: ReportRuleHitDetail[]
}) {
  const { highRisk, caution } = basisGroups(basis)
  const row = (s: BasisSignal) => {
    const record = signalRuleRecord(s, details)
    return <SignalRow key={s.key} s={s} record={record ? signalRecordText(record) : undefined} />
  }
  return (
    <div style={whyStyle}>
      <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 10 }}>
        <h3 style={{ margin: 0, fontSize: 16, fontWeight: 700, color: '#1E3A8A' }}>
          왜 이 판정인가요?
        </h3>
        <span style={{ fontSize: 12, color: '#475569' }}>
          돈워리 판정 기준표로 정리했습니다. 같은 근거면 언제나 같은 판정이 나옵니다.
        </span>
      </div>
      {reason && (
        <div style={{ fontSize: 14, color: '#1E3A8A' }}>
          <b>판정 이유</b> · {reason}
        </div>
      )}
      <Group title="고위험 징후" note="정상 고객에게는 거의 나타나지 않는 신호" color="#991B1B">
        {highRisk.length === 0 ? <None /> : highRisk.map(row)}
      </Group>
      <Group title="주의 징후" note="정상 고객에게도 나타날 수 있는 신호" color="#92400E">
        {caution.length === 0 ? <None /> : caution.map(row)}
      </Group>
      <Group title="정상 거래 근거" note="이 고객의 평소 거래와 맞는 점" color="#166534">
        {basis.normals.length === 0 ? (
          <None text="확인된 정상 거래 근거가 없습니다." />
        ) : (
          basis.normals.map((n) => <NormalRow key={n.key} n={n} />)
        )}
      </Group>
      <div style={{ borderTop: '1px dashed #CBD5E1', paddingTop: 12 }}>
        <div style={{ fontSize: 13, fontWeight: 700, color: '#1E3A8A', marginBottom: 8 }}>
          판정 기준표
        </div>
        <div style={ruleGridStyle}>
          {BASIS_RULE_ROWS.map((row) => {
            const hit = row.rule === basis.rule
            return (
              <div key={row.rule} style={hit ? ruleHitStyle(basis) : ruleStyle}>
                {hit && <b>✓ 이 결제 </b>}
                {row.text}
              </div>
            )
          })}
        </div>
        <div style={{ fontSize: 12, color: '#475569', marginTop: 8 }}>
          기준표 판정은 참고용입니다. 최종 판정은 보안 직원이 근거를 확인한 뒤 합니다.
        </div>
      </div>
    </div>
  )
}

// 징후 한 줄 — 비교 화면도 같은 모양을 쓴다. record: "+30점 · 증거 E-…" (FDS 규칙 징후만)
export function SignalRow({
  s,
  tag,
  record,
}: {
  s: BasisSignal
  tag?: ReactNode
  record?: string
}) {
  const tone = levelTone[s.level]
  const own = signalTag(s)
  return (
    <div style={{ ...rowStyle, background: tone.bg, border: `1px solid ${tone.border}` }}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
        <span style={{ fontSize: 14, fontWeight: 700, color: tone.text }}>{s.label}</span>
        {(s.detail || record) && (
          <span style={{ fontSize: 12, color: tone.text }}>
            {[s.detail, record].filter(Boolean).join(' · ')}
          </span>
        )}
      </div>
      {tag ?? (
        <span
          style={{
            ...pillStyle,
            color: own.open ? tone.text : '#475569',
            border: `1px solid ${own.open ? tone.line : '#CBD5E1'}`,
          }}
        >
          {own.text}
        </span>
      )}
    </div>
  )
}

export function NormalRow({ n, note = true }: { n: NormalBasis; note?: boolean }) {
  const tone = levelTone.NORMAL
  return (
    <div
      style={{
        ...rowStyle,
        display: 'block',
        background: tone.bg,
        border: `1px solid ${tone.border}`,
      }}
    >
      <div style={{ fontSize: 14, fontWeight: 700, color: tone.text }}>{n.label}</div>
      {note && <div style={{ fontSize: 12, color: tone.text, marginTop: 2 }}>{normalNote(n)}</div>}
    </div>
  )
}

export function Group({
  title,
  note,
  color,
  children,
}: {
  title: string
  note: string
  color: string
  children: ReactNode
}) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'baseline', gap: 8 }}>
        <span style={{ fontSize: 14, fontWeight: 700, color }}>{title}</span>
        <span style={{ fontSize: 12, color: '#475569' }}>{note}</span>
      </div>
      {children}
    </div>
  )
}

function None({ text = '없음' }: { text?: string }) {
  return <span style={{ fontSize: 13, color: '#94A3B8' }}>{text}</span>
}

// 걸린 칸 색은 그 칸의 판정 색 — 승인 취소 빨강 · 추가 확인 노랑 · 매입 진행 초록
function ruleHitStyle(basis: DecisionBasis): CSSProperties {
  const tone =
    basis.recommendation === 'REJECT_REVIEW'
      ? { bg: '#FEE2E2', border: '#B91C1C', text: '#7F1D1D' }
      : basis.recommendation === 'ACCEPT_REVIEW'
        ? { bg: '#DCFCE7', border: '#15803D', text: '#14532D' }
        : { bg: '#FEF3C7', border: '#B45309', text: '#78350F' }
  return { ...ruleStyle, background: tone.bg, border: `2px solid ${tone.border}`, color: tone.text }
}

const summaryStyle: CSSProperties = {
  background: '#FAF5FF',
  border: '1px solid #E9D5FF',
  borderRadius: 12,
  padding: '18px 20px',
  display: 'flex',
  flexDirection: 'column',
  gap: 10,
  marginBottom: 16,
}
const whyStyle: CSSProperties = {
  border: '2px solid #1D4ED8',
  borderRadius: 12,
  padding: '18px 20px',
  display: 'flex',
  flexDirection: 'column',
  gap: 16,
  background: '#F8FAFF',
  marginBottom: 16,
}
const rowStyle: CSSProperties = {
  display: 'flex',
  flexWrap: 'wrap',
  alignItems: 'center',
  justifyContent: 'space-between',
  gap: 8,
  borderRadius: 10,
  padding: '10px 14px',
}
const pillStyle: CSSProperties = {
  fontSize: 12,
  fontWeight: 700,
  padding: '3px 8px',
  borderRadius: 6,
  background: '#FFFFFF',
}
const ruleGridStyle: CSSProperties = {
  display: 'grid',
  gridTemplateColumns: 'repeat(auto-fit, minmax(300px, 1fr))',
  gap: 8,
  fontSize: 13,
}
const ruleStyle: CSSProperties = {
  border: '1px solid #E2E8F0',
  background: '#FFFFFF',
  color: '#475569',
  borderRadius: 8,
  padding: '8px 10px',
}
