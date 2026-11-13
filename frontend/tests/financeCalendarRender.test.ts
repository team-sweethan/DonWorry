import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { createServer } from 'vite'

test('settlement calendar selects and highlights Korean today across the UTC date boundary', async (t) => {
  const previousZone = process.env.TZ
  process.env.TZ = 'UTC'
  const server = await createServer({
    cacheDir: 'node_modules/.vite-finance-calendar-test',
    server: { middlewareMode: true },
    appType: 'custom',
  })
  try {
    t.mock.timers.enable({ apis: ['Date'], now: new Date('2026-11-01T15:00:00Z') })
    const { SettlementPrototype } = await server.ssrLoadModule(
      '/src/pages/owner/SettlementPrototype.tsx',
    )
    const html = renderToStaticMarkup(
      createElement(
        MemoryRouter,
        null,
        createElement(SettlementPrototype, {
          todayValue: '2026-11-02',
          actualEntries: [1, 2, 3].map((day) => ({
            id: `settlement:${day}`,
            orderId: `order-${day}`,
            item: 'order',
            date: new Date(`2026-11-0${day}T12:00:00`),
            gross: day * 100,
            cardFeeAmount: 0,
            pgFeeAmount: 0,
            settlementAmount: day * 100,
            status: 'sch',
            orderItems: [],
          })),
          period: { from: '2026-11-01', to: '2026-11-03' },
        }),
      ),
    )
    assert.match(
      html,
      /aria-label="11월 2일, 예정 200원"[^>]*aria-pressed="true"[^>]*class="stl-day stl-day-in  stl-today"/,
    )
    assert.doesNotMatch(html, /aria-label="11월 1일[^"]*"[^>]*stl-today/)
    // 지난 날짜의 미완료 건은 지급 대기로 구분하고 예정일이 지났다는 사실을 함께 알린다.
    assert.match(
      html,
      /aria-label="11월 1일 정산 완료 0원, 정산 예정 100원 \(예정일 이후 지급 대기\)"/,
    )
    assert.match(html, /aria-label="11월 2일 정산 완료 0원, 정산 예정 200원"/)
    assert.match(html, /aria-label="11월 3일 정산 완료 0원, 정산 예정 300원"/)
    // 그래프의 고른 날짜는 한국 날짜 기준 오늘(11월 2일)이다
    assert.match(
      html,
      /data-date="2026-11-02" class="sbc-col is-on is-today"[^>]*aria-pressed="true"/,
    )
    assert.match(html, /class="sbc-plot" style="--sbc-count:3;--sbc-active:1"/)
    assert.match(html, /class="sbc-selection" aria-hidden="true"/)
    assert.match(html, /class="sbc-overdue-notice"/)
    assert.match(html.replace(/<[^>]+>/g, ''), /지급 대기100원/)
    assert.match(html, /정산 예정일 이후 아직 지급되지 않은 금액입니다./)
    assert.match(html, /해당 주문 확인/)
    assert.match(html, /<details class="sbc-policy"><summary>/)
    assert.doesNotMatch(html, /<details class="sbc-policy"[^>]*\sopen/)
    assert.match(html, /<details class="stl-day-expenses"><summary>/)
    assert.doesNotMatch(html, /<details class="stl-day-expenses"[^>]*\sopen/)
    assert.match(html, /aria-label="그래프 날짜 선택"[^>]*value="2026\.11\.02"/)
    assert.match(html, /class="sbc-focus-weekday">\(월\)<\/span>/)
    assert.match(
      html,
      /class="stl-day-scheduled"><span>이날 정산 예정금<\/span><b><span[^>]*aria-label="200원"/,
    )
    // 첫 화면은 캘린더다. 흐름 그래프는 날짜 선택을 보존한 채 숨긴다.
    assert.match(html, /id="stl-tab-cal"[^>]*aria-selected="true"/)
    assert.match(html, /id="stl-view-flow"[^>]*hidden=""/)
    assert.doesNotMatch(html, /id="stl-view-cal"[^>]*hidden/)
    assert.doesNotMatch(html, /id="stl-view-list"/)
  } finally {
    t.mock.timers.reset()
    await server.close()
    if (previousZone === undefined) delete process.env.TZ
    else process.env.TZ = previousZone
  }
})

