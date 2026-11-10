import { Alert, Spin, Tabs } from 'antd'
import { useEffect, useRef, useState } from 'react'
import {
  getFdsRuleCatalog,
  getFdsRulePolicies,
  getInsiderRuleCatalog,
  getOperatorAccessRuleCatalog,
  type FdsRuleCatalogEntry,
  type FdsRulePolicy,
} from '../../api/fds'
import { useAuth } from '../../auth/useAuth'
import { PageHeader } from '../../components/common/PageHeader'
import { useMinimumLoadingIndicator } from '../../hooks/useMinimumLoadingIndicator'
import { refreshRuleChangeRequestBadge } from '../../hooks/useRuleChangeRequestBadge'
import { RuleCard } from './RuleCard'

import { AccountRuleOverview, PaymentRuleOverview } from './RuleOverview'

type RuleCatalogTab = 'payment' | 'account' | 'all'

function RuleCatalogTabs({
  selectedTab,
  paymentRuleCount,
  accountRuleCount,
  isAdmin,
  onSelect,
}: {
  selectedTab: RuleCatalogTab
  paymentRuleCount: number
  accountRuleCount: number
  isAdmin: boolean
  onSelect: (tab: RuleCatalogTab) => void
}) {
  return (
    <Tabs
      className="dw-rule-tabs"
      activeKey={selectedTab}
      onChange={(key) => onSelect(key as RuleCatalogTab)}
      items={[
        ...(isAdmin
          ? [
              { key: 'account', label: `계정 룰 ${accountRuleCount}` },
              { key: 'payment', label: `결제 룰 ${paymentRuleCount}` },
            ]
          : []),
        {
          key: 'all',
          label: isAdmin
            ? `전체 ${paymentRuleCount + accountRuleCount}`
            : `결제 룰 ${paymentRuleCount}`,
        },
      ]}
    />
  )
}

export function RuleCatalogPage() {
  const { user } = useAuth()
  const [rules, setRules] = useState<FdsRuleCatalogEntry[]>([])
  const [accountRules, setAccountRules] = useState<FdsRuleCatalogEntry[]>([])
  const [policies, setPolicies] = useState<FdsRulePolicy[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [chosenTab, setChosenTab] = useState<RuleCatalogTab>('account')
  const policyReloadSequence = useRef(0)
  const initialLoading = loading && rules.length === 0
  // 스피너는 0.3초 뒤에 뜬다. 그 사이에도 본문을 그리지 않아야 빈 본문 → 스피너 → 본문으로 두 번 바뀌지 않는다 (경보 상세와 같은 방식)
  const showLoading = useMinimumLoadingIndicator(initialLoading)
  const isAdmin = user?.role === 'ADMIN'
  // 관리자가 아니면 고를 수 있는 탭이 결제 룰(all) 하나뿐이다. effect로 바꾸면 첫 화면이 한 번 더 그려지므로 렌더 중에 정한다
  const selectedTab: RuleCatalogTab = isAdmin ? chosenTab : 'all'

  useEffect(() => {
    let active = true
    const loadCatalog = async () => {
      setLoading(true)
      setError(null)
      try {
        const [catalog, operatorAccessCatalog, insiderCatalog, rulePolicies] = await Promise.all([
          getFdsRuleCatalog(),
          isAdmin ? getOperatorAccessRuleCatalog() : Promise.resolve([]),
          isAdmin ? getInsiderRuleCatalog() : Promise.resolve([]),
          getFdsRulePolicies(),
        ])
        if (active) {
          setRules(catalog)
          setAccountRules([...operatorAccessCatalog, ...insiderCatalog])
          setPolicies(rulePolicies)
        }
      } catch {
        if (active) setError('탐지 룰 목록을 불러오지 못했습니다.')
      } finally {
        if (active) setLoading(false)
      }
    }
    void loadCatalog()
    return () => {
      active = false
    }
  }, [isAdmin])

  const reloadPolicies = () => {
    const sequence = ++policyReloadSequence.current
    void getFdsRulePolicies()
      .then((next) => {
        if (sequence === policyReloadSequence.current) setPolicies(next)
      })
      .finally(refreshRuleChangeRequestBadge)
  }

  return (
    <div className="dw-rule-catalog-page">
      <PageHeader
        title="탐지 룰 목록"
        description={
          isAdmin
            ? '돈워리가 어떤 규칙으로 이상 징후를 찾는지 봅니다. 결제 룰은 가맹점 결제를, 계정 룰은 돈워리 계정을 지킵니다.'
            : '가맹점 결제의 이상 징후를 찾는 결제 룰을 확인합니다.'
        }
      />
      {!loading && !showLoading && !error && (
        <RuleCatalogTabs
          selectedTab={selectedTab}
          paymentRuleCount={rules.length}
          accountRuleCount={accountRules.length}
          isAdmin={isAdmin}
          onSelect={setChosenTab}
        />
      )}
      {initialLoading || showLoading ? (
        <div className="dw-rule-loading">{showLoading && <Spin />}</div>
      ) : error ? (
        <Alert type="error" showIcon message={error} />
      ) : selectedTab === 'account' && isAdmin ? (
        <>
          <AccountRuleOverview />
          <section className="dw-rule-grid" aria-label="계정 룰">
            {accountRules.map((rule) => (
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
            내부자 규칙(SEC-INS)은 보안 직원 본인을 살피므로 관리자만 켜고 끕니다. SEC-INS-05는
            마지막 방어 장치라 끌 수 없습니다. 끈 규칙을 다시 켜면 켠 뒤의 접근 기록부터 셉니다.
          </div>
        </>
      ) : (
        <>
          {selectedTab === 'all' && isAdmin ? (
            <div className="dw-rule-overview-grid">
              <PaymentRuleOverview />
              <AccountRuleOverview />
            </div>
          ) : (
            <PaymentRuleOverview />
          )}
          <section
            className="dw-rule-grid"
            aria-label={selectedTab === 'all' ? '전체 룰' : '결제 룰'}
          >
            {selectedTab === 'all' &&
              isAdmin &&
              accountRules.map((rule) => (
                <RuleCard
                  key={rule.ruleCode}
                  rule={rule}
                  policy={policies.find((policy) => policy.ruleCode === rule.ruleCode)}
                  isAdmin={isAdmin}
                  onChanged={reloadPolicies}
                />
              ))}
            {rules.map((rule) => (
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
