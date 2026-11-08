import assert from 'node:assert/strict'
import { test } from 'node:test'
import type { CaseHistory } from '../src/types/case.ts'
import {
  customerCanceledClosure,
  customerClosureNote,
  historyDetail,
} from '../src/utils/caseHistoryText.ts'

const make = (over: Partial<CaseHistory>): CaseHistory => ({
  historyId: '1',
  caseId: '2',
  eventType: 'CASE_CREATED',
  actorType: 'SYSTEM',
  actorId: null,
  eventData: {},
  createdAt: '2026-11-02T10:28:32Z',
  ...over,
})

const time = (iso: string) => `T(${iso})`

test('고객이 모든 결제를 취소해 닫힌 Case만 종료 시각을 돌려준다 (#443)', () => {
  const closedByCustomer = make({
    eventType: 'CASE_CLOSED',
    eventData: { caseId: '2', reason: 'CUSTOMER_CANCELED' },
  })
  const closedByStaff = make({ eventType: 'CASE_CLOSED', eventData: { caseId: '2' } })

  assert.equal(customerCanceledClosure([make({}), closedByCustomer]), '2026-11-02T10:28:32Z')
  assert.equal(customerCanceledClosure([make({}), closedByStaff]), null)
  assert.equal(customerCanceledClosure([]), null)
})

test('종료 · 고객 취소 이력은 내부 ID 대신 읽을 수 있는 문구로 보인다 (#443)', () => {
  assert.equal(
    historyDetail(
      make({ eventType: 'CASE_CLOSED', eventData: { caseId: '2', reason: 'CUSTOMER_CANCELED' } }),
      time,
    ),
    '고객이 모든 결제의 승인을 취소함',
  )
  assert.equal(
    historyDetail(make({ eventType: 'CASE_CLOSED', eventData: { caseId: '2' } }), time),
    '',
  )
  assert.equal(
    historyDetail(
      make({
        eventType: 'PAYMENT_CANCELED_BY_CUSTOMER',
        eventData: {
          paymentId: '7',
          casePaymentId: '2',
          cancellationId: '1',
          completedAt: '2026-11-02T10:28:32.166011Z',
        },
      }),
      time,
    ),
    '결제 7 · 실제 완료 T(2026-11-02T10:28:32.166011Z)',
  )
})

test('그 밖의 이력은 예전처럼 값을 잇고 completedAt만 실제 완료 시각으로 바꾼다', () => {
  assert.equal(
    historyDetail(
      make({
        eventType: 'HANDOFF_COMPLETION_RECORDED',
        eventData: { handoffId: '3', completedAt: '2026-11-02T01:00:00Z' },
      }),
      time,
    ),
    '3 · 실제 완료 T(2026-11-02T01:00:00Z)',
  )
  assert.equal(
    historyDetail(
      make({ eventType: 'CASE_STATUS_CHANGED', eventData: { from: 'OPEN', to: 'INVESTIGATING' } }),
      time,
    ),
    '생성됨 → 조사 중',
  )
  // 내부 번호(사건 번호 · casePaymentId)는 이어 붙이지 않는다 (#648)
  assert.equal(
    historyDetail(make({ eventType: 'CASE_CREATED', eventData: { caseId: '8' } }), time),
    '',
  )
  assert.equal(
    historyDetail(
      make({
        eventType: 'PAYMENT_LINKED',
        eventData: { evidenceVersion: '1', casePaymentId: '8', paymentId: '24' },
      }),
      time,
    ),
    '결제 24 · 증거 v1',
  )
})

test('고객 취소 종료 안내는 판정이 있었으면 "판정 없이 끝났다"고 하지 않는다 (PR #444 리뷰)', () => {
  assert.match(customerClosureNote(0), /^판정 · 금융 조치 없이 끝난 Case입니다/)
  assert.doesNotMatch(customerClosureNote(1), /판정 · 금융 조치 없이/)
  assert.match(customerClosureNote(1), /판정 · 인계 기록은 아래에 그대로 남아 있습니다/)
})
