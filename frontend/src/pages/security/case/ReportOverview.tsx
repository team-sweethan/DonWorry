import type { CSSProperties } from 'react'
import { Tooltip } from 'antd'
import type { BasisSignal, NormalBasis, ReportBody } from '../../../types/case'
import { won } from '../../../utils/format'
import {
  appendedFactTexts,
  mlReportLine,
  overviewText,
  paymentCardId,
  ruleEvidenceRows,
  signalTexts,
  summaryFold,
  summaryLines,
  topRiskScore,
} from '../../../utils/reportView'
import { mlCell, type MlCell, type MlScores } from '../../../utils/transactionList'
import { SummaryBox } from './BasisCard'
import { dateTime } from './caseLabels'
import { ReportSection } from './ReportSection'

// ① 무슨 일인가 (#644 PR 3) — 개요 한 줄 · 가로 타임라인 · 결제 표 · 한눈에 보기(AI 문장)
// 개요 · 타임라인 · 결제 표는 코드가 채운 칸이고, AI 문장은 한눈에 보기에만 있다 (#543)
// 결제 표 줄 id는 paymentCardId — 한눈에 보기의 "결제 N 접속 정보" 링크가 이 줄로 온다 (#559)

type PaymentAnalysis = ReportBody['paymentAnalyses'][number]

export function ReportOverview({
  body,
  label,
  ml,
  marks,
}: {
  body: ReportBody
  label: string
  ml: MlScores
  marks: { signals: BasisSignal[]; normals: NormalBasis[] } | null
}) {
  const score = topRiskScore(body.paymentAnalyses.map((p) => p.riskScore))
  const summary = summaryLines(body)
  return (
    <ReportSection no="①" title="무슨 일인가" by="code" note={label}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
        <p style={{ margin: 0, fontSize: 17, lineHeight: 1.7, color: '#0F172A' }}>
          {overviewText(body)}
        </p>
        <span style={{ fontSize: 13, color: '#475569' }}>
          FDS 최고 {score ?? '-'}점 (확률 아님) · 판정은 보안 직원이 합니다
        </span>
      </div>
      <HorizontalTimeline items={body.timeline} />
      <PaymentTable payments={body.paymentAnalyses} ml={ml} />
      <div style={{ fontSize: 12, color: '#64748B' }}>
        FDS 점수는 걸린 규칙 점수의 합이고 확률이 아닙니다. ML 참고 위험도는 결제 승인에 쓰지 않는
        참고 값입니다.
      </div>
      <SummaryBox
        lines={summary.map((l) => l.text)}
        basis={marks}
        links={summary.map((l) => l.links)}
        fold={summaryFold(body)}
        appended={appendedFactTexts(body)}
      />
    </ReportSection>
  )
}

// 사건 경위 — 왼쪽에서 오른쪽으로 시간순. 칸 너비를 고정해 사건마다 모양이 같게 둔다
function HorizontalTimeline({ items }: { items: ReportBody['timeline'] }) {
  if (items.length === 0) return null
  return (
    <div style={{ overflowX: 'auto', paddingBottom: 4 }}>
      <ol aria-label="사건 경위" style={timelineStyle}>
        {items.map((t, i) => (
          <li key={i} style={timelineItemStyle}>
            <span style={{ ...dotStyle, ...timelineDot[t.kind ?? 'OTHER'] }} />
            <span style={{ fontSize: 12, color: '#64748B' }}>{dateTime(t.at)}</span>
            <span style={{ fontSize: 13, lineHeight: 1.5, color: '#0F172A' }}>{t.event}</span>
          </li>
        ))}
      </ol>
    </div>
  )
}

// 결제 표 — 카드 · 접속 · FDS 점수 · 걸린 규칙 · ML 참고. 결제별 근거 카드(#591)를 한 줄씩으로 합쳤다
function PaymentTable({ payments, ml }: { payments: PaymentAnalysis[]; ml: MlScores }) {
  return (
    <div style={{ overflowX: 'auto', border: '1px solid #E2E8F0', borderRadius: 10 }}>
      <div role="table" aria-label="사건 결제" style={{ minWidth: 960 }}>
        <div role="row" style={{ ...rowStyle, background: '#F8FAFC', fontWeight: 700 }}>
          <span role="columnheader">결제</span>
          <span role="columnheader">요청 시각</span>
          <span role="columnheader" style={{ textAlign: 'right' }}>
            금액
          </span>
          <span role="columnheader">카드 · 접속</span>
          <span role="columnheader" style={{ textAlign: 'right' }}>
            FDS 점수
          </span>
          <span role="columnheader">걸린 규칙</span>
          <span role="columnheader">ML 참고</span>
        </div>
        {payments.map((p) => (
          <div
            key={p.paymentId}
            id={paymentCardId(p.paymentId)}
            role="row"
            style={{ ...rowStyle, borderTop: '1px solid #E2E8F0', scrollMarginTop: 80 }}
          >
            <span role="cell" style={{ fontWeight: 700 }}>
              {p.paymentId}
            </span>
            <span role="cell">{dateTime(p.requestedAt)}</span>
            <span role="cell" style={{ textAlign: 'right' }}>
              {won(p.amount)}
            </span>
            <span role="cell">
              <AccessCell signals={p.signals} />
            </span>
            <span role="cell" style={{ textAlign: 'right' }}>
              {p.riskScore === null ? '모름' : `${p.riskScore}점`}
            </span>
            <span role="cell">
              <RuleCell analysis={p} />
            </span>
            <span role="cell">
              <MlCellText cell={mlCell(p.paymentId, ml)} />
            </span>
          </div>
        ))}
      </div>
    </div>
  )
}

