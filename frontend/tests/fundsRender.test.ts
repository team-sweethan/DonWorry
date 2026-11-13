import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { createServer } from 'vite'

// 완료·예정 구분을 유지하면서 현재 잔액과 첫 부족일·최대 부족액을 각각 표시한다.
test('fundsOverview_preservesBalancesAndShortageEvidence', async () => {
  const server = await createServer({
    cacheDir: 'node_modules/.vite-funds-render-test',
    server: { middlewareMode: true, ws: false, hmr: false, watch: null },
    appType: 'custom',
  })
  try {
    const { CashFlowOverview } = await server.ssrLoadModule('/src/pages/owner/CashFlowOverview.tsx')
    const days = [
      {
        date: '2026-11-13',
        opening: 100000,
        completed: 0,
        scheduled: 20000,
        expenses: 30000,
        closing: 90000,
      },
      {
        date: '2026-11-14',
        opening: 90000,
        completed: 0,
        scheduled: 10000,
        expenses: 120000,
        closing: -20000,
      },
      {
        date: '2026-11-15',
        opening: -20000,
        completed: 0,
        scheduled: 0,
        expenses: 10000,
        closing: -30000,
      },
    ]
    const render = (props: Record<string, unknown> = {}) =>
      renderToStaticMarkup(
        createElement(
          MemoryRouter,
          null,
          createElement(CashFlowOverview, {
            days,
            currentBalance: 100000,
            graphDate: '2026-11-14',
            incomes: [
              {
                id: 'income-14',
                date: '2026-11-14',
                title: '정산 지급 예상',
                amount: 10000,
                note: '환불 차감 반영',
              },
            ],
            costs: [
              {
                id: 'cost-14',
                date: '2026-11-14',
                title: '재료비',
                amount: 120000,
                note: '예정 지출',
              },
            ],
            onManageExpenses: () => {},
            onDetails: () => {},
            ...props,
          }),
        ),
      )
    const html = render()
    // 카운트업의 첫 프레임은 0원이다. 보조기기에 제공하는 정확한 금액을 기준으로 검증한다.
    const readable = (markup: string) =>
      markup
        .replace(
          /<span[^>]*class="finance-money"[^>]*aria-label="([^"]+)"[^>]*><span[^>]*>[^<]*<\/span><\/span>/g,
          '$1',
        )
        .replace(/<[^>]+>/g, '')
    const text = readable(html)
    assert.match(text, /오늘의 계산 잔액100,000원/)
    assert.match(text, /11월 14일, 자금이 부족할 수 있어요/)
    assert.match(text, /기간 내 최대 부족액\s*30,000원/)
    assert.match(text, /기간 내 정산 유입30,000원/)
    assert.match(text, /기간 내 지출160,000원/)
    assert.match(text, /기간 마지막 날의 예상 잔액-30,000원/)
    assert.match(text, /들어오는 정산금10,000원나가는 지출120,000원/)
    assert.match(html, /id="funds-day-evidence"/)
    assert.match(text, /재료비/)
    assert.ok(html.indexOf('우리 가게 자금 예보') < html.indexOf('자금 현황 빠른 요약'))
    assert.ok(html.indexOf('우리 가게 자금 예보') < html.indexOf('funds-period-summary'))
    assert.doesNotMatch(html, /class="funds-hero-grid"/)

    assert.match(text, /이날 마감 잔액이 설정한 기준보다 20,000원 낮아질/)
    assert.match(text, /지출 계획 확인/)
    assert.match(text, /운영자금 상품 보기/)
    assert.match(html, /class="[^"]*funds-loan-action/)

    assert.match(html, /<details[^>]*class="cashflow-surface funds-day-details"/)
    assert.doesNotMatch(html, /<details[^>]*class="cashflow-surface funds-day-details"[^>]*\sopen/)
    assert.match(html, /<details class="funds-period-summary">/)
    assert.doesNotMatch(html, /class="cashflow-loan-card"/)
    const refunded = render({ recovery: { outstanding: 2000, projected: 1500, remaining: 500 } })
    assert.match(refunded, /<details class="recovery-notice funds-refund-details"/)
    assert.doesNotMatch(
      refunded,
      /<details class="recovery-notice funds-refund-details"[^>]*\sopen/,
    )
    assert.match(readable(refunded), /앞으로 차감할 금액 2,000원/)
    assert.match(readable(refunded), /조회 기간 말까지 차감 예상1,500원/)
    assert.match(readable(refunded), /기간 이후에 남을 차감액500원/)
    // 미래에서 시작하는 기간에도 오늘 잔액을 기간 시작 잔액으로 바꾸지 않는다.
    const future = render({ days: days.slice(1), currentBalance: 77777 })
    assert.match(readable(future), /오늘의 계산 잔액77,777원/)
    const safe = render({ days: [days[0]], graphDate: days[0].date, incomes: [], costs: [] })
    assert.match(safe, /cashflow-verdict-safe/)
    assert.match(safe, /class="funds-decision-icon"/)
    assert.ok(safe.indexOf('등록한 지출을 감당할 수 있어요') < safe.indexOf('오늘의 계산 잔액'))
    assert.doesNotMatch(safe, /부족한 이유 확인/)
    assert.match(readable(safe), /선택 기간에 등록된 지출 합계30,000원/)
  } finally {
    await server.close()
  }
})

