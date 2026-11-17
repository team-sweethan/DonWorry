import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  boothFlow,
  boothSteps,
  resolveBoothSpot,
  resolveBoothStep,
  type BoothSpot,
} from '../src/booth/boothSteps.ts'
import { boothCaseForPath, parseBoothCase } from '../src/booth/boothSettings.ts'

const open = { canOpen: () => true }

// 기본 코스에서 발표의 주요 기능과 부스에서 짚을 보안 장치가 빠지지 않는다.
test('boothSteps_whenCoreOnly_coversMainFlowAndSecurityDetails', () => {
  const expected = {
    public: ['landing', 'apply', 'mail'],
    owner: [
      'owner-integration',
      'owner-settlements',
      'owner-funds',
      'owner-loans',
      'owner-actions',
    ],
    security: [
      'security-rules',
      'security-alerts',
      'security-masking',
      'security-report',
      'security-ai-switch',
    ],
    admin: [
      'admin-applications',
      'admin-case-assignments',
      'admin-raw-view',
      'admin-rule-requests',
      'admin-operator-access',
      'admin-login-security',
      'admin-insider',
      'admin-closing',
    ],
  } as const
  for (const track of ['public', 'owner', 'security', 'admin'] as const) {
    const ids = boothSteps(track, { all: false, caseId: '12', ...open }).map((step) => step.id)
    for (const id of expected[track]) assert.ok(ids.includes(id), `${track}: ${id}`)
  }
})

// 신청 심사 다음에 직원 자동 배정과 담당 현황을 기본 코스에서 설명한다.
test('boothSteps_whenAdminCore_visitsAssignmentsAfterApplications', () => {
  const steps = boothSteps('admin', { all: false, caseId: null, ...open })
  const index = steps.findIndex((step) => step.id === 'admin-applications')
  const assignment = steps[index + 1]
  assert.equal(assignment?.id, 'admin-case-assignments')
  assert.equal(assignment.path, '/admin/case-assignments')
  assert.equal(assignment.spots?.length, 2)
})

// 핵심 보기에서는 core 단계만, 전체 보기에서는 모든 단계가 나온다.
test('boothSteps_whenCoreOnly_keepsCoreSteps', () => {
  const core = boothSteps('security', { all: false, caseId: null, ...open })
  const all = boothSteps('security', { all: true, caseId: null, ...open })
  assert.ok(core.length < all.length)
  assert.ok(core.every((step) => step.core))
})

// 권한이 없는 주소는 단계에서 빠진다 — 들어가면 403 기록이 쌓이기 때문이다.
test('boothSteps_whenPathNotAllowed_dropsStep', () => {
  const steps = boothSteps('owner', {
    all: true,
    caseId: null,
    canOpen: (path) => path !== '/owner/funds',
  })
  assert.equal(
    steps.some((step) => step.path === '/owner/funds'),
    false,
  )
  assert.ok(steps.some((step) => step.id === 'owner-closing'))
})

// 준비한 Case ID가 있으면 상세 · 보고서 단계가 그 주소로 바로 간다.
test('boothSteps_whenCaseIdGiven_pointsCaseSteps', () => {
  const steps = boothSteps('security', { all: false, caseId: '12', ...open })
  assert.equal(steps.find((step) => step.id === 'security-masking')?.path, '/security/cases/12')
  assert.equal(
    steps.find((step) => step.id === 'security-report')?.path,
    '/security/cases/12/report',
  )
})

// 연락처와 보고서가 서로 다른 합성 사건에 있을 때 준비한 예시를 구분해서 보여 준다.
test('boothSteps_whenSeparateReportPrepared_preservesContactCaseAndLabelsExample', () => {
  const steps = boothSteps('security', { all: false, caseId: '4', reportCaseId: '399', ...open })
  assert.equal(steps.find((step) => step.id === 'security-masking')?.path, '/security/cases/4')
  const report = steps.find((step) => step.id === 'security-report')
  assert.equal(report?.path, '/security/cases/399/report')
  assert.match(report?.spots?.[0]?.note ?? '', /별도 사건/)
})

