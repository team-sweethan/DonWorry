import assert from 'node:assert/strict'
import { test } from 'node:test'
import { latestSelection } from '../src/pages/owner/latestSelection.ts'

type Row = { id: string; amount: number }
const byId = (row: Row) => row.id

// 같은 기간 새로고침 뒤에는 열린 상세가 새 결과의 같은 항목을 보여 준다 (#803 리뷰).
test('latestSelection_whenListRefreshed_returnsLatestItemWithSameId', () => {
  const opened: Row = { id: 'settlement:1', amount: 1000 }
  const refreshed: Row[] = [
    { id: 'settlement:2', amount: 500 },
    { id: 'settlement:1', amount: 900 },
  ]
  assert.deepEqual(latestSelection(refreshed, opened, byId), { id: 'settlement:1', amount: 900 })
})

// 새 결과에 그 항목이 없으면 상세를 닫는다.
test('latestSelection_whenItemDisappears_closesDetail', () => {
  assert.equal(
    latestSelection(
      [{ id: 'settlement:2', amount: 500 }],
      { id: 'settlement:1', amount: 1000 },
      byId,
    ),
    null,
  )
})

// 열린 상세가 없으면 아무것도 고르지 않는다.
test('latestSelection_whenNothingSelected_returnsNull', () => {
  assert.equal(latestSelection([{ id: 'settlement:1', amount: 1 }], null, byId), null)
})
