import { useState } from 'react'
import { Alert, Button, Card, Input, Radio, Space, Typography } from 'antd'
import { useFeedback } from '../../../components/common/Feedback'
import type {
  CasePayment,
  Decision,
  PaymentDecision,
  SubmitDecisionsRequest,
} from '../../../types/case'
import type { DecisionMode } from '../../../utils/caseRules'
import { won } from '../../../utils/format'
import { dateTime, decisionLabel } from './caseLabels'
import { CodeTag } from './CodeTag'

type Draft = { decision?: Decision; reason: string }

/**
 * 결제별 ACCEPT / REJECT 판정 (기획 2.8). 전체 선택은 편의 기능이고 저장은 결제별로 된다.
 * 확정된 판정은 MVP에서 정정하지 않으므로 잠근다.
 */
export function DecisionPanel({
  payments,
  decisions,
  mode,
  evidenceVersion,
  onSubmit,
}: {
  payments: CasePayment[]
  decisions: PaymentDecision[]
  mode: DecisionMode
  evidenceVersion: number
  /** 저장 성공 여부. 오류 안내는 호출한 쪽이 한다. */
  onSubmit: (request: SubmitDecisionsRequest) => Promise<boolean>
}) {
  const { confirmDanger } = useFeedback()
  const [drafts, setDrafts] = useState<Record<string, Draft>>({})
  const decisionOf = (id: string) => decisions.find((d) => d.casePaymentId === id)
  const undecided = payments.filter((p) => !decisionOf(p.casePaymentId))
  const editable = mode.kind !== 'blocked' && undecided.length > 0
  const draftOf = (id: string): Draft => drafts[id] ?? { reason: '' }
  const update = (id: string, patch: Partial<Draft>) =>
    setDrafts((prev) => ({ ...prev, [id]: { ...draftOf(id), ...patch } }))
  const setAll = (decision: Decision) =>
    setDrafts((prev) => {
      const next = { ...prev }
      for (const p of undecided) next[p.casePaymentId] = { ...draftOf(p.casePaymentId), decision }
      return next
    })

  const selected = undecided.filter((p) => draftOf(p.casePaymentId).decision)
  const missingReason = selected.some((p) => !draftOf(p.casePaymentId).reason.trim())

  const submit = () => {
    const accept = selected.filter((p) => draftOf(p.casePaymentId).decision === 'ACCEPT').length
    confirmDanger({
      title: `결제 ${selected.length}건의 판정을 확정할까요?`,
      content: `ACCEPT ${accept}건(매입 요청) · REJECT ${selected.length - accept}건(승인 취소 요청). 확정한 판정은 변경할 수 없습니다.`,
      onConfirm: async () => {
        const saved = await onSubmit({
          evidenceVersion,
          reportId: mode.kind === 'normal' ? mode.report.reportId : null,
          decisions: selected.map((p) => ({
            casePaymentId: p.casePaymentId,
            decision: draftOf(p.casePaymentId).decision as Decision,
            reason: draftOf(p.casePaymentId).reason.trim(),
          })),
        })
        if (saved) setDrafts({})
      },
    })
  }

  return (
    <Card
      title={<span className="dw-section-title">결제별 판정</span>}
      extra={
        editable && (
          <Space size={6}>
            <Button size="small" onClick={() => setAll('ACCEPT')}>
              전체 ACCEPT
            </Button>
            <Button size="small" onClick={() => setAll('REJECT')}>
              전체 REJECT
            </Button>
          </Space>
        )
      }
      style={{ borderColor: '#E2E8F0' }}
    >
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        {mode.kind === 'normal' && undecided.length > 0 && (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            기준 보고서 v{mode.report.reportVersion} (증거 v{mode.report.evidenceVersion})
          </Typography.Text>
        )}
        {mode.kind === 'manual' && undecided.length > 0 && (
          <Alert
            type="warning"
            showIcon
            message="수동 판정"
            description="AI 보고서 없이 판정합니다. 사유에 직접 조사한 근거를 적어 주세요."
          />
        )}
        {mode.kind === 'blocked' && undecided.length > 0 && (
          <Alert type="info" showIcon message={mode.message} />
        )}
        {payments.map((p) => {
          const decided = decisionOf(p.casePaymentId)
          const draft = draftOf(p.casePaymentId)
          return (
            <div key={p.casePaymentId} style={rowBox}>
              <Space size={8} wrap style={{ marginBottom: 8 }}>
                <strong>{p.paymentId}</strong>
                <span>{p.amount === null ? '-' : won(p.amount)}</span>
                <span style={{ color: '#64748b' }}>FDS {p.riskScore ?? '-'}점</span>
              </Space>
              {decided ? (
                <div style={{ fontSize: 13 }}>
                  <CodeTag value={decisionLabel[decided.decision]} />
                  <div style={{ marginTop: 6 }}>{decided.reason}</div>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {decided.staffId} · {dateTime(decided.decidedAt)} ·{' '}
                    {decided.reportId ? `보고서 ${decided.reportId}` : '수동 판정'}
                  </Typography.Text>
                </div>
              ) : (
                <>
                  <Radio.Group
                    disabled={!editable}
                    value={draft.decision}
                    onChange={(e) => update(p.casePaymentId, { decision: e.target.value })}
                    optionType="button"
                    buttonStyle="solid"
                    size="small"
                    options={[
                      { value: 'ACCEPT', label: 'ACCEPT · 매입 허용' },
                      { value: 'REJECT', label: 'REJECT · 매입 거부' },
                    ]}
                  />
                  <Input.TextArea
                    aria-label={`${p.paymentId} 판정 사유`}
                    disabled={!editable}
                    value={draft.reason}
                    onChange={(e) => update(p.casePaymentId, { reason: e.target.value })}
                    placeholder={
                      mode.kind === 'manual' ? '직접 조사한 근거 (필수)' : '판정 사유 (필수)'
                    }
                    autoSize={{ minRows: 2, maxRows: 4 }}
                    maxLength={500}
                    style={{ marginTop: 8 }}
                  />
                </>
              )}
            </div>
          )
        })}
        {editable && (
          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <Button
              type="primary"
              disabled={selected.length === 0 || missingReason}
              onClick={submit}
            >
              판정 확정 ({selected.length}건)
            </Button>
            {missingReason && (
              <Typography.Text type="danger" style={{ fontSize: 12 }}>
                판정을 고른 결제에 사유를 입력하세요.
              </Typography.Text>
            )}
          </div>
        )}
      </Space>
    </Card>
  )
}

const rowBox = {
  padding: '12px 14px',
  border: '1px solid #E2E8F0',
  borderRadius: 6,
}
