import assert from 'node:assert/strict'
import { test } from 'node:test'
import type { LinkedMatch, LinkedNearby, LinkedPayments } from '../src/types/case.ts'
import { linkedExplanations } from '../src/utils/linkedPaymentsView.ts'

// "다른 손님과 겹치는 것" 카드 설명 고르기 (#637)
// 몰린 사건에 "공용 인터넷일 가능성" 설명이 붙는 식으로 반대 설명이 나오면 직원이 잘못 읽는다

const NO_NEARBY: LinkedNearby = {
  otherCustomerCount: 0,
  paymentCount: 0,
  resultCounts: [],
  declinedCount: 0,
}
const NONE: LinkedMatch = {
  provided: true,
  otherCustomerCount: 0,
  paymentCount: 0,
  resultCounts: [],
  declinedCount: 0,
  nearby: NO_NEARBY,
}
const NOT_PROVIDED: LinkedMatch = { ...NONE, provided: false }

function ip(nearbyPayments: number): LinkedMatch {
  return {
    provided: true,
    otherCustomerCount: 9,
    paymentCount: 27,
    resultCounts: [{ result: 'ALLOW', count: 27 }],
    declinedCount: 0,
    nearby: {
      otherCustomerCount: nearbyPayments > 0 ? 1 : 0,
      paymentCount: nearbyPayments,
      resultCounts: nearbyPayments > 0 ? [{ result: 'ALLOW', count: nearbyPayments }] : [],
      declinedCount: 0,
    },
  }
}

function linked(parts: Partial<Pick<LinkedPayments, 'card' | 'device' | 'ip'>>): LinkedPayments {
  return { lookbackDays: 30, nearbyWindowMinutes: 60, card: NONE, device: NONE, ip: NONE, ...parts }
}

function keys(l: LinkedPayments) {
  return linkedExplanations(l).map((e) => e.key)
}

test('linkedExplanations_byCounts_picksMatchingCases', () => {
  // 사건 15 모양: 인터넷만 겹치고 1시간 안에 몰림
  assert.deepEqual(keys(linked({ ip: ip(3) })), ['ipBurst', 'results', 'privacy'])
  // 사건 14 모양: 인터넷만 겹치고 1시간 안엔 없음
  assert.deepEqual(keys(linked({ ip: ip(0) })), ['ipSpread', 'results', 'privacy'])
  // 같은 카드 + 카드사 거절, 인터넷은 띄엄띄엄
  const card: LinkedMatch = {
    provided: true,
    otherCustomerCount: 1,
    paymentCount: 2,
    resultCounts: [{ result: 'DENY', count: 2 }],
    declinedCount: 1,
    nearby: NO_NEARBY,
  }
  assert.deepEqual(keys(linked({ card, ip: ip(0) })), [
    'card',
    'ipSpread',
    'declined',
    'results',
    'privacy',
  ])
  // 인터넷 정보를 받지 못함 — 겹친 것이 없으니 FDS 결과 · 개인정보 설명은 없다
  assert.deepEqual(keys(linked({ ip: NOT_PROVIDED })), ['notProvided'])
  // 아무것도 겹치지 않고 정보도 다 받음 — 설명 없음 (버튼을 숨긴다)
  assert.deepEqual(keys(linked({})), [])
})
