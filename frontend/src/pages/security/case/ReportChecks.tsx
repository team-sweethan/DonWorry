import type { CSSProperties } from 'react'
import type { BasisSignal, ReportBody } from '../../../types/case'
import {
  caseNormals,
  caseSignalId,
  checkSignalRecord,
  checkSignals,
  isOpenSignal,
  normalNote,
  signalRecordText,
  type CheckSignal,
} from '../../../utils/reportView'
import { goToCard } from './goToCard'
import { ReportSection } from './ReportSection'

// ② 확인할 것 (#644 PR 3) — 왼쪽 이상 징후 · 오른쪽 정상 근거 반반. 게이지 · 기울기는 그리지 않는다
// 카드 개수가 무게로 읽히면 주의 징후만 여럿인 정상 손님이 사기처럼 보인다 (#618) — 등급 색이 먼저 보이게
// 징후 카드 = 이름 · 등급 / 사실 / 확인할 것 / 결제 번호. 결제마다 반복하던 근거(#591 ④)를 한 장으로 합쳤다
// 체크는 화면에서만 기억한다 — 판정 기록이 아니다

const LEVEL = {
  HIGH_RISK: { text: '고위험', line: '#DC2626', bg: '#FEF2F2', fg: '#7F1D1D' },
  CAUTION: { text: '주의', line: '#F59E0B', bg: '#FFFBEB', fg: '#78350F' },
} satisfies Record<BasisSignal['level'], { text: string; line: string; bg: string; fg: string }>

// 근거 자료로 이동할 수 있는 징후 — 코드로 확인한 것만 잇는다 (11/08 결정, 나머지는 추측해서 잇지 않음)
const SIGNAL_CARD: Record<string, string> = {
  LINKED_CARD: 'report-linked',
  LINKED_DEVICE: 'report-linked',
  LINKED_IP: 'report-linked',
}

// 정상 근거를 하나도 찾지 못했을 때 "무엇을 찾아봤는지" — Spring DecisionBasisCalculator의 정상 근거는 비슷한 금액 하나
// 기준을 바꾸면 여기도 함께 고친다 (SIMILAR_LOW · SIMILAR_HIGH · 2건 이상)
const NORMAL_SEARCHED = '최근 30일, 이번 금액의 0.5 ~ 1.5배 결제 중 매입 완료 2건 이상'

export function ReportChecks({
  body,
  checked,
  onToggle,
}: {
  body: ReportBody
  checked: string[]
  onToggle: (id: string) => void
}) {
  const signals = checkSignals(body)
  return (
    <ReportSection
      no="②"
      title="확인할 것"
      by="code"
      note="징후마다 무엇을 확인하면 되는지 · 체크는 이 화면에서만 기억합니다 (판정 기록 아님)"
    >
      {signals === null ? (
        <div style={{ fontSize: 13.5, color: '#475569' }}>
          징후 목록이 생기기 전 보고서입니다. 위 ① 결제 표의 걸린 규칙을 확인하세요.
        </div>
      ) : (
        <div style={columnsStyle}>
          <SignalSide signals={signals} body={body} checked={checked} onToggle={onToggle} />
          <NormalSide body={body} />
        </div>
      )}
      {/* 징후 정리가 없거나(예전 보고서 · 연결 조회 실패로 빈 근거) 있어도 늘 보인다 (#652 Codex 리뷰) */}
      {body.additionalChecks.length > 0 && (
        <div style={{ fontSize: 13, color: '#334155', lineHeight: 1.6 }}>
          <b style={{ color: '#6D28D9' }}>AI 추가 제안</b> · {body.additionalChecks.join(' · ')}
        </div>
      )}
    </ReportSection>
  )
}

function SignalSide({
  signals,
  body,
  checked,
  onToggle,
}: {
  signals: CheckSignal[]
  body: ReportBody
  checked: string[]
  onToggle: (id: string) => void
}) {
  // 다른 징후에 포함된 것은 카드 대신 아래 한 줄로 — 같은 행동을 두 번 세지 않는다
  const counted = signals.filter((s) => s.countedIn)
  const cards = signals.filter((s) => !s.countedIn)
  const high = cards.filter((s) => s.level === 'HIGH_RISK').length
  return (
    <div style={sideStyle}>
      <div style={sideHeadStyle}>
        <b style={{ color: '#991B1B' }}>이상 징후</b>
        <span style={countStyle}>
          고위험 {high} · 주의 {cards.length - high}
        </span>
      </div>
      {cards.length === 0 && <div style={emptyStyle}>적중한 징후가 없습니다.</div>}
      {cards.map((s) => {
        const id = caseSignalId(s)
        const record = checkSignalRecord(body, s)
        return (
          <SignalCard
            key={id}
            s={s}
            fact={[s.detail, record && signalRecordText(record)].filter(Boolean).join(' · ')}
            checked={checked.includes(id)}
            onToggle={() => onToggle(id)}
          />
        )
      })}
      {counted.map((s) => (
        <div key={caseSignalId(s)} style={{ fontSize: 12.5, color: '#64748B' }}>
          {s.label}({LEVEL[s.level].text}, 결제 {s.paymentIds.join(' · ')})는 {s.countedIn}와 같은
          행동이라 따로 세지 않았습니다.
        </div>
      ))}
    </div>
  )
}

