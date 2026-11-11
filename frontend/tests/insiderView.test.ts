import assert from 'node:assert/strict'
import { test } from 'node:test'
import type { InsiderAlert } from '../src/api/insiderAlerts.ts'
import type { InsiderMonitorEvent, InsiderMonitorStaff } from '../src/api/insiderMonitor.ts'
import { incidentFor, staffByUrgency } from '../src/pages/admin/insiderOfficeView.ts'
import {
  focusGroupFirst,
  groupAlertsByStaff,
  matchesLogFilter,
  newLogCount,
  eventKey,
  waitAge,
} from '../src/pages/admin/insiderView.ts'

const alert = (over: Partial<InsiderAlert>): InsiderAlert => ({
  alertId: 1,
  staffId: 1,
  staffName: '가',
  ruleCode: 'INS-01',
  ruleLabel: '규칙',
  severity: 'CAUTION',
  status: 'OPEN',
  summary: '',
  evidenceCount: 1,
  firstEventAt: '2026-11-09T01:00:00Z',
  lastEventAt: '2026-11-09T01:00:00Z',
  createdAt: '2026-11-09T01:00:00Z',
  updatedAt: '2026-11-09T01:00:00Z',
  closedByName: null,
  closedAt: null,
  closeMemo: null,
  ...over,
})

test('경보 묶음은 가장 높은 등급 직원이 위, 묶음 안은 최근 경보가 위', () => {
  const groups = groupAlertsByStaff([
    alert({ alertId: 1, staffId: 1, staffName: '가', severity: 'CAUTION' }),
    alert({
      alertId: 2,
      staffId: 2,
      staffName: '나',
      severity: 'CAUTION',
      lastEventAt: '2026-11-09T02:00:00Z',
    }),
    alert({
      alertId: 3,
      staffId: 2,
      staffName: '나',
      severity: 'HIGH',
      lastEventAt: '2026-11-09T03:00:00Z',
    }),
  ])
  assert.deepEqual(
    groups.map((g) => g.staffId),
    [2, 1],
  )
  assert.equal(groups[0].topSeverity, 'HIGH')
  assert.equal(groups[0].lastEventAt, '2026-11-09T03:00:00Z')
  assert.deepEqual(
    groups[0].alerts.map((a) => a.alertId),
    [3, 2],
  )
})

test('같은 등급이면 경보 많은 직원 → 이름순', () => {
  const groups = groupAlertsByStaff([
    alert({ alertId: 1, staffId: 1, staffName: '다' }),
    alert({ alertId: 2, staffId: 2, staffName: '나' }),
    alert({ alertId: 3, staffId: 3, staffName: '가' }),
    alert({ alertId: 4, staffId: 1, staffName: '다' }),
  ])
  assert.deepEqual(
    groups.map((g) => g.staffId),
    [1, 3, 2],
  )
})

test('기다린 시간은 분 · 시간 · 날로, 하루가 넘으면 late', () => {
  const now = new Date('2026-11-09T12:00:00Z')
  assert.deepEqual(waitAge('2026-11-09T11:30:00Z', now), { text: '30분', late: false })
  assert.deepEqual(waitAge('2026-11-09T09:00:00Z', now), { text: '3시간', late: false })
  assert.deepEqual(waitAge('2026-11-07T11:00:00Z', now), { text: '2일째', late: true })
  // 서버 시각이 조금 앞서도 음수로 보이지 않는다
  assert.deepEqual(waitAge('2026-11-09T12:01:00Z', now), { text: '0분', late: false })
})

const member = (over: Partial<InsiderMonitorStaff>): InsiderMonitorStaff => ({
  staffId: 1,
  staffName: '가',
  stage: 'NORMAL',
  openAlertCount: 0,
  openInsiderCaseId: null,
  locked: false,
  ...over,
})

