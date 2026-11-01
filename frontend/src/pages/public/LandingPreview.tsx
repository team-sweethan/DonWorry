import { useEffect, useRef, useState, type ReactNode, type RefObject } from 'react'
import {
  AuditOutlined,
  BankOutlined,
  CalendarOutlined,
  CheckCircleFilled,
  ClockCircleOutlined,
  CreditCardOutlined,
  FundOutlined,
  GlobalOutlined,
  LockFilled,
  ReloadOutlined,
  RobotOutlined,
  WalletOutlined,
} from '@ant-design/icons'
import { BrandMark } from '../../components/brand/BrandMark'

// 랜딩에서 보여 주는 예시 화면이다. 메뉴·상태·판정 이름은 실제 화면(navigation.ts · ownerLabels.ts ·
// caseLabels.ts)과 맞춘다. 숫자와 가맹점은 예시라 스크린 리더에는 읽히지 않게 둔다.

type Tone = 'red' | 'amber' | 'blue' | 'green' | 'gray'
type CaseRow = {
  id: number
  status: { label: string; tone: Tone }
  payments: number
  amount: string
  time: string
}

const pastCases: CaseRow[] = [
  {
    id: 1039,
    status: { label: '조사 종료', tone: 'gray' },
    payments: 2,
    amount: '324,000원',
    time: '10/29 11:18',
  },
  {
    id: 1036,
    status: { label: '조사 종료', tone: 'gray' },
    payments: 1,
    amount: '89,000원',
    time: '10/28 19:57',
  },
  {
    id: 1031,
    status: { label: '조사 종료', tone: 'gray' },
    payments: 1,
    amount: '158,000원',
    time: '10/27 16:40',
  },
  {
    id: 1027,
    status: { label: '조사 종료', tone: 'gray' },
    payments: 3,
    amount: '96,500원',
    time: '10/26 13:12',
  },
]

// 첫 화면에서 번갈아 보여 주는 두 결말 — 취소 결정과 정상 처리 결정
const scenarios = [
  {
    amount: '1,280,000원',
    reason: '해외 IP 고액 결제',
    decision: { label: '취소 결정', tone: 'red' as Tone },
    payment: { label: '승인 취소', tone: 'red' as Tone },
    note: '결제를 취소해서 피해가 생기지 않았어요',
  },
  {
    amount: '420,000원',
    reason: '평소보다 큰 금액',
    decision: { label: '정상 처리 결정', tone: 'green' as Tone },
    payment: { label: '매입 완료', tone: 'green' as Tone },
    note: '문제없는 결제라 정상 처리했어요',
  },
]

/** 이야기 단계: 보류된 결제가 Case로 들어옴 → 판정 → 실제 처리 완료. */
type Phase = 'arrive' | 'decide' | 'close'
const timeline: { phase: Phase; at: number }[] = [
  { phase: 'arrive', at: 0 },
  { phase: 'decide', at: 2400 },
  { phase: 'close', at: 4800 },
]
const LOOP_MS = 9000
const phaseIndex = (phase: Phase) => timeline.findIndex((step) => step.phase === phase)

/** 화면에 보이는 동안만 이야기를 돌린다. 움직임을 줄였으면 판정이 나온 장면에서 멈춘다. */
function useStory(target: RefObject<HTMLElement | null>) {
  const [still] = useState(() => window.matchMedia('(prefers-reduced-motion: reduce)').matches)
  const [phase, setPhase] = useState<Phase>(still ? 'close' : 'arrive')
  const [round, setRound] = useState(0)
  useEffect(() => {
    const element = target.current
    if (!element || still) return
    let timers: number[] = []
    let loop = 0
    const play = () => {
      timers.forEach(window.clearTimeout)
      timers = timeline.map((step) =>
        window.setTimeout(() => {
          setRound(loop)
          setPhase(step.phase)
        }, step.at),
      )
      timers.push(
        window.setTimeout(() => {
          loop += 1
          play()
        }, LOOP_MS),
      )
    }
    const stop = () => {
      timers.forEach(window.clearTimeout)
      timers = []
    }
    const observer = new IntersectionObserver(([entry]) => {
      if (entry.isIntersecting && !document.hidden) play()
      else stop()
    })
    observer.observe(element)
    return () => {
      observer.disconnect()
      stop()
    }
  }, [target, still])
  return { phase, scenario: scenarios[round % scenarios.length], round }
}

// 사장님 메뉴 (navigation.ts의 owner)
const ownerNav: { title: string; items: { icon: ReactNode; label: string }[] }[] = [
  { title: '결제 보안', items: [{ icon: <AuditOutlined />, label: '조치 결과' }] },
  {
    title: '자금관리',
    items: [
      { icon: <CalendarOutlined />, label: '정산 내역' },
      { icon: <WalletOutlined />, label: '자금 현황' },
      { icon: <BankOutlined />, label: '연계 대출 상품' },
    ],
  },
]

