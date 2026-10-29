import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  applyApplicationDetailSnapshot,
  requireApplicationDetailRefresh,
} from '../src/pages/admin/applicationDetailSnapshot.ts'
import type { ApplicationDetail } from '../src/api/adminApplications.ts'

function detail(status: ApplicationDetail['application']['status'], id = 31): ApplicationDetail {
  return {
    application: {
      applicationId: id,
      merchantName: '신청',
      businessRegistrationNumber: '1234567890',
      contactName: '담당',
      status,
      createdAt: '2026-10-29T00:00:00',
      emailVerifiedAt: '2026-10-29T00:01:00',
      latestMail: null,
    },
    requestedLoginId: 'owner',
    email: 'owner@example.com',
    phone: '01011112222',
    inquiry: null,
    consentVersion: 'v1',
    consentedAt: '2026-10-29T00:00:00',
    reviewedAt: null,
    reviewerUserId: null,
    reviewerName: null,
    rejectionReason: null,
    merchantId: null,
    userId: null,
    loginId: null,
    passwordChangeRequired: null,
    duplicateMerchants: [],
    latestMail: null,
    resendAllowed: false,
    resendBlockedReason: 'STATE_NOT_ELIGIBLE',
    nextResendAt: null,
  }
}

// 카드가 받은 최신 상세는 상단 상태·발급 정보·검토 폼과 같은 부모 스냅샷에 반영한다.
test('detailSnapshot_whenAnotherAdminDecides_updatesWholeSharedSnapshot', () => {
  const current = { key: '31:0', data: detail('REVIEW_PENDING') }
  for (const status of ['APPROVED', 'REJECTED'] as const) {
    const latest = {
      ...detail(status),
      reviewerUserId: 5,
      rejectionReason: status === 'REJECTED' ? '보완 후 재신청' : null,
      merchantId: status === 'APPROVED' ? 10 : null,
    }
    const next = applyApplicationDetailSnapshot(current, '31:0', latest)
    assert.equal(next?.data?.application.status, status)
    assert.equal(next?.data?.reviewerUserId, 5)
    assert.equal(next?.data?.rejectionReason, latest.rejectionReason)
    assert.equal(next?.data?.merchantId, latest.merchantId)
    assert.equal(next?.data, latest)
    assert.equal(current.data.application.status, 'REVIEW_PENDING')
  }
})

// 전체 재조회나 신청 이동 이후 늦게 끝난 카드 요청은 현재 상세를 덮지 않는다.
test('detailSnapshot_whenResponseIsStale_preservesCurrentRequestAndApplication', () => {
  const refreshed = { key: '31:1', data: detail('APPROVED') }
  assert.equal(
    applyApplicationDetailSnapshot(refreshed, '31:0', detail('REVIEW_PENDING')),
    refreshed,
  )
  const other = { key: '32:0', data: detail('REVIEW_PENDING', 32) }
  assert.equal(applyApplicationDetailSnapshot(other, '31:0', detail('APPROVED')), other)
  assert.equal(applyApplicationDetailSnapshot(other, '32:0', detail('APPROVED')), other)
  assert.equal(applyApplicationDetailSnapshot(null, '31:0', detail('APPROVED')), null)
})

// 결과 불명 후 현재 요청의 상세 조회가 성공하면 검토 잠금도 같은 상태 전이에서 해제한다.
test('detailSnapshot_whenRefreshSucceeds_clearsReviewBlockOnlyForCurrentRequest', () => {
  const original = { key: '31:0', data: detail('REVIEW_PENDING') }
  const blocked = requireApplicationDetailRefresh(original, '31:0')
  assert.equal(blocked?.needsRefresh, true)
  const fresh = applyApplicationDetailSnapshot(blocked, '31:0', detail('REVIEW_PENDING'))
  assert.equal(fresh?.needsRefresh ?? false, false)
  assert.equal(fresh?.data?.application.status, 'REVIEW_PENDING')
  const newer = { key: '31:1', data: detail('REVIEW_PENDING'), needsRefresh: true }
  assert.equal(applyApplicationDetailSnapshot(newer, '31:0', detail('APPROVED')), newer)
  assert.equal(newer.needsRefresh, true)
  assert.equal(requireApplicationDetailRefresh(newer, '31:0'), newer)
})
