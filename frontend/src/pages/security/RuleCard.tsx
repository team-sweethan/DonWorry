import { DownOutlined } from '@ant-design/icons'
import { Alert, Button, Card, Input, Modal, Switch, Tag, Typography } from 'antd'
import { useState } from 'react'
import {
  requestRuleDisable,
  setFdsRuleActive,
  type FdsRuleCatalogEntry,
  type FdsRulePolicy,
} from '../../api/fds'
import { refreshRuleChangeRequestBadge } from '../../hooks/useRuleChangeRequestBadge'
import { displayRuleCode } from '../../utils/ruleDisplayCode'

function formatLastMatchedAt(value: string | null) {
  if (!value) return '최근 적중 없음'
  return new Intl.DateTimeFormat('ko-KR', {
    month: 'numeric',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(new Date(value))
}

function FrequencySparkline({ counts }: { counts: number[] }) {
  const values = counts.length === 7 ? counts : Array.from({ length: 7 }, () => 0)
  const maximum = Math.max(...values, 1)
  const points = values
    .map((value, index) => `${index * 16},${30 - (value / maximum) * 24}`)
    .join(' ')
  const area = `0,30 ${points} 96,30`
  return (
    <svg className="dw-rule-sparkline" viewBox="0 0 96 32" aria-label="최근 7일 룰 적중 빈도">
      <polygon points={area} className="dw-rule-sparkline-area" />
      <polyline points={points} className="dw-rule-sparkline-line" />
      <circle
        cx="96"
        cy={30 - (values[6] / maximum) * 24}
        r="2.5"
        className="dw-rule-sparkline-dot"
      />
    </svg>
  )
}

export function RuleCard({
  rule,
  policy,
  isAdmin,
  onChanged,
}: {
  rule: FdsRuleCatalogEntry
  policy: FdsRulePolicy | undefined
  isAdmin: boolean
  onChanged: () => void
}) {
  const [submitting, setSubmitting] = useState(false)
  const [disableModalOpen, setDisableModalOpen] = useState(false)
  const [disableReason, setDisableReason] = useState('')
  const [actionError, setActionError] = useState<string | null>(null)
  const active = policy?.active ?? true
  const pending = policy?.disableRequestPending ?? false
  const requestDisable = async () => {
    setSubmitting(true)
    try {
      await requestRuleDisable(rule.ruleCode, disableReason.trim())
      setDisableModalOpen(false)
      setDisableReason('')
      onChanged()
    } catch {
      setActionError('끄기 요청을 처리하지 못했습니다. 상태를 다시 확인해주세요.')
      onChanged()
    } finally {
      setSubmitting(false)
    }
  }
  const closeDisableModal = () => {
    if (submitting) return
    setDisableModalOpen(false)
    setDisableReason('')
  }
  const changeActive = async (next: boolean) => {
    setSubmitting(true)
    try {
      await setFdsRuleActive(rule.ruleCode, next)
      onChanged()
      refreshRuleChangeRequestBadge()
    } catch {
      setActionError('룰 상태를 변경하지 못했습니다. 상태를 다시 확인해주세요.')
      onChanged()
    } finally {
      setSubmitting(false)
    }
  }
  const isAccountRule = rule.ruleCode.startsWith('SEC_') || rule.ruleCode.startsWith('INS_')
  // 정책 줄이 없는 규칙은 끄기 · 켜기 주소가 없다 — SEC-INS-05(높음 경보 중 내보내기 차단)가 그렇다 (#685)
  const alwaysOn = policy === undefined
  return (
    <Card
      className={`dw-rule-card ${isAccountRule ? 'dw-rule-card-account' : 'dw-rule-card-payment'}${active ? '' : ' dw-rule-card-inactive'}`}
      size="small"
    >
      {actionError && (
        <Alert
          type="error"
          showIcon
          message={actionError}
          closable
          onClose={() => setActionError(null)}
        />
      )}
      <details className="dw-rule-disclosure">
        <summary className="dw-rule-card-summary">
          <div className="dw-rule-card-top">
            <span>
              <Tag className="dw-rule-code">{displayRuleCode(rule.ruleCode)}</Tag>
              <Typography.Text type="secondary" className="dw-rule-group">
                {rule.category}
              </Typography.Text>
            </span>
            <Tag className={active ? 'dw-rule-status-active' : 'dw-rule-status-inactive'}>
              {active ? '사용 중' : '꺼짐'}
            </Tag>
          </div>
          <h2>{rule.ruleName}</h2>
          <span className="dw-rule-card-expand">
            <span className="dw-rule-expand-label">상세 보기</span>
            <span className="dw-rule-collapse-label">접기</span>
            <DownOutlined />
          </span>
        </summary>
        <div className="dw-rule-card-detail">
          <dl className="dw-rule-definition">
            <div>
              <dt>기준</dt>
              <dd>{rule.condition}</dd>
            </div>
            <div>
              <dt>결과</dt>
              <dd>
                <strong>{rule.resultDescription}</strong>
              </dd>
            </div>
            <div className="dw-rule-example">
              <dt>예시</dt>
              <dd>{rule.example}</dd>
            </div>
          </dl>
          {rule.standaloneDescription && (
            <p className="dw-rule-card-note">
              {rule.ruleCode === 'R07'
                ? 'R07이 켜져 있고 평가 시점에 유효한 차단 항목과 일치하면 100점으로 DENY가 됩니다.'
                : rule.standaloneDescription}
            </p>
          )}
          <div className="dw-rule-frequency">
            <div>
              <span>최근 7일 적중</span>
              <strong>{rule.recentMatchCount.toLocaleString('ko-KR')}건</strong>
              <small>마지막 {formatLastMatchedAt(rule.lastMatchedAt)}</small>
            </div>
            <FrequencySparkline counts={rule.dailyMatchCounts} />
            {alwaysOn ? (
              <Tag className="dw-rule-always-on">항상 켜짐</Tag>
            ) : isAdmin ? (
              <Switch
                className="dw-rule-policy-switch"
                checked={active}
                loading={submitting}
                checkedChildren="켜짐"
                unCheckedChildren="꺼짐"
                onChange={(checked) => void changeActive(checked)}
              />
            ) : !active ? (
              <Button
                className="dw-rule-request-action dw-rule-enable-action"
                loading={submitting}
                type="primary"
                onClick={() => void changeActive(true)}
              >
                다시 켜기
              </Button>
            ) : pending ? (
              <Tag className="dw-rule-disable-pending">끄기 승인 대기</Tag>
            ) : (
              <Button
                className="dw-rule-request-action"
                disabled={submitting}
                loading={submitting}
                onClick={() => setDisableModalOpen(true)}
              >
                끄기 요청
              </Button>
            )}
          </div>
        </div>
      </details>
      <Modal
        title={`${rule.ruleName} 끄기 요청`}
        open={disableModalOpen}
        okText="끄기 요청 보내기"
        cancelText="취소"
        confirmLoading={submitting}
        okButtonProps={{ disabled: !disableReason.trim() }}
        onCancel={closeDisableModal}
        onOk={() => void requestDisable()}
      >
        <Typography.Paragraph type="secondary">
          룰을 끄려면 관리자의 승인이 필요합니다. 요청 사유는 승인자가 확인합니다.
        </Typography.Paragraph>
        <div className="dw-rule-disable-reason-label">
          <Typography.Text strong>요청 사유</Typography.Text>
          <Typography.Text type="secondary">{disableReason.length}/500</Typography.Text>
        </div>
        <Input.TextArea
          autoFocus
          maxLength={500}
          placeholder="예: 오탐 여부를 확인하는 동안 일시 중지가 필요합니다."
          rows={4}
          value={disableReason}
          onChange={(event) => setDisableReason(event.target.value)}
        />
      </Modal>
    </Card>
  )
}
