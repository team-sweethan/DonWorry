import { useEffect, useState, type CSSProperties, type ReactNode } from 'react'
import { Alert, Card, Collapse, Descriptions, Table, Tag, Typography } from 'antd'
import { getMlShadowScores } from '../../../api/mlShadowScores'
import type { AiRecommendation, ReportBody } from '../../../types/case'
import {
  appendedFactTexts,
  caseNormals,
  caseSignals,
  checkRows,
  durationText,
  isCodeReason,
  mlReportLine,
  recommendationSource,
  recommendationSummaryTitle,
  reportRecommendationSource,
  ruleEvidenceRows,
  signalTexts,
  paymentCardId,
  summaryBoxLines,
  summaryFold,
  summaryLines,
  traceProviderLabel,
  fixedReportNotice,
  type RecommendationSource,
} from '../../../utils/reportView'
import { customerPart } from '../../../utils/caseRules'
import {
  mlCell,
  mlScoresRequestKey,
  mlScoresView,
  type LoadedMlScores,
  type MlScores,
} from '../../../utils/transactionList'
import { won } from '../../../utils/format'
import { dateTime, recommendationLabel } from './caseLabels'
import { SummaryBox, WhyCard } from './BasisCard'
import { LinkedPaymentsCard } from './LinkedPaymentsCard'
import { OrderContextCard } from './OrderContextCard'
import { PaymentHistoryCard } from './PaymentHistoryCard'
import { ReportConclusion } from './ReportConclusion'
import { SimilarCasesCard } from './SimilarCasesCard'

// AI 보고서 전문 — 결론 먼저 (#591): ① 결론 → ② 사건 경위 → ③ 연결 → ④ 판정 근거 상세 → ⑤ 조사 범위 · 한계
// ①은 코드가 쓴 칸만, AI 문장(조사 요약)은 ③에만 둔다 (#543). AI는 판단하지 않고 사실만 전달한다 (#548)
// AI 요약 문장 끝 링크가 표 카드로 이동한다 — 카드 id는 summaryLines의 target과 같다 (#559)

type PaymentAnalysis = ReportBody['paymentAnalyses'][number]

