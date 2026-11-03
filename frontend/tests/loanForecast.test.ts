import assert from 'node:assert/strict'
import { test } from 'node:test'
import { loanRequirement, defaultPreviewLoan } from '../src/pages/owner/loanForecast.ts'

test('loanRequirement_usesLowestBalanceRatherThanFirstShortage', () => {
  assert.deepEqual(
    loanRequirement([
      { date: '2026-10-30', closing: -200000 },
      { date: '2026-10-28', closing: 100000 },
      { date: '2026-10-29', closing: -50000 },
      { date: '2026-10-31', closing: 10000 },
    ]),
    { firstShortage: '2026-10-29', minimumDate: '2026-10-30', minimum: -200000, required: 200000 },
  )
})
test('loanRequirement_whenNonnegative_requiresNoExtraFunds', () => {
  assert.equal(loanRequirement([{ date: '2026-10-28', closing: 0 }])?.required, 0)
  assert.equal(loanRequirement([{ date: '2026-10-28', closing: 0 }])?.firstShortage, null)
  assert.equal(loanRequirement([]), null)
})
test('defaultPreviewLoan_calculatesDeclaredDemoInputs', () => {
  const result = defaultPreviewLoan(new Date('2026-10-27T03:00:00Z'))
  assert.equal(result.required, 50000)
  assert.equal(result.firstShortage, '2026-10-29')
  assert.equal(result.totalIn, 630000)
  assert.equal(result.preview, true)
})
