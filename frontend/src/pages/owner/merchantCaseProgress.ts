/**
 * 조치 결과 상세의 결제별 진행 단계.
 * 결제 요청 → 잠시 보류 → 담당자 판정 → 실제 결과 네 칸으로 보여주되, 확인된 단계까지만 채운다.
 * 직원 판정과 실제 결제 상태(P5)는 따로 온다. 판정만 났다고 매입 완료로 보이지 않게 한다 (#205).
 * 테스트가 Node 타입 제거로 불러오므로 import에 `.ts`를 붙인다 (tests/README.md).
 */
import type { MerchantCaseStatus, MerchantPaymentResult } from '../../types/merchantCase.ts'
import type { StatusTone } from './statusTone.ts'

export type ProgressStep = {
  label: string
  state: 'done' | 'current' | 'skipped' | 'todo'
  tone: StatusTone
}

export type PaymentProgress = {
  steps: ProgressStep[]
  /** 사장님에게 먼저 보여줄 한 줄 결과 */
  result: string
  tone: StatusTone
}

const finalLabel: Partial<Record<NonNullable<MerchantPaymentResult['paymentStatus']>, string>> = {
  CAPTURED: '매입 완료',
  VOIDED: '승인 취소',
  DENIED: '결제 차단',
  DECLINED: '승인 거절',
  EXPIRED: '승인 만료',
  PENDING: '처리 중',
}

export function paymentProgress(p: MerchantPaymentResult): PaymentProgress {
  const requested: ProgressStep = { label: '결제 요청', state: 'done', tone: 'success' }
  const held: ProgressStep = { label: '잠시 보류', state: 'done', tone: 'success' }

  // 고객이 가맹점에서 직접 취소한 결제는 담당자 판정 없이 끝난다 (#439)
  if (p.customerCanceled)
    return {
      steps: [
        requested,
        held,
        {
          label: p.decision ? decisionLabel(p.decision) : '판정 없음',
          state: p.decision ? 'done' : 'skipped',
          tone: p.decision ? 'success' : 'waiting',
        },
        { label: '고객 취소', state: 'done', tone: 'waiting' },
      ],
      result: '고객이 직접 취소했어요',
      tone: 'waiting',
    }

  const decision: ProgressStep = p.decision
    ? { label: decisionLabel(p.decision), state: 'done', tone: 'success' }
    : { label: '담당자 확인 중', state: 'current', tone: 'progress' }

  if (p.paymentStatus === null)
    return {
      steps: [requested, held, decision, { label: '확인 불가', state: 'todo', tone: 'waiting' }],
      result: '결제 상태를 확인하지 못했어요',
      tone: 'waiting',
    }

  if (p.paymentStatus === 'HELD')
    return p.decision
      ? {
          steps: [
            requested,
            held,
            decision,
            { label: '반영 중', state: 'current', tone: 'progress' },
          ],
          result:
            p.decision === 'ACCEPT'
              ? '정상 처리로 결정됐고 매입을 진행하고 있어요'
              : '취소로 결정됐고 승인 취소를 진행하고 있어요',
          tone: 'progress',
        }
      : {
          steps: [requested, held, decision, { label: '결과', state: 'todo', tone: 'waiting' }],
          result: '담당자가 확인하고 있어요',
          tone: 'progress',
        }

  const label = finalLabel[p.paymentStatus] ?? p.paymentStatus
  const captured = p.paymentStatus === 'CAPTURED'
  const tone: StatusTone = captured
    ? 'success'
    : p.paymentStatus === 'PENDING'
      ? 'progress'
      : 'danger'
  return {
    steps: [
      requested,
      held,
      // 판정 없이 결제가 끝났으면(만료 등) 판정 칸은 건너뛴 것으로 보여준다
      p.decision ? decision : { label: '판정 없음', state: 'skipped', tone: 'waiting' },
      { label, state: 'done', tone },
    ],
    result: captured
      ? '매입이 끝났어요. 정산 대상이에요'
      : p.paymentStatus === 'VOIDED'
        ? '매입 전에 승인이 취소돼 정산에 들어가지 않아요'
        : `${label} 상태예요`,
    tone,
  }
}

function decisionLabel(decision: NonNullable<MerchantPaymentResult['decision']>) {
  return decision === 'ACCEPT' ? '정상 처리 결정' : '취소 결정'
}

export type CaseConclusion = {
  /** 결제 수 · 확인된 금액 합계 (금액을 모르는 결제는 합계에서 빼고 따로 센다) */
  paymentCount: number
  totalAmount: number
  unknownAmountCount: number
  /** 결과별 묶음. 같은 결과끼리 건수와 금액을 더한다. 화면에 순서대로 쓴다 */
  groups: { result: string; tone: StatusTone; count: number; amount: number }[]
}

/** 상세 맨 위 한 줄 결론. 결제별 진행 단계와 같은 판단(paymentProgress)으로 묶는다. */
export function caseConclusion(payments: MerchantPaymentResult[]): CaseConclusion {
  const groups = new Map<string, CaseConclusion['groups'][number]>()
  let totalAmount = 0
  let unknownAmountCount = 0
  for (const p of payments) {
    const progress = paymentProgress(p)
    const label = outcomeLabel(p, progress)
    const group = groups.get(label) ?? { result: label, tone: progress.tone, count: 0, amount: 0 }
    group.count += 1
    if (p.amount === null) unknownAmountCount += 1
    else {
      group.amount += p.amount
      totalAmount += p.amount
    }
    groups.set(label, group)
  }
  return {
    paymentCount: payments.length,
    totalAmount,
    unknownAmountCount,
    groups: [...groups.values()],
  }
}

