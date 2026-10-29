import type { CSSProperties, ReactNode } from 'react'
import { Card, Collapse, Descriptions, Table, Tag, Typography } from 'antd'
import type { AiRecommendation, ReportBody } from '../../../types/case'
import {
  reportHeadline,
  ruleEvidenceRows,
  signalTexts,
  topRiskScore,
  unknownItems,
} from '../../../utils/reportView'
import { won } from '../../../utils/format'
import { dateTime, recommendationLabel } from './caseLabels'
import { CodeTag } from './CodeTag'
import { PaymentHistoryCard } from './PaymentHistoryCard'
import { SimilarCasesCard } from './SimilarCasesCard'

// AI 보고서 전문. 결론 → 결제별 근거 비교 → 비슷한 과거 사건 → 고객 최근 결제 → 종합 분석 → 경위 · 확인할 것 → 상세 정보 순서 (프로토타입 v4.2)

type PaymentAnalysis = ReportBody['paymentAnalyses'][number]

export function ReportFull({ body, label }: { body: ReportBody; label: string }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      <VerdictCard body={body} label={label} />
      {body.paymentAnalyses.map((p) => (
        <PaymentEvidence key={p.paymentId} analysis={p} />
      ))}
      {body.similarCases !== undefined && <SimilarCasesCard similar={body.similarCases} />}
      {body.paymentHistory !== undefined && <PaymentHistoryCard history={body.paymentHistory} />}
      {body.overallAnalysis.length > 0 && (
        <Card title={<CardTitle title="종합 분석" ai />} style={cardStyle}>
          <Bullets items={body.overallAnalysis} />
        </Card>
      )}
      <div className="dw-section-grid">
        <Card title={<CardTitle title="사건 경위" note="시간순" />} style={cardStyle}>
          <Timeline items={body.timeline} />
        </Card>
        <Card title={<CardTitle title="판정 전에 확인할 것" />} style={cardStyle}>
          <SubTitle>AI가 아직 모르는 것</SubTitle>
          <Checklist items={unknownItems(body)} color="#F59E0B" />
          <SubTitle top>먼저 해 볼 것</SubTitle>
          <Checklist items={body.additionalChecks} color="#94A3B8" />
        </Card>
      </div>
      <Collapse
        items={[
          {
            key: 'more',
            label: '상세 근거와 보고서 정보 펼치기',
            children: <MoreInfo body={body} />,
          },
        ]}
      />
    </div>
  )
}

// 결론 카드. 큰 숫자는 FDS 점수 최댓값 그대로이며 사기 확률이 아니다
function VerdictCard({ body, label }: { body: ReportBody; label: string }) {
  const headline = reportHeadline(body)
  const score = topRiskScore(body.paymentAnalyses.map((p) => p.riskScore))
  return (
    <section style={verdictStyle}>
      <div style={{ textAlign: 'center' }}>
        <ScoreGauge score={score} dark />
      </div>
      <div>
        <div style={{ fontSize: 12, color: '#93C5FD', fontWeight: 700 }}>{label} · AI 결론</div>
        {headline ? (
          <div style={{ fontSize: 22, fontWeight: 800, margin: '6px 0 8px', lineHeight: 1.4 }}>
            {headline}
          </div>
        ) : (
          <div style={{ fontSize: 15, fontWeight: 700, margin: '6px 0 8px', lineHeight: 1.5 }}>
            결론 항목이 생기기 전 보고서입니다. 아래 요약과 근거를 확인하세요.
          </div>
        )}
        <p style={{ fontSize: 14, color: '#C8D5EE', lineHeight: 1.75, margin: 0 }}>
          {body.summary.join(' ')}
        </p>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginTop: 14 }}>
          {body.verdict && (
            <RecChip rec={body.verdict.overallRecommendation} text="종합 권고" strong />
          )}
          {body.paymentAnalyses.map((p) => (
            <RecChip key={p.paymentId} rec={p.recommendation} text={`결제 ${p.paymentId}`} />
          ))}
          {body.additionalChecks[0] && (
            <span style={{ ...chipStyle, background: '#1E3A8A', color: '#BFDBFE' }}>
              ☎ {body.additionalChecks[0]}
            </span>
          )}
        </div>
      </div>
    </section>
  )
}

