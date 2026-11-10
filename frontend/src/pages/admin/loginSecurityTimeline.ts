import type {
  LoginSecurityAttempt,
  LoginSecurityDashboard,
  LoginSecurityState,
} from '../../api/loginSecurity.ts'
import { displayRuleCode } from '../../utils/ruleDisplayCode.ts'

/**
 * 로그인 보안 화면의 시간 흐름 (#788) — 그래프 · 사건 목록 · 실시간 시도 · 레이더가 같은 번호와 같은 시각을 쓰게 한다.
 * 화면은 2초마다 읽으므로, 새 시도는 발생 간격 그대로 약 2초 늦게 다시 보여 준다(REPLAY_DELAY_MS).
 */
export const REPLAY_DELAY_MS = 2_200

const ms = (iso: string) => new Date(iso).getTime()
const browserOf = (device: string) => device.split(' · ')[0] ?? device

export const attemptKey = (a: LoginSecurityAttempt) =>
  `${a.occurredAt}-${a.account}-${a.staffId ?? ''}-${a.success}`

export type IncidentKind = 'entry' | 'takeover' | 'relogin' | 'block'

/** 그래프 위 번호 사건 — n은 1부터 시각 순서 */
export type Incident = {
  key: string
  n: number
  kind: IncidentKind
  at: string
  title: string
  detail: string
  tip: string[]
  caseId: number | null
  /** 이 사건을 만든 실시간 시도 줄 (경계 진입 · 반출 차단은 없음) */
  attemptKey: string | null
}

/** 꺾은선 범위 [첫 칸 시작, 마지막 칸 끝) — 사건 번호는 이 안에서만 매긴다 */
export function chartRange(d: LoginSecurityDashboard): { from: number; to: number } | null {
  const first = d.buckets[0]
  const last = d.buckets[d.buckets.length - 1]
  if (!first || !last) return null
  return { from: ms(first.start), to: ms(last.start) + d.bucketSeconds * 1_000 }
}

/**
 * ① 경계 진입(서버 1초 계산) · ② 탈취 의심(SEC-INS-10) · ③ 같은 직원의 이후 의심 로그인 · ④ 반출 차단(SEC-INS-05)을
 * 꺾은선 범위 안에서 시각 순으로 번호 매긴다. 같은 시각이면 위 순서대로.
 */
export function buildIncidents(d: LoginSecurityDashboard): Incident[] {
  const range = chartRange(d)
  if (!range) return []
  const inRange = (iso: string) => ms(iso) >= range.from && ms(iso) < range.to
  const found: Omit<Incident, 'n'>[] = []

  for (const p of d.attackPeriods) {
    if (p.start === null || !inRange(p.start)) continue
    found.push({
      key: `entry-${p.start}`,
      kind: 'entry',
      at: p.start,
      title: '경계 진입',
      detail: `최근 ${d.windowMinutes}분 시도 ${d.minAttempts}건 · 실패율 ${d.failureRatePercentThreshold}% 기준을 넘음`,
      tip: [
        `시도 ${d.minAttempts}건 이상 · 실패율 ${d.failureRatePercentThreshold}% 이상`,
        '이때부터 처음 보는 브라우저로 들어온 보안 직원을 탈취 의심으로 봄',
      ],
      caseId: null,
      attemptKey: null,
    })
  }

  const suspected = d.attempts.filter((a) => a.suspected && a.staffId !== null)
  for (const alert of d.alerts) {
    const code = displayRuleCode(alert.ruleCode)
    const name = alert.staffName ?? `ID ${alert.staffId}`
    if (code === 'SEC-INS-10') {
      // 경보가 가리키는 로그인만 잇는다 — 첫 기록 = 탐지한 로그인, 마지막 기록(다르면) = 열린 경보에 묶인 다음 로그인 (#791 Codex 리뷰)
      // 직원 번호 · 시각 앞뒤로만 고르면 같은 직원의 닫힌 경보와 새 경보가 서로의 로그인을 가져간다
      // 한계: 서버처럼 처음 · 마지막 로그인만 남아, 그 사이에 묶인 로그인은 번호가 붙지 않는다
      const loginAt = (iso: string | null) =>
        iso === null
          ? undefined
          : suspected.find((a) => a.staffId === alert.staffId && ms(a.occurredAt) === ms(iso))
      const detected = loginAt(alert.firstEventAt)
      const merged =
        alert.lastEventAt !== null &&
        (alert.firstEventAt === null || ms(alert.lastEventAt) !== ms(alert.firstEventAt))
          ? loginAt(alert.lastEventAt)
          : undefined
      if (inRange(alert.createdAt)) {
        found.push({
          key: `takeover-${alert.alertId}`,
          kind: 'takeover',
          at: alert.createdAt,
          // 탈취 의심이면 서버가 바로 정지 · 세션을 끊는다 (#790)
          title: '탈취 의심 · 계정 정지',
          detail: `${name} · 처음 보는 브라우저 (${code})`,
          tip: [
            `${name} · ${detected ? browserOf(detected.device) : '처음 보는 브라우저'}`,
            `경계 중 로그인 성공 → 바로 정지 · 세션 끊김 (${code})`,
          ],
          caseId: alert.insiderCaseId,
          attemptKey: detected ? attemptKey(detected) : null,
        })
      }
      if (merged && inRange(merged.occurredAt)) {
        found.push({
          // 같은 열린 경보에 로그인이 또 묶이면 다른 사건 — 경보 번호만 쓰면 레이더가 이미 띄운 사건으로 본다 (#791 Codex 3차 리뷰)
          key: `relogin-${alert.alertId}-${attemptKey(merged)}`,
          kind: 'relogin',
          at: merged.occurredAt,
          title: `같은 계정 ${browserOf(merged.device)} 로그인`,
          detail: '같은 탈취 의심 경보에 묶임',
          tip: [`${name} · ${merged.device}`, '새 경보 없이 같은 경보에 묶음'],
          caseId: alert.insiderCaseId,
          attemptKey: attemptKey(merged),
        })
      }
    } else if (code === 'SEC-INS-05' && inRange(alert.createdAt)) {
      found.push({
        key: `block-${alert.alertId}`,
        kind: 'block',
        at: alert.createdAt,
        title: '내보내기 차단 · 계정 정지',
        detail: `${name} · ${code} · 빼낸 데이터 0건`,
        tip: [`${name} · 거래 내역 내보내기 시도`, `차단 · 세션 끊김 · 계정 정지 (${code})`],
        caseId: alert.insiderCaseId,
        attemptKey: null,
      })
    }
  }

  const order: Record<IncidentKind, number> = { entry: 0, takeover: 1, relogin: 2, block: 3 }
  return found
    .sort((a, b) => ms(a.at) - ms(b.at) || order[a.kind] - order[b.kind])
    .map((x, i) => ({ ...x, n: i + 1 }))
}

