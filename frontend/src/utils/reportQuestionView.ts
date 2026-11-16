import type { BasisSignal, QuestionFact, ReportBody, ReportQuestion } from '../types/case.ts'
import { paymentCardId, reportFactLink, type SummaryLink } from './reportView.ts'

// 보고서 뒤 사건 질문 칸 (#753, ADR-0086) — 근거 칩 · 오늘 질문 수

// 서버 한도와 같다 (#752 AiReportQuestionWriter — 직원 · 보고서마다 최근 24시간, 실패 포함)
export const QUESTION_DAILY_LIMIT = 30
export const QUESTION_MAX_LENGTH = 300
// 질문 칸 id — 의심 카드 "이 의심에 대해 AI에게 묻기"가 이 칸으로 이동한다 (#819)
export const QUESTIONS_ID = 'report-questions'
const LIMIT_WINDOW_MS = 24 * 60 * 60 * 1000

// 자주 묻는 질문 — 누르면 입력창에 넣기만 한다 (실수로 눌러도 AI를 부르지 않게)
// 마지막은 판정 질문 — 판정은 직원이 하고 확인할 것을 보여 준다는 것을 알 수 있게 둔다
export const QUESTION_EXAMPLES = [
  '신규 기기로 한 결제는?',
  '같은 IP 결제는 언제였나요?',
  '이 고객 평소 결제는?',
  '결제 사이 간격은?',
  '이 결제를 취소할까요?',
  // 판정 질문에 AI가 답하지 않고 확인할 것을 보여 주는 것을 시연에서 보이려고 둘 다 둔다 (#819, 11/14 사용자)
  '이 결제를 승인할까요?',
] as const

// 의심 카드 "이 의심에 대해 AI에게 묻기" (#819) — 질문 칸에 채우기만 한다. 보내기는 직원이 [묻기]
// 의심의 사실을 묻는 질문만 둔다. 판정 질문은 넣지 않는다 (판정은 보안 직원)
const SUSPICION_QUESTIONS: Record<string, string> = {
  R01: '이 고객이 예전에도 비슷한 금액으로 결제한 적 있나요?',
  R02: '신규 기기로 한 결제는 언제였나요?',
  R03: '신규 카드로 한 결제는 언제였나요?',
  R04: '결제 사이 간격은 어떻게 되나요?',
  R06: '소액 결제가 몇 번, 얼마 사이에 있었나요?',
  R07: '차단 목록과 겹친 결제는 어느 결제인가요?',
  R08: '거절된 결제와 다시 시도한 결제 사이 간격은?',
  LINKED_CARD: '같은 카드를 쓴 다른 고객 결제는 언제였나요?',
  LINKED_DEVICE: '같은 기기를 쓴 다른 고객 결제는 언제였나요?',
  LINKED_IP: '같은 IP를 쓴 다른 고객 결제는 언제였나요?',
}

export function askQuestionFor(signal: Pick<BasisSignal, 'key' | 'label'>): string {
  const fixed = SUSPICION_QUESTIONS[signal.key]
  if (fixed) return fixed
  // 해외 접속 · 고위험 국가 · 모르는 규칙 — 징후 이름으로 묻는다 (이름이 길어도 질문 한도를 넘지 않게 자른다)
  const label = signal.label.slice(0, 100)
  return `'${label}' 징후가 있는 결제는 언제였나요?`
}

// 근거 사실 → 이동할 보고서 카드. 카드가 없으면(계산값 · 보고서 밖 결제) null
// 결제 줄은 그 결제가 이 보고서 결제 표에 있을 때만 — 새로 조회한 다른 결제는 보고서에 줄이 없다
export function factLink(fact: QuestionFact, body: ReportBody): SummaryLink | null {
  if (fact.source === 'COMPUTED') return null
  if (fact.source === 'REPORT') {
    const saved = body.facts?.find((f) => f.id === fact.id)
    if (saved) return reportFactLink(saved)
  }
  return paymentRowLink(fact.paymentId, body)
}

function paymentRowLink(paymentId: string | null | undefined, body: ReportBody) {
  if (!paymentId || !body.paymentAnalyses.some((p) => p.paymentId === paymentId)) return null
  return { target: paymentCardId(paymentId), label: `결제 ${paymentId}` }
}

// 보고서에 없던 값 표시 — 새로 조회한 값은 조회 시각과 함께, 계산값은 "계산"
// 보고서 사실 · 결제 표 값은 표시 없음 (카드 이동 칩만)
export type FactBadge = { label: string; retrievedAt: string | null }

const TOOL_LABELS: Record<string, string> = {
  get_payment_history: '고객 이력',
  find_linked_payments: '같은 카드 · 기기 · IP',
}

export function factBadge(fact: QuestionFact): FactBadge | null {
  if (fact.source === 'COMPUTED') return { label: '계산', retrievedAt: null }
  if (fact.source !== 'ADDED' && fact.source !== 'PREVIOUS') return null
  const tool = fact.toolName ? TOOL_LABELS[fact.toolName] : undefined
  return {
    label: tool ? `새로 조회 · ${tool}` : '새로 조회',
    retrievedAt: fact.retrievedAt ?? null,
  }
}

// 최근 24시간 질문 수 — 서버처럼 실패 · 진행 중도 센다
export function questionsToday(questions: ReportQuestion[], now: number): number {
  const since = now - LIMIT_WINDOW_MS
  return questions.filter((q) => Date.parse(q.askedAt) > since).length
}

// 답 문장 하나의 근거 칩 — 같은 카드 · 같은 표시는 한 번만
export type FactChip = { kind: 'link'; link: SummaryLink } | { kind: 'badge'; badge: FactBadge }

export function sentenceChips(
  factIds: string[],
  facts: QuestionFact[],
  body: ReportBody,
): FactChip[] {
  const byId = new Map(facts.map((f) => [f.id, f]))
  const chips: FactChip[] = []
  const seen = new Set<string>()
  for (const id of factIds) {
    const fact = byId.get(id)
    if (!fact) continue
    const badge = factBadge(fact)
    if (badge) {
      const key = `badge:${badge.label}:${badge.retrievedAt ?? ''}`
      if (!seen.has(key)) chips.push({ kind: 'badge', badge })
      seen.add(key)
      continue
    }
    const link = factLink(fact, body)
    if (link && !seen.has(link.target)) chips.push({ kind: 'link', link })
    if (link) seen.add(link.target)
  }
  return chips
}
