import type { CSSProperties } from 'react'
import { Card, Tag, Typography } from 'antd'
import type { LinkedMatch, LinkedNearby, LinkedPayments } from '../../../types/case'

// 같은 카드 · 기기 · IP를 쓴 다른 결제 (#352) — 다른 고객이 같은 카드 등을 썼는지 보는 참고 자료. 자동 판정에 쓰지 않는다
// linkedPayments가 없으면(예전 보고서) 카드를 그리지 않고, null이면 "조회하지 못함"을 보여 준다
// 건수 · 고객 수만 온다 — 카드 토큰 · IP 주소 · 기기 ID · 다른 고객 번호는 서버가 보내지 않는다
// 30일 줄 아래 "그중 이 결제 앞뒤 1시간" 줄 (#637) — 몰렸는지(공격) 흩어졌는지(공용 와이파이 등)를 나란히 본다

// FDS 판정 코드값 → 한국어 · 색. 모르는 값은 코드값 그대로
const RESULT_LABEL: Record<string, { label: string; color: string }> = {
  ALLOW: { label: '허용', color: 'green' },
  REVIEW: { label: '검토', color: 'gold' },
  DENY: { label: '차단', color: 'red' },
  ERROR: { label: '평가 오류', color: 'default' },
}

const ROWS: Array<{ key: 'card' | 'device' | 'ip'; label: string }> = [
  { key: 'card', label: '같은 카드' },
  { key: 'device', label: '같은 기기' },
  { key: 'ip', label: '같은 IP' },
]

function resultLabel(code: string) {
  return Object.hasOwn(RESULT_LABEL, code) ? RESULT_LABEL[code] : { label: code, color: 'default' }
}

// 60분 → "1시간", 90분 → "90분"
function windowLabel(minutes: number) {
  return minutes % 60 === 0 ? `${minutes / 60}시간` : `${minutes}분`
}

function Counts({ counts, strong }: { counts: LinkedNearby; strong: boolean }) {
  return (
    <>
      <b style={{ fontSize: strong ? 14 : 13, marginRight: 4 }}>
        다른 고객 {counts.otherCustomerCount}명 · 결제 {counts.paymentCount}건
      </b>
      {counts.resultCounts.map((c) => {
        const r = resultLabel(c.result)
        return (
          <Tag key={c.result} color={r.color} style={tagStyle}>
            {r.label} {c.count}건
          </Tag>
        )
      })}
      {counts.declinedCount > 0 && (
        <Tag color="volcano" style={tagStyle}>
          카드사 거절 {counts.declinedCount}건
        </Tag>
      )}
    </>
  )
}

function MatchLine({ match, windowMinutes }: { match: LinkedMatch; windowMinutes: number | null }) {
  if (!match.provided) {
    return <span style={{ color: '#94A3B8' }}>정보 없음 (가맹점이 보내지 않음)</span>
  }
  if (match.paymentCount === 0) {
    return <span style={{ color: '#64748b' }}>다른 고객 결제 없음</span>
  }
  const nearby = match.nearby
  return (
    <span style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <span style={lineStyle}>
        <Counts counts={match} strong />
      </span>
      {/* 예전 보고서(nearby 없음)는 이 줄을 그리지 않는다 */}
      {nearby && windowMinutes !== null && (
        <span style={lineStyle}>
          <span style={{ color: '#64748b', marginRight: 2 }}>
            그중 이 결제 앞뒤 {windowLabel(windowMinutes)}
          </span>
          {nearby.paymentCount === 0 ? (
            <span style={{ color: '#64748b' }}>없음</span>
          ) : (
            <Counts counts={nearby} strong={false} />
          )}
        </span>
      )}
    </span>
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
          같은 카드 · 기기 · IP를 쓴 다른 결제
          <span style={{ color: '#94A3B8', fontSize: 12, fontWeight: 500, marginLeft: 8 }}>
            최근 {days}일 · 같은 가맹점 · 이 고객 제외
          </span>
        </span>
      }
      extra={<span style={{ color: '#94A3B8', fontSize: 12 }}>참고용 · 자동 판정에 쓰지 않음</span>}
    >
      {linked === null ? (
        <Typography.Text type="secondary">
          같은 카드 · 기기 · IP를 쓴 다른 결제를 조회하지 못했습니다. 판정에는 영향이 없습니다.
        </Typography.Text>
      ) : (
        ROWS.map((row) => (
          <div key={row.key} style={rowStyle}>
            <span style={{ color: '#64748b', fontWeight: 600 }}>{row.label}</span>
            <MatchLine match={linked[row.key]} windowMinutes={windowMinutes} />
          </div>
        ))
      )}
    </Card>
  )
}

const rowStyle: CSSProperties = {
  display: 'grid',
  gridTemplateColumns: '90px 1fr',
  gap: 10,
  alignItems: 'center',
  padding: '8px 0',
  borderBottom: '1px solid #F1F5F9',
  fontSize: 13,
}
const lineStyle: CSSProperties = { display: 'flex', flexWrap: 'wrap', gap: 6, alignItems: 'center' }
const tagStyle: CSSProperties = { marginInlineEnd: 0, fontWeight: 600 }
