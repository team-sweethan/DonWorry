import { useEffect, useState, type CSSProperties, type ReactNode } from 'react'
import { ArrowLeftOutlined } from '@ant-design/icons'
import { Alert, App, Button, Spin, Tag, Typography } from 'antd'
import { useLocation, useNavigate, useParams } from 'react-router-dom'
import { getCase } from '../../api/cases'
import { PageHeader } from '../../components/common/PageHeader'
import { ScreenResult } from '../../components/common/ScreenResult'
import type { BasisSignal, CaseDetail, ReportBody } from '../../types/case'
import { won } from '../../utils/format'
import {
  caseNormals,
  caseSignals,
  compareReport,
  compareSignals,
  decisionProgress,
  reportRecommendationSource,
  topRiskScore,
  verdictLines,
  type CompareReport,
  type CompareRow,
} from '../../utils/reportView'
import { Group, NormalRow, SignalRow } from './case/BasisCard'
import { dateTime, decisionLabel, recommendationLabel } from './case/caseLabels'
import { CodeTag } from './case/CodeTag'

// 비슷한 사건 나란히 보기 (#501) — 왼쪽 판정이 끝난 과거 사건, 오른쪽 지금 판정할 사건
// 두 사건 모두 기존 Case 상세 조회를 그대로 쓴다 (보고서 · 판정 · 판정 사유가 들어 있음)

// report: 최근 성공 보고서와 증거 버전 불일치 여부 (#501 리뷰), body는 그 본문
type Side = { detail: CaseDetail; report: CompareReport | null; body: ReportBody | null }

const side = (detail: CaseDetail): Side => {
  const report = compareReport(detail)
  return { detail, report, body: report?.body ?? null }
}

export function CaseComparePage() {
  const { id = '', pastId = '' } = useParams()
  const navigate = useNavigate()
  // 목록 복귀 조건(#243)을 돌아갈 때 다시 넘긴다
  const listReturn = useLocation().state
  const { message } = App.useApp()
  const scope = `${id}:${pastId}`
  const [loaded, setLoaded] = useState<{
    scope: string
    result: { now: Side; past: Side } | null | 'error'
  } | null>(null)
  useEffect(() => {
    let alive = true
    Promise.all([getCase(id), getCase(pastId)])
      .then(([now, past]) => {
        if (!alive) return
        const result = now && past ? { now: side(now), past: side(past) } : null
        setLoaded({ scope: `${id}:${pastId}`, result })
      })
      .catch(() => {
        if (!alive) return
        message.error('사건을 불러오지 못했습니다. 잠시 후 다시 시도하세요.')
        setLoaded({ scope: `${id}:${pastId}`, result: 'error' })
      })
    return () => {
      alive = false
    }
  }, [id, pastId, message])
  const result = loaded?.scope === scope ? loaded.result : undefined

  if (result === undefined) return <Spin style={{ display: 'block', margin: '120px auto' }} />
  if (result === 'error') return <ScreenResult kind="500" />
  if (result === null) return <ScreenResult kind="404" />

  const { now, past } = result
  const nowSignals = now.body ? caseSignals(now.body) : null
  const pastSignals = past.body ? caseSignals(past.body) : null
  const rows = nowSignals && pastSignals ? compareSignals(pastSignals, nowSignals) : []
  const shared = rows.filter((r) => r.tag !== 'ONLY').length
  const reportPath = `/security/cases/${id}/report`

  return (
    <>
      <PageHeader
        title="비슷한 사건 나란히 보기"
        description="왼쪽은 판정이 끝난 과거 사건, 오른쪽은 지금 판정할 사건입니다. 같은 줄끼리 비교하세요."
        actions={
          <Button
            icon={<ArrowLeftOutlined />}
            onClick={() => navigate(reportPath, { state: listReturn })}
          >
            사건 {id} 보고서로 돌아가기
          </Button>
        }
      />
      <div style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
          {rows.length > 0 && (
            <span style={{ ...chipStyle, ...blueChip }}>겹친 징후 {shared}개</span>
          )}
          <PastDecisionChip detail={past.detail} />
        </div>
        <Pair
          past={<CaseHead side={past} title="과거 사건" />}
          now={<CaseHead side={now} title="지금 사건" current />}
        />
        {(past.report?.evidenceMismatch || now.report?.evidenceMismatch) && (
          <Pair past={<MismatchNote side={past} />} now={<MismatchNote side={now} />} />
        )}
        {pastSignals === null || nowSignals === null ? (
          <Alert
            type="info"
            showIcon
            message={`${pastSignals === null ? '과거' : '지금'} 사건이 예전 보고서라 징후 정리가 없습니다.`}
            description="판정 근거가 생기기 전에 만든 보고서입니다. 아래 기준표 판정 · 직원 판정만 비교하세요."
          />
        ) : (
          <>
            <SignalRows
              title="고위험 징후"
              note="정상 고객에게는 거의 나타나지 않는 신호"
              color="#991B1B"
              rows={rows.filter((r) => level(r) === 'HIGH_RISK')}
            />
            <SignalRows
              title="주의 징후"
              note="정상 고객에게도 나타날 수 있는 신호"
              color="#92400E"
              rows={rows.filter((r) => level(r) === 'CAUTION')}
            />
            <Group title="정상 거래 근거" note="이 고객의 평소 거래와 맞는 점" color="#166534">
              <Pair past={<Normals body={past.body} />} now={<Normals body={now.body} />} />
            </Group>
          </>
        )}
        <Group title="검토 권고" note="기준표 판정 · 예전 보고서는 AI 권고" color="#1E3A8A">
          <Pair past={<Verdict body={past.body} />} now={<Verdict body={now.body} />} />
        </Group>
        <Group title="보안 직원 최종 판정" note="판정 사유는 직원이 직접 적은 내용" color="#0F172A">
          <Pair
            past={<Decisions side={past} />}
            now={
              <Decisions
                side={now}
                current={
                  <Undecided
                    checks={now.body?.additionalChecks ?? []}
                    onGo={() => navigate(`/security/cases/${id}`, { state: listReturn })}
                    caseId={id}
                    remaining={decisionProgress(now.detail).remaining}
                    partial={now.detail.decisions.length > 0}
                  />
                }
              />
            }
          />
        </Group>
        <div style={noteStyle}>
          과거 판정은 참고 자료입니다. 징후가 같아도 고객 확인 결과에 따라 판정은 달라질 수
          있습니다.
        </div>
      </div>
    </>
  )
}

