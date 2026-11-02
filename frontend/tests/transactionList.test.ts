import assert from 'node:assert/strict'
import { test } from 'node:test'
import type { MlShadowScore } from '../src/types/mlShadowScore.ts'
import {
  caseCell,
  defaultTransactionListQuery,
  mlCell,
  parseTransactionListQuery,
  transactionApiParams,
  transactionListSearch,
} from '../src/utils/transactionList.ts'

const parse = (search: string) => parseTransactionListQuery(new URLSearchParams(search))

// transactionListQuery — 뒤로 가기 후에도 같은 조건, 모르는 값은 버리고, 한국 날짜 경계로 서버에 보낸다
test('transactionListQuery: 기본값은 주소에 안 쓰고 · 모르는 값은 무시하고 · 한국 날짜 경계로 보낸다', () => {
  // 기본 조건이면 주소가 깨끗하다
  assert.equal(transactionListSearch(defaultTransactionListQuery), '')
  assert.deepEqual(parse(''), defaultTransactionListQuery)

  // 주소 → 조건 → 주소가 그대로 돌아온다 (뒤로 가기 · 새로고침)
  const search =
    '?from=2026-10-24&to=2026-10-30&merchantId=3&status=HELD&orderRef=ORD-1&customerRef=customer-abc&page=2'
  assert.equal(transactionListSearch(parse(search)), search)

  // 모르는 값 · 반쪽 기간 · 거꾸로 된 기간 · 달력에 없는 날은 기본값으로
  assert.deepEqual(
    parse('?from=2026-10-24&merchantId=-1&status=CAPTURE&page=0&unknown=1'),
    defaultTransactionListQuery,
  )
  assert.equal(parse('?from=2026-10-30&to=2026-10-24').from, '')
  assert.equal(parse('?from=2026-02-30&to=2026-03-01').from, '')

  // 10/30 하루 = 한국 10/30 0시 ~ 10/31 0시 전. 페이지는 서버 0부터
  const params = transactionApiParams(parse('?from=2026-10-30&to=2026-10-30&page=3'))
  assert.equal(params.get('from'), '2026-10-30T00:00:00+09:00')
  assert.equal(params.get('to'), '2026-10-31T00:00:00+09:00')
  assert.equal(params.get('page'), '2')
  // + 가 공백으로 바뀌지 않게 인코딩된다
  assert.match(params.toString(), /from=2026-10-30T00%3A00%3A00%2B09%3A00/)
  // 월말을 넘어가도 다음 날을 맞게 계산한다
  assert.equal(
    transactionApiParams(parse('?from=2026-10-01&to=2026-10-31')).get('to'),
    '2026-11-01T00:00:00+09:00',
  )
})

// caseCell_whenLinksFailed_showsUnknown — 사건 링크 조회 실패를 "사건 없음(-)"으로 그리지 않는다
test('caseCell_whenLinksFailed_showsUnknown: 링크 조회 실패는 확인 불가, 링크 없음과 구분한다', () => {
  assert.deepEqual(caseCell('15', { status: 'failed' }), { kind: 'unknown' })
  assert.deepEqual(caseCell('15', { status: 'loading' }), { kind: 'loading' })
  const loaded = { status: 'loaded' as const, caseIdByPaymentId: new Map([['15', '13']]) }
  assert.deepEqual(caseCell('15', loaded), { kind: 'case', caseId: '13' })
  assert.deepEqual(caseCell('16', loaded), { kind: 'none' })
})

const mlScore = (patch: Partial<MlShadowScore>): MlShadowScore => ({
  paymentId: '15',
  modelVersion: 'rf-v1',
  status: 'SUCCEEDED',
  mlRiskLevel: 'HIGH',
  fraudProbability: 0.62,
  threshold: 0.3,
  failureCode: null,
  scoredAt: '2026-11-02T10:00:00Z',
  ...patch,
})
const loadedMl = (...scores: MlShadowScore[]) => ({
  status: 'loaded' as const,
  scoreByPaymentId: new Map(scores.map((score) => [score.paymentId, score])),
})

// mlCell_nonScoredStates_neverShowPercent — 조회 실패 · 기록 없음 · 채점 실패 · 채점 안 함을 섞거나 "0%"로 그리지 않는다
test('mlCell_nonScoredStates_neverShowPercent: 채점 못 한 경우는 상태끼리 구분하고 숫자를 내지 않는다', () => {
  assert.deepEqual(mlCell('15', { status: 'loading' }), { kind: 'loading' })
  // 조회 실패를 "기록 없음"으로 그리면 채점된 결제를 직원이 기록 없음으로 읽는다
  assert.deepEqual(mlCell('15', { status: 'failed' }), { kind: 'unknown' })
  assert.deepEqual(mlCell('16', loadedMl(mlScore({}))), { kind: 'none' })
  const failed = mlScore({
    status: 'FAILED',
    mlRiskLevel: null,
    fraudProbability: null,
    threshold: null,
    failureCode: 'ML_TIMEOUT',
    scoredAt: null,
  })
  assert.deepEqual(mlCell('15', loadedMl(failed)), { kind: 'failed' })
  const skipped = { ...failed, status: 'SKIPPED' as const, failureCode: 'HISTORY_LIMIT_EXCEEDED' }
  assert.deepEqual(mlCell('15', loadedMl(skipped)), { kind: 'skipped' })
})

// mlCell_scored_floorsPercentAndUsesServerLevel — 퍼센트는 내림, 기준 이상 여부는 서버 등급을 따른다
test('mlCell_scored_floorsPercentAndUsesServerLevel: 29.96%는 29%, 0.29는 29%, 기준 이상은 서버 HIGH로', () => {
  assert.deepEqual(mlCell('15', loadedMl(mlScore({}))), {
    kind: 'scored',
    percent: 62,
    thresholdPercent: 30,
    aboveThreshold: true,
    modelVersion: 'rf-v1',
  })
  // 반올림하면 "30%"인데 기준 미만으로 보여 헷갈린다
  const justBelow = mlCell(
    '15',
    loadedMl(mlScore({ fraudProbability: 0.2996, mlRiskLevel: 'LOW' })),
  )
  assert.equal(justBelow.kind === 'scored' && justBelow.percent, 29)
  assert.equal(justBelow.kind === 'scored' && justBelow.aboveThreshold, false)
  // 부동소수 오차(0.29 * 100 = 28.999…)로 한 단계 내려가지 않는다
  const exact = mlCell('15', loadedMl(mlScore({ fraudProbability: 0.29, mlRiskLevel: 'LOW' })))
  assert.equal(exact.kind === 'scored' && exact.percent, 29)
  // 화면이 기준을 따로 계산하지 않는다 — 서버가 HIGH라고 했으면 기준 이상
  const serverHigh = mlCell('15', loadedMl(mlScore({ fraudProbability: 0.3, mlRiskLevel: 'HIGH' })))
  assert.equal(serverHigh.kind === 'scored' && serverHigh.aboveThreshold, true)
})