// refreshKey — 화면의 [새로고침] 횟수. 바뀌면 ML 참고 위험도도 다시 조회한다 (#547 Codex 리뷰)
// onDecide — 판정할 수 있는 Case일 때만 온다 (끝난 Case는 판정하러 가기를 보여 주지 않는다)
export function ReportFull({
  body,
  label,
  refreshKey,
  onDecide,
}: {
  body: ReportBody
  label: string
  refreshKey: number
  onDecide?: () => void
}) {
  const summary = summaryLines(body)
  const source = reportRecommendationSource(body)
  const ml = useMlScores(
    body.paymentAnalyses.map((p) => p.paymentId),
    refreshKey,
  )
  // 판정 전에 확인할 것 체크 — 화면에서만 기억한다 (보고서 버전을 바꾸면 화면이 새로 그려져 비워진다)
  const [checked, setChecked] = useState<string[]>([])
  const toggle = (id: string) =>
    setChecked((now) => (now.includes(id) ? now.filter((x) => x !== id) : [...now, id]))
  const signals = caseSignals(body)
  // 요약 밑줄용 — 결제가 여러 건이면 사건 전체 징후 · 정상 근거로 긋는다
  const marks = signals ? { signals, normals: caseNormals(body) } : null
  const fixedNotice = fixedReportNotice(body.investigationTrace)
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      {fixedNotice && <Alert type="info" showIcon message={fixedNotice} />}
      <ReportConclusion
        body={body}
        label={label}
        source={source}
        checked={checked}
        onToggle={toggle}
        onDecide={onDecide}
      />
      <Card title={<CardTitle title="② 사건 경위" note="시간순" />} style={cardStyle}>
        <Timeline items={body.timeline} />
      </Card>
      <Card
        title={<CardTitle title="③ 연결 — 표만 봐서는 바로 안 보이는 것" />}
        style={cardStyle}
        styles={{ body: { display: 'flex', flexDirection: 'column', gap: 16 } }}
      >
        <SummaryBox
          lines={summary.map((l) => l.text)}
          basis={marks}
          links={summary.map((l) => l.links)}
          fold={summaryFold(body)}
          appended={appendedFactTexts(body)}
        />
        {body.paymentHistory !== undefined && (
          <div id="report-history" style={anchorStyle}>
            <PaymentHistoryCard history={body.paymentHistory} />
          </div>
        )}
        {body.linkedPayments !== undefined && (
          <div id="report-linked" style={anchorStyle}>
            <LinkedPaymentsCard linked={body.linkedPayments} />
          </div>
        )}
        {body.orderContext !== undefined && (
          <div id="report-order" style={anchorStyle}>
            <OrderContextCard order={body.orderContext} />
          </div>
        )}
        {body.similarCases !== undefined && (
          <div id="report-similar" style={anchorStyle}>
            <SimilarCasesCard similar={body.similarCases} caseId={String(body.overview.caseId)} />
          </div>
        )}
      </Card>
      <div
        id="report-evidence"
        style={{ ...anchorStyle, display: 'flex', flexDirection: 'column', gap: 16 }}
      >
        <div style={{ display: 'flex', flexWrap: 'wrap', justifyContent: 'space-between', gap: 8 }}>
          <span className="dw-section-title">④ 판정 근거 상세 — 왜 이 판정인가요?</span>
          <span style={{ color: '#94A3B8', fontSize: 12 }}>같은 근거면 언제나 같은 판정</span>
        </div>
        {body.paymentAnalyses.map((p) => (
          <div key={p.paymentId} id={paymentCardId(p.paymentId)} style={anchorStyle}>
            <PaymentEvidence analysis={p} ml={mlReportLine(mlCell(p.paymentId, ml))} />
          </div>
        ))}
      </div>
      <ScopeCard body={body} />
      <Collapse
        items={[
          {
            key: 'more',
            label: '상세 근거와 보고서 정보 펼치기',
            children: <MoreInfo body={body} />,
          },
        ]}
      />
      <DecisionBar body={body} source={source} checked={checked} onDecide={onDecide} />
    </div>
  )
}

// 화면 아래 고정 판정 줄 — 기준표 판정 · 확인 n/전체 · 남은 번호 · [판정하러 가기]
function DecisionBar({
  body,
  source,
  checked,
  onDecide,
}: {
  body: ReportBody
  source: RecommendationSource
  checked: string[]
  onDecide?: () => void
}) {
  const overall = body.verdict?.overallRecommendation
  const rows = checkRows(body)
  if (!overall && !onDecide) return null
  const left = rows.filter((r) => !checked.includes(r.id)).map((r) => r.row)
  return (
    <div style={barStyle}>
      {overall && (
        <>
          <span style={{ fontSize: 13, color: '#475569' }}>{source}</span>
          <span style={{ ...chipStyle, ...recLight[overall], border: '1px solid currentColor' }}>
            {recommendationLabel[overall].label}
          </span>
        </>
      )}
      {rows.length > 0 && (
        <>
          <span style={{ fontSize: 14 }}>
            확인 <b>{rows.length - left.length}</b> / {rows.length} 완료
          </span>
          <span style={{ fontSize: 13, color: left.length ? '#92400E' : '#166534' }}>
            {left.length ? `남은 확인 ${left.join(' · ')}번` : '확인할 것을 모두 체크했습니다'}
          </span>
        </>
      )}
      {onDecide && (
        <button type="button" onClick={onDecide} style={barButtonStyle}>
          판정하러 가기
        </button>
      )}
    </div>
  )
}

