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
  mergeAttempts,
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

// events = 경보가 가리키는 첫 · 마지막 로그인 시각 (SEC-INS-10). 없으면 생성 시각
function alert(
  alertId: number,
  ruleCode: string,
  createdAt: string,
  opts: { first?: string; last?: string; open?: boolean; caseId?: number } = {},
): LoginSecurityAlert {
  return {
    alertId,
    staffId: 31,
    staffName: '한지우',
    ruleCode,
    ruleLabel: '',
    severity: 'HIGH',
    summary: '',
    open: opts.open ?? true,
    createdAt,
    insiderCaseId: opts.caseId ?? 27,
    firstEventAt: opts.first ?? createdAt,
    lastEventAt: opts.last ?? opts.first ?? createdAt,
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
    [
      alert(9, 'SEC-INS-05', at('06:59:57.000')),
      alert(5, 'SEC-INS-10', at('06:58:50.200'), {
        first: detected.occurredAt,
        last: chrome.occurredAt,
      }),
    ],
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

// 같은 직원의 닫힌 경보와 새 경보가 함께 있으면 로그인은 자기 경보에만 붙는다 — 새 경보의 로그인이 닫힌 경보의 재로그인으로 겹쳐 보이면 안 된다 (#791 Codex 리뷰)
test('buildIncidents_whenStaffHasClosedAndNewAlert_attachesLoginsToTheirOwnAlert', () => {
  const firstAttack = attempt(at('06:56:00.000'), '기타 브라우저 · 기타 운영체제', true)
  const secondAttack = attempt(at('06:58:50.000'), '기타 브라우저 · 기타 운영체제', true)
  const chrome = attempt(at('06:59:07.000'), 'Chrome · Windows', true)
  const d = dashboard(
    [chrome, secondAttack, firstAttack],
    [
      alert(5, 'SEC-INS-10', at('06:56:00.200'), {
        first: firstAttack.occurredAt,
        open: false,
        caseId: 20,
      }),
      alert(8, 'SEC-INS-10', at('06:58:50.200'), {
        first: secondAttack.occurredAt,
        last: chrome.occurredAt,
        caseId: 21,
      }),
    ],
  )
  d.attackPeriods = []

  const incidents = buildIncidents(d)

  assert.deepEqual(
    incidents.map((i) => [i.kind, i.attemptKey, i.caseId]),
    [
      ['takeover', attemptKey(firstAttack), 20],
      ['takeover', attemptKey(secondAttack), 21],
      ['relogin', attemptKey(chrome), 21],
    ],
  )
})

// 2초에 40건이 넘게 들어와 새 응답에서 밀려난 시도도, 아직 화면 시계가 닿지 않았으면 버퍼에 남아 줄로 보인다 (#791 Codex 리뷰)
test('mergeAttempts_whenNewResponsePushesOutUnplayedAttempt_keepsIt', () => {
  const unplayed = attempt(at('07:00:01.900'), '기타 브라우저', false)
  const shownOld = attempt(at('06:59:00.000'), '기타 브라우저', false)
  const newer = Array.from({ length: 40 }, (_, i) =>
    attempt(
      new Date(Date.parse(at('07:00:02.000')) + i * 50).toISOString(),
      '기타 브라우저',
      false,
    ),
  )

  const merged = mergeAttempts([unplayed, shownOld], newer, {
    viewNowMs: Date.parse(at('07:00:01.800')),
    keepShown: 40,
    oldestMs: Date.parse(at('06:50:04.000')),
  })

  assert.ok(merged.some((a) => attemptKey(a) === attemptKey(unplayed)))
  assert.ok(merged.some((a) => attemptKey(a) === attemptKey(shownOld)))
  assert.equal(merged.length, 42)
  assert.deepEqual(
    merged.map((a) => a.occurredAt),
    [...merged.map((a) => a.occurredAt)].sort().reverse(),
  )
})

// 새 시도가 없어도 10분이 지난 줄은 버퍼에서 빠진다 — 남기면 오래된 실패 · 탈취 의심이 "최근 10분"으로 계속 보인다 (#791 Codex 2차 리뷰)
test('mergeAttempts_whenShownAttemptOlderThanLookback_dropsIt', () => {
  const expired = attempt(at('06:49:59.000'), '기타 브라우저', false)
  const recent = attempt(at('06:55:00.000'), '기타 브라우저', false)

  const merged = mergeAttempts([recent, expired], [], {
    viewNowMs: Date.parse(at('06:59:57.800')),
    keepShown: 40,
    oldestMs: Date.parse(at('06:50:00.000')),
  })

  assert.deepEqual(merged.map(attemptKey), [attemptKey(recent)])
})

// 2초마다 한꺼번에 받은 시도도 일어난 간격(0.4초)대로 레이더에 닿는다 — 이미 날린 시도는 다시 날리지 않는다
test('scheduleReplay_whenAttemptsArriveTogether_spacesByOccurredAt', () => {
  const first = attempt(at('07:00:00.000'), '기타 브라우저', false)
  const second = attempt(at('07:00:00.400'), '기타 브라우저', false)
  const third = attempt(at('07:00:00.800'), '기타 브라우저', false)
  const serverNow = Date.parse(at('07:00:01.500'))
  const offset = 1_000

  const launches = scheduleReplay(
    [third, second, first],
    new Map([[attemptKey(first), 'unknown']]),
    {
      clockOffsetMs: offset,
      nowMs: serverNow + offset,
      serverNowMs: serverNow,
      maxAgeMs: 6_000,
      delayMs: 2_200,
    },
  )

  assert.deepEqual(
    launches.map((l) => [l.key, l.arriveAt]),
    [
      [attemptKey(second), Date.parse(second.occurredAt) + offset + 2_200],
      [attemptKey(third), Date.parse(third.occurredAt) + offset + 2_200],
    ],
  )
  assert.equal(launches[1].arriveAt - launches[0].arriveAt, 400)
})

// 성공으로 먼저 받은 로그인에 탈취 의심이 다음 응답에서 붙으면 그 점을 탈취 의심으로 다시 보낸다 — 아니면 금색 점이 초록으로 지나간다 (#791 Codex 3차 리뷰)
test('scheduleReplay_whenSuspectedArrivesLate_reschedulesAsSuspected', () => {
  const login = attempt(at('07:00:00.000'), '기타 브라우저 · 기타 운영체제', true)
  const serverNow = Date.parse(at('07:00:01.000'))

  const launches = scheduleReplay([login], new Map([[attemptKey(login), 'ok']]), {
    clockOffsetMs: 0,
    nowMs: serverNow,
    serverNowMs: serverNow,
    maxAgeMs: 6_000,
    delayMs: 2_200,
  })

  assert.deepEqual(launches, [
    {
      key: attemptKey(login),
      arriveAt: Date.parse(login.occurredAt) + 2_200,
      kind: 'suspected',
      upgrade: true,
    },
  ])
})

// 같은 열린 경보에 로그인이 또 묶이면 새 사건으로 본다 — 키가 같으면 레이더가 이미 띄운 사건으로 보고 문구를 건너뛴다 (#791 Codex 3차 리뷰)
test('buildIncidents_whenSameAlertMergesAnotherLogin_givesEachReloginItsOwnKey', () => {
  const detected = attempt(at('06:58:50.000'), '기타 브라우저 · 기타 운영체제', true)
  const chrome = attempt(at('06:59:07.000'), 'Chrome · Windows', true)
  const firefox = attempt(at('06:59:30.000'), 'Firefox · Windows', true)
  const reloginKey = (last: LoginSecurityAttempt) =>
    buildIncidents(
      dashboard(
        [last, detected],
        [
          alert(8, 'SEC-INS-10', at('06:58:50.200'), {
            first: detected.occurredAt,
            last: last.occurredAt,
          }),
        ],
      ),
    ).find((i) => i.kind === 'relogin')?.key

  assert.notEqual(reloginKey(chrome), reloginKey(firefox))
})