// 선택 날짜·0원·음수 잔액을 보존하고 짧은 기간의 그래프는 한 화면을 채운다.
test('fundsChart_preservesSelectionAndFillsShortPeriod', async () => {
  const server = await createServer({
    cacheDir: 'node_modules/.vite-funds-render-test',
    server: { middlewareMode: true, ws: false, hmr: false, watch: null },
    appType: 'custom',
  })
  try {
    const { CashBalanceChart } = await server.ssrLoadModule('/src/pages/owner/FinanceCharts.tsx')
    const html = renderToStaticMarkup(
      createElement(CashBalanceChart, {
        points: [
          { date: '2026-11-13', balance: 0, income: 0, expense: 0 },
          { date: '2026-11-14', balance: -9000, income: 1000, expense: 10000 },
        ],
        selectedDate: '2026-11-14',
        minimumOperatingFunds: 5000,
        onSelect: () => {},
      }),
    )
    assert.match(html, /viewBox="0 0 900 346"/)
    assert.match(html, /aria-label="2026-11-14 예상 잔액 -9,000원" aria-pressed="true"/)
    assert.match(html, /aria-label="2026-11-13 예상 잔액 0원" aria-pressed="false"/)
    assert.match(html, /aria-label="다음 날짜 잔액"[^>]*disabled=""/)
    assert.match(html.replace(/<[^>]+>/g, ''), /들어오는 정산금1,000원나가는 지출10,000원/)
    assert.doesNotMatch(html, /finance-chart-cursor/)
    assert.doesNotMatch(html, /(?:NaN|Infinity)/)
    assert.match(html, /하루가 끝나면 얼마가 남나요/)
    assert.match(html, /그날의 정산금과 지출/)
    assert.match(html, /위 잔액과 아래 입출금의 금액 눈금은 달라요/)
    assert.match(html, /funds-weather-status is-rain/)
    // 잔액이 수억 원이어도 하루 입출금 막대는 자체 눈금에서 읽을 수 있어야 한다.
    const large = renderToStaticMarkup(
      createElement(CashBalanceChart, {
        points: [{ date: '2026-11-13', balance: 352070100, income: 2000000, expense: 300000 }],
        selectedDate: '2026-11-13',
        onSelect: () => {},
      }),
    )
    assert.match(large, /data-flow="income" data-amount="2000000"[^>]*height="48"/)
    const expenseHeight = large.match(
      /data-flow="expense" data-amount="300000"[^>]*height="([^"]+)"/,
    )
    assert.ok(expenseHeight)
    assert.ok(Math.abs(Number(expenseHeight[1]) - Math.sqrt(300000 / 2000000) * 48) < 0.000001)
    assert.ok(Number(expenseHeight[1]) > 7.2)
    assert.match(large, /작은 금액 확대 중/)
    assert.match(large, /두 배 높아도 금액이 두 배인 것은 아니에요/)
    assert.match(large, /aria-label="352,070,100원"/)
    assert.match(large, /funds-weather-status is-sun/)
    assert.match(large, /현재 표시 범위 아래/)
    assert.match(large, /aria-pressed="true">변화 확대/)
    assert.match(large, /aria-pressed="false">0원부터/)
    const zero = renderToStaticMarkup(
      createElement(CashBalanceChart, {
        points: [{ date: '2026-11-13', balance: 0, income: 0, expense: 0 }],
        onSelect: () => {},
      }),
    )
    assert.doesNotMatch(zero, /(?:NaN|Infinity)/)
    assert.match(zero, /data-flow="income" data-amount="0"[^>]*height="0"/)
  } finally {
    await server.close()
  }
})

// 자동 지급 경고는 노출하고, 제외 진단은 가게 전체 기준의 정확한 금액을 접어서 제공한다.
test('fundsNoticesAndPeriodControl_keepWarningsAndAmountsWhileSimplifyingEntry', async () => {
  const server = await createServer({
    cacheDir: 'node_modules/.vite-funds-render-test',
    server: { middlewareMode: true, ws: false, hmr: false, watch: null },
    appType: 'custom',
  })
  try {
    const { FundsForecastNotices } = await server.ssrLoadModule(
      '/src/pages/owner/FundsForecastNotices.tsx',
    )
    const renderNotice = (batchEnabled: boolean, excluded: { label: string; amount: number }[]) =>
      renderToStaticMarkup(
        createElement(
          MemoryRouter,
          null,
          createElement(FundsForecastNotices, { batchEnabled, excluded }),
        ),
      )
    assert.equal(renderNotice(true, []), '')
    const notice = renderNotice(false, [{ label: '예정일 이후 지급 대기', amount: 127324800 }])
    assert.match(notice, /자동 지급이 꺼져 있어요/)
    assert.match(notice, /예정대로 지급된다고 가정해 계산/)
    assert.match(notice, /href="\/owner\/settlements"/)
    assert.match(notice, /aria-label="127,324,800원"/)
    assert.match(notice, /선택 기간의 합계가 아니라/)
    assert.match(notice, /<details class="funds-excluded-details">/)
    assert.doesNotMatch(notice, /<details class="funds-excluded-details"[^>]*\sopen/)
    assert.doesNotMatch(
      renderNotice(true, [{ label: '취소된 주문', amount: 100 }]),
      /자동 지급이 꺼져 있어요/,
    )
    const { FundsPeriodControl } = await server.ssrLoadModule(
      '/src/pages/owner/FundsPeriodControl.tsx',
    )
    const control = renderToStaticMarkup(
      createElement(FundsPeriodControl, {
        value: { from: '2026-11-13', to: '2026-11-19' },
        today: '2026-11-13',
        min: '2026-11-13',
        max: '2027-11-13',
        onChange: () => {},
      }),
    )
    assert.match(control, /7일간/)
    assert.match(control, /aria-label="이전 기간 보기"[^>]*disabled=""/)
    assert.match(control, /aria-label="다음 기간 보기"/)
    assert.match(control, /readonly=""/i)
    assert.match(control, /value="2026\.11\.13"/)
    assert.match(control, /value="2026\.11\.19"/)
  } finally {
    await server.close()
  }
})
