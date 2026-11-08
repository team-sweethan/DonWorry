import { Button, Card, Collapse, Timeline, Typography } from 'antd'
import type { ActionHandoff, CaseHistory, CasePayment, PaymentDecision } from '../../../types/case'
import {
  actionTypeLabel,
  dateTime,
  handoffErrorLabel,
  handoffStatusLabel,
  historyEventLabel,
  paymentStatusLabel,
} from './caseLabels'
import { CodeTag } from './CodeTag'
import { historyDetail } from '../../../utils/caseHistoryText'

/**
 * 결제별 금융 조치 인계 (설계 3.8, ADR-0027 완료 기반). ACCEPTED는 실제 매입·취소 완료다.
 * 대기 중이면 "인계 상태 다시 확인"으로 P5에 저장된 결과를 받아온다 — 매입을 다시 실행하지 않는다 (#161).
 */
export function HandoffCard({
  payments,
  decisions,
  handoffs,
  recheckingId,
  onRecheck,
}: {
  payments: CasePayment[]
  decisions: PaymentDecision[]
  handoffs: ActionHandoff[]
  recheckingId: string | null
  onRecheck: (handoffId: string) => void
}) {
  const rows = handoffs.map((h) => {
    const decision = decisions.find((d) => d.decisionId === h.decisionId)
    const payment = payments.find((p) => p.casePaymentId === decision?.casePaymentId)
    return { ...h, payment }
  })
  return (
    <Card
      title={<span className="dw-section-title">금융 조치 인계</span>}
      style={{ borderColor: '#E2E8F0' }}
    >
      {/* 판정 칸 옆 좁은 자리에 들어가도록 표 대신 결제별 줄로 보인다 (#648). 보이는 값은 예전 표와 같다 */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
        {rows.length === 0 && (
          <Typography.Text type="secondary">판정된 결제가 없습니다.</Typography.Text>
        )}
        {rows.map((r) => (
          <div key={r.handoffId} style={handoffRow}>
            <strong>
              결제 {r.payment?.paymentId ?? '-'} · {actionTypeLabel[r.actionType]}
            </strong>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
              <span style={{ fontSize: 12, color: '#64748b' }}>인계 상태</span>
              <CodeTag value={handoffStatusLabel[r.status]} />
              {r.status === 'REQUESTED' && (
                <Button
                  size="small"
                  loading={recheckingId === r.handoffId}
                  disabled={recheckingId !== null && recheckingId !== r.handoffId}
                  onClick={() => onRecheck(r.handoffId)}
                >
                  인계 상태 다시 확인
                </Button>
              )}
            </div>
            {r.lastErrorCode && (
              <div style={{ fontSize: 12, color: '#dc2626' }}>
                {handoffErrorLabel[r.lastErrorCode] ?? r.lastErrorCode}
              </div>
            )}
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
              <span style={{ fontSize: 12, color: '#64748b' }}>실제 결제 상태 (P5)</span>
              {r.payment?.paymentStatus ? (
                <CodeTag value={paymentStatusLabel[r.payment.paymentStatus]} />
              ) : (
                '-'
              )}
            </div>
            <span style={{ fontSize: 12, color: '#64748b' }}>
              완료 작업 ID {r.targetOperationId ?? '-'}
            </span>
          </div>
        ))}
      </div>
      <Typography.Paragraph type="secondary" style={{ fontSize: 12, margin: '12px 0 0' }}>
        모든 결제의 실제 매입·취소가 끝나면 Case가 종료됩니다. 시간이 지났다고 실패나 종료로 바꾸지
        않습니다. &quot;인계 상태 다시 확인&quot;은 P5에 저장된 결과를 받아올 뿐 매입을 다시
        실행하지 않습니다.
      </Typography.Paragraph>
    </Card>
  )
}

/**
 * case_history — INSERT 전용 감사 이력을 시간순으로 보여준다.
 * 판정에 바로 쓰는 정보가 아니라 접어 두고, 접힌 줄에 건수와 마지막 일만 보인다 (#648)
 */
export function HistoryCard({ history }: { history: CaseHistory[] }) {
  const sorted = [...history].sort((a, b) => Date.parse(a.createdAt) - Date.parse(b.createdAt))
  const last = sorted.at(-1)
  return (
    <Collapse
      style={{ background: 'white', borderColor: '#E2E8F0' }}
      items={[
        {
          key: 'history',
          label: (
            <span>
              <span className="dw-section-title">Case 이력</span>
              <span style={{ marginLeft: 10, fontSize: 12, color: '#64748b' }}>
                {sorted.length}건
                {last &&
                  ` · 마지막 ${dateTime(last.createdAt)} ${historyEventLabel[last.eventType]}`}
              </span>
            </span>
          ),
          children: <HistoryTimeline sorted={sorted} />,
        },
      ]}
    />
  )
}

function HistoryTimeline({ sorted }: { sorted: CaseHistory[] }) {
  return (
    <Timeline
      items={sorted.map((h) => ({
        color:
          h.eventType === 'HANDOFF_FAILED'
            ? 'red'
            : h.eventType === 'CASE_CLOSED'
              ? 'green'
              : 'blue',
        children: (
          <div style={{ fontSize: 13 }}>
            <strong>{historyEventLabel[h.eventType]}</strong>
            {historyDetail(h, dateTime) && (
              <span style={{ color: '#475569' }}> · {historyDetail(h, dateTime)}</span>
            )}
            <div style={{ fontSize: 12, color: '#94a3b8' }}>
              {dateTime(h.createdAt)} · {h.actorType === 'STAFF' ? h.actorId : '시스템'}
            </div>
          </div>
        ),
      }))}
    />
  )
}

const handoffRow = {
  display: 'flex',
  flexDirection: 'column' as const,
  gap: 6,
  padding: '10px 12px',
  border: '1px solid #E2E8F0',
  borderRadius: 6,
  fontSize: 13,
}
