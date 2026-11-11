import type { AiSwitchStatus } from '../api/aiSwitch.ts'
import type { OperatorAccessSummary } from '../api/fds.ts'

/** 관리자 홈 상태 줄의 한 칸 색. ok = 이상 없음, warn = 확인 필요, alert = 기능이 멈춤 */
export type HomeTone = 'ok' | 'warn' | 'alert'

/** AI 조사 상태 (ADR-0083). 설정이 꺼져 있으면 스위치와 관계없이 고정 보고서다 */
export function aiStatusView(status: AiSwitchStatus): {
  tone: HomeTone
  label: string
  detail: string
} {
  if (!status.switchOn) {
    return {
      tone: 'alert',
      label: '긴급정지 중',
      detail: '새 보고서는 Spring 고정 보고서로 만듭니다',
    }
  }
  if (!status.configEnabled) {
    return {
      tone: 'warn',
      label: 'AI 서버 꺼짐',
      detail: '서버 설정이 꺼져 고정 보고서로 만듭니다',
    }
  }
  return { tone: 'ok', label: '정상 작동', detail: 'AI 조사 보고서를 만들고 있습니다' }
}

/** 꺼진 탐지 룰 수 — 꺼진 룰은 이상 거래 탐지에서 빠진다 */
export function ruleStatusView(disabled: number): {
  tone: HomeTone
  label: string
  detail: string
} {
  return disabled === 0
    ? { tone: 'ok', label: '모두 사용 중', detail: '모든 탐지 룰이 결제를 평가합니다' }
    : {
        tone: 'warn',
        label: `${disabled.toLocaleString('ko-KR')}개 꺼짐`,
        detail: '꺼진 룰은 이상 거래 탐지에서 빠집니다',
      }
}

/**
 * 내부자 보안 — 열린 사건은 직원 계정 정지와 이어지므로 빨강, 열린 경보만 있으면 주황.
 * 값은 사이드바 배지가 센 수를 쓴다(null = 세는 중).
 */
export function insiderStatusView(
  alerts: number | null,
  cases: number | null,
): { tone: HomeTone; label: string; detail: string } | null {
  if (alerts === null || cases === null) return null
  const detail = `열린 경보 ${alerts.toLocaleString('ko-KR')}건 · 사건 ${cases.toLocaleString('ko-KR')}건`
  if (cases > 0)
    return { tone: 'alert', label: `사건 ${cases.toLocaleString('ko-KR')}건 진행 중`, detail }
  if (alerts > 0) return { tone: 'warn', label: `경보 ${alerts.toLocaleString('ko-KR')}건`, detail }
  return { tone: 'ok', label: '이상 없음', detail: '열린 내부자 경보 · 사건이 없습니다' }
}

const weekday = ['일', '월', '화', '수', '목', '금', '토']

/**
 * 차트 날짜 칸 이름 — 오늘이면 "오늘", 아니면 "11.9 (월)".
 * 날짜만 있는 값(한국 날짜)이라 UTC 자정으로 읽고 UTC 요일을 쓴다. 한국 자정으로 만들면 UTC로는 전날이다 (PR #774 리뷰)
 */
export function chartDayLabel(date: string, today: string) {
  if (date === today) return '오늘'
  const [, month, day] = date.split('-').map(Number)
  return `${month}.${day} (${weekday[new Date(`${date}T00:00:00Z`).getUTCDay()]})`
}

/** 오늘 날짜(한국 시간, YYYY-MM-DD) */
export function seoulToday(now: Date) {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul' }).format(now)
}

/**
 * 사장님 계정 접근 이상 — 홈에서는 정보로만 보인다(배지는 자동 정지 계정 수).
 * 요약은 최근 7일(한국 날짜) 거부 요청이고, 오늘 칸이 없으면 0건이다.
 */
export function operatorAccessView(
  summary: OperatorAccessSummary,
  today: string,
): { tone: HomeTone; today: number; week: number; accounts: number } {
  const todayCount = summary.daily.find((day) => day.date === today)?.count ?? 0
  return {
    tone: todayCount > 0 ? 'warn' : 'ok',
    today: todayCount,
    week: summary.deniedCount,
    accounts: summary.accountCount,
  }
}

/**
 * count: 건수, null = 세는 중, 'error' = 불러오지 못함.
 * urgent: 보안 항목(내부자 · 자동 정지) — 남아 있으면 주황 대신 빨강으로 먼저 보이게 한다
 */
export type TodoItem = {
  label: string
  to: string
  count: number | null | 'error'
  urgent?: boolean
}
export type TodoGroup = { title: string; items: TodoItem[] }

/** 처리할 일 합계. 아직 세는 중(null)인 칸은 빼고 센다 */
export function todoTotal(groups: TodoGroup[]) {
  return groups.reduce(
    (sum, group) =>
      sum +
      group.items.reduce(
        (part, item) => part + (typeof item.count === 'number' ? item.count : 0),
        0,
      ),
    0,
  )
}

/** 불러오지 못한 칸이 있는지 — 있으면 "모두 처리됨"을 보이지 않는다 (PR #774 리뷰) */
export function todoFailed(groups: TodoGroup[]) {
  return groups.some((group) => group.items.some((item) => item.count === 'error'))
}

/** 아직 세는 칸이 있는지 */
export function todoCounting(groups: TodoGroup[]) {
  return groups.some((group) => group.items.some((item) => item.count === null))
}

/** "확인 필요" 줄 — 남아 있는 처리할 일만, 보안 항목(urgent)을 먼저 */
export function attentionItems(groups: TodoGroup[]) {
  const open = groups.flatMap((group) =>
    group.items.flatMap((item) =>
      typeof item.count === 'number' && item.count > 0
        ? [{ label: item.label, to: item.to, count: item.count, urgent: item.urgent === true }]
        : [],
    ),
  )
  return [...open.filter((item) => item.urgent), ...open.filter((item) => !item.urgent)]
}
