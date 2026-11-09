import assert from 'node:assert/strict'
import { test } from 'node:test'
import type { CaseHistory } from '../src/types/case.ts'
import {
  customerCanceledClosure,
  customerClosureNote,
  historyDetail,
  type HistoryRefs,
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

// 판정 · 인계 이력의 결제 번호 찾기 (#666) — 사건 결제 8 → 결제 24, 사건 결제 9 → 결제 25
// 서버 JSON의 ID가 숫자로 와도 찾는지 보려고 eventData 일부는 숫자로 넣는다
const refs: HistoryRefs = {
  payments: [
    { casePaymentId: '8', paymentId: '24' },
    { casePaymentId: '9', paymentId: '25' },
  ],
  decisions: [
    { decisionId: '31', casePaymentId: '8' },
    { decisionId: '32', casePaymentId: '9' },
  ],
  handoffs: [
    { handoffId: '40', decisionId: '31' },
    { handoffId: '41', decisionId: '32' },
  ],
}
const data = (value: Record<string, unknown>) => value as Record<string, string>

test('historyDetail_decisionConfirmed_showsPaymentNumberAndDecision', () => {
  assert.equal(
    historyDetail(
      make({
        eventType: 'DECISION_CONFIRMED',
        eventData: data({ decisionId: 32, casePaymentId: 9, decision: 'REJECT' }),
      }),
      time,
      refs,
    ),
    '결제 25 · 승인 취소 요청',
  )
})

test('historyDetail_handoffEvents_resolvePaymentThroughDecision', () => {
  const detail = (eventType: CaseHistory['eventType'], eventData: Record<string, unknown>) =>
    historyDetail(make({ eventType, eventData: data(eventData) }), time, refs)

  assert.equal(
    detail('HANDOFF_REQUESTED', { handoffId: 41, decisionId: 32, actionType: 'VOID' }),
    '결제 25 · 승인 취소 처리',
  )
  assert.equal(
    detail('HANDOFF_ACCEPTED', {
      handoffId: 40,
      targetOperationId: 'op-9f3a',
      completedAt: '2026-11-02T01:00:00Z',
    }),
    '결제 24 · 실제 완료 T(2026-11-02T01:00:00Z)',
  )
  assert.equal(
    detail('HANDOFF_ACCEPTED', { handoffId: 41, targetOperationId: 'op-1', source: 'RECHECK' }),
    '결제 25 · 다시 확인으로 반영',
  )
  assert.equal(
    detail('HANDOFF_FAILED', { handoffId: 41, errorCode: 'PAYMENT_NOT_HELD' }),
    '결제 25 · 결제가 이미 보류 상태가 아닙니다.',
  )
  assert.equal(
    detail('HANDOFF_RECHECKED', { handoffId: 40, result: 'PENDING' }),
    '결제 24 · 아직 처리 중',
  )
  assert.equal(
    detail('HANDOFF_COMPLETION_RECORDED', { handoffId: 41, completedAt: '2026-11-02T02:00:00Z' }),
    '결제 25 · 실제 완료 T(2026-11-02T02:00:00Z)',
  )
})

test('historyDetail_unknownCodeOrMissingPayment_keepsRawValues', () => {
  // 처음 보는 코드는 값 그대로
  assert.equal(
    historyDetail(
      make({
        eventType: 'HANDOFF_FAILED',
        eventData: { handoffId: '40', errorCode: 'NEW_P5_CODE' },
      }),
      time,
      refs,
    ),
    '결제 24 · NEW_P5_CODE',
  )
  assert.equal(
    historyDetail(
      make({
        eventType: 'DECISION_CONFIRMED',
        eventData: { decisionId: '31', casePaymentId: '8', decision: 'HOLD' },
      }),
      time,
      refs,
    ),
    '결제 24 · HOLD',
  )
  // 결제를 찾지 못하면 예전처럼 값을 모두 잇는다 — 빈칸 · 엉뚱한 결제 번호를 보이지 않는다
  assert.equal(
    historyDetail(
      make({
        eventType: 'HANDOFF_FAILED',
        eventData: { handoffId: '99', errorCode: 'PAYMENT_NOT_HELD' },
      }),
      time,
      refs,
    ),
    '99 · PAYMENT_NOT_HELD',
  )
  assert.equal(
    historyDetail(
      make({
        eventType: 'DECISION_CONFIRMED',
        eventData: { decisionId: '31', casePaymentId: '8', decision: 'ACCEPT' },
      }),
      time,
    ),
    '31 · 8 · ACCEPT',
  )
})
