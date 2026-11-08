import { useEffect, useState, type CSSProperties, type ReactNode } from 'react'
import { Alert, Collapse, Descriptions, Table, Typography } from 'antd'
import { getMlShadowScores } from '../../../api/mlShadowScores'
import type { ReportBody } from '../../../types/case'
import {
  caseNormals,
  caseSignalId,
  caseSignals,
  checkRows,
  checkSignals,
  durationText,
  fixedReportNotice,
  legacyReportText,
  traceProviderLabel,
} from '../../../utils/reportView'
import { customerPart } from '../../../utils/caseRules'
import {
  mlScoresRequestKey,
  mlScoresView,
  type LoadedMlScores,
  type MlScores,
} from '../../../utils/transactionList'
import { won } from '../../../utils/format'
import { goToCard } from './goToCard'
import { dateTime } from './caseLabels'
import { LinkedPaymentsCard } from './LinkedPaymentsCard'
import { OrderContextCard } from './OrderContextCard'
import { PaymentHistoryCard } from './PaymentHistoryCard'
import { ReportChecks } from './ReportChecks'
import { ReportOverview } from './ReportOverview'
import { ReportPastCompare, type NowProgress } from './ReportPastCompare'
import { ReportSection } from './ReportSection'

// AI 보고서 전문 (#644 PR 3): ① 무슨 일인가 → ② 확인할 것 → ③ 지난 사건과 비교 → ④ 근거 자료 → ⑤ 조사 범위(접음)
// 직원이 묻는 순서대로 — 무슨 일인가, 무엇을 확인하나, 지난 사건은 어떻게 판정했나, 근거 자료
// 판정 권고는 #644에서 뺐다 — 보고서는 자료와 확인할 것만, 판정은 보안 직원 (ADR-0009 결정 13)
// 정보마다 자리는 하나: 징후는 ②에만, 결제별 점수 · 규칙은 ① 결제 표에만, AI 문장은 ① 한눈에 보기에만 (#543)
// AI 요약 문장 끝 링크가 근거 자료 카드로 이동한다 — 카드 id는 summaryLines의 target과 같다 (#559)

// refreshKey — 화면의 [새로고침] 횟수. 바뀌면 ML 참고 위험도도 다시 조회한다 (#547 Codex 리뷰)
// onDecide — 판정할 수 있는 Case일 때만 온다 (끝난 Case는 판정하러 가기를 보여 주지 않는다)
// progress — 지금 사건 판정 진행 (decisionProgress). onDecide 유무로 판정 끝을 정하면 일부 판정 사건이 "판정 끝"으로 보였다 (#652 Codex 리뷰)
export function ReportFull({
  body,
  label,
  refreshKey,
  progress,
  onDecide,
}: {
  body: ReportBody
  label: string
  refreshKey: number
  progress: NowProgress
  onDecide?: () => void
}) {
  const ml = useMlScores(
    body.paymentAnalyses.map((p) => p.paymentId),
    refreshKey,
  )
  // 판정 전에 확인할 것 체크 — 화면에서만 기억한다 (보고서 버전을 바꾸면 화면이 새로 그려져 비워진다)
  const [checked, setChecked] = useState<string[]>([])
  const toggle = (id: string) =>
    setChecked((now) => (now.includes(id) ? now.filter((x) => x !== id) : [...now, id]))
  const signals = caseSignals(body)
  // 한눈에 보기 밑줄용 — 결제가 여러 건이면 사건 전체 징후 · 정상 근거로 긋는다
  const marks = signals ? { signals, normals: caseNormals(body) } : null
  const fixedNotice = fixedReportNotice(body.investigationTrace)
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      {fixedNotice && <Alert type="info" showIcon message={fixedNotice} />}
      <ReportOverview body={body} label={label} ml={ml} marks={marks} />
      <ReportChecks body={body} checked={checked} onToggle={toggle} />
      {body.similarCases !== undefined && (
        <ReportPastCompare
          body={body}
          similar={body.similarCases}
          progress={progress}
          refreshKey={refreshKey}
        />
      )}
      <EvidenceSection body={body} />
      <Collapse
        items={[
          {
            key: 'scope',
            label: <b>⑤ 조사 범위 · 한계 · 보고서 정보</b>,
            // 접어 둬도 내용을 미리 그려 인쇄 때 펼쳐 찍는다 (#623, reportPrint.css)
            forceRender: true,
            children: <ScopeBody body={body} />,
          },
        ]}
      />
      <DecisionBar body={body} checked={checked} onDecide={onDecide} />
    </div>
  )
}

