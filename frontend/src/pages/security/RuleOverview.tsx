import {
  ArrowRightOutlined,
  AuditOutlined,
  CalculatorOutlined,
  CheckCircleOutlined,
  CreditCardOutlined,
  EyeOutlined,
  LockOutlined,
  PauseOutlined,
  CaretRightOutlined,
  SafetyCertificateOutlined,
  StopOutlined,
  UserSwitchOutlined,
} from '@ant-design/icons'
import { useState } from 'react'
import './RuleOverview.css'

function MotionControl({
  paused,
  onToggle,
  label,
}: {
  paused: boolean
  onToggle: () => void
  label: string
}) {
  return (
    <button
      type="button"
      className="rule-guide-replay"
      onClick={onToggle}
      aria-label={`${label} 애니메이션 ${paused ? '재생' : '일시정지'}`}
      aria-pressed={paused}
    >
      {paused ? <CaretRightOutlined /> : <PauseOutlined />}
      {paused ? '흐름 재생' : '일시정지'}
    </button>
  )
}

export function PaymentRuleOverview() {
  const [paused, setPaused] = useState(false)
  return (
    <section
      className={`rule-guide rule-guide-payment${paused ? ' is-paused' : ''}`}
      aria-labelledby="payment-rule-guide-title"
    >
      <div className="rule-guide-layout">
        <header className="rule-guide-intro">
          <span className="rule-guide-kicker">
            <CreditCardOutlined /> 결제 룰
            <MotionControl
              paused={paused}
              onToggle={() => setPaused((value) => !value)}
              label="결제 평가 예시"
            />
          </span>
          <h2 id="payment-rule-guide-title">
            켜진 룰을 평가하고, <br />
            위험 점수를 합산합니다
          </h2>
          <p>
            가맹점 결제의 위험 점수로
            <br className="rule-guide-desktop-break" /> FDS 평가 결과를 나눕니다.
          </p>
        </header>
        <div className="rule-guide-content rule-guide-playback">
          <ol
            className="rule-guide-payment-flow"
            aria-label="켜진 룰의 조건을 평가하고, 점수를 합산해 최대 100점으로 제한한 뒤 구간을 판정합니다."
          >
            <li className="rule-guide-step">
              <span className="rule-guide-step-label">
                <span>01</span> 적용 룰 평가
              </span>
              <div className="rule-guide-step-main">
                <StopOutlined />
                <strong>켜진 룰의 조건을 확인해요</strong>
              </div>
              <p className="rule-guide-bypass">
                R07: 유효한 차단 항목 일치 시 <b>+100점</b>
              </p>
            </li>
            <li className="rule-guide-connector" aria-hidden="true">
              <span>적중</span>
              <ArrowRightOutlined />
            </li>
            <li className="rule-guide-step">
              <span className="rule-guide-step-label">
                <span>02</span> 위험 점수 합산
              </span>
              <div className="rule-guide-step-main">
                <CalculatorOutlined />
                <strong>적중한 룰 점수를 더해요</strong>
              </div>
              <p>합산 점수는 최대 100점</p>
            </li>
          </ol>
          <ol className="rule-guide-bands" aria-label="위험 점수별 판정 구간">
            <li className="rule-guide-band-allow">
              <span>
                0–49<small>점</small>
              </span>
              <strong>
                <CheckCircleOutlined /> ALLOW
              </strong>
            </li>
            <li className="rule-guide-band-review">
              <span>
                50–79<small>점</small>
              </span>
              <strong>
                <EyeOutlined /> REVIEW
              </strong>
            </li>
            <li className="rule-guide-band-deny">
              <span>
                80<small>점 이상</small>
              </span>
              <strong>
                <StopOutlined /> DENY
              </strong>
            </li>
          </ol>
        </div>
      </div>
      <footer className="rule-guide-footer rule-guide-playback">
        <span className="rule-guide-footer-label">합산 예시</span>
        <span className="rule-guide-equation">
          <span className="rule-guide-score-term">
            고액 결제 <b>+30</b>
          </span>
          <span className="rule-guide-plus">＋</span>
          <span className="rule-guide-score-term">
            신규 단말기 <b>+25</b>
          </span>
        </span>
        <span className="rule-guide-example-result">
          <ArrowRightOutlined /> 55점 · REVIEW
        </span>
        <span className="rule-guide-example-note">
          두 룰만 적중한 예시입니다. 신규 단말기 룰은 과거 정상 승인 이력이 있을 때 적용됩니다.
        </span>
      </footer>
    </section>
  )
}

export function AccountRuleOverview() {
  const [paused, setPaused] = useState(false)
  return (
    <section
      className={`rule-guide rule-guide-account${paused ? ' is-paused' : ''}`}
      aria-labelledby="account-rule-guide-title"
    >
      <div className="rule-guide-layout">
        <header className="rule-guide-intro">
          <span className="rule-guide-kicker">
            <SafetyCertificateOutlined /> 계정 룰{' '}
            <span className="rule-guide-audience">관리자 전용</span>
            <MotionControl
              paused={paused}
              onToggle={() => setPaused((value) => !value)}
              label="계정 접근 탐지"
            />
          </span>
          <h2 id="account-rule-guide-title">
            반복 권한 위반과 <br />
            직원의 접근 이상을 탐지합니다
          </h2>
          <p>
            사장님 계정의 반복 접근 거부와
            <br className="rule-guide-desktop-break" /> 직원의 고객정보 조회 패턴을 살핍니다.
          </p>
        </header>
        <div className="rule-guide-content rule-guide-account-flow rule-guide-playback">
          <ul className="rule-guide-signals" aria-label="계정 룰이 살피는 접근">
            <li>
              <span className="rule-guide-signal-icon">
                <UserSwitchOutlined />
              </span>
              <div>
                <strong>역할 밖 접근</strong>
                <span>사장님 계정의 역할 범위 밖 API 반복 호출</span>
              </div>
            </li>
            <li>
              <span className="rule-guide-signal-icon">
                <LockOutlined />
              </span>
              <div>
                <strong>권한 없는 호출</strong>
                <span>사장님 계정의 필요 권한 없는 API 반복 호출</span>
              </div>
            </li>
            <li>
              <span className="rule-guide-signal-icon">
                <AuditOutlined />
              </span>
              <div>
                <strong>직원 접근 이상</strong>
                <span>담당 밖 접근 · 집중 열람 · 대량 조회</span>
              </div>
            </li>
          </ul>
          <div className="rule-guide-connector" aria-hidden="true">
            <ArrowRightOutlined />
          </div>
          <div className="rule-guide-protection">
            <span className="rule-guide-shield" aria-hidden="true">
              <SafetyCertificateOutlined />
            </span>
            <strong>이상 징후 탐지</strong>
            <span>
              탐지 기록 · 경보
              <br />
              규칙별 차단 · 정지
            </span>
          </div>
        </div>
      </div>
      <footer className="rule-guide-footer">
        <LockOutlined />
        <span>최근 적중과 적용 상태는 아래 카드에서 확인합니다.</span>
        <span className="rule-guide-footer-note">
          내부자 룰 변경은 관리자만 · SEC-INS-05는 항상 켜짐
        </span>
      </footer>
    </section>
  )
}
