import { useEffect, useState, type CSSProperties, type ReactNode } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { getCase, isCaseAssigneeOnly } from '../../../api/cases'
import type { CaseDetail, ReportBody, SimilarCaseItem, SimilarCases } from '../../../types/case'
import { won } from '../../../utils/format'
import {
  compareColumn,
  compareReport,
  nowDecisionText,
  pastCompareRows,
  topRiskScore,
  type CompareColumn,
  type PastCell,
} from '../../../utils/reportView'
import { dateTime, decisionLabel } from './caseLabels'
import { ReportSection } from './ReportSection'

// ③ 지난 사건과 비교 (#644 PR 3) — 지금 사건과 비슷한 끝난 사건 2건을 징후 줄로 나란히 놓는다
// 줄 = 징후 · 정상 근거 · 직원 판정과 사유. 지금 사건과 다른 칸은 테두리. 해석 문장 · 권고 줄은 없다
// 지난 사건 자료는 기존 사건 상세 조회(getCase)로 화면이 가져온다 — 보고서에 새 자료를 넣지 않는다 (지난 사건 수만큼 조회가 는다)
// 지난 사건 하나를 못 불러와도 그 열만 "불러오지 못함"이고 나머지는 그대로 보인다

export const PAST_SHOWN = 2

type Past =
  | { status: 'loading' }
  | { status: 'error' | 'forbidden' | 'missing' }
  | { status: 'loaded'; detail: CaseDetail }

// 지금 사건 판정 진행 — decisionProgress 결과. [판정하러 가기] 조건(판정 0건)과 달리 일부 판정도 구분한다 (#652 Codex 리뷰)
export type NowProgress = { decided: number; remaining: number; done: boolean }

export function ReportPastCompare({
  body,
  similar,
  progress,
  refreshKey,
}: {
  body: ReportBody
  similar: SimilarCases | null
  progress: NowProgress
  // 화면의 [새로고침] 횟수 — 바뀌면 지난 사건도 다시 조회한다 (실패한 열을 새로고침으로 되살리게)
  refreshKey: number
}) {
  const shown = similar?.cases.slice(0, PAST_SHOWN) ?? []
  const pasts = usePastCases(
    shown.map((c) => c.caseId),
    refreshKey,
  )
  const rest = similar ? similar.cases.slice(PAST_SHOWN) : []
  const notListed = similar ? similar.totalMatched - similar.cases.length : 0
  return (
    <ReportSection
      id="report-similar"
      no="③"
      title="지난 사건과 비교"
      by="record"
      note="비슷한 끝난 사건 · 참고 자료이고 사건마다 사실이 다릅니다"
    >
      {similar === null ? (
        <div style={mutedText}>
          비슷한 지난 사건을 조회하지 못했습니다. 판정에는 영향이 없습니다.
        </div>
      ) : shown.length === 0 ? (
        <div style={mutedText}>비슷한 지난 사건이 없습니다.</div>
      ) : (
        <>
          <CompareTable body={body} items={shown} pasts={pasts} progress={progress} />
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px 14px', fontSize: 12.5 }}>
            <span style={{ color: '#64748B' }}>
              <span style={diffDot} /> 지금 사건과 다른 칸
            </span>
            {(rest.length > 0 || notListed > 0) && <MoreCases rest={rest} notListed={notListed} />}
          </div>
          <div style={{ fontSize: 12, color: '#64748B' }}>
            지난 판정은 참고 자료입니다. 징후가 같아도 고객 확인 결과에 따라 판정은 달라질 수
            있습니다.
          </div>
        </>
      )}
    </ReportSection>
  )
}

// 지난 사건 상세를 사건마다 따로 조회한다 — 하나가 실패해도 다른 열은 보인다
// [새로고침]이면 다시 조회하되, 응답이 올 때까지 이전 결과를 그대로 둔다 (열이 "불러오는 중"으로 깜빡이지 않게)
function usePastCases(ids: string[], refreshKey: number): Record<string, Past> {
  const key = ids.join(',')
  const [loaded, setLoaded] = useState<{ key: string; byId: Record<string, Past> }>({
    key: '',
    byId: {},
  })
  useEffect(() => {
    if (!key) return
    let alive = true
    for (const id of key.split(',')) {
      getCase(id)
        .then((detail): Past => (detail ? { status: 'loaded', detail } : { status: 'missing' }))
        .catch((error: unknown): Past => ({
          status: isCaseAssigneeOnly(error) ? 'forbidden' : 'error',
        }))
        .then((past) => {
          if (!alive) return
          setLoaded((now) => ({
            key,
            byId: { ...(now.key === key ? now.byId : {}), [id]: past },
          }))
        })
    }
    return () => {
      alive = false
    }
  }, [key, refreshKey])
  return loaded.key === key ? loaded.byId : {}
}