// ⑤ 조사 범위 · 한계 — 조회한 것 / 일부러 보지 않은 것 / 확인할 방법이 없는 것 (#591)
// 칸이 없는 예전 · 고정 보고서는 한계 문장 목록을 그대로 보여 준다
function ScopeCard({ body }: { body: ReportBody }) {
  const scope = body.scope
  const trace = body.investigationTrace
  return (
    <Card title={<CardTitle title="⑤ 조사 범위 · 한계" />} style={cardStyle}>
      {scope ? (
        <div style={scopeGridStyle}>
          <ScopeColumn title="조회한 것" color="#166534" items={scope.checked} mark="✓ " />
          <ScopeColumn
            title="일부러 보지 않은 것"
            color="#1E3A8A"
            items={scope.withheld}
            note="개인정보 보호를 위해 AI에 넘기지 않습니다."
          />
          <ScopeColumn
            title="확인할 방법이 없는 것"
            color="#92400E"
            items={scope.unavailable}
            note="FDS 점수는 부정거래 확률이 아닙니다."
          />
        </div>
      ) : (
        <Bullets items={body.recommendation.limitations} />
      )}
      {trace && (
        <div style={{ fontSize: 12, color: '#64748B', marginTop: 14 }}>
          어떻게 만들었나 · {traceProviderLabel(trace)} · 조사 단계 {trace.steps.length}개 ·{' '}
          {durationText(trace.durationMs)} · 자세한 과정은 위 [조사 과정] 탭
        </div>
      )}
    </Card>
  )
}

function ScopeColumn({
  title,
  color,
  items,
  mark = '',
  note,
}: {
  title: string
  color: string
  items: string[]
  mark?: string
  note?: string
}) {
  return (
    <div
      style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 13, lineHeight: 1.7 }}
    >
      <div style={{ fontWeight: 700, color }}>
        {title} {items.length > 0 && items.length}
      </div>
      {items.length === 0 ? (
        <span style={{ color: '#94A3B8' }}>없음</span>
      ) : (
        items.map((item) => (
          <div key={item}>
            {mark}
            {item}
          </div>
        ))
      )}
      {note && <div style={{ color: '#64748B' }}>{note}</div>}
    </div>
  )
}

