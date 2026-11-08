import { useState, type CSSProperties } from 'react'
import { Button, Card, Tag, Typography } from 'antd'
import { DownOutlined, RightOutlined } from '@ant-design/icons'
import type { LinkedMatch, LinkedPayments } from '../../../types/case'
import {
  LINKED_KINDS,
  linkedExplanations,
  linkedSentences,
  windowLabel,
} from '../../../utils/linkedPaymentsView'

// 다른 손님과 겹치는 것 — 같은 카드 · 기기 · 인터넷(IP)을 다른 손님이 쓴 결제 (#352 · #637)
// 참고 자료라 자동 판정에 쓰지 않는다. linkedPayments가 없으면(예전 보고서) 카드를 그리지 않고, null이면 "조회하지 못함"
// 건수 · 손님 수만 온다 — 카드 토큰 · IP 주소 · 기기 ID · 다른 손님 번호는 서버가 보내지 않는다
// 줄 문장 · "어떻게 보나요?" 설명은 utils/linkedPaymentsView의 고정 문장이다 (LLM이 쓰지 않음)

// FDS 판정 코드값 → 한국어 · 색. 모르는 값은 코드값 그대로
const RESULT_LABEL: Record<string, { label: string; color: string }> = {
  ALLOW: { label: '허용', color: 'green' },
  REVIEW: { label: '검토', color: 'gold' },
  DENY: { label: '차단', color: 'red' },
  ERROR: { label: '평가 오류', color: 'default' },
}

function resultLabel(code: string) {
  return Object.hasOwn(RESULT_LABEL, code) ? RESULT_LABEL[code] : { label: code, color: 'default' }
}

function ResultTags({ counts }: { counts: LinkedMatch['resultCounts'] }) {
  return (
    <>
      {counts.map((c) => {
        const r = resultLabel(c.result)
        return (
          <Tag key={c.result} color={r.color} style={tagStyle}>
            {r.label} {c.count}건
          </Tag>
        )
      })}
    </>
  )
}

// 펼침 안 "겹친 결제들의 FDS 결과" — 겹친 종류마다 30일 · 전후 시간
function ResultBreakdown({ linked }: { linked: LinkedPayments }) {
  const windowMinutes = linked.nearbyWindowMinutes ?? null
  return (
    <div style={{ display: 'grid', gap: 6, marginTop: 6 }}>
      {LINKED_KINDS.filter((k) => linked[k.key].provided && linked[k.key].paymentCount > 0).map(
        (k) => {
          const match = linked[k.key]
          return (
            <div key={k.key} style={{ display: 'grid', gap: 4 }}>
              <span style={breakdownRowStyle}>
                <span style={breakdownLabelStyle}>{k.label} · 최근 30일</span>
                <ResultTags counts={match.resultCounts} />
                {match.declinedCount > 0 && (
                  <Tag color="volcano" style={tagStyle}>
                    카드사 거절 {match.declinedCount}건
                  </Tag>
                )}
              </span>
              {match.nearby && windowMinutes !== null && match.nearby.paymentCount > 0 && (
                <span style={breakdownRowStyle}>
                  <span style={breakdownLabelStyle}>
                    {k.label} · 전후 {windowLabel(windowMinutes)}
                  </span>
                  <ResultTags counts={match.nearby.resultCounts} />
                  {match.nearby.declinedCount > 0 && (
                    <Tag color="volcano" style={tagStyle}>
                      카드사 거절 {match.nearby.declinedCount}건
                    </Tag>
                  )}
                </span>
              )}
            </div>
          )
        },
      )}
    </div>
  )
}

function Explanations({ linked }: { linked: LinkedPayments }) {
  const [open, setOpen] = useState(false)
  const items = linkedExplanations(linked)
  // 겹친 것도 빠진 정보도 없으면 읽을 설명이 없다
  if (items.length === 0) return null
  // 화면 사용 안내라 인쇄하지 않는다 (#623)
  return (
    <div className="dw-no-print" style={{ marginTop: 10 }}>
      <Button
        type="link"
        size="small"
        style={{ padding: 0, fontWeight: 600 }}
        icon={open ? <DownOutlined /> : <RightOutlined />}
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
      >
        이 내용은 어떻게 보나요?
      </Button>
      {open && (
        <div style={explainBoxStyle}>
          {items.map((item) => (
            <div key={item.key}>
              <div style={{ fontWeight: 700, color: '#334155', marginBottom: 2 }}>{item.title}</div>
              <div style={{ color: '#475569', lineHeight: 1.6 }}>{item.body}</div>
              {item.key === 'results' && <ResultBreakdown linked={linked} />}
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

export function LinkedPaymentsCard({ linked }: { linked: LinkedPayments | null }) {
  const days = linked?.lookbackDays ?? 30
  const windowMinutes = linked?.nearbyWindowMinutes ?? null

  return (
    <Card
      style={{ borderColor: '#E2E8F0' }}
      title={
        <span className="dw-section-title">
          다른 손님과 겹치는 것
          <span style={{ color: '#94A3B8', fontSize: 12, fontWeight: 500, marginLeft: 8 }}>
            최근 {days}일 · 이 가맹점 결제만
          </span>
        </span>
      }
      extra={<span style={{ color: '#94A3B8', fontSize: 12 }}>참고용 · 자동 판정에 쓰지 않음</span>}
    >
      {linked === null ? (
        <Typography.Text type="secondary">
          다른 손님과 겹치는 것을 조회하지 못했습니다. 판정에는 영향이 없습니다.
        </Typography.Text>
      ) : (
        <>
          {LINKED_KINDS.map((kind) => {
            const match = linked[kind.key]
            const [first, ...rest] = linkedSentences(kind.key, match, windowMinutes)
            const muted = !match.provided || match.paymentCount === 0
            return (
              <div key={kind.key} style={rowStyle}>
                <span style={{ color: '#64748b', fontWeight: 600 }}>{kind.label}</span>
                <span style={{ display: 'grid', gap: 2 }}>
                  <span
                    style={muted ? { color: '#64748b' } : { color: '#0F172A', fontWeight: 600 }}
                  >
                    {first}
                  </span>
                  {rest.map((line) => (
                    <span key={line} style={{ color: '#334155' }}>
                      {line}
                    </span>
                  ))}
                </span>
              </div>
            )
          })}
          <Explanations linked={linked} />
        </>
      )}
    </Card>
  )
}

const rowStyle: CSSProperties = {
  display: 'grid',
  gridTemplateColumns: '64px 1fr',
  gap: 10,
  alignItems: 'start',
  padding: '10px 0',
  borderBottom: '1px solid #F1F5F9',
  fontSize: 14,
}
const tagStyle: CSSProperties = { marginInlineEnd: 0, fontWeight: 600 }
const explainBoxStyle: CSSProperties = {
  display: 'grid',
  gap: 14,
  marginTop: 8,
  padding: '14px 16px',
  background: '#F8FAFC',
  border: '1px solid #E2E8F0',
  borderRadius: 8,
  fontSize: 13,
}
const breakdownRowStyle: CSSProperties = {
  display: 'flex',
  flexWrap: 'wrap',
  gap: 6,
  alignItems: 'center',
}
const breakdownLabelStyle: CSSProperties = { color: '#64748b', minWidth: 120 }