const caseStatus: Record<Phase, { label: string; tone: Tone }> = {
  arrive: { label: '조사 중', tone: 'blue' },
  decide: { label: '판정 완료 · 처리 진행 중', tone: 'amber' },
  close: { label: '조사 종료', tone: 'gray' },
}

/** 첫 화면 오른쪽 — 사장님 조치 결과 화면. 보류된 결제가 조사되고 처리되는 장면. */
export function OwnerHeroPreview() {
  const root = useRef<HTMLDivElement>(null)
  const { phase, scenario, round } = useStory(root)
  const step = phaseIndex(phase)
  const closed = step >= phaseIndex('close')
  const rejected = scenario.decision.tone === 'red'
  const story: CaseRow = {
    id: 1043 + round,
    status: caseStatus[phase],
    payments: 1,
    amount: scenario.amount,
    time: '10/29 14:31',
  }
  return (
    <div className={`dw-preview-app is-owner is-${phase}`} aria-hidden="true" ref={root}>
      <aside className="dw-preview-sidebar">
        <div className="dw-preview-brand">
          <BrandMark size={20} />
          돈워리
        </div>
        {ownerNav.map((group) => (
          <div key={group.title}>
            <div className="dw-preview-group">{group.title}</div>
            {group.items.map((item) => (
              <div
                key={item.label}
                className={`dw-preview-nav${item.label === '조치 결과' ? ' is-active' : ''}`}
              >
                {item.icon}
                {item.label}
              </div>
            ))}
          </div>
        ))}
      </aside>
      <div className="dw-preview-main">
        <div className="dw-preview-topbar">
          <span className="dw-preview-shop">케이크샵 강남점</span>
          <span className="dw-preview-user">
            <span>김</span>사장님 김사장
          </span>
        </div>
        <div className="dw-preview-body">
          <div className="dw-preview-title">
            <strong>조치 결과</strong>
            <span>이상 거래로 보류된 결제가 어떻게 처리되었는지 확인합니다.</span>
            <i className="dw-preview-refresh">
              <ReloadOutlined /> 새로고침
            </i>
          </div>
          <div className="dw-preview-owner-stats">
            <OwnerStat label="조사 중" value={step === 0 ? 1 : 0} />
            <OwnerStat
              label="정상 처리 결정"
              value={12 + (closed && !rejected ? 1 : 0)}
              tone="green"
            />
            <OwnerStat label="취소 결정" value={3 + (closed && rejected ? 1 : 0)} tone="red" />
          </div>
          <div className="dw-preview-table">
            <div className="dw-preview-row is-head">
              <span>Case ID</span>
              <span>상태</span>
              <span>연관 결제</span>
              <span className="is-num">금액</span>
              <span>발생 시각</span>
            </div>
            {[story, ...pastCases].map((row, index) => (
              <div key={row.id} className={`dw-preview-row${index === 0 ? ' is-story' : ''}`}>
                <span>
                  <strong>CASE-{row.id}</strong>
                </span>
                <span>
                  <span
                    key={index === 0 ? phase : undefined}
                    className={`dw-preview-tag is-${row.status.tone}`}
                  >
                    {row.status.label}
                  </span>
                </span>
                <span>{row.payments}건</span>
                <span className="is-num">{row.amount}</span>
                <span className="dw-preview-time">{row.time}</span>
              </div>
            ))}
          </div>
        </div>
      </div>

      {step >= phaseIndex('decide') && (
        <div className="dw-story-detail" key={`detail-${round}`}>
          <div className="dw-preview-card-head">
            <AuditOutlined />
            <strong>CASE-{story.id} · 결제별 결과</strong>
          </div>
          <dl>
            <div>
              <dt>결제</dt>
              <dd>
                PAY-{8841 + round} · {scenario.amount}
              </dd>
            </div>
            <div>
              <dt>탐지 내용</dt>
              <dd>{scenario.reason}</dd>
            </div>
            <div>
              <dt>보안 담당자 판정</dt>
              <dd>
                <span className={`dw-preview-tag is-${scenario.decision.tone}`}>
                  {scenario.decision.label}
                </span>
              </dd>
            </div>
            <div>
              <dt>실제 결제 상태</dt>
              <dd>
                <span
                  key={closed ? 'closed' : 'held'}
                  className={`dw-preview-tag is-${closed ? scenario.payment.tone : 'amber'}`}
                >
                  {closed ? scenario.payment.label : '매입 보류'}
                </span>
              </dd>
            </div>
          </dl>
          {closed && (
            <p className="dw-story-note">
              <CheckCircleFilled /> {scenario.note}
            </p>
          )}
        </div>
      )}
    </div>
  )
}

