import { useState } from 'react'
import { Alert, Button, Card, Input, Radio, Space, Tag, Typography } from 'antd'
import { useFeedback } from '../../../components/common/Feedback'
import type {
  AiReport,
  CasePayment,
  Decision,
  PaymentDecision,
  SubmitDecisionsRequest,
} from '../../../types/case'
import {
  OPPOSE_MIN_REASON,
  analysisFor,
  decidedOpposition,
  decisionBlocker,
  isOpposing,
  reasonLength,
} from '../../../utils/caseDetailView'
import type { DecisionMode } from '../../../utils/caseRules'
import { won } from '../../../utils/format'
import { dateTime, decisionLabel, recommendationLabel } from './caseLabels'
import { CodeTag } from './CodeTag'

type Draft = { decision?: Decision; reason: string }

/**
 * 결제별 ACCEPT / REJECT 판정 (기획 2.8). 전체 선택은 편의 기능이고 저장은 결제별로 된다.
 * 확정된 판정은 MVP에서 정정하지 않으므로 잠근다.
 */
export function DecisionPanel({
  payments,
  decisions,
  reports,
  mode,
  evidenceVersion,
  onSubmit,
}: {
  payments: CasePayment[]
  decisions: PaymentDecision[]
  // 확정된 판정의 "AI 권고와 다름"을 그 판정의 기준 보고서로 계산한다
  reports: AiReport[]
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

  // 판정할 결제의 AI 권고는 판정 기준 보고서에서 가져온다 (수동 판정이면 없음)
  const baseReport = mode.kind === 'normal' ? mode.report : null
  const analysisOf = (p: CasePayment) => analysisFor(baseReport, p.paymentId)
  const opposingOf = (p: CasePayment) =>
    isOpposing(analysisOf(p)?.recommendation ?? null, draftOf(p.casePaymentId).decision)

  const selected = undecided.filter((p) => draftOf(p.casePaymentId).decision)
  const blocker = decisionBlocker(
    undecided.map((p) => ({
      ...draftOf(p.casePaymentId),
      recommendation: analysisOf(p)?.recommendation ?? null,
    })),
  )

  const submit = () => {
    const accept = selected.filter((p) => draftOf(p.casePaymentId).decision === 'ACCEPT').length
    const opposing = selected.filter(opposingOf).length
    confirmDanger({
      title: `결제 ${selected.length}건의 판정을 확정할까요?`,
      content: `ACCEPT ${accept}건(매입 요청) · REJECT ${selected.length - accept}건(승인 취소 요청)${opposing ? ` · 그중 AI 권고와 반대 ${opposing}건` : ''}. 확정한 판정은 변경할 수 없습니다.`,
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
          const analysis = decided ? null : analysisOf(p)
          const opposing = !decided && opposingOf(p)
          const decidedAgainst = decided ? decidedOpposition(decided, p.paymentId, reports) : null
          return (
            <div key={p.casePaymentId} style={rowBox}>
              <Space size={8} wrap style={{ marginBottom: 8 }}>
                <strong>결제 {p.paymentId}</strong>
                <span>{p.amount === null ? '-' : won(p.amount)}</span>
                <span style={{ color: '#64748b' }}>FDS {p.riskScore ?? '-'}점</span>
                {analysis && (
                  <span style={{ color: '#64748b' }}>
                    AI 권고 <CodeTag value={recommendationLabel[analysis.recommendation]} />
                  </span>
                )}
              </Space>
              {analysis?.reason && (
                <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 8 }}>
                  {analysis.reason}
                </Typography.Paragraph>
              )}
              {decided ? (
                <div style={{ fontSize: 13 }}>
                  <Space size={4} wrap>
                    <CodeTag value={decisionLabel[decided.decision]} />
                    {decidedAgainst && (
                      <Tag color="orange">
                        AI 권고와 다름 ({recommendationLabel[decidedAgainst].label})
                      </Tag>
                    )}
                  </Space>
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
                    status={
                      opposing && reasonLength(draft.reason) < OPPOSE_MIN_REASON
                        ? 'warning'
                        : undefined
                    }
                    style={{ marginTop: 8 }}
                  />
                  {opposing && analysis && (
                    <Alert
                      type="warning"
                      showIcon
                      style={{ marginTop: 8, fontSize: 12, padding: '6px 10px' }}
                      message={`AI 권고(${recommendationLabel[analysis.recommendation].label})와 반대되는 판정입니다. 직접 확인한 근거를 ${OPPOSE_MIN_REASON}자 이상 적어 주세요 — 지금 ${reasonLength(draft.reason)} / ${OPPOSE_MIN_REASON}자 (공백 제외)`}
                    />
                  )}
                </>
              )}
            </div>
          )
        })}
        {editable && (
          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <Button type="primary" disabled={blocker !== null} onClick={submit}>
              판정 확정 ({selected.length}건)
            </Button>
            {blocker === 'MISSING_REASON' && (
              <Typography.Text type="danger" style={{ fontSize: 12 }}>
                판정을 고른 결제에 사유를 입력하세요.
              </Typography.Text>
            )}
            {blocker === 'SHORT_OPPOSING_REASON' && (
              <Typography.Text type="danger" style={{ fontSize: 12 }}>
                AI 권고와 반대인 판정은 사유를 {OPPOSE_MIN_REASON}자 이상(공백 제외) 입력하세요.
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