test('관제 책상은 정지 → 내부자 사건 → 주의 → 정상, 같은 단계는 경보 많은 순', () => {
  const sorted = staffByUrgency([
    member({ staffId: 1, stage: 'NORMAL' }),
    member({ staffId: 2, stage: 'CAUTION', openAlertCount: 1 }),
    member({ staffId: 3, stage: 'CAUTION', openAlertCount: 2 }),
    member({ staffId: 4, stage: 'BLOCKED', locked: true }),
    member({ staffId: 5, stage: 'INSIDER_CASE' }),
  ])
  assert.deepEqual(
    sorted.map((s) => s.staffId),
    [4, 5, 3, 2, 1],
  )
})

test('같은 초 안의 경보도 소수점 자릿수와 상관없이 최근 것이 위', () => {
  const [group] = groupAlertsByStaff([
    alert({ alertId: 1, lastEventAt: '2026-11-09T01:00:05Z' }),
    alert({ alertId: 2, lastEventAt: '2026-11-09T01:00:05.100200Z' }),
  ])
  assert.deepEqual(
    group.alerts.map((a) => a.alertId),
    [2, 1],
  )
  assert.equal(group.lastEventAt, '2026-11-09T01:00:05.100200Z')
})

const event = (over: Partial<InsiderMonitorEvent>): InsiderMonitorEvent => ({
  occurredAt: '2026-11-09T01:00:00Z',
  kind: 'ACCESS',
  type: 'CASE_DETAIL_VIEW',
  clientIp: null,
  device: null,
  result: 'ALLOWED',
  denyReason: null,
  caseId: null,
  merchantId: null,
  merchantName: null,
  paymentId: null,
  fieldType: null,
  itemCount: null,
  customerScope: null,
  alertId: null,
  ruleCode: null,
  ruleLabel: null,
  severity: null,
  summary: null,
  lockReason: null,
  insiderCaseId: null,
  decision: null,
  ...over,
})

test('기록 거르기 — 위험 접근은 거부 · 무관 고객 · 전체 전화번호 · 로그인 실패', () => {
  const plain = event({})
  const denied = event({ result: 'DENIED' })
  const unrelated = event({ customerScope: 'UNRELATED' })
  const raw = event({ type: 'CONTACT_RAW_VIEW' })
  const loginOk = event({ kind: 'LOGIN', type: 'LOGIN_SUCCESS', result: null })
  const loginFail = event({ kind: 'LOGIN', type: 'LOGIN_FAILURE', result: null })
  const alertRaised = event({ kind: 'ALERT', type: 'ALERT_RAISED', result: null })
  const locked = event({ kind: 'LOCK', type: 'LOCKED', result: null })
  const all = [plain, denied, unrelated, raw, loginOk, loginFail, alertRaised, locked]
  const pick = (filter: Parameters<typeof matchesLogFilter>[1]) =>
    all.filter((e) => matchesLogFilter(e, filter))
  assert.equal(pick('ALL').length, all.length)
  assert.deepEqual(pick('RISK'), [denied, unrelated, raw, loginFail])
  assert.deepEqual(pick('ALERT'), [alertRaised, locked])
  assert.deepEqual(pick('LOGIN'), [loginOk, loginFail])
})

test('새 기록 수 — 맨 아래를 떠날 때 받아 둔 줄 밖의 줄만 센다', () => {
  const a = event({ occurredAt: '2026-11-09T01:00:00Z' })
  const b = event({ occurredAt: '2026-11-09T01:00:05Z', caseId: 1 })
  const c = event({ occurredAt: '2026-11-09T01:00:10Z', caseId: 2 })
  const seen = new Set([a, b].map(eventKey))
  assert.equal(newLogCount([a, b, c], null, false), 0)
  assert.equal(newLogCount([a, b], seen, false), 0)
  assert.equal(newLogCount([a, b, c], seen, false), 1)
})

