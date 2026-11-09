import assert from 'node:assert/strict'
import { test } from 'node:test'
import type { InsiderAlert } from '../src/api/insiderAlerts.ts'
import type { InsiderMonitorStaff } from '../src/api/insiderMonitor.ts'
import { staffByUrgency } from '../src/pages/admin/insiderOfficeView.ts'
import { groupAlertsByStaff, waitAge } from '../src/pages/admin/insiderView.ts'

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