// 카드 · 기기 · 접속 국가 (#346) — 가맹점이 안 보낸 값은 "정보 없음"
function AccessCell({ signals }: { signals: PaymentAnalysis['signals'] }) {
  if (signals === undefined) return <span style={mutedStyle}>—</span>
  if (signals === null) return <span style={mutedStyle}>FDS 평가 없음</span>
  const [card, device, country] = signalTexts(signals)
  const short = (t: { value: string; missing: boolean }) => (t.missing ? '정보 없음' : t.value)
  return (
    <span style={{ display: 'flex', flexDirection: 'column', gap: 1 }}>
      <span style={card.missing ? mutedStyle : undefined}>{short(card)}</span>
      <span style={{ fontSize: 12, color: '#64748B' }}>
        접속 {short(country)} · 기기 {short(device)}
      </span>
    </span>
  )
}

// 걸린 규칙 · 규칙 점수 — 기준 · 이번 값은 글자를 줄이려고 마우스를 올리면 보인다
function RuleCell({ analysis }: { analysis: PaymentAnalysis }) {
  const rules = ruleEvidenceRows(analysis)
  if (rules.length === 0) return <span style={mutedStyle}>없음</span>
  return (
    <span style={{ display: 'flex', flexWrap: 'wrap', gap: '2px 10px' }}>
      {rules.map((r, i) => {
        const text = (
          <span>
            {r.name}
            {r.appliedScore !== null && (
              <span style={{ color: '#64748B' }}> +{r.appliedScore}</span>
            )}
          </span>
        )
        return r.criterion && r.observed ? (
          <Tooltip key={i} title={`기준 ${r.criterion} · 이번 ${r.observed}`}>
            {text}
          </Tooltip>
        ) : (
          <span key={i}>{text}</span>
        )
      })}
    </span>
  )
}

// ML 참고 위험도 (#544) — 거래 내역 "ML 참고" 칸과 같은 짧은 글자, 자세한 문장은 마우스를 올리면
function MlCellText({ cell }: { cell: MlCell }) {
  const text =
    cell.kind === 'scored'
      ? `${cell.percent}%${cell.aboveThreshold ? ' · 기준 이상' : ''}`
      : {
          loading: '…',
          unknown: '확인 불가',
          none: '기록 없음',
          failed: '채점 실패',
          skipped: '채점 안 함',
        }[cell.kind]
  const strong = cell.kind === 'scored' && cell.aboveThreshold
  return (
    <Tooltip title={mlReportLine(cell)}>
      <span style={strong ? { color: '#B45309', fontWeight: 700 } : mutedStyle}>{text}</span>
    </Tooltip>
  )
}

const rowStyle: CSSProperties = {
  display: 'grid',
  gridTemplateColumns: '84px 136px 110px 200px 80px minmax(220px, 1fr) 120px',
  gap: 12,
  alignItems: 'start',
  padding: '10px 14px',
  fontSize: 13,
  borderRadius: 8,
}
const mutedStyle: CSSProperties = { color: '#94A3B8' }
const timelineStyle: CSSProperties = {
  display: 'flex',
  margin: 0,
  padding: '0 0 0 2px',
  listStyle: 'none',
}
const timelineItemStyle: CSSProperties = {
  position: 'relative',
  flex: '0 0 168px',
  display: 'flex',
  flexDirection: 'column',
  gap: 2,
  padding: '22px 12px 0 0',
  borderTop: '2px solid #E2E8F0',
  marginTop: 6,
}
const dotStyle: CSSProperties = {
  position: 'absolute',
  left: 0,
  top: -7,
  width: 12,
  height: 12,
  borderRadius: '50%',
  background: '#fff',
  // 색은 timelineDot이 정한다 — border 한 줄 묶음과 섞으면 색 덮어쓰기가 안 될 수 있다
  borderWidth: 2,
  borderStyle: 'solid',
}
// 점 색 (#310) — 결제(매입 보류) 주황 · 사건 생성 파랑, 종류가 없는 예전 보고서는 회색
const timelineDot: Record<'PAYMENT' | 'CASE' | 'OTHER', CSSProperties> = {
  PAYMENT: { borderColor: '#D97706', background: '#FEF3C7' },
  CASE: { borderColor: '#2563EB', background: '#2563EB' },
  OTHER: { borderColor: '#94A3B8' },
}