function OwnerStat({ label, value, tone }: { label: string; value: number; tone?: Tone }) {
  return (
    <div className={`dw-preview-stat${tone ? ` is-${tone}` : ''}`}>
      <small>{label}</small>
      <b key={value}>{value}</b>
    </div>
  )
}

/* ── 사장님이 쓰는 화면 카드 ── */

/** 조치 결과 — 실제 화면 요약 */
export function OwnerResultCard() {
  const rows = [
    {
      id: 1043,
      label: '조사 종료',
      decision: '취소 결정',
      tone: 'red' as Tone,
      amount: '1,280,000원',
    },
    {
      id: 1039,
      label: '조사 종료',
      decision: '정상 처리 결정',
      tone: 'green' as Tone,
      amount: '324,000원',
    },
    { id: 1036, label: '조사 중', decision: '판정 전', tone: 'blue' as Tone, amount: '89,000원' },
    {
      id: 1031,
      label: '조사 종료',
      decision: '정상 처리 결정',
      tone: 'green' as Tone,
      amount: '158,000원',
    },
  ]
  return (
    <div className="dw-preview-card dw-owner-card" aria-hidden="true">
      <div className="dw-preview-owner-stats is-compact">
        <OwnerStat label="조사 중" value={1} />
        <OwnerStat label="정상 처리 결정" value={12} tone="green" />
        <OwnerStat label="취소 결정" value={3} tone="red" />
      </div>
      <ul className="dw-owner-list">
        {rows.map((row) => (
          <li key={row.id}>
            <strong>CASE-{row.id}</strong>
            <span>{row.amount}</span>
            <span className={`dw-preview-tag is-${row.tone}`}>{row.decision}</span>
          </li>
        ))}
      </ul>
      <div className="dw-owner-detail">
        <p>CASE-1043 · 결제별 결과</p>
        <dl>
          <div>
            <dt>보안 담당자 판정</dt>
            <dd>
              <span className="dw-preview-tag is-red">취소 결정</span>
            </dd>
          </div>
          <div>
            <dt>실제 결제 상태</dt>
            <dd>
              <span className="dw-preview-tag is-red">승인 취소</span>
            </dd>
          </div>
          <div>
            <dt>탐지 내용</dt>
            <dd>해외 IP 고액 결제</dd>
          </div>
        </dl>
      </div>
    </div>
  )
}

/** 정산 현황 — 날짜별 정산 금액 (앱에서도 시연 화면) */
export function OwnerSettlementCard() {
  const days = [
    { d: 27, done: '41.2만' },
    { d: 28, done: '38.9만' },
    { d: 29, sch: '52만', today: true },
    { d: 30, sch: '29.8만' },
    { d: 31, exp: '64만' },
  ]
  return (
    <div className="dw-preview-card dw-owner-card" aria-hidden="true">
      <div className="dw-preview-card-head">
        <CalendarOutlined />
        <strong>10월 정산</strong>
        <span className="dw-preview-tag is-gray">이번 주</span>
      </div>
      <div className="dw-owner-calendar">
        {days.map((day) => (
          <div key={day.d} className={day.today ? 'is-today' : undefined}>
            <b>{day.d}</b>
            {day.done && <span className="is-done">{day.done}</span>}
            {day.sch && <span className="is-sch">{day.sch}</span>}
            {day.exp && <span className="is-exp">{day.exp}</span>}
          </div>
        ))}
      </div>
      <div className="dw-owner-legend">
        <span className="is-done">정산 완료</span>
        <span className="is-sch">정산 예정</span>
        <span className="is-exp">예상 (픽업 전)</span>
      </div>
    </div>
  )
}

/** 자금 현황 — 예정 정산과 지출로 본 앞으로의 잔액 (앱에서도 시연 화면) */
export function OwnerCashflowCard() {
  const bars = [62, 70, 54, 38, -14, 26, 48]
  const labels = ['월', '화', '수', '목', '금', '토', '일']
  return (
    <div className="dw-preview-card dw-owner-card" aria-hidden="true">
      <div className="dw-preview-card-head">
        <FundOutlined />
        <strong>앞으로 7일 예상 잔액</strong>
      </div>
      <div className="dw-owner-bars">
        {bars.map((value, index) => (
          <div key={labels[index]} className={value < 0 ? 'is-short' : undefined}>
            <i style={{ height: `${Math.abs(value)}%` }} />
            <small>{labels[index]}</small>
          </div>
        ))}
      </div>
      <p className="dw-owner-alert">금요일에 약 140,000원이 부족할 수 있어요</p>
    </div>
  )
}

/* ── 스크롤 흐름 소개의 단계별 화면 ── */