const level = (r: CompareRow) => (r.now ?? r.past)?.level

function Pair({ past, now }: { past: ReactNode; now: ReactNode }) {
  return (
    <div style={pairStyle}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>{past}</div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>{now}</div>
    </div>
  )
}

function CaseHead({ side, title, current }: { side: Side; title: string; current?: boolean }) {
  const { securityCase, payments, decisions } = side.detail
  const amounts = payments.map((p) => p.amount)
  const total = amounts.some((a) => a === null)
    ? null
    : amounts.reduce<number>((sum, a) => sum + (a ?? 0), 0)
  const first = payments[0]
  const country = side.body?.paymentAnalyses[0]?.signals?.ipCountryCode
  const score = topRiskScore(payments.map((p) => p.riskScore))
  // 일부만 판정한 사건은 판정 끝이 아니다 (#501 리뷰)
  const progress = decisionProgress(side.detail)
  return (
    <div style={{ ...headStyle, border: current ? '2px solid #1D4ED8' : '1px solid #CBD5E1' }}>
      <div style={{ fontSize: 12, fontWeight: 700, color: current ? '#1D4ED8' : '#475569' }}>
        {title} ·{' '}
        {progress.done
          ? '판정 끝'
          : decisions.length > 0
            ? `일부 판정 · 남은 결제 ${progress.remaining}건`
            : '판정 대기'}
      </div>
      <div style={{ fontSize: 18, fontWeight: 700 }}>
        사건 {securityCase.caseId} · {total === null ? '금액 모름' : won(total)}
      </div>
      <div style={{ fontSize: 13, color: '#475569' }}>
        결제 {payments.length}건{first?.requestedAt && ` · ${dateTime(first.requestedAt)} 결제`}
        {country && ` · 접속 국가 ${country}`}
        {` · FDS ${score ?? '-'}점`}
      </div>
      <div style={{ fontSize: 12, color: side.report?.evidenceMismatch ? '#B45309' : '#64748B' }}>
        {side.report
          ? `보고서 v${side.report.reportVersion} · 증거 v${side.report.evidenceVersion}` +
            (side.report.evidenceMismatch
              ? ` (지금 사건 증거 v${securityCase.evidenceVersion})`
              : '')
          : 'AI 보고서 없음'}
      </div>
    </div>
  )
}