/** 결제 한 건의 짧은 결과 이름 (확인 중 · 반영 중 · 매입 완료 · 승인 취소 · 고객 취소 …) */
export function paymentOutcome(p: MerchantPaymentResult) {
  const progress = paymentProgress(p)
  return { label: outcomeLabel(p, progress), tone: progress.tone }
}

/** 결론 줄에 쓰는 짧은 결과 이름 — 마지막 단계 이름을 쓰되 진행 중은 하나로 묶는다 */
function outcomeLabel(p: MerchantPaymentResult, progress: PaymentProgress) {
  if (p.paymentStatus === null && !p.customerCanceled) return '확인 불가'
  const last = progress.steps[progress.steps.length - 1]
  if (!last || last.state === 'todo' || last.state === 'current')
    return progress.tone === 'progress' && !p.decision ? '확인 중' : '반영 중'
  return last.label
}

/** 결과 이름별 문장 끝 — [이어질 때, 끝날 때] */
const phrases: Record<string, [string, string]> = {
  '매입 완료': ['정상 매입됐고', '정상 매입됐어요'],
  // 승인 취소는 매입 전 취소다. 지급 후 환불 회수와 다른 단계라 "환불"이라고 쓰지 않는다 (docs/settlement/merchant-guide.md)
  '승인 취소': ['매입 전에 승인이 취소됐고', '매입 전에 승인이 취소됐어요'],
  '고객 취소': ['고객이 직접 취소했고', '고객이 직접 취소했어요'],
  '반영 중': ['결정을 반영하는 중이고', '결정을 반영하고 있어요'],
  '확인 중': ['아직 확인 중이고', '아직 확인 중이에요'],
}
const phrase = (label: string, last: boolean) => {
  const known = phrases[label]
  if (known) return known[last ? 1 : 0]
  return last ? `${label} 상태예요` : `${label} 상태이고`
}
const wonText = (n: number) => `${n.toLocaleString('ko-KR')}원`

/**
 * 사건 결론을 한 문장으로. 예) "2건 116,000원은 정상 매입됐고, 1건 420,000원은 매입 전에 승인이 취소됐어요"
 * 결과가 하나뿐이면 "결제 2건 모두 정상 매입됐어요". 금액을 모르는 묶음은 건수만 쓴다.
 */
export function conclusionSentence(c: CaseConclusion) {
  if (c.groups.length === 0) return ''
  if (c.groups.length === 1)
    return `결제 ${c.paymentCount}건 모두 ${phrase(c.groups[0]!.result, true)}`
  return c.groups
    .map((g, i) => {
      const last = i === c.groups.length - 1
      const amount = g.amount > 0 ? ` ${wonText(g.amount)}` : ''
      return `${g.count}건${amount}은 ${phrase(g.result, last)}`
    })
    .join(', ')
}

export type HeldMoney = {
  key: 'checking' | 'accepting' | 'rejecting'
  label: string
  count: number
  amount: number
}[]

/**
 * 아직 처리가 끝나지 않은(결제 상태가 HELD인) 돈이 어디로 가는 중인지.
 * 담당자 확인 중 · 정상 결정 후 매입 진행 중 · 취소 결정 후 취소 진행 중으로 나눈다.
 * 이미 매입 · 취소가 끝난 결제와 고객이 직접 취소한 결제는 넣지 않는다. 금액을 모르는 결제는 건수만 센다.
 */
export function heldMoney(details: { payments: MerchantPaymentResult[] }[]): HeldMoney {
  const buckets: HeldMoney = [
    { key: 'checking', label: '담당자 확인 중', count: 0, amount: 0 },
    { key: 'accepting', label: '정상 결정 · 매입 진행 중', count: 0, amount: 0 },
    { key: 'rejecting', label: '취소 결정 · 취소 진행 중', count: 0, amount: 0 },
  ]
  for (const d of details)
    for (const p of d.payments) {
      if (p.paymentStatus !== 'HELD' || p.customerCanceled) continue
      const bucket = buckets[p.decision === 'ACCEPT' ? 1 : p.decision === 'REJECT' ? 2 : 0]!
      bucket.count += 1
      bucket.amount += p.amount ?? 0
    }
  return buckets
}

const shortResult: Record<string, string> = {
  '매입 완료': '정상',
  '승인 취소': '취소',
  '고객 취소': '고객 취소',
  '확인 중': '확인 중',
  '반영 중': '반영 중',
}

/** 목록 "결과" 칸 — 결과별 건수를 짧게. 예) "정상 2 · 취소 1", "확인 중 2" */
export function resultSummary(c: CaseConclusion) {
  return c.groups.map((g) => `${shortResult[g.result] ?? g.result} ${g.count}`).join(' · ')
}

/** 사건 상태 표시 — 관리 화면 공통 모양과 같다: 확인 중(반쯤 찬 원) · 반영 중(점선 원) · 끝남(체크) */
export const caseStatus: Record<MerchantCaseStatus, { word: string; tone: StatusTone }> = {
  INVESTIGATING: { word: '확인 중', tone: 'progress' },
  PROCESSING: { word: '결정 · 반영 중', tone: 'pending' },
  CLOSED: { word: '끝남', tone: 'success' },
}