test('같은 초 안에서 마지막 줄이 가장 늦지 않아도 그대로면 새 기록 0 (서버: 초 → 종류 → 시각 순)', () => {
  const access = event({ occurredAt: '2026-11-09T01:00:00.900Z', caseId: 1 })
  const alertRow = event({
    occurredAt: '2026-11-09T01:00:00.700Z',
    kind: 'ALERT',
    type: 'ALERT_RAISED',
    alertId: 7,
  })
  const events = [access, alertRow]
  assert.equal(newLogCount(events, new Set(events.map(eventKey)), false), 0)
})

test('이미 본 줄보다 이른 시각으로 늦게 들어온 줄도 새 기록으로 센다', () => {
  const access = event({ occurredAt: '2026-11-09T01:00:00.900Z', caseId: 1 })
  const lateAlert = event({
    occurredAt: '2026-11-09T01:00:00.700Z',
    kind: 'ALERT',
    type: 'ALERT_RAISED',
    alertId: 7,
  })
  assert.equal(newLogCount([lateAlert, access], new Set([eventKey(access)]), false), 1)
})

test('잘렸고 본 줄이 하나도 안 남았으면 many, 남아 있으면 정확한 수', () => {
  const old = event({ occurredAt: '2026-11-09T00:59:00Z' })
  const a = event({ occurredAt: '2026-11-09T01:00:00Z', caseId: 1 })
  const b = event({ occurredAt: '2026-11-09T01:00:05Z', caseId: 2 })
  assert.equal(newLogCount([a, b], new Set([eventKey(old)]), true), 'many')
  assert.equal(newLogCount([a, b], new Set([eventKey(a)]), true), 1)
})

test('새 기록 수는 지금 거르기에 맞는 줄만 센다', () => {
  const base = event({ occurredAt: '2026-11-09T01:00:00Z' })
  const login = event({ occurredAt: '2026-11-09T01:00:05Z', kind: 'LOGIN', type: 'LOGIN_SUCCESS' })
  const raised = event({ occurredAt: '2026-11-09T01:00:10Z', kind: 'ALERT', type: 'ALERT_RAISED' })
  const events = [base, login, raised]
  const seen = new Set([eventKey(base)])
  assert.equal(newLogCount(events, seen, false, 'ALL'), 2)
  assert.equal(newLogCount(events, seen, false, 'ALERT'), 1)
  assert.equal(newLogCount(events, seen, false, 'RISK'), 0)
  // 잘렸어도 맞는 새 줄이 없으면 버튼을 띄우지 않는다
  const gone = new Set(['없는 줄'])
  assert.equal(newLogCount(events, gone, true, 'RISK'), 0)
  assert.equal(newLogCount(events, gone, true, 'ALERT'), 'many')
})

test('관제에서 온 직원 묶음이 맨 위, 없으면 순서 그대로', () => {
  const groups = groupAlertsByStaff([
    alert({ alertId: 1, staffId: 1, staffName: '가', severity: 'CRITICAL' }),
    alert({ alertId: 2, staffId: 2, staffName: '나' }),
    alert({ alertId: 3, staffId: 3, staffName: '다' }),
  ])
  assert.deepEqual(
    focusGroupFirst(groups, 3).map((g) => g.staffId),
    [3, 1, 2],
  )
  assert.deepEqual(
    focusGroupFirst(groups, 9).map((g) => g.staffId),
    [1, 2, 3],
  )
  assert.equal(focusGroupFirst(groups, null), groups)
})

// 판정이 끝난 직원은 정지가 남아도 사고 카드가 없다 — 판정 대기 중일 때만 (#740)
test('incidentFor_whenDecidedButStillLocked_showsNoIncident', () => {
  assert.equal(
    incidentFor(member({ stage: 'BLOCKED', locked: true, openInsiderCaseId: null })),
    null,
  )
  assert.equal(
    incidentFor(member({ stage: 'BLOCKED', locked: true, openInsiderCaseId: 7 })),
    'BLOCKED',
  )
  assert.equal(incidentFor(member({ stage: 'INSIDER_CASE', openInsiderCaseId: 7 })), 'INSIDER_CASE')
})
