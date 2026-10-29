import { Alert, Button } from 'antd'
import {
  ArrowRightOutlined,
  FileSearchOutlined,
  SafetyCertificateOutlined,
  SolutionOutlined,
} from '@ant-design/icons'
import { Link } from 'react-router-dom'
import './LandingPage.css'

const features = [
  {
    icon: <SafetyCertificateOutlined />,
    title: '이상 거래 확인',
    description: '탐지된 경보와 의심 거래를 확인합니다.',
    detail: '주의가 필요한 거래를 모아 살펴보세요.',
  },
  {
    icon: <FileSearchOutlined />,
    title: '조사와 판정 지원',
    description: '보안 직원이 조사 보고서와 증거를 검토하고 판정합니다.',
    detail: '거래의 맥락을 확인하고 판단의 근거를 남깁니다.',
  },
  {
    icon: <SolutionOutlined />,
    title: '조치 결과 조회',
    description: '가맹점은 관련 사건의 처리 상황과 결과를 확인합니다.',
    detail: '어떤 조치를 했고 어떻게 끝났는지 확인하세요.',
  },
]

const steps = ['신청 및 이메일 확인', '관리자 검토', '승인 후 계정 발급', '첫 비밀번호 설정']

export function LandingPage({ onRetry }: { onRetry?: () => void }) {
  return (
    <div className="dw-landing">
      <a className="dw-landing-skip" href="#landing-main">
        본문 바로가기
      </a>
      <header className="dw-landing-header">
        <div className="dw-landing-container dw-landing-header-inner">
          <Link className="dw-landing-brand" to="/" aria-label="돈워리 홈">
            돈워리<span>DonWorry</span>
          </Link>
          <Link className="dw-landing-login" to="/login">
            로그인 <ArrowRightOutlined aria-hidden="true" />
          </Link>
        </div>
      </header>
      {onRetry && (
        <div className="dw-landing-container dw-landing-session">
          <Alert
            type="warning"
            showIcon
            role="status"
            message="로그인 상태를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요."
            action={<Button onClick={onRetry}>다시 시도</Button>}
          />
        </div>
      )}
      <main id="landing-main" tabIndex={-1}>
        <section className="dw-landing-hero" aria-labelledby="landing-title">
          <div className="dw-landing-container">
            <p className="dw-landing-eyebrow">거래를 살피고, 안심을 더합니다</p>
            <h1 id="landing-title">
              의심 거래는 꼼꼼하게,
              <br />
              <span>가맹점 운영은 편안하게.</span>
            </h1>
            <p className="dw-landing-lead">
              이상 거래 확인부터 조사와 조치 결과까지,
              <br />
              돈워리에서 확인하세요.
            </p>
            <div className="dw-landing-apply">
              <Button
                type="primary"
                size="large"
                disabled
                aria-describedby="landing-apply-description"
              >
                이용 신청 · 준비 중
              </Button>
              <p id="landing-apply-description">
                온라인 이용 신청은 준비 중입니다.
                <br />
                기존 계정이 있다면 로그인해 주세요.
              </p>
            </div>
            <div className="dw-landing-hero-note">
              <SafetyCertificateOutlined aria-hidden="true" />
              <span>거래 확인부터 조치 결과까지 이어지는 관리</span>
            </div>
          </div>
        </section>
        <section
          className="dw-landing-features dw-landing-container"
          aria-labelledby="landing-features-title"
        >
          <p className="dw-landing-section-label">돈워리에서 할 수 있는 일</p>
          <h2 id="landing-features-title">
            거래를 확인하고,
            <br className="dw-landing-mobile-break" /> 결과까지 살펴보세요.
          </h2>
          <div className="dw-landing-feature-grid">
            {features.map((feature) => (
              <article className="dw-landing-feature" key={feature.title}>
                <span className="dw-landing-feature-icon" aria-hidden="true">
                  {feature.icon}
                </span>
                <h3>{feature.title}</h3>
                <p>{feature.description}</p>
                <p className="dw-landing-feature-detail">{feature.detail}</p>
              </article>
            ))}
          </div>
        </section>
        <section className="dw-landing-start" aria-labelledby="landing-start-title">
          <div className="dw-landing-container">
            <p className="dw-landing-section-label">이용 시작 안내</p>
            <h2 id="landing-start-title">이용 신청은 준비 중입니다</h2>
            <p className="dw-landing-start-description">
              온라인 신청이 열리면 다음 절차로 이용을 시작합니다.
            </p>
            <ol className="dw-landing-steps">
              {steps.map((step, index) => (
                <li key={step}>
                  <span aria-hidden="true">0{index + 1}</span>
                  {step}
                </li>
              ))}
            </ol>
            <p className="dw-landing-start-note">
              신청만으로 이용이 승인되지는 않으며, 결제 연동은 별도로 진행합니다.
            </p>
          </div>
        </section>
      </main>
      <footer className="dw-landing-footer">
        <div className="dw-landing-container">
          <span>
            <strong>돈워리</strong> DonWorry
          </span>
          <p>거래를 살피고, 안심을 더합니다.</p>
        </div>
      </footer>
    </div>
  )
}
