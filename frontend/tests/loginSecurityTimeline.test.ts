import assert from 'node:assert/strict'
import { test } from 'node:test'
import type {
  LoginSecurityAlert,
  LoginSecurityAttempt,
  LoginSecurityDashboard,
} from '../src/api/loginSecurity.ts'
import {
  attemptKey,
  buildIncidents,
  scheduleReplay,
} from '../src/pages/admin/loginSecurityTimeline.ts'

const at = (hms: string) => `2026-11-10T${hms}Z`

function attempt(occurredAt: string, device: string, suspected: boolean): LoginSecurityAttempt {
  return {
    occurredAt,
    success: suspected,
    account: suspected ? 'SECURITY_STAFF' : 'UNKNOWN',
    staffId: suspected ? 31 : null,
    staffName: suspected ? '한지우' : null,
    device,
    suspected,
  }
}

function alert(alertId: number, ruleCode: string, createdAt: string): LoginSecurityAlert {
  return {
    alertId,
    staffId: 31,
    staffName: '한지우',
    ruleCode,
    ruleLabel: '',
    severity: 'HIGH',
    summary: '',
    open: true,
    createdAt,
    insiderCaseId: 27,
  }
}

// 그래프 범위 06:55:10 ~ 07:00:10 (10초 칸 30개)
function dashboard(
  attempts: LoginSecurityAttempt[],
  alerts: LoginSecurityAlert[],
): LoginSecurityDashboard {
  const from = Date.parse(at('06:55:10'))
  return {
    generatedAt: at('07:00:00'),
    state: 'UNDER_ATTACK',
    windowMinutes: 5,
    minAttempts: 30,
    failureRatePercentThreshold: 30,
    recentAttempts: 52,
    recentFailures: 49,
    recentUnknownAccountFailures: 49,
    recentFailureRatePercent: 94,
    suspectedTakeoverCount: 1,
    bucketSeconds: 10,
    buckets: Array.from({ length: 30 }, (_, i) => ({
      start: new Date(from + i * 10_000).toISOString(),
      attempts: 0,
      failures: 0,
      unknownAccountFailures: 0,
    })),
    attackPeriods: [{ start: at('06:58:29'), end: null }],
    attempts,
    alerts,
  }
}

// 그래프 · 사건 목록 · 실시간 시도 줄의 같은 번호가 같은 사건을 가리킨다 — 응답 경보 순서와 상관없이 시각 순 ①②③④
test('buildIncidents_whenAttackTakeoverAndBlock_numbersInTimeOrder', () => {
  const detected = attempt(at('06:58:50.000'), '기타 브라우저 · 기타 운영체제', true)
  const chrome = attempt(at('06:59:07.000'), 'Chrome · Windows', true)
  const d = dashboard(
    [chrome, detected, attempt(at('06:58:49.600'), '기타 브라우저 · 기타 운영체제', false)],
    [alert(9, 'SEC-INS-05', at('06:59:57.000')), alert(5, 'SEC-INS-10', at('06:58:50.200'))],
  )

  const incidents = buildIncidents(d)

  assert.deepEqual(
    incidents.map((i) => [i.n, i.kind, i.attemptKey]),
    [
      [1, 'entry', null],
      [2, 'takeover', attemptKey(detected)],
      [3, 'relogin', attemptKey(chrome)],
      [4, 'block', null],
    ],
  )
})

// 2초마다 한꺼번에 받은 시도도 일어난 간격(0.4초)대로 레이더에 닿는다 — 이미 날린 시도는 다시 날리지 않는다
test('scheduleReplay_whenAttemptsArriveTogether_spacesByOccurredAt', () => {
  const first = attempt(at('07:00:00.000'), '기타 브라우저', false)
  const second = attempt(at('07:00:00.400'), '기타 브라우저', false)
  const third = attempt(at('07:00:00.800'), '기타 브라우저', false)
  const serverNow = Date.parse(at('07:00:01.500'))
  const offset = 1_000

  const launches = scheduleReplay([third, second, first], new Set([attemptKey(first)]), {
    clockOffsetMs: offset,
    nowMs: serverNow + offset,
    serverNowMs: serverNow,
    maxAgeMs: 6_000,
    delayMs: 2_200,
  })

  assert.deepEqual(
    launches.map((l) => [l.key, l.arriveAt]),
    [
      [attemptKey(second), Date.parse(second.occurredAt) + offset + 2_200],
      [attemptKey(third), Date.parse(third.occurredAt) + offset + 2_200],
    ],
  )
  assert.equal(launches[1].arriveAt - launches[0].arriveAt, 400)
})