// 결제 하나의 판정 · 근거 — 판정 근거가 있으면 왜 이 판정인가요?
// 조사 요약은 ③에 있다. 여기 요약 칸은 AI가 결제별 이유를 쓰던 예전 보고서(#501~#532)만
function PaymentEvidence({ analysis: p, ml }: { analysis: PaymentAnalysis; ml: string }) {
  const source = recommendationSource(p)
  return (
    <Card
      style={cardStyle}
      title={
        <span style={{ display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap' }}>
          <span className="dw-section-title">결제 {p.paymentId}</span>
          <span style={{ fontWeight: 500 }}>{won(p.amount)}</span>
          <span style={{ color: '#64748b', fontWeight: 500 }}>
            {p.riskScore === null ? 'FDS 점수 모름' : `FDS ${p.riskScore}점`}
          </span>
        </span>
      }
      extra={<RecTag rec={p.recommendation} source={source} />}
    >
      {p.signals !== undefined && <SignalLine signals={p.signals} />}
      {p.basis ? (
        <>
          <SummaryBox lines={summaryBoxLines([], p)} basis={p.basis} />
          <WhyCard
            basis={p.basis}
            reason={isCodeReason(p.reason) ? p.reason : undefined}
            details={p.ruleHitDetails}
          />
          <MlLine text={ml} />
        </>
      ) : (
        <>
          {p.reason && (
            <p style={{ fontSize: 14, lineHeight: 1.7, margin: '0 0 14px' }}>
              <AiTag /> {p.reason}
            </p>
          )}
          <MlLine text={ml} />
          <RuleCompare analysis={p} />
        </>
      )}
    </Card>
  )
}

// 예전 보고서(판정 근거 없음)의 적중 규칙 — 예전 AI 문장(위험 근거 · 정상 정황)은 보여 주지 않는다 (#548)
function RuleCompare({ analysis: p }: { analysis: PaymentAnalysis }) {
  const rules = ruleEvidenceRows(p)
  return (
    <div style={{ ...sideStyle, background: '#FEF2F2', border: '1px solid #FECACA' }}>
      <SideTitle color="#991B1B" title="⚠ 적중 규칙" count={rules.length} />
      {rules.map((r, i) => (
        <Item
          key={`rule-${i}`}
          title={r.appliedScore === null ? r.name : `${r.name} (+${r.appliedScore}점)`}
        >
          {r.criterion && r.observed && (
            <span style={{ display: 'block', fontSize: 12.5, color: '#7F1D1D' }}>
              기준 {r.criterion} · 이번 {r.observed}
            </span>
          )}
          FDS 기록{r.evidenceId && ` · 증거 ${r.evidenceId}`}
        </Item>
      ))}
    </div>
  )
}

// ML 참고 위험도 (#544) — 보고서에 넣지 않고 화면을 열 때 읽는다
// ML 채점은 평가가 저장된 뒤 주기적으로(기본 30초) 돌아 보고서보다 늦을 수 있다
function useMlScores(paymentIds: string[], refreshKey: number): MlScores {
  const ids = paymentIds.join(',')
  const key = mlScoresRequestKey(paymentIds, refreshKey)
  const [loaded, setLoaded] = useState<LoadedMlScores | null>(null)
  useEffect(() => {
    if (!ids) return
    let alive = true
    // 실패해도 보고서 다른 칸은 그대로 보이고 이 줄만 "불러오지 못했어요"
    getMlShadowScores(ids.split(','))
      .then((found) => {
        if (!alive) return
        const scoreByPaymentId = new Map(found.map((score) => [score.paymentId, score]))
        setLoaded({ key, scores: { status: 'loaded', scoreByPaymentId } })
      })
      .catch(() => {
        if (alive) setLoaded({ key, scores: { status: 'failed' } })
      })
    return () => {
      alive = false
    }
  }, [ids, key])
  return mlScoresView(loaded, paymentIds, refreshKey)
}

// ML 참고 위험도 한 줄 — 판정(기준표)과 섞이지 않게 회색 점선 칸
function MlLine({ text }: { text: string }) {
  return <div style={mlLineStyle}>{text}</div>
}

function SignalLine({ signals }: { signals: PaymentAnalysis['signals'] }) {
  return (
    <div style={signalLineStyle}>
      {signals ? (
        signalTexts(signals).map((text) => (
          <span key={text.label}>
            <span style={{ color: '#94A3B8' }}>{text.label}</span>{' '}
            <span style={{ fontWeight: 600, color: text.missing ? '#94A3B8' : '#0F172A' }}>
              {text.value}
            </span>
          </span>
        ))
      ) : (
        <span style={{ color: '#94A3B8' }}>카드 · 기기 · 접속 국가 정보 없음 (FDS 평가 없음)</span>
      )}
    </div>
  )
}

function MoreInfo({ body }: { body: ReportBody }) {
  const { overview } = body
  const ruleTable = reportRecommendationSource(body) === '기준표 판정'
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <Descriptions size="small" column={2} colon={false} title="사건 개요">
        <Descriptions.Item label="Case">{overview.caseId}</Descriptions.Item>
        <Descriptions.Item label="가맹점">{overview.merchantId}</Descriptions.Item>
        <Descriptions.Item label="고객">{customerPart(overview.customerRef)}</Descriptions.Item>
        <Descriptions.Item label="대상 거래">{overview.paymentCount}건</Descriptions.Item>
        <Descriptions.Item label="총액">{won(overview.totalAmount)}</Descriptions.Item>
        <Descriptions.Item label="조사 기준">{dateTime(overview.investigatedAt)}</Descriptions.Item>
      </Descriptions>
      <div>
        <SubTitle>{recommendationSummaryTitle(body)}</SubTitle>
        <p style={{ fontSize: 13, margin: '0 0 6px' }}>{body.recommendation.summary}</p>
        <Bullets items={body.recommendation.limitations} />
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {ruleTable ? '기준표 판정은' : 'AI 권고는'} 참고용이며 최종 판정은 직원이 결제별로 합니다.
        </Typography.Text>
      </div>
      <div>
        <SubTitle>증거 추적 정보</SubTitle>
        <Table
          size="small"
          rowKey="evidenceId"
          pagination={false}
          dataSource={body.evidence}
          columns={[
            { title: '증거 ID', dataIndex: 'evidenceId', width: 110 },
            { title: 'Tool', dataIndex: 'toolName' },
            { title: '출처', dataIndex: 'source', width: 80 },
            { title: '조회 시각', dataIndex: 'retrievedAt', width: 140, render: dateTime },
            { title: '데이터 버전', dataIndex: 'dataVersion', width: 100 },
          ]}
        />
      </div>
    </div>
  )
}

// 반원 게이지. 색은 FDS 판정 구간 (0~49 ALLOW · 50~79 REVIEW · 80~ DENY)
export function ScoreGauge({ score, dark = false }: { score: number | null; dark?: boolean }) {
  const circle = Math.PI * 60
  const filled = score === null ? 0 : (circle * Math.min(score, 100)) / 100
  const color =
    score === null ? '#94A3B8' : score >= 80 ? '#F87171' : score >= 50 ? '#FBBF24' : '#60A5FA'
  const text = dark ? '#fff' : '#0F172A'
  const sub = dark ? '#B5C5EB' : '#64748B'
  return (
    <svg
      viewBox="0 0 150 104"
      width={160}
      role="img"
      aria-label={`FDS 최고 점수 ${score ?? '모름'}`}
    >
      <path
        d="M15 76 A60 60 0 0 1 135 76"
        fill="none"
        stroke={dark ? 'rgba(255,255,255,.14)' : '#E2E8F0'}
        strokeWidth={13}
        strokeLinecap="round"
      />
      {score !== null && (
        <path
          d="M15 76 A60 60 0 0 1 135 76"
          fill="none"
          stroke={color}
          strokeWidth={13}
          strokeLinecap="round"
          strokeDasharray={`${filled} ${circle}`}
        />
      )}
      <text x={75} y={66} textAnchor="middle" fontSize={30} fontWeight={800} fill={text}>
        {score ?? '-'}
      </text>
      <text x={75} y={88} textAnchor="middle" fontSize={11} fill={sub}>
        FDS 최고 점수
      </text>
      <text x={75} y={102} textAnchor="middle" fontSize={10} fill={sub}>
        (사기 확률 아님)
      </text>
    </svg>
  )
}

const recLight: Record<AiRecommendation, CSSProperties> = {
  REJECT_REVIEW: { background: '#FEE2E2', color: '#991B1B' },
  ACCEPT_REVIEW: { background: '#DCFCE7', color: '#166534' },
  NEEDS_CHECK: { background: '#FEF3C7', color: '#92400E' },
}

// 결제 카드 오른쪽 위 판정 꼬리표 — "기준표 판정 · 승인 취소 검토"
function RecTag({ rec, source }: { rec: AiRecommendation; source: RecommendationSource }) {
  return (
    <span style={{ ...chipStyle, ...recLight[rec], border: '1px solid currentColor' }}>
      {source} · {recommendationLabel[rec].label}
    </span>
  )
}

function AiTag() {
  return (
    <Tag color="purple" style={{ marginInlineEnd: 0, fontSize: 11 }}>
      AI 해석
    </Tag>
  )
}

function CardTitle({ title, note, ai }: { title: string; note?: string; ai?: boolean }) {
  return (
    <span style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
      <span className="dw-section-title">{title}</span>
      {ai && <AiTag />}
      {note && <span style={{ color: '#94A3B8', fontSize: 12, fontWeight: 500 }}>{note}</span>}
    </span>
  )
}

function SideTitle({ title, count, color }: { title: string; count: number; color: string }) {
  return (
    <h4
      style={{ margin: '0 0 10px', fontSize: 14, display: 'flex', justifyContent: 'space-between' }}
    >
      <span style={{ color }}>{title}</span>
      <span style={{ color: '#94A3B8', fontSize: 12, fontWeight: 500 }}>{count}개</span>
    </h4>
  )
}

function Item({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div style={itemStyle}>
      <b style={{ display: 'block', fontSize: 13.5 }}>{title}</b>
      <div style={{ fontSize: 11, color: '#94A3B8', marginTop: 2 }}>{children}</div>
    </div>
  )
}

function SubTitle({ children, top }: { children: ReactNode; top?: boolean }) {
  return (
    <div
      style={{
        fontSize: 12,
        fontWeight: 700,
        color: '#64748b',
        margin: top ? '14px 0 4px' : '0 0 4px',
      }}
    >
      {children}
    </div>
  )
}

function Timeline({ items }: { items: ReportBody['timeline'] }) {
  if (items.length === 0) return <Typography.Text type="secondary">없음</Typography.Text>
  return (
    <ol
      style={{
        margin: 0,
        padding: '0 0 0 18px',
        listStyle: 'none',
        borderLeft: '2px solid #E2E8F0',
      }}
    >
      {items.map((t, i) => (
        <li
          key={i}
          style={{ position: 'relative', fontSize: 13, lineHeight: 1.6, marginBottom: 10 }}
        >
          <span style={{ ...dotStyle, ...timelineDot[t.kind ?? 'OTHER'] }} />
          <span style={{ color: '#64748b', marginRight: 8 }}>{dateTime(t.at)}</span>
          {t.event}
        </li>
      ))}
    </ol>
  )
}

function Bullets({ items }: { items: string[] }) {
  if (items.length === 0) return <Typography.Text type="secondary">없음</Typography.Text>
  return (
    <ul style={{ margin: 0, paddingLeft: 18, fontSize: 13, lineHeight: 1.7 }}>
      {items.map((item, i) => (
        <li key={i}>{item}</li>
      ))}
    </ul>
  )
}

const cardStyle: CSSProperties = { borderColor: '#E2E8F0' }
// 요약 링크로 이동할 카드 — 둥근 모서리를 맞춰 강조 테두리가 카드 모양을 따른다 (#559)
const anchorStyle: CSSProperties = { borderRadius: 8, scrollMarginTop: 80 }
const signalLineStyle: CSSProperties = {
  display: 'flex',
  flexWrap: 'wrap',
  gap: 16,
  fontSize: 13,
  margin: '0 0 12px',
  padding: '8px 12px',
  background: '#F8FAFC',
  borderRadius: 8,
}
const chipStyle: CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 6,
  padding: '6px 11px',
  borderRadius: 7,
  fontSize: 12.5,
  fontWeight: 700,
}
const sideStyle: CSSProperties = { borderRadius: 12, padding: '16px 18px' }
const mlLineStyle: CSSProperties = {
  fontSize: 13,
  color: '#475569',
  background: '#F8FAFC',
  border: '1px dashed #CBD5E1',
  borderRadius: 8,
  padding: '8px 12px',
  marginBottom: 16,
}
const itemStyle: CSSProperties = {
  background: '#fff',
  borderRadius: 8,
  padding: '10px 12px',
  marginBottom: 8,
  lineHeight: 1.55,
}
const dotStyle: CSSProperties = {
  position: 'absolute',
  left: -24,
  top: 6,
  width: 10,
  height: 10,
  borderRadius: '50%',
  background: '#fff',
  // 색은 timelineDot이 정한다 — border 한 줄 묶음과 섞으면 색 덮어쓰기가 안 될 수 있다
  borderWidth: 2,
  borderStyle: 'solid',
}
// 사건 경위 점 색 (#310) — 결제(매입 보류) 주황 · 사건 생성 파랑, 종류가 없는 예전 보고서는 회색
const timelineDot: Record<'PAYMENT' | 'CASE' | 'OTHER', CSSProperties> = {
  PAYMENT: { borderColor: '#D97706', background: '#FEF3C7' },
  CASE: { borderColor: '#2563EB', background: '#2563EB' },
  OTHER: { borderColor: '#94A3B8' },
}
const barStyle: CSSProperties = {
  position: 'sticky',
  bottom: 0,
  zIndex: 5,
  background: '#fff',
  border: '1px solid #CBD5E1',
  borderRadius: 12,
  boxShadow: '0 -4px 16px rgba(15, 23, 42, 0.08)',
  padding: '10px 16px',
  display: 'flex',
  flexWrap: 'wrap',
  alignItems: 'center',
  gap: 12,
}
const barButtonStyle: CSSProperties = {
  marginLeft: 'auto',
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
const scopeGridStyle: CSSProperties = {
  display: 'grid',
  gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))',
  gap: 16,
}
