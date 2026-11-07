import { useState, type CSSProperties, type ReactNode } from 'react'
import { Tag } from 'antd'
import type { BasisSignal, DecisionBasis, NormalBasis } from '../../../types/case'
import {
  markSignals,
  normalNote,
  signalTag,
  type MarkKind,
  type SummaryFold,
  type SummaryLink,
} from '../../../utils/reportView'
import { goToCard } from './goToCard'

// 징후 · 정상 근거 부품 (#501) — 보고서 "한눈에 보기"(AI 문장)와 비교 화면의 징후 · 정상 근거 줄
// 판정 권고 · 기준표 칸 · 판정 이유는 #644에서 뺐다 (ADR-0009 결정 13). 결제별 근거 카드도 #644 PR 3에서 ② 확인할 것으로 합쳤다

// 고위험 빨강 · 주의 노랑 · 정상 초록 — 밑줄과 근거 칸이 같은 색을 쓴다
const levelTone: Record<MarkKind, { bg: string; border: string; text: string; line: string }> = {
  HIGH_RISK: { bg: '#FEF2F2', border: '#FECACA', text: '#7F1D1D', line: '#DC2626' },
  CAUTION: { bg: '#FFFBEB', border: '#FDE68A', text: '#78350F', line: '#F59E0B' },
  NORMAL: { bg: '#F0FDF4', border: '#BBF7D0', text: '#14532D', line: '#16A34A' },
}

// 한눈에 보기 (#644, 전 "조사 요약") — AI가 자료 여러 개를 이어 설명한 문장. 징후 이름이 그대로 나오면 같은 색 밑줄
// links: 문장 끝 표 링크 (#559) — 밑줄과 뜻이 겹치지 않게 글자 링크로 둔다
// 문장은 점 목록으로, fold가 있으면 처음 몇 문장만 보이고 [더 보기] (#591)
// 번호 대신 점 — 판정 순서처럼 읽히지 않게
// appended: 안전망 — AI가 빠뜨려 시스템이 덧붙인 사실. 접지 않고 늘 보인다
export function SummaryBox({
  lines,
  basis,
  links = [],
  fold,
  appended = [],
}: {
  lines: string[]
  basis: Pick<DecisionBasis, 'signals' | 'normals'> | null
  links?: SummaryLink[][]
  fold?: SummaryFold
  appended?: string[]
}) {
  const [open, setOpen] = useState(false)
  if (lines.length === 0 && appended.length === 0) return null
  const folded = !!fold && fold.hidden > 0 && !open
  const shown = folded ? lines.slice(0, lines.length - fold.hidden) : lines
  return (
    <div style={summaryStyle}>
      <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 10 }}>
        <h3 style={{ margin: 0, fontSize: 15, fontWeight: 700, color: '#581C87' }}>한눈에 보기</h3>
        <Tag color="purple" style={{ marginInlineEnd: 0 }}>
          AI가 씀 · 자료 여러 개를 함께 봐야 보이는 것 · 판정이 아닙니다
        </Tag>
      </div>
      <ul style={listStyle}>
        {shown.map((line, i) => (
          <li key={i}>
            {markSignals(line, basis).map((part, j) =>
              part.mark ? (
                <span key={j} style={{ borderBottom: `3px solid ${levelTone[part.mark].line}` }}>
                  {part.text}
                </span>
              ) : (
                <span key={j}>{part.text}</span>
              ),
            )}
            <SummaryLinks links={links[i] ?? []} color="#6D28D9" />
          </li>
        ))}
      </ul>
      {fold && fold.hidden > 0 && (
        <button type="button" onClick={() => setOpen(!open)} style={moreStyle}>
          {open ? (
            '접기'
          ) : (
            <>
              더 보기 · {fold.hidden}문장 남음
              {fold.hiddenNotable > 0 && (
                <b style={{ color: '#92400E' }}> · 눈여겨볼 사실 {fold.hiddenNotable}개 포함</b>
              )}
            </>
          )}
        </button>
      )}
      {appended.length > 0 && (
        <div style={appendedStyle}>
          <b>⚠ 요약에 빠진 사실 (시스템이 덧붙임)</b>
          <ul style={{ margin: '4px 0 0', paddingLeft: 20 }}>
            {appended.map((text, i) => (
              <li key={i}>{text}</li>
            ))}
          </ul>
        </div>
      )}
      <div style={{ fontSize: 12, color: '#6B21A8' }}>
        밑줄 색은 아래 확인할 것과 같은 색입니다. 빨강 고위험 징후 · 노랑 주의 징후 · 초록 정상 근거
      </div>
    </div>
  )
}

// 요약 문장 끝 "› 고객 최근 결제" 링크 — 누르면 그 카드로 이동해 잠깐 강조한다 (#559)
export function SummaryLinks({ links, color }: { links: SummaryLink[]; color: string }) {
  return links.map((link) => (
    <button
      key={link.target}
      type="button"
      onClick={() => goToCard(link.target)}
      style={{ ...linkStyle, color }}
    >
      › {link.label}
    </button>
  ))
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

const linkStyle: CSSProperties = {
  background: 'none',
  border: 'none',
  padding: '0 0 0 6px',
  fontSize: 12.5,
  fontWeight: 600,
  cursor: 'pointer',
  whiteSpace: 'nowrap',
}
const summaryStyle: CSSProperties = {
  background: '#FAF5FF',
  border: '1px solid #E9D5FF',
  borderRadius: 12,
  padding: '14px 18px',
  display: 'flex',
  flexDirection: 'column',
  gap: 8,
}
const listStyle: CSSProperties = {
  margin: 0,
  paddingLeft: 20,
  fontSize: 14.5,
  lineHeight: 1.7,
  color: '#1E1B4B',
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
}
const moreStyle: CSSProperties = {
  alignSelf: 'flex-start',
  minHeight: 40,
  padding: '0 14px',
  border: '1px solid #D8B4FE',
  background: '#fff',
  borderRadius: 8,
  fontSize: 14,
  color: '#581C87',
  cursor: 'pointer',
}
const appendedStyle: CSSProperties = {
  fontSize: 14,
  lineHeight: 1.65,
  color: '#78350F',
  background: '#FFFBEB',
  border: '1px solid #FDE68A',
  borderRadius: 8,
  padding: '8px 12px',
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
