import { Button, Card, Table, Timeline, Typography } from 'antd'
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
      <Table
        size="small"
        rowKey="handoffId"
        pagination={false}
        dataSource={rows}
        locale={{ emptyText: '판정된 결제가 없습니다.' }}
        columns={[
          { title: '결제', key: 'payment', render: (_, r) => r.payment?.paymentId ?? '-' },
          {
            title: '조치',
            dataIndex: 'actionType',
            render: (v: ActionHandoff['actionType']) => actionTypeLabel[v],
          },
          {
            title: '인계 상태',
            dataIndex: 'status',
            render: (v: ActionHandoff['status'], r) => (
              <>
                <CodeTag value={handoffStatusLabel[v]} />
                {r.lastErrorCode && (
                  <div style={{ fontSize: 12, color: '#dc2626', marginTop: 4 }}>
                    {handoffErrorLabel[r.lastErrorCode] ?? r.lastErrorCode}
                  </div>
                )}
                {v === 'REQUESTED' && (
                  <Button
                    size="small"
                    style={{ marginTop: 4 }}
                    loading={recheckingId === r.handoffId}
                    disabled={recheckingId !== null && recheckingId !== r.handoffId}
                    onClick={() => onRecheck(r.handoffId)}
                  >
                    인계 상태 다시 확인
                  </Button>
                )}
              </>
            ),
          },
          {
            title: '완료 작업 ID',
            dataIndex: 'targetOperationId',
            render: (v: string | null) => v ?? '-',
          },
          {
            title: '실제 결제 상태 (P5)',
            key: 'paymentStatus',
            render: (_, r) =>
              r.payment?.paymentStatus ? (
                <CodeTag value={paymentStatusLabel[r.payment.paymentStatus]} />
              ) : (
                '-'
              ),
          },
        ]}
      />
      <Typography.Paragraph type="secondary" style={{ fontSize: 12, margin: '12px 0 0' }}>
        모든 결제의 실제 매입·취소가 끝나면 Case가 종료됩니다. 시간이 지났다고 실패나 종료로 바꾸지
        않습니다. &quot;인계 상태 다시 확인&quot;은 P5에 저장된 결과를 받아올 뿐 매입을 다시
        실행하지 않습니다.
      </Typography.Paragraph>
    </Card>
  )
}

/** case_history — INSERT 전용 감사 이력을 시간순으로 보여준다. */
export function HistoryCard({ history }: { history: CaseHistory[] }) {
  const sorted = [...history].sort((a, b) => Date.parse(a.createdAt) - Date.parse(b.createdAt))
  return (
    <Card
      title={<span className="dw-section-title">Case 이력</span>}
      style={{ borderColor: '#E2E8F0' }}
    >
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
              {Object.keys(h.eventData).length > 0 && (
                <span style={{ color: '#475569' }}> · {eventDataText(h.eventData)}</span>
              )}
              <div style={{ fontSize: 12, color: '#94a3b8' }}>
                {dateTime(h.createdAt)} · {h.actorType === 'STAFF' ? h.actorId : '시스템'}
              </div>
            </div>
          ),
        }))}
      />
    </Card>
  )
}

/** completedAt은 P5가 알려준 실제 완료 시각이다 — P3 반영 시각(이력 시각)과 구분해 보여준다. */
function eventDataText(eventData: Record<string, string>) {
  return Object.entries(eventData)
    .map(([key, value]) => (key === 'completedAt' ? `실제 완료 ${dateTime(value)}` : value))
    .join(' · ')
}