// 진행자가 고른 단계는 그 화면에 있는 동안 유지된다 (주소 없이 머무는 단계 포함).
test('resolveBoothStep_whenChosenOnSamePage_keepsChosen', () => {
  const steps = boothSteps('security', { all: false, caseId: null, ...open })
  const step = resolveBoothStep(
    steps,
    { stepId: 'security-masking', at: '/security/cases', spot: 0 },
    '/security/cases',
  )
  assert.equal(step?.id, 'security-masking')
})

// Case를 열면 고른 단계가 그대로 이어지고, 메뉴로 다른 화면에 가면 그 화면 단계로 따라간다.
test('resolveBoothStep_whenPageChanges_followsPage', () => {
  const steps = boothSteps('security', { all: false, caseId: null, ...open })
  const position = { stepId: 'security-report', at: '/security/cases/7', spot: 0 }
  assert.equal(resolveBoothStep(steps, position, '/security/cases/7/report')?.id, 'security-report')
  assert.equal(resolveBoothStep(steps, position, '/security/rules')?.id, 'security-rules')
})

// 사장님 마무리 후 자금 현황을 다시 열면 마무리 문구 대신 그 화면의 안내로 돌아간다.
test('resolveBoothStep_whenOwnerLeavesClosing_followsDestination', () => {
  const steps = boothSteps('owner', { all: false, caseId: null, ...open })
  const position = { stepId: 'owner-closing', at: '/owner/actions', spot: 0 }
  assert.equal(resolveBoothStep(steps, position, '/owner/actions')?.id, 'owner-closing')
  assert.equal(resolveBoothStep(steps, position, '/owner/funds')?.id, 'owner-funds')
  assert.equal(resolveBoothStep(steps, position, '/owner/actions/3')?.id, 'owner-actions')
  assert.equal(resolveBoothStep(steps, position, '/account/password')?.id, 'owner-home')
})

// 관리자 마무리 후 신청 화면으로 이동해도 질문에 맞는 화면 설명을 계속할 수 있다.
test('resolveBoothStep_whenAdminLeavesClosing_followsDestination', () => {
  const steps = boothSteps('admin', { all: false, caseId: null, ...open })
  const position = { stepId: 'admin-closing', at: '/admin/insider-monitor', spot: 0 }
  assert.equal(resolveBoothStep(steps, position, '/admin/insider-monitor')?.id, 'admin-closing')
  assert.equal(resolveBoothStep(steps, position, '/admin/applications')?.id, 'admin-applications')
  assert.equal(
    resolveBoothStep(steps, position, '/admin/applications/12')?.id,
    'admin-applications',
  )
  assert.equal(resolveBoothStep(steps, position, '/account/password')?.id, 'admin-home')
})

// 고른 단계가 없으면 지금 주소에 맞는 단계, 없으면 첫 단계를 보여 준다.
test('resolveBoothStep_whenNothingChosen_matchesPath', () => {
  const steps = boothSteps('owner', { all: false, caseId: null, ...open })
  assert.equal(resolveBoothStep(steps, null, '/owner/loans')?.id, 'owner-loans')
  assert.equal(resolveBoothStep(steps, null, '/owner/actions/3')?.id, 'owner-actions')
  assert.equal(resolveBoothStep(steps, null, '/account/password')?.id, 'owner-home')
})

// 목록 화면의 단계는 그 안의 상세 화면으로 들어가도 이어진다 (경보 목록 → 경보 상세).
test('resolveBoothStep_whenDetailOpened_keepsListStep', () => {
  const steps = boothSteps('security', { all: false, caseId: null, ...open })
  const position = { stepId: 'security-alerts', at: '/security/alerts', spot: 1 }
  assert.equal(resolveBoothStep(steps, position, '/security/alerts/31')?.id, 'security-alerts')
})

