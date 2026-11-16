import type { CSSProperties } from 'react'
import { Collapse } from 'antd'
import type { ReportBody } from '../../../types/case'
import { askQuestionFor } from '../../../utils/reportQuestionView'
import {
  caseSignalId,
  checkSignalRecord,
  extraResultText,
  extrasBySuspicion,
  isOpenSignal,
  linkedCasesForSignal,
  paymentCardId,
  reducingFact,
  signalRecordText,
  suspicionCards,
  type CheckSignal,
  type ExtraView,
  type ExtrasBySuspicion,
  type ReducingKind,
} from '../../../utils/reportView'
import { dateTime } from './caseLabels'
import { goToCard } from './goToCard'
import { LinkedCaseList } from './ReportLinkedCases'
import { ReportSection } from './ReportSection'
import { SIGNAL_LEVEL } from './signalLevel'
import './reportPrint.css'

// ② 의심별 조사 (#819) — 의심 하나당 카드 한 장: 키우는 사실 · 줄이는 사실 · AI가 넓혀 본 것 · 아직 모르는 것
// 예전 ② 확인할 것(이상 징후 칸 · 정상 근거 칸 따로)을 합쳤다 — 직원이 근거를 징후에 짝짓지 않게
// 사실을 의심에 붙이는 것은 코드다 (AI가 위험 · 정상으로 나누지 않음, ADR-0009 결정 8). 점수는 결제 표에만 (카드에는 규칙 기록 한 줄)
// 정상 사유가 없는 의심이 위(고위험 먼저), 설명된 의심 · 다른 징후에 포함된 의심은 아래 접는 줄
// 체크는 화면에서만 기억한다 — 판정 기록이 아니다. 체크 id는 caseSignalId (아래 고정 줄 checkRows와 같다)

const LEVEL = SIGNAL_LEVEL

// 근거 자료로 이동 — 같은 카드 · 기기 · IP는 그 표, 나머지(규칙 · 해외 접속)는 결제 표의 그 결제 줄 (걸린 규칙 · 접속 칸)
const LINKED_CARD_TARGET: Record<string, string> = {
  LINKED_CARD: 'report-linked',
  LINKED_DEVICE: 'report-linked',
  LINKED_IP: 'report-linked',
}

const LINKED_KIND_LABEL: Record<string, string> = {
  LINKED_CARD: '카드',
  LINKED_DEVICE: '기기',
  LINKED_IP: 'IP',
}

