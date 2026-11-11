import assert from 'node:assert/strict'
import { test } from 'node:test'
import { canOpenPath } from '../src/config/navigation.ts'
import {
  baseDataset,
  baseSplits,
  compareSet,
  mlModelTab,
  realisticDataset,
  realisticSplits,
  scenarios,
} from '../src/pages/security/mlModel/mlModelRecord.ts'
import type { AuthUser } from '../src/types/auth.ts'

const sum = (values: number[]) => values.reduce((total, value) => total + value, 0)

// 구간(학습 · 검증 · 시험 · 제외) 건수를 더하면 데이터 전체 · 정상 · 사기 수와 같다
test('mlModelRecord_splitCounts_sumToDatasetTotals', () => {
  assert.equal(sum(baseSplits.map((row) => row.normal)), baseDataset.normal)
  assert.equal(sum(baseSplits.map((row) => row.fraud)), baseDataset.fraud)
  assert.equal(sum(baseSplits.map((row) => row.normal + row.fraud)), baseDataset.total)
  assert.equal(sum(realisticSplits.map((row) => row.normal)), realisticDataset.normal)
  assert.equal(sum(realisticSplits.map((row) => row.fraud)), realisticDataset.fraud)
  assert.equal(sum(realisticSplits.map((row) => row.normal + row.fraud)), realisticDataset.total)
  // 모델 비교 문제지는 검증 구간 그대로다
  const validation = baseSplits.find((row) => row.key === 'validation')
  assert.deepEqual({ normal: validation?.normal, fraud: validation?.fraud }, compareSet)
})

// 시나리오 7종을 더하면 사기 수 · 공격 사건 수와 같고, 시나리오마다 구간 배치를 더하면 그 시나리오 건수다
test('mlModelRecord_scenarioCounts_sumToFraudTotal', () => {
  assert.equal(sum(scenarios.map((s) => s.transactions)), baseDataset.fraud)
  assert.equal(sum(scenarios.map((s) => s.episodes)), baseDataset.attackEpisodes)
  for (const s of scenarios) {
    const { train, validation, test: testCount, excluded } = s.split
    assert.equal(train + validation + testCount + excluded, s.transactions, s.label)
  }
  const fraudBy = (key: string) => baseSplits.find((row) => row.key === key)?.fraud
  assert.equal(sum(scenarios.map((s) => s.split.train)), fraudBy('train'))
  assert.equal(sum(scenarios.map((s) => s.split.validation)), fraudBy('validation'))
  assert.equal(sum(scenarios.map((s) => s.split.test)), fraudBy('test'))
  assert.equal(sum(scenarios.map((s) => s.split.excluded)), fraudBy('excluded'))
})

// 모르는 ?tab= 값이나 빈 값이면 "한눈에"를 보여 준다
test('mlModelTab_whenUnknownTabParam_showsOverview', () => {
  assert.equal(mlModelTab('nope'), 'overview')
  assert.equal(mlModelTab(''), 'overview')
  assert.equal(mlModelTab(null), 'overview')
  assert.equal(mlModelTab('split'), 'split')
})

const base: AuthUser = {
  userId: 1,
  passwordChangeRequired: false,
  loginId: 'user',
  displayName: '테스트',
  role: 'SECURITY_STAFF',
  merchantId: null,
  tenantScope: 'ALL',
  permissions: [],
}

// ML 모델 화면은 보안 직원 메뉴에만 있다
test('canOpenPath_securityStaffOnly_opensMlModel', () => {
  assert.equal(canOpenPath(base, '/security/ml-model'), true)
  assert.equal(
    canOpenPath({ ...base, role: 'ADMIN', tenantScope: 'ALL' }, '/security/ml-model'),
    false,
  )
  assert.equal(
    canOpenPath(
      {
        ...base,
        role: 'OPERATOR',
        merchantId: 7,
        tenantScope: 'MERCHANT',
        permissions: ['FDS_VIEW', 'FINANCE_VIEW'],
      },
      '/security/ml-model',
    ),
    false,
  )
})
