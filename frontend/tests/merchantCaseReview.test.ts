import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { createServer } from 'vite'
import {
  currentMerchantCaseDetail,
  merchantCasePaymentSummary,
} from '../src/pages/owner/merchantCaseView.ts'
import type { CaseDetailState } from '../src/pages/owner/merchantCaseView.ts'
import type { MerchantCaseDetail, MerchantCaseRow } from '../src/types/merchantCase.ts'

const row: MerchantCaseRow = {
  caseId: '1',
  displayStatus: 'INVESTIGATING',
  createdAt: '2026-11-12T00:00:00Z',
  closedAt: null,
  paymentCount: 1,
}
const detail: MerchantCaseDetail = {
  ...row,
  summary: null,
  payments: [
    {
      paymentId: '10',
      amount: 77777,
      requestedAt: null,
      paymentStatus: null,
      decision: null,
      customerCanceled: false,
    },
  ],
}
const ready = (patch: Partial<CaseDetailState> = {}): CaseDetailState => ({
  detail,
  status: 'ready',
  stale: false,
  failed: false,
  ...patch,
})

// null이나 미완료 결제를 상세 조회 성공만으로 모두 처리된 것으로 표시하지 않는다.
test('merchantCasePaymentSummary_keepsUnknownAndPendingSeparateFromCompletedPayments', () => {
  assert.equal(merchantCasePaymentSummary([row], [ready()]).kind, 'unknown')
  assert.equal(merchantCasePaymentSummary([row], [ready()]).buckets, null)
  const pending = {
    ...detail,
    payments: [{ ...detail.payments[0], paymentStatus: 'PENDING' as const }],
  }
  assert.equal(merchantCasePaymentSummary([row], [ready({ detail: pending })]).kind, 'pending')
  const completed = {
    ...detail,
    payments: [{ ...detail.payments[0], paymentStatus: 'CAPTURED' as const }],
  }
  assert.equal(merchantCasePaymentSummary([row], [ready({ detail: completed })]).kind, 'complete')
  assert.equal(
    merchantCasePaymentSummary([row], [ready({ detail: completed, stale: true })]).kind,
    'loading',
  )
  assert.equal(
    merchantCasePaymentSummary([row], [ready({ detail: completed, failed: true })]).kind,
    'unavailable',
  )
  assert.equal(
    merchantCasePaymentSummary([row], [ready({ detail: { ...detail, payments: [] } })]).kind,
    'unavailable',
  )
  assert.equal(merchantCasePaymentSummary([], [], 'error').kind, 'unavailable')
  assert.equal(merchantCasePaymentSummary([], [], 'loading').kind, 'loading')
  assert.equal(merchantCasePaymentSummary([], []).kind, 'complete')
  assert.equal(
    merchantCasePaymentSummary([row], [ready({ detail: null, status: 'missing' })]).kind,
    'unavailable',
  )
  const held = { ...detail, payments: [{ ...detail.payments[0], paymentStatus: 'HELD' as const }] }
  const heldSummary = merchantCasePaymentSummary([row], [ready({ detail: held })])
  assert.equal(heldSummary.kind, 'held')
  assert.equal(heldSummary.heldCount, 1)
  assert.equal(heldSummary.heldTotal, 77777)
})

// 직전 상세와 실패 뒤 보존한 상세는 모두 최신 결과로 계산하지 않는다.
test('currentMerchantCaseDetail_marksRetainedDetailStaleAcrossRefreshAndFailure', () => {
  assert.equal(currentMerchantCaseDetail({ key: '1/0', detail, failed: false }, '2/0').stale, true)
  assert.equal(currentMerchantCaseDetail({ key: '2/0', detail, failed: true }, '2/0').stale, true)
  assert.equal(currentMerchantCaseDetail({ key: '2/0', detail, failed: false }, '2/0').stale, false)
})

// 새 목록에서 끝난 접힌 사건은 이전 상세의 진행 상태·금액·결과를 최신처럼 보이지 않는다.
test('merchantCaseRow_whenLatestListClosesCachedCase_usesListStatusAndHidesStaleAmounts', async () => {
  const server = await createServer({
    cacheDir: 'node_modules/.vite-merchant-case-review-test',
    server: { middlewareMode: true, ws: false, hmr: false, watch: null },
    appType: 'custom',
  })
  try {
    const { MerchantCaseRowItem } = await server.ssrLoadModule(
      '/src/pages/owner/MerchantCaseCard.tsx',
    )
    const { MemoryRouter } = await import('react-router-dom')
    const closedRow = { ...row, displayStatus: 'CLOSED', closedAt: '2026-11-13T00:00:00Z' }
    const render = (state: CaseDetailState) =>
      renderToStaticMarkup(
        createElement(
          MemoryRouter,
          null,
          createElement(MerchantCaseRowItem, {
            caseId: '1',
            row: closedRow,
            state,
            open: false,
            onToggle: () => {},
            onRetry: () => {},
            isNew: false,
            settlementLink: false,
          }),
        ),
      )
    const html = render(ready({ stale: true }))
    assert.match(html, /끝남/)
    assert.doesNotMatch(html, /확인 중/)
    assert.doesNotMatch(html, /77,777원/)
    assert.match(html, /class="mct-money">—/)
    const mismatched = render(ready())
    assert.match(mismatched, /끝남/)
    assert.doesNotMatch(mismatched, /77,777원/)
    assert.doesNotMatch(mismatched, /확인 불가/)
    const failed = render(ready({ failed: true }))
    assert.doesNotMatch(failed, /77,777원/)
    const fresh = render(
      ready({
        detail: {
          ...detail,
          displayStatus: 'CLOSED',
          payments: [{ ...detail.payments[0], paymentStatus: 'CAPTURED' }],
        },
      }),
    )
    assert.match(fresh, /77,777원/)
    assert.match(fresh, /정상 1/)
  } finally {
    await server.close()
  }
})
