import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  caseCell,
  defaultTransactionListQuery,
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
