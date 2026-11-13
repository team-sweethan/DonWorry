import type { CSSProperties } from 'react'

const won = (n: number) => `${n.toLocaleString('ko-KR')}원`
const delay = (ms: number) => ({ '--ow-delay': `${ms}ms` }) as CSSProperties

/** 상세를 다 받았으면 보류 중 결제 건수 · 금액, 아직이면 사건 수만. 금액을 모르는 결제가 섞이면(null) 건수만 */
const stageValue = (money: { count: number; amount: number | null } | null, cases: number) =>
  money
    ? money.count > 0
      ? money.amount === null
        ? `${money.count}건 · 금액 일부 확인 불가`
        : `${money.count}건 · ${won(money.amount)}`
      : '없음'
    : cases > 0
      ? `사건 ${cases}건`
      : '없음'

type Step = {
  key: string
  title: string
  desc: string
  value: string
  /** 지금 결제가 머물러 있는 단계 — 점에 물결이 두 번 퍼진다 */
  live: boolean
}

/**
 * 조치 결과 맨 위 "처리 과정" 보드 — 배달앱 주문 상태 바처럼 네 단계를 한 줄로 보여준다.
 * 결제 멈춤 → 담당자 확인 → 결정 반영 → 끝남. 각 단계에 지금 머물러 있는 건수 · 금액을 적는다.
 * 보여주기만 한다(거르기는 아래 필터 한 곳). 단계 설명은 docs/settlement/merchant-guide.md에 있는 내용만 쓴다.
 * 금액은 진행 중 사건 상세를 모두 받은 뒤에만 쓴다 — 그 전에는 건수만 보인다.
 */
export function MerchantCaseProcess({
  newToday,
  checking,
  applying,
  checkingCases,
  applyingCases,
  closedCases,
}: {
  newToday: number
  checking: { count: number; amount: number | null } | null
  applying: { count: number; amount: number | null } | null
  /** 금액을 아직 모를 때 쓰는 사건 수 */
  checkingCases: number
  applyingCases: number
  closedCases: number
}) {
  const steps: Step[] = [
    {
      key: 'held',
      title: '결제 멈춤',
      desc: '이상 거래가 의심되면 매입을 잠시 멈춰요',
      value: newToday > 0 ? `최근 24시간 새로 ${newToday}건` : '최근 24시간 새 사건 없음',
      live: false,
    },
    {
      key: 'checking',
      title: '담당자 확인',
      desc: '보안 담당자가 결제를 확인해요',
      value: stageValue(checking, checkingCases),
      live: checking ? checking.count > 0 : checkingCases > 0,
    },
    {
      key: 'applying',
      title: '결정 반영',
      desc: '정상은 매입, 취소는 매입 전 승인 취소',
      value: stageValue(applying, applyingCases),
      live: applying ? applying.count > 0 : applyingCases > 0,
    },
    {
      key: 'closed',
      title: '끝남',
      desc: '정상 매입은 3영업일 뒤 정산 예정',
      value: `사건 ${closedCases}건`,
      live: false,
    },
  ]
  return (
    <section className="mcp" aria-label="처리 과정">
      <span className="mcp-track" aria-hidden="true">
        <span className="mcp-fill" />
      </span>
      <ol className="mcp-steps">
        {steps.map((s, i) => (
          <li key={s.key} className={`mcp-step${s.live ? ' is-live' : ''}`} style={delay(i * 320)}>
            <span className="mcp-node" aria-hidden="true">
              {i + 1}
            </span>
            <strong className="mcp-title">{s.title}</strong>
            <span className="mcp-value">{s.value}</span>
            <span className="mcp-desc">{s.desc}</span>
          </li>
        ))}
      </ol>
    </section>
  )
}