// 네 역할을 이어 봐도 순서를 지키고, 사장님 창 전환 안내가 전체 마무리로 잘못 이동하지 않는다.
test('boothFlow_whenRolesJoined_keepsRoleOrderAndAdminClosing', () => {
  const steps = boothFlow(['public', 'owner', 'security', 'admin'], { all: false, caseId: null })
  assert.equal(steps[0]?.track, 'public')
  assert.equal(steps.at(-1)?.id, 'admin-closing')
  assert.equal(steps.at(-1)?.track, 'admin')
  assert.equal(steps.filter((step) => step.id === 'owner-closing').length, 0)
  assert.equal(new Set(steps.map((step) => step.id)).size, steps.length)
  assert.deepEqual(
    [...new Set(steps.map((step) => step.track))],
    ['public', 'owner', 'security', 'admin'],
  )
})

// 준비한 역할만 이어 볼 때도 빠진 역할로 이동하지 않는다.
test('boothFlow_whenAdminMissing_endsWithinPreparedRoles', () => {
  const steps = boothFlow(['public', 'owner', 'security'], { all: false, caseId: null })
  assert.equal(steps.at(-1)?.track, 'security')
  assert.ok(steps.every((step) => step.track !== 'admin'))
})

const spot: BoothSpot = {
  sel: '.detail',
  note: '결제별 처리 결과입니다.',
  fallback: { sel: '.list', note: '완료 사건을 펼쳐 주세요.' },
}

// 상세가 접혀 있으면 실제 보이는 목록을 짚고, 펼치면 상세 설명으로 전환한다.
test('resolveBoothSpot_whenDetailHidden_guidesOpeningThenUsesDetail', () => {
  const list = { id: 'list' }
  const detail = { id: 'detail' }
  const closed = resolveBoothSpot(spot, (target) => (target.sel === '.list' ? list : null))
  assert.deepEqual(closed, { target: list, note: '완료 사건을 펼쳐 주세요.', waiting: true })
  const opened = resolveBoothSpot(spot, (target) => (target.sel === '.detail' ? detail : list))
  assert.deepEqual(opened, { target: detail, note: '결제별 처리 결과입니다.', waiting: false })
})

// 데이터가 없으면 다른 설명을 사실처럼 붙이지 않고, 준비 안내를 유지한다.
test('resolveBoothSpot_whenNeitherTargetExists_keepsPreparationNotice', () => {
  assert.deepEqual(
    resolveBoothSpot(spot, () => null),
    {
      target: null,
      note: '완료 사건을 펼쳐 주세요.',
      waiting: true,
    },
  )
})

// Case 설정에는 주소 조각·0·음수 등을 저장하지 않는다.
test('parseBoothCase_whenInvalid_rejectsUnsafeIdentifiers', () => {
  for (const value of [null, '', '0', '-1', '12/report', '../12', '1?x=1', 'abc', '1e3', ' 12']) {
    assert.equal(parseBoothCase(value), null)
  }
  assert.equal(parseBoothCase('12'), '12')
})

// 진행자가 새 사건을 골랐으면 저장된 예전 사건이 아니라 현재 사건의 보고서로 이어진다.
test('boothCaseForPath_whenAnotherCaseOpened_followsCurrentCase', () => {
  assert.equal(boothCaseForPath('/security/cases/7', '12'), '7')
  assert.equal(boothCaseForPath('/security/cases/7/report', '12'), '7')
  assert.equal(boothCaseForPath('/security/cases', '12'), '12')
  assert.equal(boothCaseForPath('/security/cases/no-id', null), null)
})

// 별도 보고서 예시를 열어도 마스킹 단계는 연락처가 준비된 사건을 잊지 않는다.
test('boothCaseForPath_whenSeparateReportOpened_keepsPreparedContactCase', () => {
  assert.equal(boothCaseForPath('/security/cases/399/report', '4', '399'), '4')
  assert.equal(boothCaseForPath('/security/cases/7', '4', '399'), '7')
})

// 경보 목록 다음에는 새로 준비한 평가를 바로 열어 행 선택 없이 근거를 보여 준다.
test('boothSteps_withEvaluation_visitsPreparedDetailAfterList', () => {
  const steps = boothSteps('security', {
    all: false,
    caseId: '411',
    evaluationId: '30079',
    ...open,
  })
  const index = steps.findIndex((step) => step.id === 'security-alerts')
  assert.equal(steps[index].path, '/security/alerts')
  assert.equal(steps[index + 1].path, '/security/alerts/30079')
})
