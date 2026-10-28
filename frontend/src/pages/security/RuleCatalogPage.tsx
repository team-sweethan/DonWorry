import { Alert, Button, Card, Input, Modal, Spin, Switch, Tag, Typography } from 'antd'
import { useEffect, useRef, useState } from 'react'
import {
  getFdsRuleCatalog,
  getFdsRulePolicies,
  requestRuleDisable,
  setFdsRuleActive,
  type FdsRuleCatalogEntry,
  type FdsRulePolicy,
} from '../../api/fds'
import { useAuth } from '../../auth/useAuth'
import { PageHeader } from '../../components/common/PageHeader'
import { useMinimumLoadingIndicator } from '../../hooks/useMinimumLoadingIndicator'
import { refreshRuleChangeRequestBadge } from '../../hooks/useRuleChangeRequestBadge'

type RuleCatalogTab = 'payment' | 'account' | 'all'

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

function RuleCard({
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
  return (
    <Card className={`dw-rule-card${active ? '' : ' dw-rule-card-inactive'}`} size="small">
      {actionError && (
        <Alert
          type="error"
          showIcon
          message={actionError}
          closable
          onClose={() => setActionError(null)}
        />
      )}
      <div className="dw-rule-card-top">
        <span>
          <Tag className="dw-rule-code">{rule.ruleCode}</Tag>
          <Typography.Text type="secondary" className="dw-rule-group">
            {rule.category}
          </Typography.Text>
        </span>
        <Tag className={active ? 'dw-rule-status-active' : 'dw-rule-status-inactive'}>
          {active ? '사용 중' : '꺼짐'}
        </Tag>
      </div>
      <h2>{rule.ruleName}</h2>
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
        <div>
          <dt>예시</dt>
          <dd>{rule.example}</dd>
        </div>
      </dl>
      <div className="dw-rule-frequency">
        <div>
          <span>최근 7일 적중</span>
          <strong>{rule.recentMatchCount.toLocaleString('ko-KR')}건</strong>
          <small>마지막 {formatLastMatchedAt(rule.lastMatchedAt)}</small>
        </div>
        <FrequencySparkline counts={rule.dailyMatchCounts} />
        {isAdmin ? (
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
      <Modal
        title={`${rule.ruleName} 끄기 요청`}
        open={disableModalOpen}
        okText="끄기 요청 보내기"
        cancelText="취소"
        confirmLoading={submitting}
        okButtonProps={{ disabled: !disableReason.trim() }}
        onCancel={() => {
          if (!submitting) setDisableModalOpen(false)
        }}
        onOk={() => void requestDisable()}
      >
        <Typography.Paragraph type="secondary">
          룰을 끄려면 관리자의 승인이 필요합니다. 요청 사유는 승인자가 확인합니다.
        </Typography.Paragraph>
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

function RuleCatalogTabs({
  selectedTab,
  paymentRuleCount,
  onSelect,
}: {
  selectedTab: RuleCatalogTab
  paymentRuleCount: number
  onSelect: (tab: RuleCatalogTab) => void
}) {
  return (
    <Card className="dw-rule-tabs" size="small">
      <div className="dw-rule-tab-list" role="tablist" aria-label="룰 카탈로그 범위">
        <button
          className={selectedTab === 'payment' ? 'is-active' : undefined}
          role="tab"
          aria-selected={selectedTab === 'payment'}
          onClick={() => onSelect('payment')}
        >
          결제 룰 {paymentRuleCount || 8}
        </button>
        <button
          className={selectedTab === 'account' ? 'is-active' : undefined}
          role="tab"
          aria-selected={selectedTab === 'account'}
          onClick={() => onSelect('account')}
        >
          계정 룰 0
        </button>
        <button
          className={selectedTab === 'all' ? 'is-active' : undefined}
          role="tab"
          aria-selected={selectedTab === 'all'}
          onClick={() => onSelect('all')}
        >
          전체 {paymentRuleCount || 8}
        </button>
      </div>
    </Card>
  )
}

function PaymentRuleOverview() {
  return (
    <Card className="dw-rule-overview" size="small">
      <div className="dw-rule-overview-heading">
        <div>
          <strong>결제 룰 · 차단 목록 → 점수 합산 → 구간 판정</strong>
          <Typography.Text type="secondary">
            차단 목록을 먼저 확인하고, 일치하지 않으면 적중한 룰 점수를 합산합니다.
          </Typography.Text>
        </div>
      </div>
      <div className="dw-rule-score-scale" aria-label="FDS 점수 구간">
        <span className="dw-rule-score-allow">ALLOW 0~49</span>
        <span className="dw-rule-score-review">REVIEW 50~79</span>
        <span className="dw-rule-score-deny">DENY 80 이상</span>
      </div>
      <div className="dw-rule-overview-examples">
        <span>
          예) 고액 결제(+30) + 신규 단말기(+25) = <strong>55점 · REVIEW</strong>
        </span>
        <span>
          예) 차단 목록(R07) 일치 = <strong>100점 · 즉시 DENY</strong>
        </span>
      </div>
    </Card>
  )
}

function AccountRuleOverview() {
  return (
    <Card className="dw-rule-account-overview" size="small">
      <div className="dw-rule-overview-heading">
        <div>
          <strong>계정 룰 · 탐지 + 방어</strong>
          <Typography.Text type="secondary">
            계정이 뚫리면 공격자가 의심 결제를 허용하거나 Rule을 끌 수 있어, 탐지와 함께 통제를
            둡니다.
          </Typography.Text>
        </div>
        <Tag>개발 예정</Tag>
      </div>
      <div className="dw-account-rule-placeholders">
        <div>
          <strong>로그인 보안</strong>
          <span>의심 로그인 탐지 룰을 준비 중입니다.</span>
        </div>
        <div>
          <strong>권한 변경 통제</strong>
          <span>민감 권한 변경 검증 룰을 준비 중입니다.</span>
        </div>
        <div>
          <strong>정책 제어</strong>
          <span>룰 변경 승인 절차를 준비 중입니다.</span>
        </div>
      </div>
    </Card>
  )
}

export function RuleCatalogPage() {
  const { user } = useAuth()
  const [rules, setRules] = useState<FdsRuleCatalogEntry[]>([])
  const [policies, setPolicies] = useState<FdsRulePolicy[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [selectedTab, setSelectedTab] = useState<RuleCatalogTab>('payment')
  const policyReloadSequence = useRef(0)
  const showLoading = useMinimumLoadingIndicator(loading && rules.length === 0)
  const visibleRules = selectedTab === 'account' ? [] : rules

  useEffect(() => {
    let active = true
    const loadCatalog = async () => {
      setLoading(true)
      setError(null)
      try {
        const [catalog, rulePolicies] = await Promise.all([
          getFdsRuleCatalog(),
          getFdsRulePolicies(),
        ])
        if (active) {
          setRules(catalog)
          setPolicies(rulePolicies)
        }
      } catch {
        if (active) setError('룰 카탈로그를 불러오지 못했습니다.')
      } finally {
        if (active) setLoading(false)
      }
    }
    void loadCatalog()
    return () => {
      active = false
    }
  }, [])

  const reloadPolicies = () => {
    const sequence = ++policyReloadSequence.current
    void getFdsRulePolicies()
      .then((next) => {
        if (sequence === policyReloadSequence.current) setPolicies(next)
      })
      .finally(refreshRuleChangeRequestBadge)
  }
  const isAdmin = user?.role === 'ADMIN'

  return (
    <div className="dw-rule-catalog-page">
      <PageHeader
        title="Rule 카탈로그"
        description="돈워리가 어떤 규칙으로 이상 징후를 찾는지 봅니다. 결제 룰은 가맹점 결제를, 계정 룰은 돈워리 계정을 지킵니다."
      />
      {showLoading ? (
        <div className="dw-rule-loading">
          <Spin />
        </div>
      ) : error ? (
        <Alert type="error" showIcon message={error} />
      ) : selectedTab === 'account' ? (
        <>
          <AccountRuleOverview />
          <RuleCatalogTabs
            selectedTab={selectedTab}
            paymentRuleCount={rules.length}
            onSelect={setSelectedTab}
          />
          <Card className="dw-rule-empty-state">등록된 계정 룰이 없습니다.</Card>
        </>
      ) : (
        <>
          {selectedTab === 'all' ? (
            <div className="dw-rule-overview-grid">
              <PaymentRuleOverview />
              <AccountRuleOverview />
            </div>
          ) : (
            <PaymentRuleOverview />
          )}
          <RuleCatalogTabs
            selectedTab={selectedTab}
            paymentRuleCount={rules.length}
            onSelect={setSelectedTab}
          />
          <section
            className="dw-rule-grid"
            aria-label={selectedTab === 'all' ? '전체 룰' : '결제 룰'}
          >
            {visibleRules.map((rule) => (
              <RuleCard
                key={rule.ruleCode}
                rule={rule}
                policy={policies.find((policy) => policy.ruleCode === rule.ruleCode)}
                isAdmin={isAdmin}
                onChanged={reloadPolicies}
              />
            ))}
          </section>
          <div className="dw-rule-note">
            R02·R03은 과거 정상 승인 이력이 없는 첫 결제에는 적용하지 않습니다. R04·R06·R08은 평가
            시점 이전의 결제 이력만 집계합니다. 보안 직원의 `끄기 요청`은 관리자 승인 뒤에만
            반영됩니다.
          </div>
        </>
      )}
    </div>
  )
}