function SignalRows({
  title,
  note,
  color,
  rows,
}: {
  title: string
  note: string
  color: string
  rows: CompareRow[]
}) {
  return (
    <Group title={title} note={note} color={color}>
      {rows.length === 0 ? (
        <span style={{ fontSize: 13, color: '#94A3B8' }}>두 사건 모두 없음</span>
      ) : (
        rows.map((r) => (
          <Pair
            key={(r.now ?? r.past)?.key}
            past={<CompareCell s={r.past} row={r} />}
            now={<CompareCell s={r.now} row={r} />}
          />
        ))
      )}
    </Group>
  )
}

const tagText = { SAME: '두 사건 공통', COUNTRY_DIFFERS: '나라만 다름', ONLY: '이 사건만' }

function CompareCell({ s, row }: { s: BasisSignal | null; row: CompareRow }) {
  if (!s) return <div style={emptyCell}>없음</div>
  const strong = row.tag === 'SAME'
  return (
    <SignalRow
      s={s}
      tag={
        <span
          style={{
            ...chipStyle,
            fontSize: 12,
            padding: '3px 8px',
            ...(strong
              ? { background: '#1D4ED8', color: '#FFFFFF' }
              : { background: '#E2E8F0', color: '#334155' }),
          }}
        >
          {tagText[row.tag]}
        </span>
      }
    />
  )
}

function Normals({ body }: { body: ReportBody | null }) {
  const normals = body ? caseNormals(body) : []
  if (normals.length === 0) return <div style={emptyCell}>없음</div>
  return normals.map((n) => <NormalRow key={n.label} n={n} note={false} />)
}

// 결론이 없는 예전 단일 결제 보고서도 결제별 권고를 보여 준다 (verdictLines, #501 리뷰)
function Verdict({ body }: { body: ReportBody | null }) {
  if (!body) return <div style={emptyCell}>AI 보고서 없음 (직접 조사)</div>
  const source = reportRecommendationSource(body)
  const lines = verdictLines(body)
  return (
    <div style={{ ...headStyle, border: '1px solid #E2E8F0', gap: 6 }}>
      {lines.overall && (
        <span style={{ fontSize: 14, fontWeight: 700 }}>
          {source} · {recommendationLabel[lines.overall].label}
        </span>
      )}
      {lines.payments.map((p) => (
        <span
          key={p.paymentId}
          style={
            lines.overall ? { fontSize: 13, color: '#475569' } : { fontSize: 14, fontWeight: 700 }
          }
        >
          {lines.overall ? '' : `${source} · `}결제 {p.paymentId} ·{' '}
          {recommendationLabel[p.recommendation].label}
        </span>
      ))}
    </div>
  )
}

// 보고서가 사건의 지금 증거보다 앞선 버전으로 만들어졌으면 알린다 (#501 리뷰)
function MismatchNote({ side }: { side: Side }) {
  if (!side.report?.evidenceMismatch) return null
  return (
    <Alert
      type="warning"
      showIcon
      message={`이 보고서는 증거 v${side.report.evidenceVersion} 기준입니다.`}
      description={`사건은 지금 증거 v${side.detail.securityCase.evidenceVersion}이라, 나중에 합쳐진 결제는 이 보고서에서 조사하지 않았습니다. 징후 · 권고는 그 전 결제 기준으로 보세요.`}
    />
  )
}

// 이미 한 판정 + 남은 결제 — 지금 사건은 남은 결제가 있으면 판정 이동 칸(current)을 함께 보여 준다 (#501 리뷰)
function Decisions({ side, current }: { side: Side; current?: ReactNode }) {
  const { detail } = side
  const progress = decisionProgress(detail)
  const left = !progress.done && progress.remaining > 0
  return (
    <>
      {detail.decisions.length === 0 && !(current && left) && (
        <div style={emptyCell}>판정 기록 없음</div>
      )}
      <DecisionCards detail={detail} />
      {left &&
        (current ?? (
          <div style={emptyCell}>판정 안 된 결제 {progress.remaining}건이 남아 있습니다.</div>
        ))}
    </>
  )
}

