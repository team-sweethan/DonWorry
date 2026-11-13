import assert from 'node:assert/strict'
import { test } from 'node:test'
import type { MerchantPaymentResult } from '../src/types/merchantCase.ts'
import {
  caseConclusion,
  conclusionSentence,
  heldMoney,
  paymentDecisionNote,
  paymentOutcome,
  paymentProgress,
  resultSummary,
} from '../src/pages/owner/merchantCaseProgress.ts'

const payment = (patch: Partial<MerchantPaymentResult>): MerchantPaymentResult => ({
  paymentId: '1',
  amount: 10_000,
  requestedAt: null,
  paymentStatus: 'HELD',
  decision: null,
  customerCanceled: false,
  ...patch,
})

const states = (p: MerchantPaymentResult) => paymentProgress(p).steps.map((s) => s.state)

test('판정 전 보류 결제는 담당자 확인 단계가 진행 중이다', () => {
  const p = paymentProgress(payment({}))
  assert.deepEqual(states(payment({})), ['done', 'done', 'current', 'todo'])
  assert.equal(p.steps[2]?.label, '담당자 확인 중')
  assert.equal(p.result, '담당자가 확인하고 있어요')
})

test('정상 처리로 판정돼도 결제가 보류 중이면 매입 완료로 보이지 않는다', () => {
  const p = paymentProgress(payment({ decision: 'ACCEPT' }))
  assert.deepEqual(states(payment({ decision: 'ACCEPT' })), ['done', 'done', 'done', 'current'])
  assert.equal(p.steps[3]?.label, '반영 중')
  assert.doesNotMatch(p.result, /매입이 끝났/)
})

test('실제 결제 상태가 매입 완료 · 승인 취소면 마지막 단계까지 채운다', () => {
  const captured = paymentProgress(payment({ decision: 'ACCEPT', paymentStatus: 'CAPTURED' }))
  assert.equal(captured.steps[3]?.label, '매입 완료')
  assert.equal(captured.tone, 'success')
  const voided = paymentProgress(payment({ decision: 'REJECT', paymentStatus: 'VOIDED' }))
  assert.equal(voided.steps[2]?.label, '취소 결정')
  assert.equal(voided.steps[3]?.label, '승인 취소')
  assert.equal(voided.tone, 'danger')
})

test('고객이 직접 취소한 결제는 담당자 거절과 구분한다', () => {
  const p = paymentProgress(payment({ paymentStatus: 'VOIDED', customerCanceled: true }))
  assert.deepEqual(
    p.steps.map((s) => [s.label, s.state]),
    [
      ['결제 요청', 'done'],
      ['잠시 보류', 'done'],
      ['판정 없음', 'skipped'],
      ['고객 취소', 'done'],
    ],
  )
  assert.equal(p.result, '고객이 직접 취소했어요')
})

test('결제 상태를 모르면 완료로 채우지 않는다', () => {
  const p = paymentProgress(payment({ decision: 'ACCEPT', paymentStatus: null }))
  assert.equal(p.steps[3]?.state, 'todo')
  assert.equal(p.result, '결제 상태를 확인하지 못했어요')
  assert.equal(paymentOutcome(payment({ paymentStatus: null })).label, '확인 불가')
  assert.equal(
    paymentOutcome(payment({ decision: 'ACCEPT', paymentStatus: null })).label,
    '확인 불가',
  )
})

test('판정 없이 끝난 결제는 판정 칸을 건너뛴 것으로 보여준다', () => {
  const p = paymentProgress(payment({ paymentStatus: 'EXPIRED' }))
  assert.deepEqual(
    p.steps.map((s) => s.state),
    ['done', 'done', 'skipped', 'done'],
  )
  assert.equal(p.steps[3]?.label, '승인 만료')
})

test('사건 결론은 결과별 건수와 금액을 묶고, 금액을 모르는 결제는 합계에서 뺀다', () => {
  const c = caseConclusion([
    payment({ paymentId: '1', amount: 58_000, decision: 'ACCEPT', paymentStatus: 'CAPTURED' }),
    payment({ paymentId: '2', amount: 58_000, decision: 'ACCEPT', paymentStatus: 'CAPTURED' }),
    payment({ paymentId: '3', amount: 420_000, decision: 'REJECT', paymentStatus: 'VOIDED' }),
    payment({ paymentId: '4', amount: null, decision: null, paymentStatus: 'HELD' }),
  ])
  assert.equal(c.paymentCount, 4)
  assert.equal(c.totalAmount, 536_000)
  assert.equal(c.unknownAmountCount, 1)
  assert.deepEqual(
    c.groups.map((g) => [g.result, g.count, g.amount]),
    [
      ['매입 완료', 2, 116_000],
      ['승인 취소', 1, 420_000],
      ['확인 중', 1, 0],
    ],
  )
})

