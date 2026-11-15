import {
  AuditOutlined,
  CheckCircleFilled,
  ClockCircleOutlined,
  CreditCardOutlined,
  GlobalOutlined,
  LockFilled,
  RobotOutlined,
} from '@ant-design/icons'

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
        <span>매입 요청</span>
        <span className="is-reject">
          {decided && <CheckCircleFilled />}
          승인 취소 요청
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