function DecisionCards({ detail }: { detail: CaseDetail }) {
  return detail.decisions.map((d) => {
    const payment = detail.payments.find((p) => p.casePaymentId === d.casePaymentId)
    const reject = d.decision === 'REJECT'
    return (
      <div
        key={d.decisionId}
        style={{ ...headStyle, border: `2px solid ${reject ? '#B91C1C' : '#15803D'}`, gap: 8 }}
      >
        <div style={{ display: 'flex', flexWrap: 'wrap', justifyContent: 'space-between', gap: 8 }}>
          <span>
            {payment && <b style={{ marginRight: 8 }}>결제 {payment.paymentId}</b>}
            <CodeTag value={decisionLabel[d.decision]} />
          </span>
          <span style={{ fontSize: 12, color: '#475569' }}>{dateTime(d.decidedAt)} 판정</span>
        </div>
        <div style={{ fontSize: 13, lineHeight: 1.7 }}>
          <b>판정 사유</b> · {d.reason}
        </div>
      </div>
    )
  })
}

function Undecided({
  checks,
  onGo,
  caseId,
  remaining,
  partial,
}: {
  checks: string[]
  onGo: () => void
  caseId: string
  remaining: number
  partial: boolean
}) {
  return (
    <div style={{ ...headStyle, border: '2px dashed #94A3B8', gap: 10 }}>
      <span style={{ fontSize: 16, fontWeight: 700, color: '#475569' }}>
        {partial ? `남은 결제 ${remaining}건 · 아직 판정 전` : '아직 판정 전'}
      </span>
      {checks.length > 0 && (
        <>
          <Typography.Text style={{ fontSize: 13 }}>
            보고서가 먼저 해 볼 것으로 적은 확인
          </Typography.Text>
          {checks.map((c, i) => (
            <div key={i} style={{ fontSize: 13 }}>
              □ {c}
            </div>
          ))}
        </>
      )}
      <Button type="primary" style={{ alignSelf: 'flex-start' }} onClick={onGo}>
        사건 {caseId} 판정하러 가기
      </Button>
    </div>
  )
}

// 과거 사건 직원 판정 요약 — 결제별 판정 건수
function PastDecisionChip({ detail }: { detail: CaseDetail }) {
  const reject = detail.decisions.filter((d) => d.decision === 'REJECT').length
  const accept = detail.decisions.length - reject
  if (detail.decisions.length === 0) return <Tag>과거 판정 기록 없음</Tag>
  return (
    <>
      {reject > 0 && (
        <span style={{ ...chipStyle, background: '#FEE2E2', color: '#991B1B' }}>
          과거 직원 판정 · 승인 취소 {reject}건
        </span>
      )}
      {accept > 0 && (
        <span style={{ ...chipStyle, background: '#DCFCE7', color: '#166534' }}>
          과거 직원 판정 · 매입 진행 {accept}건
        </span>
      )}
    </>
  )
}

const pairStyle: CSSProperties = {
  display: 'grid',
  gridTemplateColumns: 'repeat(auto-fit, minmax(300px, 1fr))',
  gap: 16,
}
const headStyle: CSSProperties = {
  background: '#FFFFFF',
  borderRadius: 12,
  padding: '14px 18px',
  display: 'flex',
  flexDirection: 'column',
  gap: 4,
}
const emptyCell: CSSProperties = {
  border: '1px dashed #CBD5E1',
  borderRadius: 10,
  padding: '10px 14px',
  fontSize: 13,
  color: '#94A3B8',
}
const chipStyle: CSSProperties = {
  fontSize: 13,
  fontWeight: 700,
  padding: '6px 11px',
  borderRadius: 7,
}
const blueChip: CSSProperties = { background: '#DBEAFE', color: '#1E3A8A' }
const noteStyle: CSSProperties = {
  fontSize: 12,
  color: '#475569',
  background: '#FFFFFF',
  border: '1px solid #E2E8F0',
  borderRadius: 10,
  padding: '10px 14px',
}