// 결제 하나의 권고 · 이유와 위험 근거 / 정상 정황 비교
function PaymentEvidence({ analysis: p }: { analysis: PaymentAnalysis }) {
  const rules = ruleEvidenceRows(p)
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
      extra={<CodeTag value={recommendationLabel[p.recommendation]} />}
    >
      {p.signals !== undefined && <SignalLine signals={p.signals} />}
      {p.reason && (
        <p style={{ fontSize: 14, lineHeight: 1.7, margin: '0 0 14px' }}>
          <AiTag /> {p.reason}
        </p>
      )}
      <div style={compareStyle}>
        <div style={{ ...sideStyle, background: '#FEF2F2', border: '1px solid #FECACA' }}>
          <SideTitle
            color="#991B1B"
            title="⚠ 위험 근거"
            count={rules.length + p.riskEvidence.length}
          />
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
          {p.riskEvidence.map((text, i) => (
            <Item key={`risk-${i}`} title={text}>
              <AiTag />
            </Item>
          ))}
        </div>
        <div style={{ ...sideStyle, background: '#F0FDF4', border: '1px solid #BBF7D0' }}>
          <SideTitle color="#166534" title="✓ 정상 정황" count={p.normalEvidence.length} />
          {p.normalEvidence.length === 0 ? (
            <Typography.Text type="secondary" style={{ fontSize: 13 }}>
              확인된 정상 정황이 없습니다.
            </Typography.Text>
          ) : (
            p.normalEvidence.map((text, i) => (
              <Item key={i} title={text}>
                <AiTag />
              </Item>
            ))
          )}
        </div>
      </div>
    </Card>
  )
}

// 카드 · 기기 · 접속 국가 한 줄 (#346). 가맹점이 안 보낸 값은 숨기지 않고 "정보 없음"으로 보여 준다
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
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <Descriptions size="small" column={2} colon={false} title="사건 개요">
        <Descriptions.Item label="Case">{overview.caseId}</Descriptions.Item>
        <Descriptions.Item label="가맹점">{overview.merchantId}</Descriptions.Item>
        <Descriptions.Item label="고객">{overview.customerRef}</Descriptions.Item>
        <Descriptions.Item label="대상 거래">{overview.paymentCount}건</Descriptions.Item>
        <Descriptions.Item label="총액">{won(overview.totalAmount)}</Descriptions.Item>
        <Descriptions.Item label="조사 기준">{dateTime(overview.investigatedAt)}</Descriptions.Item>
      </Descriptions>
      <div>
        <SubTitle>AI 권고 원문</SubTitle>
        <p style={{ fontSize: 13, margin: '0 0 6px' }}>{body.recommendation.summary}</p>
        <Bullets items={body.recommendation.limitations} />
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          AI 권고는 참고용이며 최종 판정은 직원이 결제별로 합니다.
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

const recColor: Record<AiRecommendation, CSSProperties> = {
  REJECT_REVIEW: { background: '#7F1D1D', color: '#FECACA' },
  ACCEPT_REVIEW: { background: '#14532D', color: '#BBF7D0' },
  NEEDS_CHECK: { background: '#78350F', color: '#FDE68A' },
}

function RecChip({ rec, text, strong }: { rec: AiRecommendation; text: string; strong?: boolean }) {
  return (
    <span
      style={{
        ...chipStyle,
        ...recColor[rec],
        outline: strong ? '1px solid currentColor' : undefined,
      }}
    >
      {text} · {recommendationLabel[rec].label}
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

function Checklist({ items, color }: { items: string[]; color: string }) {
  if (items.length === 0) return <Typography.Text type="secondary">없음</Typography.Text>
  return items.map((item, i) => (
    <div
      key={i}
      style={{ display: 'flex', gap: 8, alignItems: 'flex-start', fontSize: 13, lineHeight: 1.7 }}
    >
      <i
        style={{
          flex: '0 0 12px',
          height: 12,
          marginTop: 5,
          border: `2px solid ${color}`,
          borderRadius: 3,
        }}
      />
      <span>{item}</span>
    </div>
  ))
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
const verdictStyle: CSSProperties = {
  background: 'radial-gradient(ellipse at top right, #284D91, transparent 60%), #172554',
  color: '#fff',
  borderRadius: 14,
  padding: '22px 26px',
  display: 'grid',
  gridTemplateColumns: '170px 1fr',
  gap: 26,
  alignItems: 'center',
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
const compareStyle: CSSProperties = {
  display: 'grid',
  gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))',
  gap: 16,
}
const sideStyle: CSSProperties = { borderRadius: 12, padding: '16px 18px' }
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
