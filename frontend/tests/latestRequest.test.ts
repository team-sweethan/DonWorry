import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createLatestRequest } from '../src/utils/latestRequest.ts'

// 번호 A 대조 중 입력을 B로 바꾸면, 늦게 온 A의 "일치"가 B의 결과처럼 보여 본인 확인을 잘못한다 (#621 리뷰)
test('latestRequest_whenInputChangedDuringRequest_dropsOldResponse', () => {
  const requests = createLatestRequest()
  const forA = requests.begin()

  requests.invalidate() // 입력을 B로 바꿈

  assert.equal(requests.isCurrent(forA), false)
})

// 결제 A 원문 요청 중 창을 닫고 결제 B 창을 열면, 늦게 온 A의 번호가 B 제목 아래 보여 다른 주문자에게 연락한다 (#621 리뷰)
test('latestRequest_whenClosedAndOtherTargetOpened_dropsOldResponse', () => {
  const requests = createLatestRequest()
  const forA = requests.begin()

  requests.invalidate() // A 창 닫기
  requests.invalidate() // B 창 열기
  const forB = requests.begin()

  assert.equal(requests.isCurrent(forA), false)
  assert.equal(requests.isCurrent(forB), true)
})
