import assert from 'node:assert/strict'
import { test } from 'node:test'
import { boothScrollDelta, advanceBoothScroll } from '../src/booth/boothScroll.ts'

// 자막 아래 남은 내용은 앞 화면과 겹치게 나누고, 마지막 내용까지 보이면 이동을 끝낸다.
test('boothScrollDelta_pagesThroughTallTarget_withoutSkippingTail', () => {
  let top = 0
  let remaining = 2000
  const moves: number[] = []
  for (let i = 0; i < 20; i++) {
    const delta = boothScrollDelta(top, top + 1800, 8, 600, remaining)
    if (!delta) break
    moves.push(delta)
    top -= delta
    remaining -= delta
  }
  assert.ok(moves.length >= 3)
  assert.ok(moves.every((delta) => delta <= 592 * 0.75))
  assert.ok(top + 1800 <= 604)
  assert.equal(boothScrollDelta(top, top + 1800, 8, 600, remaining), 0)
  assert.equal(boothScrollDelta(10, 1800, 8, 600, 0), 0)
})

// 내부 표의 가로 스크롤을 끝까지 보여 주고, 더 움직일 수 없으면 다음 박스로 진행한다.
test('advanceBoothScroll_finishesNestedHorizontalTable_beforeAdvancing', () => {
  const original = {
    window: Object.getOwnPropertyDescriptor(globalThis, 'window'),
    document: Object.getOwnPropertyDescriptor(globalThis, 'document'),
    getComputedStyle: Object.getOwnPropertyDescriptor(globalThis, 'getComputedStyle'),
  }
  const body = {}
  const root = {
    getBoundingClientRect: () => ({ top: 0, bottom: 800, left: 0, right: 1000 }),
    scrollHeight: 800,
    clientHeight: 800,
    scrollWidth: 1000,
    clientWidth: 1000,
    scrollTop: 0,
    scrollLeft: 0,
  }
  const target = {
    parentElement: body,
    getBoundingClientRect: () => ({ top: 100, bottom: 400, left: 50, right: 850 }),
    getClientRects: () => [1],
    querySelectorAll: () => [table],
    contains: (node: unknown) => node === table,
  }
  const moves: number[] = []
  const verticalMoves: number[] = []
  let vertical = false
  const table = {
    parentElement: target,
    clientTop: 0,
    clientLeft: 0,
    clientHeight: 300,
    clientWidth: 800,
    scrollHeight: 300,
    scrollWidth: 2200,
    scrollTop: 0,
    scrollLeft: 0,
    getBoundingClientRect: target.getBoundingClientRect,
    getClientRects: () => [1],
    scrollBy: ({ left, top }: { left: number; top: number }) => {
      moves.push(left)
      verticalMoves.push(top)
      table.scrollLeft += left
      table.scrollTop += top
    },
  }
  try {
    Object.defineProperty(globalThis, 'window', { configurable: true, value: { innerWidth: 1000 } })
    Object.defineProperty(globalThis, 'document', {
      configurable: true,
      value: { body, documentElement: root, scrollingElement: root },
    })
    Object.defineProperty(globalThis, 'getComputedStyle', {
      configurable: true,
      value: (node: unknown) => ({
        overflowX: node === table ? 'auto' : 'visible',
        overflowY: node === table && vertical ? 'auto' : 'visible',
      }),
    })
    for (let i = 0; i < 10 && advanceBoothScroll(target as unknown as HTMLElement, 650); i++) {
      /* 다음 화면 */
    }
    assert.equal(table.scrollLeft, 1400)
    assert.ok(moves.length >= 3)
    assert.equal(advanceBoothScroll(target as unknown as HTMLElement, 650), false)
    vertical = true
    table.scrollHeight = 1100
    for (let i = 0; i < 10 && advanceBoothScroll(target as unknown as HTMLElement, 650); i++) {
      /* 다음 화면 */
    }
    assert.equal(table.scrollTop, 800)
    assert.ok(verticalMoves.filter((delta) => delta > 0).length >= 4)
    assert.equal(advanceBoothScroll(target as unknown as HTMLElement, 650), false)
  } finally {
    for (const key of ['window', 'document', 'getComputedStyle'] as const) {
      const descriptor = original[key]
      if (descriptor) Object.defineProperty(globalThis, key, descriptor)
      else Reflect.deleteProperty(globalThis, key)
    }
  }
})
