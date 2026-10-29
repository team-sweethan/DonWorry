import assert from 'node:assert/strict'
import { test } from 'node:test'
import type { HandoffListItem } from '../src/types/case.ts'
import {
  defaultHandoffListQuery,
  handoffCounts,
  handoffListSearch,
  matchesHandoffTab,
  parseHandoffListQuery,
} from '../src/utils/handoffListQuery.ts'

const parse = (search: string) => parseHandoffListQuery(new URLSearchParams(search))

const make = (over: Partial<HandoffListItem> = {}): HandoffListItem => ({
  handoffId: '1',
  caseId: '1',
  paymentId: '1',
  amount: 300_000,
  decision: 'ACCEPT',
  actionType: 'CAPTURE',
  status: 'REQUESTED',
  lastErrorCode: null,
  staffId: '3',
  decidedAt: '2026-10-29T01:00:00Z',
  requestedAt: '2026-10-29T01:00:00Z',
  acceptedAt: null,
  ...over,
})

test('빈 주소는 전체 탭 1페이지이고, 기본값은 주소에 쓰지 않는다', () => {
  assert.deepEqual(parse(''), defaultHandoffListQuery)
  assert.equal(handoffListSearch(defaultHandoffListQuery), '')
})

test('탭 · 페이지를 주소와 주고받고, 모르는 값은 무시한다', () => {
  assert.deepEqual(parse('?status=FAILED&page=2'), { status: 'FAILED', page: 2 })
  assert.equal(handoffListSearch({ status: 'FAILED', page: 2 }), '?status=FAILED&page=2')
  assert.deepEqual(parse('?status=DONE&page=0'), defaultHandoffListQuery)
})

test('탭으로 거르고 탭마다 건수를 센다', () => {
  const items = [
    make({ status: 'REQUESTED' }),
    make({ status: 'ACCEPTED' }),
    make({ status: 'ACCEPTED' }),
    make({ status: 'FAILED' }),
  ]
  assert.deepEqual(handoffCounts(items), { ALL: 4, REQUESTED: 1, ACCEPTED: 2, FAILED: 1 })
  assert.equal(items.filter((h) => matchesHandoffTab(h, 'ACCEPTED')).length, 2)
  assert.equal(items.filter((h) => matchesHandoffTab(h, 'ALL')).length, 4)
})
