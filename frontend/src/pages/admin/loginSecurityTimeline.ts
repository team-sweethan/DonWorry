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
      const mine = suspected
        .filter((a) => a.staffId === alert.staffId)
        .sort((a, b) => ms(a.occurredAt) - ms(b.occurredAt))
      // 경보가 잡은 로그인 = 경보 생성 직전 의심 로그인. 그 뒤 의심 로그인은 열린 경보에 묶인 것
      const detected = mine.filter((a) => ms(a.occurredAt) <= ms(alert.createdAt)).at(-1)
      if (inRange(alert.createdAt)) {
        found.push({
          key: `takeover-${alert.alertId}`,
          kind: 'takeover',
          at: alert.createdAt,
          title: '탈취 의심 · 높음',
          detail: `${name} · 처음 보는 브라우저 (${code})`,
          tip: [
            `${name} · ${detected ? browserOf(detected.device) : '처음 보는 브라우저'}`,
            `경계 중 로그인 성공 (${code})`,
          ],
          caseId: alert.insiderCaseId,
          attemptKey: detected ? attemptKey(detected) : null,
        })
      }
      for (const later of mine.filter((a) => ms(a.occurredAt) > ms(alert.createdAt))) {
        if (!inRange(later.occurredAt)) continue
        found.push({
          key: `relogin-${attemptKey(later)}`,
          kind: 'relogin',
          at: later.occurredAt,
          title: `같은 계정 ${browserOf(later.device)} 로그인`,
          detail: '열린 탈취 의심 경보에 묶임',
          tip: [`${name} · ${later.device}`, '새 경보 없이 열린 경보에 묶음'],
          caseId: alert.insiderCaseId,
          attemptKey: attemptKey(later),
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

export type LaunchKind = 'unknown' | 'fail' | 'ok' | 'suspected'

/** 레이더에 날릴 점 하나 — arriveAt은 이 브라우저 시계(ms) 기준 가운데 닿는 시각 */
export type Launch = { key: string; arriveAt: number; kind: LaunchKind }

export const launchKind = (a: LoginSecurityAttempt): LaunchKind =>
  a.suspected ? 'suspected' : a.success ? 'ok' : a.account === 'UNKNOWN' ? 'unknown' : 'fail'

/**
 * 새로 받은 시도를 발생 간격 그대로 다시 보여 줄 시각을 정한다.
 * clockOffsetMs = 브라우저 시계 - 서버 시계. 이미 본 시도와 serverNowMs보다 maxAgeMs 넘게 오래된 시도(멈췄다 다시 읽은 경우)는 날리지 않는다.
 */
export function scheduleReplay(
  attempts: LoginSecurityAttempt[],
  seen: ReadonlySet<string>,
  opts: {
    clockOffsetMs: number
    nowMs: number
    serverNowMs: number
    maxAgeMs: number
    delayMs?: number
  },
): Launch[] {
  const delay = opts.delayMs ?? REPLAY_DELAY_MS
  return attempts
    .filter((a) => !seen.has(attemptKey(a)) && ms(a.occurredAt) >= opts.serverNowMs - opts.maxAgeMs)
    .sort((a, b) => ms(a.occurredAt) - ms(b.occurredAt))
    .map((a) => ({
      key: attemptKey(a),
      arriveAt: Math.max(opts.nowMs, ms(a.occurredAt) + opts.clockOffsetMs + delay),
      kind: launchKind(a),
    }))
}

/** 레이더 · 상태 띠가 쓰는 상태 이름 */
export const stateWord: Record<LoginSecurityState, string> = {
  NORMAL: '평소',
  CAUTION: '주의',
  UNDER_ATTACK: '로그인 경계 중',
}
