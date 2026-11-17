import assert from 'node:assert/strict'
import { test } from 'node:test'
import { prepareBoothSpot, type BoothPreparation } from '../src/booth/boothPreparation.ts'

const button = { matches: () => false } as unknown as Element

test('preparation_waitsForAsyncContact_withoutRepeatingAuditedRead', () => {
  const clicked = new Set<BoothPreparation>()
  let loaded = false
  let calls = 0
  const find = (selector: string) =>
    selector === '[data-booth-action="contact-read"]' ||
    (loaded && selector === '[data-booth-contact-ready]')
      ? button
      : null
  assert.equal(
    prepareBoothSpot(['contact-read'], clicked, find, () => calls++),
    false,
  )
  assert.equal(
    prepareBoothSpot(['contact-read'], clicked, find, () => calls++),
    false,
  )
  assert.equal(calls, 1)
  loaded = true
  assert.equal(
    prepareBoothSpot(['contact-read'], clicked, find, () => calls++),
    true,
  )
})

test('preparation_opensNestedPayoutTabsInOrder_andKeepsSelectedTabs', () => {
  const clicked = new Set<BoothPreparation>()
  let list = false
  let payout = false
  const actions: BoothPreparation[] = ['settlement-list', 'payout-kind']
  const find = (selector: string) => {
    if (selector === '[data-booth-action="settlement-list"]') return button
    if (list && selector === '[data-booth-action="payout-kind"]') return button
    if (list && selector.includes('settlement-list') && selector.includes('true')) return button
    if (payout && selector.includes('payout-kind') && selector.includes('true')) return button
    return null
  }
  assert.equal(
    prepareBoothSpot(actions, clicked, find, () => {
      list = true
    }),
    false,
  )
  assert.equal(
    prepareBoothSpot(actions, clicked, find, () => {
      payout = true
    }),
    false,
  )
  assert.equal(
    prepareBoothSpot(actions, clicked, find, () => assert.fail('already selected')),
    true,
  )
})

test('preparation_waitsForMissingOrDisabledControl', () => {
  const clicked = new Set<BoothPreparation>()
  assert.equal(
    prepareBoothSpot(
      ['completed-case'],
      clicked,
      () => null,
      () => assert.fail(),
    ),
    false,
  )
  const disabled = { matches: () => true } as unknown as Element
  assert.equal(
    prepareBoothSpot(
      ['completed-case'],
      clicked,
      (selector) => (selector.includes('true') ? null : disabled),
      () => assert.fail(),
    ),
    false,
  )
  assert.equal(clicked.size, 0)
})