function CompareTable({
  body,
  items,
  pasts,
  progress,
}: {
  body: ReportBody
  items: SimilarCaseItem[]
  pasts: Record<string, Past>
  progress: NowProgress
}) {
  const pastStates = items.map((c): Past => pasts[c.caseId] ?? { status: 'loading' })
  const pastReports = pastStates.map((p) =>
    p.status === 'loaded' ? compareReport(p.detail) : null,
  )
  const columns: CompareColumn[] = [
    compareColumn(body),
    ...pastReports.map((r) => compareColumn(r?.body ?? null)),
  ]
  const rows = pastCompareRows(columns)
  const hasNormals = (c: CompareColumn) => (c ? c.normals.length > 0 : null)
  const grid = { ...gridStyle, gridTemplateColumns: `168px repeat(${columns.length}, 248px)` }
  return (
    <div style={{ overflowX: 'auto' }}>
      <div role="table" aria-label="지난 사건과 비교" style={grid}>
        <span role="columnheader" />
        <NowHead body={body} progress={progress} />
        {items.map((c, i) => (
          <PastHead
            key={c.caseId}
            caseId={body.overview.caseId}
            item={c}
            past={pastStates[i]}
            mismatch={pastReports[i]?.evidenceMismatch ?? false}
            column={columns[i + 1]}
          />
        ))}
        {rows.map((r) => (
          <Row key={r.id} head={r.label} sub={r.level === 'HIGH_RISK' ? '고위험' : '주의'}>
            {r.cells.map((cell, i) => (
              <Cell key={i} diff={r.diff[i]} tone={cellTone(cell)}>
                {cellText(cell)}
              </Cell>
            ))}
          </Row>
        ))}
        <Row head="정상 근거">
          {columns.map((c, i) => {
            const now = hasNormals(columns[0])
            const mine = hasNormals(c)
            const diff = i > 0 && now !== null && mine !== null && now !== mine
            return (
              <Cell key={i} diff={diff} tone={mine ? 'normal' : 'none'}>
                {c === null
                  ? '—'
                  : c.normals.length === 0
                    ? '없음'
                    : c.normals.map((n) => n.label).join(' · ')}
              </Cell>
            )
          })}
        </Row>
        <Row head="직원 판정" sub="사유는 직원이 쓴 글">
          <Cell tone="none">
            {nowDecisionText(progress)}
            {progress.decided > 0 && ' · 판정 기록은 사건 상세에서'}
          </Cell>
          {pastStates.map((p, i) => (
            <Cell key={i} tone="none">
              {p.status === 'loaded' ? <Decisions detail={p.detail} /> : '—'}
            </Cell>
          ))}
        </Row>
      </div>
    </div>
  )
}

function NowHead({ body, progress }: { body: ReportBody; progress: NowProgress }) {
  const score = topRiskScore(body.paymentAnalyses.map((p) => p.riskScore))
  return (
    <div role="columnheader" style={{ ...headStyle, border: '2px solid #1D4ED8' }}>
      <span style={headNote}>지금 · {nowDecisionText(progress)}</span>
      <b>사건 {body.overview.caseId}</b>
      <span style={headNote}>
        {won(body.overview.totalAmount)} · FDS {score ?? '-'}
      </span>
    </div>
  )
}

function PastHead({
  caseId,
  item,
  past,
  mismatch,
  column,
}: {
  caseId: string
  item: SimilarCaseItem
  past: Past
  mismatch: boolean
  column: CompareColumn
}) {
  const navigate = useNavigate()
  // 목록 복귀 조건(#243)을 다른 사건으로 갈 때도 그대로 넘긴다
  const listReturn = useLocation().state
  const similarity =
    item.similarity === 'SAME_CUSTOMER' ? '같은 고객' : `같은 규칙 ${item.sharedRules.length}개`
  let state: ReactNode = null
  if (past.status === 'loading') state = <span className="dw-loading-reveal">불러오는 중…</span>
  else if (past.status === 'error') state = '불러오지 못함'
  else if (past.status === 'forbidden') state = '열람 권한 없음'
  else if (past.status === 'missing') state = '사건을 찾지 못함'
  else if (column === null) state = '징후 정리가 없는 보고서 — 직원 판정만 비교'
  const amounts = past.status === 'loaded' ? past.detail.payments.map((p) => p.amount) : []
  const total = amounts.some((a) => a === null)
    ? null
    : amounts.reduce<number>((sum, a) => sum + (a ?? 0), 0)
  const score =
    past.status === 'loaded' ? topRiskScore(past.detail.payments.map((p) => p.riskScore)) : null
  return (
    <div role="columnheader" style={{ ...headStyle, border: '1px solid #CBD5E1' }}>
      <span style={headNote}>
        지난 · {similarity}
        {item.closedAt && ` · ${dateTime(item.closedAt).slice(0, 10)} 종료`}
      </span>
      <button
        type="button"
        style={caseLinkStyle}
        onClick={() => navigate(`/security/cases/${item.caseId}`, { state: listReturn })}
      >
        사건 {item.caseId}
      </button>
      {past.status === 'loaded' && (
        <span style={headNote}>
          {total === null ? '금액 모름' : won(total)} · FDS {score ?? '-'}
        </span>
      )}
      {state && <span style={{ ...headNote, color: '#B45309' }}>{state}</span>}
      {mismatch && (
        <span style={{ ...headNote, color: '#B45309' }}>
          보고서가 사건의 지금 증거보다 앞선 버전 기준
        </span>
      )}
      <button
        type="button"
        style={{ ...caseLinkStyle, fontSize: 12.5, fontWeight: 600 }}
        onClick={() =>
          navigate(`/security/cases/${caseId}/compare/${item.caseId}`, { state: listReturn })
        }
      >
        나란히 보기 ›
      </button>
    </div>
  )
}