/** 1 탐지 — 결제마다 위험 점수를 매긴다. */
export function DetectPanel() {
  const rules = [
    { name: '해외 IP 고액 결제', hit: true },
    { name: '평소 대비 고액', hit: true },
    { name: '단시간 반복 결제', hit: false },
    { name: '차단 목록 카드', hit: false },
  ]
  return (
    <div className="dw-preview-card dw-journey-card" aria-hidden="true">
      <div className="dw-preview-card-head">
        <CreditCardOutlined />
        <strong>PAY-8841 · 1,280,000원</strong>
        <span className="dw-preview-tag is-gray">케이크샵 강남점</span>
      </div>
      {/* 보류(REVIEW)는 50~79점, 80점 이상은 바로 거절(DENY)이다 (FdsEvaluationTransactionService). */}
      <div className="dw-journey-score">
        <div className="dw-journey-gauge" style={{ ['--score' as string]: 68 }}>
          <b>68</b>
          <small>위험 점수</small>
        </div>
        <ul className="dw-journey-rules">
          {rules.map((rule) => (
            <li key={rule.name} className={rule.hit ? 'is-hit' : undefined}>
              <span />
              {rule.name}
              <em>{rule.hit ? '적중' : '통과'}</em>
            </li>
          ))}
        </ul>
      </div>
      <div className="dw-journey-verdict">
        FDS 판정 <span className="dw-preview-tag is-amber">REVIEW</span>
      </div>
    </div>
  )
}

/** 2 보류 — 확인이 필요한 결제는 매입 전에 멈춘다. */
export function HoldPanel() {
  return (
    <div className="dw-preview-card dw-journey-card" aria-hidden="true">
      <div className="dw-journey-hold">
        <span className="dw-journey-lock">
          <LockFilled />
        </span>
        <strong>결제 보류</strong>
        <p>보안 담당자가 확인할 때까지 매입하지 않아요.</p>
      </div>
      <ol className="dw-journey-track">
        <li className="is-done">결제 승인</li>
        <li className="is-now">보류</li>
        <li>매입 또는 취소</li>
      </ol>
    </div>
  )
}

/** 3 조사 — AI가 근거를 정리한다. 판정은 하지 않는다. */
export function ReportPreview({ decided = false }: { decided?: boolean }) {
  return (
    <div className="dw-preview-card dw-journey-card" aria-hidden="true">
      <div className="dw-preview-card-head">
        <RobotOutlined />
        <strong>AI 조사 보고서</strong>
        <span className="dw-preview-tag is-gray">v2</span>
      </div>
      <p className="dw-preview-key">요약</p>
      <p className="dw-preview-summary">
        평소 국내에서 3만 원대 결제만 하던 고객이 해외 IP에서 128만 원을 결제했어요.
      </p>
      <p className="dw-preview-key">근거</p>
      <ul className="dw-preview-evidence">
        <li>
          <GlobalOutlined />
          접속 국가 변경<span className="dw-preview-tag is-red">KR → 해외</span>
        </li>
        <li>
          <CreditCardOutlined />
          최근 결제 평균 대비<span className="dw-preview-tag is-amber">36배</span>
        </li>
        <li>
          <ClockCircleOutlined />
          카드 첫 사용 후 경과<span className="dw-preview-tag is-gray">4분</span>
        </li>
      </ul>
      <div className={`dw-preview-decision${decided ? ' is-decided' : ' is-pending'}`}>
        <span>ACCEPT · 매입 허용</span>
        <span className="is-reject">
          {decided && <CheckCircleFilled />}
          REJECT · 매입 거부
        </span>
      </div>
    </div>
  )
}

const ownerSteps = [
  { title: '취소 결정', detail: '보안 담당자가 매입을 거부했어요', time: '14:52', state: 'done' },
  { title: '조사 중', detail: '보고서와 근거를 검토하고 있어요', time: '14:40', state: 'done' },
  { title: '결제 보류', detail: '위험 점수 68 · 해외 IP 고액 결제', time: '14:31', state: 'past' },
]

/** 5 결과 — 사장님은 내 가게 결제가 어떻게 처리됐는지 본다. 문구는 고객사 화면(ownerLabels)과 맞춘다. */
export function OwnerTimelinePreview() {
  return (
    <div className="dw-preview-card dw-journey-card" aria-hidden="true">
      <div className="dw-preview-card-head">
        <AuditOutlined />
        <strong>케이크샵 강남점 · 1,280,000원</strong>
        <span className="dw-preview-tag is-gray">조사 종료</span>
      </div>
      <ol className="dw-preview-timeline">
        {ownerSteps.map((step) => (
          <li key={step.title} className={`is-${step.state}`}>
            <span className="dw-preview-dot" />
            <div>
              <strong>{step.title}</strong>
              <small>{step.detail}</small>
            </div>
            <time>{step.time}</time>
          </li>
        ))}
      </ol>
    </div>
  )
}