test('판정 후 결제가 아직 보류 중이면 결론은 반영 중이다', () => {
  const c = caseConclusion([payment({ decision: 'ACCEPT', paymentStatus: 'HELD' })])
  assert.deepEqual(
    c.groups.map((g) => g.result),
    ['반영 중'],
  )
})

test('결론 문장은 결과별로 이어 쓰고, 결과가 하나면 모두로 묶는다', () => {
  const mixed = caseConclusion([
    payment({ amount: 58_000, decision: 'ACCEPT', paymentStatus: 'CAPTURED' }),
    payment({ amount: 58_000, decision: 'ACCEPT', paymentStatus: 'CAPTURED' }),
    payment({ amount: 420_000, decision: 'REJECT', paymentStatus: 'VOIDED' }),
  ])
  assert.equal(
    conclusionSentence(mixed),
    '2건 116,000원은 정상 매입됐고, 1건 420,000원은 매입 전에 승인이 취소됐어요',
  )
  const same = caseConclusion([payment({}), payment({})])
  assert.equal(conclusionSentence(same), '결제 2건 모두 아직 확인 중이에요')
  assert.equal(conclusionSentence(caseConclusion([])), '')
})

test('결제 한 건의 짧은 결과 이름', () => {
  assert.deepEqual(paymentOutcome(payment({})), { label: '확인 중', tone: 'progress' })
  assert.deepEqual(paymentOutcome(payment({ decision: 'ACCEPT' })), {
    label: '반영 중',
    tone: 'progress',
  })
  assert.equal(
    paymentOutcome(payment({ paymentStatus: 'VOIDED', customerCanceled: true })).label,
    '고객 취소',
  )
})

test('멈춘 돈은 보류 중인 결제만 확인 중 · 매입 진행 중 · 취소 진행 중으로 나눈다', () => {
  const buckets = heldMoney([
    {
      payments: [
        payment({ amount: 189_000 }),
        payment({ amount: 189_000 }),
        payment({ amount: 342_000, decision: 'ACCEPT' }),
        payment({ amount: 50_000, decision: 'REJECT' }),
        // 이미 끝난 결제 · 고객 취소는 넣지 않는다
        payment({ amount: 58_000, decision: 'ACCEPT', paymentStatus: 'CAPTURED' }),
        payment({ amount: 96_000, paymentStatus: 'HELD', customerCanceled: true }),
      ],
    },
  ])
  assert.deepEqual(
    buckets.map((b) => [b.key, b.count, b.amount]),
    [
      ['checking', 2, 378_000],
      ['accepting', 1, 342_000],
      ['rejecting', 1, 50_000],
    ],
  )
})

test('목록 결과 칸은 결과별 건수를 짧게 쓴다', () => {
  const c = caseConclusion([
    payment({ decision: 'ACCEPT', paymentStatus: 'CAPTURED' }),
    payment({ decision: 'ACCEPT', paymentStatus: 'CAPTURED' }),
    payment({ decision: 'REJECT', paymentStatus: 'VOIDED' }),
  ])
  assert.equal(resultSummary(c), '정상 2 · 취소 1')
  assert.equal(resultSummary(caseConclusion([payment({}), payment({})])), '확인 중 2')
})

// 판정과 실제 결제 상태가 어긋난 결제도 결제마다 판정이 보여야 한다 (dev 상세의 두 칸 정보 유지)
test('결제별 담당자 판정은 결과와 따로 보여준다', () => {
  assert.equal(
    paymentDecisionNote(payment({ decision: 'ACCEPT', paymentStatus: 'EXPIRED' })),
    '담당자: 정상 처리 결정',
  )
  assert.equal(
    paymentDecisionNote(payment({ decision: 'REJECT', paymentStatus: 'CAPTURED' })),
    '담당자: 취소 결정',
  )
  assert.equal(paymentDecisionNote(payment({ paymentStatus: 'EXPIRED' })), '담당자 판정 없이 끝남')
  // 아직 확인 중이거나 상태를 모르면 결과 칸이 이미 말해 주므로 붙이지 않는다
  assert.equal(paymentDecisionNote(payment({ paymentStatus: 'HELD' })), null)
  assert.equal(paymentDecisionNote(payment({ paymentStatus: null })), null)
  assert.equal(
    paymentDecisionNote(payment({ customerCanceled: true, paymentStatus: 'VOIDED' })),
    null,
  )
})