/**
 * 최근 시도 버퍼 (#791 Codex 리뷰) — 서버는 최근 40건만 주므로, 2초에 40건이 넘으면 화면 시계가 아직 닿지 않은 시도가
 * 새 응답에서 밀려나 한 번도 안 보인다. 새 응답과 합치고(같은 줄은 새 값 — 탈취 의심 표시가 늦게 붙을 수 있다),
 * 아직 안 보인 줄은 모두, 이미 보인 줄은 최신 keepShown개만 남긴다. 최신이 앞
 * oldestMs(서버 ATTEMPT_LOOKBACK, 10분 전)보다 오래된 줄은 버린다 — 새 시도가 없을 때 지난 기록이 "최근 10분"에 남지 않게 (#791 Codex 2차 리뷰)
 */
export function mergeAttempts(
  buffer: LoginSecurityAttempt[],
  incoming: LoginSecurityAttempt[],
  opts: { viewNowMs: number; keepShown: number; oldestMs: number },
): LoginSecurityAttempt[] {
  const byKey = new Map<string, LoginSecurityAttempt>()
  for (const a of buffer) byKey.set(attemptKey(a), a)
  for (const a of incoming) byKey.set(attemptKey(a), a)
  const all = [...byKey.values()]
    .filter((a) => ms(a.occurredAt) > opts.oldestMs)
    .sort((a, b) => ms(b.occurredAt) - ms(a.occurredAt))
  const pending = all.filter((a) => ms(a.occurredAt) > opts.viewNowMs)
  const shown = all.filter((a) => ms(a.occurredAt) <= opts.viewNowMs).slice(0, opts.keepShown)
  return [...pending, ...shown]
}

export type LaunchKind = 'unknown' | 'fail' | 'ok' | 'suspected'

/**
 * 레이더에 날릴 점 하나 — arriveAt은 이 브라우저 시계(ms) 기준 가운데 닿는 시각.
 * upgrade = 이미 날린 점에 탈취 의심이 늦게 붙음. 날아가는 중이면 그 점을, 이미 닿았으면 그 자리에서 탈취 의심으로 다시 보인다
 */
export type Launch = { key: string; arriveAt: number; kind: LaunchKind; upgrade?: true }

export const launchKind = (a: LoginSecurityAttempt): LaunchKind =>
  a.suspected ? 'suspected' : a.success ? 'ok' : a.account === 'UNKNOWN' ? 'unknown' : 'fail'

/**
 * 새로 받은 시도를 발생 간격 그대로 다시 보여 줄 시각을 정한다.
 * clockOffsetMs = 브라우저 시계 - 서버 시계. launched = 이미 날린 시도와 그때 종류.
 * 이미 날린 시도는 다시 날리지 않는다 — 단 그 뒤 탈취 의심이 붙었으면 upgrade로 한 번 더 보낸다(서버 판정은 로그인 커밋 뒤라 늦게 붙을 수 있다, #791 Codex 3차 리뷰).
 * serverNowMs보다 maxAgeMs 넘게 오래된 새 시도(멈췄다 다시 읽은 경우)는 날리지 않는다.
 */
export function scheduleReplay(
  attempts: LoginSecurityAttempt[],
  launched: ReadonlyMap<string, LaunchKind>,
  opts: {
    clockOffsetMs: number
    nowMs: number
    serverNowMs: number
    maxAgeMs: number
    delayMs?: number
  },
): Launch[] {
  const delay = opts.delayMs ?? REPLAY_DELAY_MS
  const arriveAt = (a: LoginSecurityAttempt) =>
    Math.max(opts.nowMs, ms(a.occurredAt) + opts.clockOffsetMs + delay)
  const result: Launch[] = []
  for (const a of [...attempts].sort((x, y) => ms(x.occurredAt) - ms(y.occurredAt))) {
    const key = attemptKey(a)
    const before = launched.get(key)
    const kind = launchKind(a)
    if (before === undefined) {
      if (ms(a.occurredAt) >= opts.serverNowMs - opts.maxAgeMs)
        result.push({ key, arriveAt: arriveAt(a), kind })
    } else if (kind === 'suspected' && before !== 'suspected') {
      result.push({ key, arriveAt: arriveAt(a), kind, upgrade: true })
    }
  }
  return result
}

/** 레이더 · 상태 띠가 쓰는 상태 이름 */
export const stateWord: Record<LoginSecurityState, string> = {
  NORMAL: '평소',
  CAUTION: '주의',
  UNDER_ATTACK: '로그인 경계 중',
}
