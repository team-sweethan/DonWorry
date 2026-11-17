import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  boothViewportBottom,
  boothPointerPosition,
  clipBoothBox,
  observeBoothBar,
  revealBoothDetails,
} from '../src/booth/boothSpotlight.ts'

// 실제로 사용하는 DOM 속성만 가진 노드로 중첩된 details의 펼침 동작을 확인한다.
function element(tagName: string, parentElement: HTMLElement | null = null) {
  return { tagName, parentElement, open: false } as unknown as HTMLDetailsElement
}

// 키 관리 기록처럼 강조 대상 자체가 접힌 details여도 내용을 펼친다.
test('revealBoothDetails_whenTargetIsDetails_opensTargetAndAncestors', () => {
  const outer = element('DETAILS')
  const target = element('DETAILS', element('DIV', outer))
  revealBoothDetails(target)
  assert.equal(target.open, true)
  assert.equal(outer.open, true)
})

// 정산 계산식처럼 접힌 details 안의 요소를 짚을 때도 부모를 펼친다.
test('revealBoothDetails_whenTargetInsideDetails_opensContainingDetails', () => {
  const parent = element('DETAILS')
  const target = element('DIV', parent)
  revealBoothDetails(target)
  assert.equal(parent.open, true)
  assert.equal(target.open, false)
})

// 긴 자막이 260px을 차지하면 예전 124px 기준 아래에 있던 네모를 실제 자막 위로 자른다.
test('clipBoothBox_whenBarGrows_keepsBoxAboveActualBar', () => {
  const usable = boothViewportBottom(900, { top: 640 })
  assert.equal(usable, 640)
  const box = clipBoothBox({ top: 600, bottom: 820, left: 80, right: 480 }, 1200, usable)
  assert.ok(box)
  assert.equal(box.top + box.height, 636)
  assert.equal(clipBoothBox({ top: 700, bottom: 800, left: 80, right: 480 }, 1200, usable), null)
  assert.equal(boothViewportBottom(900, null), 900)
})

// 자막의 첫 표시·줄바꿈·축소에 맞춰 본문 여백을 갱신하고 숨기면 관찰과 여백을 해제한다.
test('observeBoothBar_whenResized_updatesPaddingAndCleansUpOnHide', () => {
  let height = 180.2
  let resize = () => {}
  let observed: Element | null = null
  let disconnected = false
  const properties = new Map<string, string>()
  const classes = new Set<string>()
  const bar = { getBoundingClientRect: () => ({ height }) } as HTMLElement
  const body = {
    style: {
      setProperty: (name: string, value: string) => properties.set(name, value),
      removeProperty: (name: string) => properties.delete(name),
    },
    classList: {
      add: (name: string) => classes.add(name),
      remove: (name: string) => classes.delete(name),
    },
  } as unknown as HTMLElement
  const cleanup = observeBoothBar(bar, body, (onResize) => {
    resize = onResize
    return {
      observe: (node) => {
        observed = node
      },
      disconnect: () => {
        disconnected = true
      },
    }
  })
  assert.equal(observed, bar)
  assert.equal(properties.get('--dw-booth-height'), '181px')
  assert.ok(classes.has('dw-booth-open'))
  height = 268
  resize()
  assert.equal(properties.get('--dw-booth-height'), '268px')
  height = 124
  resize()
  assert.equal(properties.get('--dw-booth-height'), '124px')
  cleanup()
  assert.equal(disconnected, true)
  assert.equal(properties.has('--dw-booth-height'), false)
  assert.equal(classes.has('dw-booth-open'), false)
})

// 소개 화면은 여백을 넓히되 자막·뷰포트 밖으로 테두리가 넘어가지 않는다.
test('clipBoothBox_withLargerPublicPadding_expandsAndClipsToViewport', () => {
  const rect = { top: 100, bottom: 300, left: 100, right: 500 }
  const regular = clipBoothBox(rect, 1200, 640)
  const enlarged = clipBoothBox(rect, 1200, 640, 24)
  assert.deepEqual(regular, { top: 94, left: 94, width: 412, height: 212 })
  assert.deepEqual(enlarged, { top: 76, left: 76, width: 448, height: 248 })
  assert.deepEqual(clipBoothBox({ top: 10, bottom: 630, left: 10, right: 1190 }, 1200, 640, 24), {
    top: 4,
    left: 4,
    width: 1192,
    height: 632,
  })
})

// 큰 안내 동전이도 화면·자막 경계를 넘지 않고, 여유가 있으면 박스 위에 놓인다.
test('boothPointerPosition_staysInsideNormalAndClippedTargets', () => {
  for (const box of [
    { top: 100, left: 100, width: 400, height: 200 },
    { top: 612, left: 4, width: 28, height: 24 },
    { top: 4, left: 1168, width: 28, height: 100 },
  ]) {
    const point = boothPointerPosition(box)
    assert.ok(point.scale > 0 && point.scale <= 1)
    assert.ok(point.left >= box.left && point.top >= 4)
    assert.ok(point.left + 96 * point.scale <= box.left + box.width + 0.001)
    assert.ok(point.top + 84 * point.scale <= box.top + box.height + 0.001)
  }
  assert.ok(boothPointerPosition({ top: 100, left: 100, width: 400, height: 200 }).top < 100)
})