// 오늘의 예정과 미래의 예정을 다른 제목으로 안내하며, 미완료 내역을 지급 완료로 표현하지 않는다.
test('payoutSummary_whenScheduledToday_namesTodayExplicitly', async () => {
  const server = await createServer({
    cacheDir: 'node_modules/.vite-finance-calendar-test',
    server: { middlewareMode: true, ws: false, hmr: false, watch: null },
    appType: 'custom',
  })
  try {
    const { PayoutSummary } = await server.ssrLoadModule('/src/pages/owner/PayoutLedger.tsx')
    const render = (date: string) =>
      renderToStaticMarkup(
        createElement(PayoutSummary, {
          data: {
            payouts: [],
            paidAmount: 72381234,
            paidSettlementAmount: 72381234,
            refundDeductionAmount: 0,
            outstandingRecoveryAmount: 0,
          },
          todayValue: '2026-11-12',
          nextScheduled: { date, amount: 16084800, count: 299 },
        }),
      )
    const today = render('2026-11-12')
    assert.match(today, /<h3>오늘 <span>정산 예정<\/span><\/h3>/)
    assert.match(today, /조회 기간에 지급된 금액/)
    assert.match(today, /aria-label="72,381,234원"/)
    assert.match(today, /finance-money__text">72,381,234원<\/span>/)
    assert.doesNotMatch(today, /약 7,238만/)
    assert.match(today, /이번 기간에는 정산 후 환불로 차감한 금액이 없습니다./)
    assert.match(today, /<details class="stl-refund-help"><summary>/)
    assert.doesNotMatch(today, /<details class="stl-refund-help"[^>]*\sopen/)
    assert.match(render('2026-11-13'), /<h3>11월 13일 <span>정산 예정<\/span><\/h3>/)
  } finally {
    await server.close()
  }
})

// 주문별 원 정산금과 0원 지급을 달력·그래프·명세에서 분리한다.
test('payout calendar and ledger render zero-cash recovery without showing original amount as income', async () => {
  const server = await createServer({
    cacheDir: 'node_modules/.vite-finance-calendar-test',
    server: { middlewareMode: true },
    appType: 'custom',
  })
  try {
    const { SettlementPrototype } = await server.ssrLoadModule(
      '/src/pages/owner/SettlementPrototype.tsx',
    )
    const { PayoutLedger } = await server.ssrLoadModule('/src/pages/owner/PayoutLedger.tsx')
    const payout = {
      payoutId: 99,
      payoutDate: '2026-11-02',
      completedAt: '2026-11-01T18:00:00Z',
      settlementAmount: 97000,
      refundDeductionAmount: 97000,
      payoutAmount: 0,
      origin: 'BATCH',
    }
    const html = renderToStaticMarkup(
      createElement(
        MemoryRouter,
        null,
        createElement(SettlementPrototype, {
          todayValue: '2026-11-02',
          period: { from: '2026-11-01', to: '2026-11-03' },
          periodControls: createElement('div', { id: 'period-control' }, '기간 조회 영역'),
          payoutReport: {
            payouts: [payout],
            paidSettlementAmount: 97000,
            refundDeductionAmount: 97000,
            paidAmount: 0,
            outstandingRecoveryAmount: 150000,
          },
          actualEntries: [
            {
              id: 'settlement:99',
              orderId: 'ORDER-99',
              item: '주문',
              date: new Date('2026-11-02T12:00:00'),
              gross: 100000,
              cardFeeAmount: 2000,
              pgFeeAmount: 1000,
              settlementAmount: 97000,
              status: 'done',
              orderItems: [],
            },
          ],
        }),
      ),
    )
    assert.match(html, /aria-label="11월 2일 실제 지급 0원,/)
    assert.doesNotMatch(html, /aria-label="11월 2일 실제 지급 97,000원/)
    assert.match(html, /현재 미회수 환불액/)
    assert.match(html, /정산 내역/)
    assert.ok(html.indexOf('정산 내역') < html.indexOf('기간 조회 영역'))
    assert.match(html, /정산 후 환불 차감/)
    assert.match(html, /이전에 정산된 주문이 고객에게 환불되어/)
    assert.match(html, /<details class="stl-calculation-details"><summary>/)
    assert.doesNotMatch(html, /<details class="stl-calculation-details"[^>]*\sopen/)
    // 하루 주문 정산금 97,000원을 실제 지급 0원과 혼동하지 않도록 상세의 대표 금액도 지급액이다.
    assert.match(html, /이날 실제 지급액<\/span><b><span[^>]*aria-label="0원"/)
    const ledger = renderToStaticMarkup(createElement(PayoutLedger, { payouts: [payout] }))
    assert.match(ledger, /지급번호 #99/)
    assert.match(ledger, /환불 회수로 전액 상계/)
    assert.match(ledger, /지급 상세/)
    const largeLedger = renderToStaticMarkup(
      createElement(PayoutLedger, {
        payouts: [
          {
            ...payout,
            settlementAmount: 123456789,
            refundDeductionAmount: 0,
            payoutAmount: 123456789,
          },
        ],
      }),
    )
    assert.match(largeLedger, /finance-money__text">123,456,789원<\/span>/)
    assert.doesNotMatch(largeLedger, /약 1억/)
    assert.doesNotMatch(largeLedger, /-0원/)
    assert.match(largeLedger, /payout-deduction is-zero/)
  } finally {
    await server.close()
  }
})

// 같은 날의 지급 완료액과 예정액을 합쳐 실제 지급액처럼 보여주지 않는다.
test('settlementCalendar_whenPaidAndScheduledShareDate_showsSeparateAmounts', async () => {
  const server = await createServer({
    cacheDir: 'node_modules/.vite-finance-calendar-test',
    server: { middlewareMode: true, ws: false, hmr: false, watch: null },
    appType: 'custom',
  })
  try {
    const { SettlementPrototype } = await server.ssrLoadModule(
      '/src/pages/owner/SettlementPrototype.tsx',
    )
    const html = renderToStaticMarkup(
      createElement(
        MemoryRouter,
        null,
        createElement(SettlementPrototype, {
          todayValue: '2026-11-02',
          period: { from: '2026-11-01', to: '2026-11-03' },
          actualEntries: [
            {
              id: 'paid-order',
              item: '주문',
              gross: 6938000,
              cardFeeAmount: 0,
              pgFeeAmount: 0,
              settlementAmount: 6938000,
              date: new Date('2026-11-02T12:00:00'),
              status: 'done',
            },
            {
              id: 'scheduled-order',
              item: '주문',
              gross: 200000,
              cardFeeAmount: 0,
              pgFeeAmount: 0,
              settlementAmount: 200000,
              date: new Date('2026-11-02T12:00:00'),
              status: 'sch',
            },
          ],
          payoutReport: {
            paidAmount: 6938000,
            paidSettlementAmount: 6938000,
            refundDeductionAmount: 0,
            outstandingRecoveryAmount: 0,
            payouts: [
              {
                payoutId: 1,
                payoutDate: '2026-11-02',
                completedAt: '2026-11-02T03:00:00Z',
                settlementAmount: 6938000,
                refundDeductionAmount: 0,
                payoutAmount: 6938000,
                origin: 'BATCH',
              },
            ],
          },
        }),
      ),
    )
    const cell = html.match(/<button[^>]*aria-label="11월 2일,[^"]*"[\s\S]*?<\/button>/)?.[0]
    assert.ok(cell)
    // 지급과 정산 예정은 칩을 따로 두고 더하지 않는다
    assert.match(
      cell,
      /class="stl-chip stl-done"[^>]*><span>지급<\/span> <span[^>]*aria-label="6,938,000원"/,
    )
    assert.match(
      cell,
      /class="stl-chip stl-sch"[^>]*><span>정산 예정<\/span> <span[^>]*aria-label="200,000원"/,
    )
    assert.doesNotMatch(cell, /7,138,000원/)
    assert.match(cell, /aria-controls="stl-selected-day-detail"/)
    assert.match(cell, /지급 6,938,000원/)
    const detail = html.slice(
      html.indexOf('class="stl-day-money"'),
      html.indexOf('class="stl-preview-heading"'),
    )
    assert.match(detail, /aria-label="6,938,000원"/)
    assert.match(detail, /aria-label="200,000원"/)
    assert.doesNotMatch(detail, /약 /)
  } finally {
    await server.close()
  }
})

// 다른 기간을 조회하는 동안 이전 결과는 키보드로도 조작할 수 없고 기간 선택은 계속 사용할 수 있다.
test('settlement_whenPeriodChanges_makesPreviousResultsInertWithoutBlockingPeriodControls', async () => {
  const server = await createServer({
    cacheDir: 'node_modules/.vite-finance-calendar-test',
    server: { middlewareMode: true, ws: false, hmr: false, watch: null },
    appType: 'custom',
  })
  try {
    const { SettlementPrototype } = await server.ssrLoadModule(
      '/src/pages/owner/SettlementPrototype.tsx',
    )
    const render = (resultsPending: boolean) =>
      renderToStaticMarkup(
        createElement(
          MemoryRouter,
          null,
          createElement(SettlementPrototype, {
            todayValue: '2026-11-02',
            period: { from: '2026-11-01', to: '2026-11-03' },
            actualEntries: [],
            resultsPending,
            periodControls: createElement('button', { id: 'change-period' }, '기간 변경'),
          }),
        ),
      )
    const pending = render(true)
    assert.match(pending, /<section[^>]*class="finance-card[^"]*"[^>]*inert=""/)
    assert.match(pending, /<div[^>]*class="stl-calendar-layout"[^>]*inert=""/)
    assert.match(pending, /<button id="change-period">기간 변경<\/button>/)
    assert.doesNotMatch(pending, /<div[^>]*class="stl-prototype"[^>]*inert/)
    assert.doesNotMatch(render(false), /\sinert=/)
  } finally {
    await server.close()
  }
})

// 이미 지난 예정일은 다음 정산에 넣지 않고, 가장 가까운 예정일의 주문만 합산한다.
test('settlementOverview_whenUpcomingAndOverdueExist_showsNearestScheduledDayWithinPeriod', async () => {
  const server = await createServer({
    cacheDir: 'node_modules/.vite-finance-calendar-test',
    server: { middlewareMode: true, ws: false, hmr: false, watch: null },
    appType: 'custom',
  })
  try {
    const { SettlementPrototype } = await server.ssrLoadModule(
      '/src/pages/owner/SettlementPrototype.tsx',
    )
    const entries = [
      { day: '01', amount: 900000, status: 'sch' },
      { day: '03', amount: 3000, status: 'sch' },
      { day: '03', amount: 4000, status: 'sch' },
      { day: '02', amount: 800000, status: 'can' },
      { day: '04', amount: 2000, status: 'sch' },
    ].map(({ day, amount, status }, index) => ({
      id: `settlement:${index}`,
      item: '주문',
      date: new Date(`2026-11-${day}T12:00:00`),
      gross: amount,
      cardFeeAmount: 0,
      pgFeeAmount: 0,
      settlementAmount: amount,
      status,
      orderItems: [],
    }))
    const render = (from: string, to: string, resultsPending = false) =>
      renderToStaticMarkup(
        createElement(
          MemoryRouter,
          null,
          createElement(SettlementPrototype, {
            todayValue: '2026-11-02',
            period: { from, to },
            actualEntries: entries,
            resultsPending,
            payoutReport: {
              payouts: [],
              paidSettlementAmount: 0,
              refundDeductionAmount: 0,
              paidAmount: 0,
              outstandingRecoveryAmount: 0,
            },
          }),
        ),
      )
    const html = render('2026-11-01', '2026-11-03')
    const upcoming = html.slice(
      html.indexOf('class="stl-upcoming"'),
      html.indexOf('class="stl-payment-equation"'),
    )
    assert.match(upcoming, /11월 3일/)
    assert.match(upcoming, /aria-label="7,000원"/)
    assert.match(upcoming, /주문 2건/)
    assert.doesNotMatch(upcoming, /aria-label="907,000원"/)
    assert.doesNotMatch(html, /선택한 기간의 정산 예정액 합계/)
    // 5주인 달에 비어 있는 여섯째 주를 붙이지 않되, 6주인 달은 마지막 날짜까지 보인다.
    assert.equal([...html.matchAll(/class="stl-day stl-day-in /g)].length, 35)
    const sixWeeks = render('2026-05-01', '2026-05-31')
    assert.equal([...sixWeeks.matchAll(/class="stl-day stl-day-in /g)].length, 42)
    assert.match(sixWeeks, /aria-label="5월 31일"/)
    // 하루만 조회했을 때 날짜 이동 버튼이 조회 범위 밖을 선택하지 않게 한다.
    const oneDay = render('2026-11-03', '2026-11-03')
    for (const label of ['이전 날짜', '다음 날짜']) {
      const button = oneDay.match(new RegExp(`<button[^>]*aria-label="${label}"[^>]*>`))?.[0]
      assert.ok(button)
      assert.match(button, /disabled=""/)
    }
    assert.match(render('2026-11-01', '2026-11-02'), /다가오는 정산이 없어요/)
    assert.match(
      render('2026-11-01', '2026-11-03', true),
      /class="stl-overview ow-rise"[^>]*inert=""/,
    )
  } finally {
    await server.close()
  }
})
