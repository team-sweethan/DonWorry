import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { createServer } from 'vite'

// 펼치기 영역 안에는 정책 변경 버튼을 넣지 않고, 기존 역할별 조작을 유지한다.
test('ruleCard_whenCollapsed_keepsPolicyActionsOutsideSummaryAndPreservesRoleControls', async () => {
  const server = await createServer({
    cacheDir: 'node_modules/.vite-rule-card-test',
    server: { middlewareMode: true, ws: false, hmr: false, watch: null },
    appType: 'custom',
  })
  try {
    const { RuleCard } = await server.ssrLoadModule('/src/pages/security/RuleCard.tsx')
    const rule = {
      ruleCode: 'R01',
      category: '금액',
      ruleName: '고액 결제',
      condition: '단건 결제 금액이 50만 원 이상',
      resultDescription: '+30점',
      example: '65만 원 결제',
      standaloneDescription: '30점으로 단독 ALLOW',
      immediateDeny: false,
      recentMatchCount: 7,
      lastMatchedAt: null,
      dailyMatchCounts: [0, 1, 0, 2, 1, 2, 1],
    }
    let changes = 0
    const render = (isAdmin: boolean, active: boolean, alwaysOn = false) =>
      renderToStaticMarkup(
        createElement(RuleCard, {
          rule: alwaysOn
            ? { ...rule, ruleCode: 'INS_05', ruleName: '높음 경보 중 내보내기 시도' }
            : rule,
          policy: alwaysOn ? undefined : { ruleCode: 'R01', active, disableRequestPending: false },
          isAdmin,
          onChanged: () => {
            changes += 1
          },
        }),
      )
    const admin = render(true, true)
    assert.match(admin, /<details class="dw-rule-disclosure">/)
    assert.doesNotMatch(admin, /<details[^>]*\sopen(?:[=>\s])/)
    const summary = admin.match(/<summary[^>]*>(.*?)<\/summary>/s)?.[1]
    assert.ok(summary)
    assert.match(summary, /고액 결제/)
    assert.match(summary, /사용 중/)
    assert.doesNotMatch(summary, /<button|<input|role="switch"|단건 결제 금액/)
    assert.match(admin, /role="switch"/)
    assert.match(admin, /단건 결제 금액이 50만 원 이상/)
    assert.match(admin, /30점으로 단독 ALLOW/)
    const staffActive = render(false, true)
    assert.match(staffActive, /끄기 요청/)
    assert.doesNotMatch(staffActive, /role="switch"/)
    assert.match(render(false, false), /다시 켜기/)
    const alwaysOn = render(true, true, true)
    assert.match(alwaysOn, /항상 켜짐/)
    assert.doesNotMatch(alwaysOn, /role="switch"/)
    assert.equal(changes, 0)
  } finally {
    await server.close()
  }
})