// ④ 근거 자료 — 조회 결과 카드 그대로. 카드가 하나도 없는 예전 보고서는 구역을 그리지 않는다
const EVIDENCE_CARDS = [
  { id: 'report-history', label: '고객 최근 결제', key: 'paymentHistory' },
  { id: 'report-linked', label: '같은 카드 · 기기 · IP', key: 'linkedPayments' },
  { id: 'report-order', label: '주문 정보', key: 'orderContext' },
] as const

function EvidenceSection({ body }: { body: ReportBody }) {
  const cards = EVIDENCE_CARDS.filter((c) => body[c.key] !== undefined)
  if (cards.length === 0) return null
  return (
    <ReportSection no="④" title="근거 자료" by="lookup">
      <nav
        aria-label="근거 자료로 이동"
        className="dw-no-print"
        style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}
      >
        {cards.map((c) => (
          <button key={c.id} type="button" onClick={() => goToCard(c.id)} style={chipStyle}>
            {c.label}
          </button>
        ))}
      </nav>
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
    </ReportSection>
  )
}

// 화면 아래 고정 줄 — 확인 n/전체 · 남은 징후 이름 · [판정하러 가기]. 권고는 없다
function DecisionBar({
  body,
  checked,
  onDecide,
}: {
  body: ReportBody
  checked: string[]
  onDecide?: () => void
}) {
  const rows = checkRows(body)
  if (rows.length === 0 && !onDecide) return null
  const names = new Map((checkSignals(body) ?? []).map((s) => [caseSignalId(s), s.label]))
  const left = rows.filter((r) => !checked.includes(r.id)).map((r) => names.get(r.id) ?? r.id)
  return (
    // 화면 아래 고정이라 인쇄하면 쪽마다 겹친다 — 찍지 않는다 (#623)
    <div className="dw-no-print" style={barStyle}>
      {rows.length > 0 && (
        <>
          <span style={{ fontSize: 14 }}>
            확인 <b>{rows.length - left.length}</b> / {rows.length} 완료
          </span>
          <span style={{ fontSize: 13, color: left.length ? '#92400E' : '#166534' }}>
            {left.length ? `남은 확인 · ${left.join(' · ')}` : '확인할 것을 모두 체크했습니다'}
          </span>
        </>
      )}
      <span style={{ fontSize: 12.5, color: '#64748B' }}>판정은 보안 직원이 합니다</span>
      {onDecide && (
        <button type="button" onClick={onDecide} style={barButtonStyle}>
          판정하러 가기
        </button>
      )}
    </div>
  )
}

// ⑤ 조사 범위 · 한계 — 조회한 것 / 일부러 보지 않은 것 / 확인할 방법이 없는 것 (#591) + 보고서 정보
// 칸이 없는 예전 · 고정 보고서는 한계 문장 목록을 그대로 보여 준다
// PR 2(#650) 전 보고서의 "판정 기준표" 글자는 화면에서만 새 이름으로 (legacyReportText)
function ScopeBody({ body }: { body: ReportBody }) {
  const scope = body.scope
  const trace = body.investigationTrace
  const limitations = body.recommendation.limitations.map(legacyReportText)
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
      {scope ? (
        <div style={scopeGridStyle}>
          <ScopeColumn
            title="조회한 것"
            color="#166534"
            items={scope.checked.map(legacyReportText)}
            mark="✓ "
          />
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
        <Bullets items={limitations} />
      )}
      {trace && (
        <div style={{ fontSize: 12, color: '#64748B' }}>
          어떻게 만들었나 · {traceProviderLabel(trace)} · 조사 단계 {trace.steps.length}개 ·{' '}
          {durationText(trace.durationMs)} · 자세한 과정은 위 [조사 과정] 탭
        </div>
      )}
      <MoreInfo body={body} limitations={limitations} />
    </div>
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

// 보고서 정보 — 사건 개요 · 조사 한계(범위 칸이 있을 때, 없으면 위에 이미 보임) · 증거 추적
function MoreInfo({ body, limitations }: { body: ReportBody; limitations: string[] }) {
  const { overview } = body
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
      {body.scope && (
        <div>
          <SubTitle>조사 한계</SubTitle>
          <Bullets items={limitations} />
        </div>
      )}
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        최종 판정은 직원이 결제별로 합니다.
      </Typography.Text>
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

// 근거 자료 카드 — 둥근 모서리를 맞춰 이동 강조 테두리가 카드 모양을 따른다 (#559)
const anchorStyle: CSSProperties = { borderRadius: 8, scrollMarginTop: 80 }
const chipStyle: CSSProperties = {
  minHeight: 32,
  padding: '0 12px',
  borderRadius: 16,
  border: '1px solid #CBD5E1',
  background: '#fff',
  color: '#1E3A8A',
  fontSize: 13,
  cursor: 'pointer',
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
