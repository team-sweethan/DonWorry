import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { createServer } from 'vite'

test('settlement calendar selects and highlights Korean today across the UTC date boundary', async (t) => {
  const previousZone = process.env.TZ
  process.env.TZ = 'UTC'
  const server = await createServer({ server: { middlewareMode: true }, appType: 'custom' })
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
      /aria-label="11월 2일, 예정 200원"[^>]*aria-pressed="true" class="stl-day stl-day-in  stl-today"/,
    )
    assert.doesNotMatch(html, /aria-label="11월 1일[^"]*"[^>]*stl-today/)
    // 막대 그래프: 지난 날짜의 예정액은 일반 예정이 아니라 "예정일 지남"으로 따로 보인다
    assert.match(html, /aria-label="11월 1일 정산 완료 0원, 정산 예정 100원 \(예정일 지남\)"/)
    assert.match(html, /aria-label="11월 2일 정산 완료 0원, 정산 예정 200원"/)
    assert.match(html, /aria-label="11월 3일 정산 완료 0원, 정산 예정 300원"/)
    // 그래프의 고른 날짜는 한국 날짜 기준 오늘(11월 2일)이다
    assert.match(
      html,
      /data-date="2026-11-02" class="sbc-col is-on is-today"[^>]*aria-pressed="true"/,
    )
    assert.match(html, /class="sbc-overdue-notice"/)
    assert.match(html.replace(/<[^>]+>/g, ''), /예정일이 지난 미완료 정산100원/)
    assert.match(html, /미완료 정산 확인/)
    assert.match(html, /<details class="stl-day-expenses"><summary>/)
    assert.doesNotMatch(html, /<details class="stl-day-expenses"[^>]*\sopen/)
    assert.match(html, /aria-label="그래프 날짜 선택"[^>]*value="2026\.11\.02"/)
    assert.match(html, /class="sbc-focus-weekday">\(월\)<\/span>/)
    assert.match(
      html,
      /class="stl-day-scheduled"><span>이날 정산 예정금<\/span><b><span[^>]*aria-label="200원"/,
    )
    // 첫 화면에는 흐름만 펼치고, 달력은 날짜 선택을 보존한 채 숨긴다.
    assert.match(html, /id="stl-tab-flow"[^>]*aria-selected="true"/)
    assert.match(html, /id="stl-view-cal"[^>]*hidden=""/)
    assert.doesNotMatch(html, /id="stl-view-flow"[^>]*hidden/)
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
          scheduledAmount: 16084800,
          todayValue: '2026-11-12',
          nextScheduled: { date, amount: 16084800, count: 299 },
        }),
      )
    const today = render('2026-11-12')
    assert.match(today, /<h3>오늘 <span>정산 예정<\/span><\/h3>/)
    assert.match(today, /조회 기간에 지급된 금액/)
    assert.match(today, /aria-label="72,381,234원"/)
    assert.match(render('2026-11-13'), /<h3>11월 13일 <span>정산 예정<\/span><\/h3>/)
  } finally {
    await server.close()
  }
})

// 주문별 원 정산금과 0원 지급을 달력·그래프·명세에서 분리한다.
test('payout calendar and ledger render zero-cash recovery without showing original amount as income', async () => {
  const server = await createServer({ server: { middlewareMode: true }, appType: 'custom' })
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
    assert.match(html, /환불 회수 차감/)
    assert.match(html, /<details class="stl-calculation-details"><summary>/)
    assert.doesNotMatch(html, /<details class="stl-calculation-details"[^>]*\sopen/)
    // 하루 주문 정산금 97,000원을 실제 지급 0원과 혼동하지 않도록 상세의 대표 금액도 지급액이다.
    assert.match(html, /이날 실제 지급액<\/span><b><span[^>]*aria-label="0원"/)
    const ledger = renderToStaticMarkup(createElement(PayoutLedger, { payouts: [payout] }))
    assert.match(ledger, /지급 #99/)
    assert.match(ledger, /환불 회수로 전액 상계/)
    assert.match(ledger, /지급 상세/)
  } finally {
    await server.close()
  }
})

// 다른 기간을 조회하는 동안 이전 결과는 키보드로도 조작할 수 없고 기간 선택은 계속 사용할 수 있다.
test('settlement_whenPeriodChanges_makesPreviousResultsInertWithoutBlockingPeriodControls', async () => {
  const server = await createServer({
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
    assert.match(upcoming, /aria-label="907,000원"/)
    // 5주인 달에 비어 있는 여섯째 주를 붙이지 않되, 6주인 달은 마지막 날짜까지 보인다.
    assert.equal([...html.matchAll(/class="stl-day stl-day-in /g)].length, 35)
    const sixWeeks = render('2026-05-01', '2026-05-31')
    assert.equal([...sixWeeks.matchAll(/class="stl-day stl-day-in /g)].length, 42)
    assert.match(sixWeeks, /aria-label="5월 31일"/)
    assert.match(render('2026-11-01', '2026-11-02'), /다가오는 정산이 없어요/)
    assert.match(
      render('2026-11-01', '2026-11-03', true),
      /class="stl-overview ow-rise"[^>]*inert=""/,
    )
  } finally {
    await server.close()
  }
})