function Decisions({ detail }: { detail: CaseDetail }) {
  if (detail.decisions.length === 0) return <span style={mutedText}>판정 기록 없음</span>
  return (
    <span style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      {detail.decisions.map((d) => {
        const payment = detail.payments.find((p) => p.casePaymentId === d.casePaymentId)
        const reject = d.decision === 'REJECT'
        return (
          <span key={d.decisionId} style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
            <b style={{ color: reject ? '#991B1B' : '#166534' }}>
              {payment && `결제 ${payment.paymentId} · `}
              {decisionLabel[d.decision].label}
            </b>
            <span>{d.reason}</span>
            <span style={{ fontSize: 12, color: '#94A3B8' }}>{dateTime(d.decidedAt)}</span>
          </span>
        )
      })}
    </span>
  )
}

function MoreCases({ rest, notListed }: { rest: SimilarCaseItem[]; notListed: number }) {
  const navigate = useNavigate()
  const listReturn = useLocation().state
  return (
    <span style={{ color: '#475569' }}>
      외 {rest.length + notListed}건
      {rest.map((c) => (
        <button
          key={c.caseId}
          type="button"
          style={{ ...caseLinkStyle, fontSize: 12.5, marginLeft: 8 }}
          onClick={() => navigate(`/security/cases/${c.caseId}`, { state: listReturn })}
        >
          사건 {c.caseId}
        </button>
      ))}
      {notListed > 0 && rest.length > 0 && ` 등`}
    </span>
  )
}

function Row({ head, sub, children }: { head: string; sub?: string; children: ReactNode }) {
  return (
    <>
      <span role="rowheader" style={{ ...cellBase, fontWeight: 700 }}>
        {head}
        {sub && (
          <span style={{ display: 'block', fontSize: 11.5, fontWeight: 500, color: '#64748B' }}>
            {sub}
          </span>
        )}
      </span>
      {children}
    </>
  )
}

type Tone = 'high' | 'caution' | 'normal' | 'none'

function Cell({
  diff = false,
  tone,
  children,
}: {
  diff?: boolean
  tone: Tone
  children: ReactNode
}) {
  return (
    <span
      role="cell"
      style={{
        ...cellBase,
        ...toneStyle[tone],
        outline: diff ? '2px solid #2563EB' : undefined,
        outlineOffset: -2,
      }}
    >
      {children}
    </span>
  )
}

function cellTone(cell: PastCell): Tone {
  if (cell.kind === 'OPEN') return cell.signal.level === 'HIGH_RISK' ? 'high' : 'caution'
  if (cell.kind === 'EXPLAINED') return 'normal'
  return 'none'
}

function cellText(cell: PastCell): string {
  switch (cell.kind) {
    case 'UNKNOWN':
      return '—'
    case 'NONE':
      return '없음'
    case 'COUNTED':
      return `있음 · ${cell.signal.countedIn}에 포함`
    case 'EXPLAINED':
      return `있음 → 설명됨 · ${cell.signal.normalReason ?? ''}`
    case 'OPEN':
      return `있음 · ${cell.signal.detail ?? '정상 사유 없음'}`
  }
}

const gridStyle: CSSProperties = {
  display: 'grid',
  gap: 6,
  fontSize: 13,
  lineHeight: 1.5,
}
const cellBase: CSSProperties = { padding: '8px 10px', borderRadius: 8 }
const toneStyle: Record<Tone, CSSProperties> = {
  high: { background: '#FEF2F2', color: '#7F1D1D' },
  caution: { background: '#FFFBEB', color: '#78350F' },
  normal: { background: '#F0FDF4', color: '#14532D' },
  none: { background: '#F8FAFC', color: '#334155' },
}
const headStyle: CSSProperties = {
  ...cellBase,
  display: 'flex',
  flexDirection: 'column',
  gap: 2,
  background: '#fff',
}
const headNote: CSSProperties = { fontSize: 12, color: '#64748B' }
const mutedText: CSSProperties = { fontSize: 13, color: '#64748B' }
const caseLinkStyle: CSSProperties = {
  alignSelf: 'flex-start',
  background: 'none',
  border: 'none',
  padding: 0,
  fontSize: 15,
  fontWeight: 700,
  color: '#1D4ED8',
  cursor: 'pointer',
  textAlign: 'left',
}
const diffDot: CSSProperties = {
  display: 'inline-block',
  width: 10,
  height: 10,
  borderRadius: 2,
  outline: '2px solid #2563EB',
  marginRight: 4,
}