export function ReportSuspicions({
  body,
  checked,
  onToggle,
  onAsk,
}: {
  body: ReportBody
  checked: string[]
  onToggle: (id: string) => void
  // 질문 칸이 있을 때만 온다 (고정 보고서는 질문할 수 없다)
  onAsk?: (question: string) => void
}) {
  const cards = suspicionCards(body)
  const extras = extrasBySuspicion(body)
  const card = (s: CheckSignal) => {
    const id = caseSignalId(s)
    return (
      <SuspicionCard
        key={id}
        s={s}
        body={body}
        extras={extras}
        checked={checked.includes(id)}
        onToggle={() => onToggle(id)}
        onAsk={onAsk}
      />
    )
  }
  return (
    <ReportSection
      no="②"
      title="의심별 조사"
      by="code"
      note="정상 사유로 설명되지 않은 의심부터 · 고위험 먼저 · 체크는 이 화면에서만 기억합니다 (판정 기록 아님)"
    >
      {cards === null ? (
        <div style={{ fontSize: 13.5, color: '#475569' }}>
          징후 목록이 생기기 전 보고서입니다. 위 ① 결제 표의 걸린 규칙을 확인하세요.
        </div>
      ) : (
        <>
          <div style={{ fontSize: 13, color: '#475569' }}>
            정상 사유로 설명되지 않은 의심 <b>{cards.open.length}</b>개 / 전체{' '}
            {cards.open.length + cards.explained.length}개
          </div>
          {cards.open.length === 0 && (
            <div style={{ fontSize: 13, color: '#94A3B8' }}>
              정상 사유로 설명되지 않은 의심이 없습니다.
            </div>
          )}
          {cards.open.map(card)}
          {(cards.explained.length > 0 || cards.counted.length > 0) && (
            <Collapse
              items={[
                {
                  key: 'settled',
                  label: (
                    <span style={{ fontSize: 13.5 }}>
                      정상 사유로 설명된 의심 {cards.explained.length}개 · 다른 징후에 포함된 행동{' '}
                      {cards.counted.length}개
                    </span>
                  ),
                  // 접어 둬도 미리 그려 인쇄 때 펼쳐 찍는다 (#623, reportPrint.css)
                  forceRender: true,
                  children: (
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                      {cards.explained.map(card)}
                      {cards.counted.map((s) => (
                        <div key={caseSignalId(s)} style={{ fontSize: 12.5, color: '#64748B' }}>
                          {s.label}({LEVEL[s.level].text}, 결제 {s.paymentIds.join(' · ')})는{' '}
                          {s.countedIn}와 같은 행동이라 따로 세지 않았습니다.
                        </div>
                      ))}
                    </div>
                  ),
                },
              ]}
            />
          )}
        </>
      )}
      {extras && extras.caseWide.length > 0 && (
        <div style={caseWideStyle}>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, alignItems: 'center' }}>
            <b style={{ fontSize: 14, color: '#6D28D9' }}>사건 전체로 넓혀 본 것</b>
            <span style={aiTagStyle}>AI 선택</span>
          </div>
          <ExtraList extras={extras.caseWide} />
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

const REDUCING_COLOR: Record<ReducingKind, string> = {
  EXPLAINED: '#166534',
  MIXED: '#92400E',
  UNCHECKED: '#92400E',
  NOT_FOUND: '#64748B',
  NO_RULE: '#64748B',
}

// 체크한 카드는 화면에서 흐리게(dw-check-done), 인쇄는 체크칸이 빠지니 흐림 대신 "✓ 확인함" (#662, reportPrint.css)
function SuspicionCard({
  s,
  body,
  extras,
  checked,
  onToggle,
  onAsk,
}: {
  s: CheckSignal
  body: ReportBody
  extras: ExtrasBySuspicion | null
  checked: boolean
  onToggle: () => void
  onAsk?: (question: string) => void
}) {
  const tone = LEVEL[s.level]
  const open = isOpenSignal(s)
  const record = checkSignalRecord(body, s)
  const reducing = reducingFact(body, s)
  const linkTarget = LINKED_CARD_TARGET[s.key] ?? paymentCardId(s.shownPaymentId)
  const linkLabel = LINKED_CARD_TARGET[s.key]
    ? '› 같은 카드 · 기기 · IP'
    : `› 결제 ${s.shownPaymentId}`
  const mine = extras?.bySignal.get(caseSignalId(s)) ?? []
  // 같은 카드 · 기기 · IP 징후에만 — 이 의심의 결제와 그 종류로 겹친 지난 사건 (#821)
  const pastCases = linkedCasesForSignal(body, s)
  return (
    <article className={checked ? 'dw-check-done' : undefined} style={cardStyle}>
      <div
        style={{
          ...headStyle,
          borderLeft: `5px solid ${tone.line}`,
          background: open ? tone.bg : '#F8FAFC',
        }}
      >
        <b style={{ fontSize: 15, color: tone.fg }}>{s.label}</b>
        <span style={{ ...pillStyle, color: tone.fg, borderColor: tone.line }}>{tone.text}</span>
        <span style={{ fontSize: 12, color: '#64748B' }}>결제 {s.paymentIds.join(' · ')}</span>
        {checked && (
          <span
            className="dw-print-only"
            style={{ fontSize: 12.5, fontWeight: 600, color: '#166534' }}
          >
            ✓ 확인함
          </span>
        )}
        {record && (
          <span style={{ marginLeft: 'auto', fontSize: 12, color: '#64748B' }}>
            {signalRecordText(record)}
          </span>
        )}
      </div>
      <div style={twoColStyle}>
        <div style={cellStyle}>
          <div style={{ ...labelStyle, color: '#9A3412' }}>의심을 키우는 사실</div>
          <div style={{ fontSize: 13.5, color: '#0F172A' }}>
            {s.detail ?? <span style={{ color: '#94A3B8' }}>세부 기록 없음</span>}
            <button type="button" onClick={() => goToCard(linkTarget)} style={linkStyle}>
              {linkLabel}
            </button>
          </div>
          {pastCases.length > 0 && (
            <div style={{ marginTop: 6 }}>
              <div style={{ fontSize: 12.5, color: '#9A3412' }}>
                이 {LINKED_KIND_LABEL[s.key]}로 열린 지난 사건 (다른 고객)
              </div>
              <LinkedCaseList cases={pastCases} compact />
            </div>
          )}
        </div>
        <div style={cellStyle}>
          <div style={{ ...labelStyle, color: '#166534' }}>의심을 줄이는 사실</div>
          <div style={{ fontSize: 13.5, color: REDUCING_COLOR[reducing.kind] }}>
            {reducing.text}
          </div>
        </div>
      </div>
      {/* extraInvestigations가 없는 보고서(#817 전 · 고정)는 칸을 그리지 않는다 — 고정 보고서는 맨 위 안내가 있다 */}
      {extras && (
        <div style={{ ...cellStyle, background: '#F5F3FF', borderTop: '1px solid #E2E8F0' }}>
          <div style={{ ...labelStyle, color: '#6D28D9' }}>
            AI가 넓혀 본 것 <span style={aiTagStyle}>AI 선택</span>
          </div>
          {mine.length === 0 ? (
            <div style={{ fontSize: 13, color: '#64748B' }}>추가 조회 없음</div>
          ) : (
            <ExtraList extras={mine} />
          )}
        </div>
      )}
      {s.checkBefore && (
        <div style={{ ...cellStyle, background: '#FFF7ED', borderTop: '1px solid #E2E8F0' }}>
          <div style={{ ...labelStyle, color: '#9A3412' }}>아직 모르는 것</div>
          <label style={{ display: 'flex', gap: 8, alignItems: 'flex-start', fontSize: 13.5 }}>
            {/* 체크박스는 정상 사유가 없는 의심에만 — 설명된 의심은 할 일을 흐리게 보여 준다 */}
            {open && (
              <input
                type="checkbox"
                className="dw-no-print"
                aria-label={`${s.label} 확인함`}
                checked={checked}
                onChange={onToggle}
                style={{ width: 18, height: 18, flex: 'none', margin: '1px 0 0' }}
              />
            )}
            <span style={{ color: open ? '#0F172A' : '#64748B' }}>{s.checkBefore}</span>
          </label>
        </div>
      )}
      {onAsk && (
        <div className="dw-no-print" style={askRowStyle}>
          <button type="button" onClick={() => onAsk(askQuestionFor(s))} style={linkStyle}>
            › 이 의심에 대해 AI에게 묻기
          </button>
          <span style={{ fontSize: 12, color: '#64748B' }}>
            질문 칸에 “{askQuestionFor(s)}”를 채웁니다 · 보내기는 직접
          </span>
        </div>
      )}
    </article>
  )
}

// AI 추가 조회 목록 — 왜(코드 문장) → 무엇을 · 언제 → 새로 나온 사실. AI는 사실만 가져오고 등급 · 정상 사유는 바꾸지 않는다
function ExtraList({ extras }: { extras: ExtraView[] }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
      {extras.map((e) => {
        const result = extraResultText(e)
        return (
          <div key={e.key} style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
            <div style={{ fontSize: 12.5, color: '#5B21B6' }}>왜: {e.reason}</div>
            {e.facts.length > 0 && (
              <ul style={{ margin: 0, paddingLeft: 18, fontSize: 13.5, lineHeight: 1.6 }}>
                {e.facts.map((f) => (
                  <li key={f.id}>
                    {f.text}
                    {f.link && (
                      <button
                        type="button"
                        onClick={() => f.link && goToCard(f.link.target)}
                        style={linkStyle}
                      >
                        › {f.link.label}
                      </button>
                    )}
                  </li>
                ))}
              </ul>
            )}
            {result && (
              <div
                style={{
                  fontSize: 13,
                  color: e.status === 'FAILED' ? '#B45309' : '#64748B',
                  fontWeight: e.status === 'FAILED' ? 600 : 400,
                }}
              >
                {result}
              </div>
            )}
            <div style={{ fontSize: 11.5, color: '#64748B' }}>
              {e.title}
              {e.retrievedAt && ` · 조회 ${dateTime(e.retrievedAt)}`}
              {e.evidenceId && ` · 증거 ${e.evidenceId}`}
            </div>
          </div>
        )
      })}
    </div>
  )
}

const cardStyle: CSSProperties = {
  border: '1px solid #E2E8F0',
  borderRadius: 12,
  overflow: 'hidden',
  background: '#fff',
}
const headStyle: CSSProperties = {
  display: 'flex',
  flexWrap: 'wrap',
  alignItems: 'center',
  gap: 8,
  padding: '10px 14px',
}
const twoColStyle: CSSProperties = {
  display: 'grid',
  gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))',
  borderTop: '1px solid #E2E8F0',
}
const cellStyle: CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 4,
  padding: '10px 14px',
  minWidth: 0,
}
const labelStyle: CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 6,
  fontSize: 12,
  fontWeight: 800,
}
const pillStyle: CSSProperties = {
  fontSize: 11.5,
  fontWeight: 700,
  padding: '1px 7px',
  borderRadius: 5,
  border: '1px solid',
  background: '#fff',
}
const aiTagStyle: CSSProperties = {
  fontSize: 11,
  fontWeight: 700,
  padding: '0 6px',
  borderRadius: 4,
  background: '#EDE9FE',
  color: '#6D28D9',
}
const askRowStyle: CSSProperties = {
  display: 'flex',
  flexWrap: 'wrap',
  alignItems: 'baseline',
  gap: 4,
  padding: '8px 14px',
  borderTop: '1px solid #E2E8F0',
}
const caseWideStyle: CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 8,
  border: '1px dashed #C4B5FD',
  background: '#F5F3FF',
  borderRadius: 12,
  padding: '12px 14px',
}
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
