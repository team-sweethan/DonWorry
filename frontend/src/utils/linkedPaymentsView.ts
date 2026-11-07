import type { LinkedMatch, LinkedPayments } from '../types/case.ts'

// "다른 손님과 겹치는 것" 카드 (#352 · #637) — 숫자를 보안 직원이 바로 읽을 수 있는 문장으로 바꾼다
// 문장 · 설명은 모두 여기 고정 문장이다. LLM이 쓰지 않고, 같은 숫자면 언제나 같은 문장이 나온다
// 설명은 모든 경우를 적어 두고 이 보고서 숫자에 해당하는 것만 고른다. 사건이 수상한지는 말하지 않는다

export type LinkedKind = 'card' | 'device' | 'ip'

export const LINKED_KINDS: Array<{ key: LinkedKind; label: string }> = [
  { key: 'card', label: '카드' },
  { key: 'device', label: '기기' },
  { key: 'ip', label: '인터넷' },
]

// 60분 → "1시간", 90분 → "90분"
export function windowLabel(minutes: number): string {
  return minutes % 60 === 0 ? `${minutes / 60}시간` : `${minutes}분`
}

const SUBJECT: Record<LinkedKind, string> = {
  card: '이 카드로',
  device: '이 휴대폰 · 컴퓨터로',
  ip: '이 손님이 결제한 인터넷(와이파이 등)에서',
}

const NOT_PROVIDED: Record<LinkedKind, string> = {
  card: '카드 정보를 받지 못해 확인하지 못했습니다.',
  device: '기기 정보를 받지 못해 확인하지 못했습니다.',
  ip: '인터넷 정보를 받지 못해 확인하지 못했습니다.',
}

// 카드 한 줄의 문장 — 첫 문장은 30일, 둘째 문장은 전후 시간 (예전 보고서는 첫 문장만)
export function linkedSentences(
  kind: LinkedKind,
  match: LinkedMatch,
  windowMinutes: number | null,
): string[] {
  if (!match.provided) return [NOT_PROVIDED[kind]]
  if (match.paymentCount === 0) return [`${SUBJECT[kind]} 결제한 다른 손님은 없습니다.`]
  const lines = [
    `${SUBJECT[kind]} 다른 손님 ${match.otherCustomerCount}명이 ${match.paymentCount}번 결제했습니다.`,
  ]
  const nearby = match.nearby
  if (nearby && windowMinutes !== null) {
    const window = windowLabel(windowMinutes)
    lines.push(
      nearby.paymentCount === 0
        ? `이 결제 전후 ${window} 안에는 없었습니다.`
        : `그중 ${nearby.otherCustomerCount}명(${nearby.paymentCount}번)은 이 결제 전후 ${window} 안에 결제했습니다.`,
    )
  }
  return lines
}

export type LinkedExplanationKey =
  | 'card'
  | 'device'
  | 'ipBurst'
  | 'ipSpread'
  | 'ipShared'
  | 'declined'
  | 'notProvided'
  | 'results'
  | 'privacy'

export type LinkedExplanation = { key: LinkedExplanationKey; title: string; body: string }

// 적어 둔 설명 전부 — 보이는 조건은 linkedExplanations
const EXPLANATIONS: Record<LinkedExplanationKey, Omit<LinkedExplanation, 'key'>> = {
  card: {
    title: '카드가 다른 손님과 겹칠 때',
    body: '한 카드를 여러 계정이 썼다는 뜻입니다. 가족이 아니라면 드문 일이라, 징후 계산에서도 고위험 징후로 봅니다.',
  },
  device: {
    title: '기기가 다른 손님과 겹칠 때',
    body: '한 휴대폰 · 컴퓨터로 여러 계정이 결제했다는 뜻입니다. 가족이 같이 쓰는 기기가 아니라면 드문 일이라 고위험 징후로 봅니다.',
  },
  ipBurst: {
    title: '인터넷이 겹치고 짧은 시간에 몰렸을 때',
    body: '짧은 시간에 여러 계정이 같은 인터넷에서 결제했습니다. 한 사람이 계정을 바꿔 가며 결제했을 수도 있습니다. 주문 내용 · 이 손님의 최근 결제와 함께 확인해 보세요.',
  },
  ipSpread: {
    title: '인터넷이 겹치지만 띄엄띄엄일 때',
    body: '회사 · 카페 · 아파트처럼 여러 사람이 와이파이 하나를 같이 쓰면 흔히 생깁니다. 한 달에 걸쳐 흩어져 있으면 그런 공용 인터넷일 가능성이 큽니다.',
  },
  // 전후 시간 숫자가 없는 예전 보고서용
  ipShared: {
    title: '인터넷이 다른 손님과 겹칠 때',
    body: '회사 · 카페 · 아파트처럼 여러 사람이 와이파이 하나를 같이 쓰면 흔히 생깁니다. 이것만으로 한 사람이 여러 계정을 썼다고 볼 수는 없습니다.',
  },
  declined: {
    title: '카드사가 거절한 결제가 있을 때',
    body: '겹친 결제 중 카드사가 승인을 거절한 결제가 있습니다. 거절 이유(도난 신고 · 한도 등)는 알 수 없어 카드사에 문의해야 합니다.',
  },
  notProvided: {
    title: '정보를 받지 못했을 때',
    body: '가맹점이 카드 · 기기 · 인터넷 정보를 보내지 않아 확인하지 못한 것이 있습니다. 정보가 없다는 것 자체는 위험 근거가 아닙니다.',
  },
  results: {
    title: '겹친 결제들의 FDS 결과',
    body: '겹친 결제들이 결제 당시 FDS에서 받은 결과입니다 (허용 · 검토 · 차단).',
  },
  privacy: {
    title: '다른 손님 개인정보',
    body: '다른 손님 개인정보 보호를 위해 개별 결제 내용은 보여 주지 않고 건수만 보여 줍니다.',
  },
}

function overlaps(match: LinkedMatch): boolean {
  return match.provided && match.paymentCount > 0
}

// 이 보고서 숫자에 해당하는 설명만 고른다. 없으면 빈 목록 (화면은 "어떻게 보나요?"를 숨긴다)
export function linkedExplanations(linked: LinkedPayments): LinkedExplanation[] {
  const keys: LinkedExplanationKey[] = []
  if (overlaps(linked.card)) keys.push('card')
  if (overlaps(linked.device)) keys.push('device')
  if (overlaps(linked.ip)) {
    const nearby = linked.ip.nearby
    if (!nearby || linked.nearbyWindowMinutes == null) keys.push('ipShared')
    else keys.push(nearby.paymentCount > 0 ? 'ipBurst' : 'ipSpread')
  }
  const kinds = [linked.card, linked.device, linked.ip]
  if (kinds.some((m) => overlaps(m) && m.declinedCount > 0)) keys.push('declined')
  if (kinds.some((m) => !m.provided)) keys.push('notProvided')
  if (kinds.some(overlaps)) keys.push('results', 'privacy')
  return keys.map((key) => ({ key, ...EXPLANATIONS[key] }))
}