// 체크박스는 정상 사유가 없는 징후에만 — 설명된 징후는 확인할 것을 흐리게 보여 준다
function SignalCard({
  s,
  fact,
  checked,
  onToggle,
}: {
  s: CheckSignal
  fact: string
  checked: boolean
  onToggle: () => void
}) {
  const tone = LEVEL[s.level]
  const open = isOpenSignal(s)
  const target = SIGNAL_CARD[s.key]
  return (
    <div
      style={{
        ...cardStyle,
        borderLeft: `4px solid ${tone.line}`,
        background: open ? tone.bg : '#F8FAFC',
        opacity: checked ? 0.6 : 1,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        {open && s.checkBefore && (
          <input
            type="checkbox"
            aria-label={`${s.label} 확인함`}
            checked={checked}
            onChange={onToggle}
            style={{ width: 18, height: 18, flex: 'none', margin: 0 }}
          />
        )}
        <b style={{ fontSize: 14, color: tone.fg }}>{s.label}</b>
        <span style={{ ...pillStyle, color: tone.fg, borderColor: tone.line }}>{tone.text}</span>
      </div>
      {(fact || target) && (
        <div style={{ fontSize: 12.5, color: '#475569' }}>
          {fact}
          {target && (
            <button type="button" onClick={() => goToCard(target)} style={linkStyle}>
              › 같은 카드 · 기기 · IP
            </button>
          )}
        </div>
      )}
      {s.checkBefore && (
        <div style={{ fontSize: 13.5, color: open ? '#0F172A' : '#64748B', lineHeight: 1.55 }}>
          확인 → {s.checkBefore}
        </div>
      )}
      <div style={{ fontSize: 12, color: '#64748B' }}>
        결제 {s.paymentIds.join(' · ')} ·{' '}
        {s.mixed ? (
          <b style={{ color: '#92400E' }}>
            결제 {s.openPaymentIds.join(' · ')}만 정상 사유 없음 · 다른 결제는 정상 사유 있음
          </b>
        ) : open ? (
          <b style={{ color: '#92400E' }}>정상 사유 찾지 못함</b>
        ) : (
          <span style={{ color: '#166534' }}>정상 사유 있음 · {s.normalReason}</span>
        )}
      </div>
    </div>
  )
}

// 정상 근거 — 설명하는 징후를 함께 적는다. 못 찾았으면 무엇을 찾아봤는지 한 칸
function NormalSide({ body }: { body: ReportBody }) {
  const normals = caseNormals(body)
  return (
    <div style={sideStyle}>
      <div style={sideHeadStyle}>
        <b style={{ color: '#166534' }}>정상 근거</b>
        <span style={countStyle}>찾은 것 {normals.length}</span>
      </div>
      {normals.length === 0 ? (
        <div style={{ ...cardStyle, background: '#F8FAFC', border: '1px dashed #CBD5E1' }}>
          <b style={{ fontSize: 14, color: '#475569' }}>찾지 못함</b>
          <span style={{ fontSize: 12.5, color: '#64748B' }}>찾아본 것: {NORMAL_SEARCHED}</span>
        </div>
      ) : (
        normals.map((n) => (
          <div
            key={n.label}
            style={{ ...cardStyle, background: '#F0FDF4', borderLeft: '4px solid #16A34A' }}
          >
            <b style={{ fontSize: 14, color: '#14532D' }}>{n.label}</b>
            <span style={{ fontSize: 12.5, color: '#166534' }}>{normalNote(n)}</span>
          </div>
        ))
      )}
      <div style={{ fontSize: 12.5, color: '#64748B' }}>
        직원이 카드사 · 고객 확인으로 정상 사유를 찾으면 판정 사유에 적습니다.
      </div>
    </div>
  )
}

const columnsStyle: CSSProperties = {
  display: 'grid',
  gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))',
  gap: 16,
  alignItems: 'start',
}
const sideStyle: CSSProperties = { display: 'flex', flexDirection: 'column', gap: 10 }
const sideHeadStyle: CSSProperties = {
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'baseline',
  fontSize: 14,
}
const countStyle: CSSProperties = { fontSize: 12, color: '#64748B' }
const cardStyle: CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
  border: '1px solid #E2E8F0',
  borderRadius: 10,
  padding: '12px 14px',
}
const pillStyle: CSSProperties = {
  fontSize: 11.5,
  fontWeight: 700,
  padding: '1px 7px',
  borderRadius: 5,
  border: '1px solid',
  background: '#fff',
}
const emptyStyle: CSSProperties = { fontSize: 13, color: '#94A3B8' }
const linkStyle: CSSProperties = {
  background: 'none',
  border: 'none',
  padding: '0 0 0 6px',
  fontSize: 12.5,
  fontWeight: 600,
  color: '#1D4ED8',
  cursor: 'pointer',
  whiteSpace: 'nowrap',
}
