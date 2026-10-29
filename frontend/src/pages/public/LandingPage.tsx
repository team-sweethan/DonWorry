import { useEffect, useRef, useState, type ReactNode, type RefObject } from 'react'
import { Alert, Button } from 'antd'
import { CheckOutlined } from '@ant-design/icons'
import { Link } from 'react-router-dom'
import './LandingPage.css'
import { BrandMark, Mascot } from '../../components/brand/BrandMark'
import { useApplicationConfig } from '../../hooks/useApplicationConfig'
import { ApplicationEntry } from './ApplicationEntry'
import {
  DetectPanel,
  HoldPanel,
  OwnerCashflowCard,
  OwnerHeroPreview,
  OwnerResultCard,
  OwnerSettlementCard,
  OwnerTimelinePreview,
  ReportPreview,
} from './LandingPreview'

// 첫 화면 요약 — 한 줄에 하나씩, 짧게
const heroPoints = [
  { label: '멈춤', text: '의심되는 결제는 정산 전에 보류돼요' },
  { label: '확인', text: '보안 담당자가 확인하고 처리를 정해요' },
  { label: '결과', text: '처리가 끝나면 사장님 화면에서 확인해요' },
]

// 단계 설명은 실제 신청 흐름(접수 메일 24시간 링크 · 검토 결과 메일 · 승인 메일의 비밀번호 설정 링크)과 맞춘다.
const steps = [
  {
    title: '신청 및 이메일 확인',
    detail: '신청서를 내면 확인 메일이 가요. 24시간 안에 메일의 링크를 눌러 주세요.',
  },
  { title: '관리자 검토', detail: '관리자가 신청 내용을 확인하고, 결과를 메일로 알려 드려요.' },
  {
    title: '승인 후 계정 발급',
    detail: '신청할 때 정한 아이디로 계정이 만들어지고, 비밀번호 설정 링크가 메일로 와요.',
  },
  { title: '첫 비밀번호 설정', detail: '링크에서 비밀번호를 정하면 바로 로그인할 수 있어요.' },
]

const checklist = [
  '가맹점명과 사업자등록번호',
  '담당자 이름 · 이메일 · 연락처',
  '앞으로 쓸 로그인 아이디',
]

const faqs = [
  {
    question: '신청하면 바로 쓸 수 있나요?',
    answer:
      '아니요. 이메일 확인 후 관리자가 검토하고, 승인되면 계정이 만들어져요. 결과는 신청한 이메일로 알려 드려요.',
  },
  {
    question: '확인 메일이 오지 않아요.',
    answer:
      '스팸함을 먼저 확인해 주세요. 이용 신청 화면 아래에서 확인 메일을 다시 받을 수 있어요. 가장 최근에 받은 링크만 쓸 수 있어요.',
  },
  {
    question: '보안 담당자도 여기서 신청하나요?',
    answer: '아니요. 이용 신청은 가맹점용이에요. 보안 담당자 계정은 관리자가 따로 만들어 드려요.',
  },
  {
    question: '결제 연동도 신청과 함께 되나요?',
    answer: '결제 연동은 계정 발급과 별도로 진행해요.',
  },
]

// 정산 · 자금 화면은 앱에서도 아직 시연 화면이라 '준비 중'으로 작게 보여 준다.
const ownerScreens: { title: string; body: string; soon?: boolean; visual: ReactNode }[] = [
  {
    title: '조치 결과',
    body: '보류된 결제가 정상 처리됐는지, 취소됐는지 결제별로 확인해요.',
    visual: <OwnerResultCard />,
  },
  {
    title: '정산 현황',
    body: '언제 얼마가 정산되는지 날짜별로 봐요.',
    soon: true,
    visual: <OwnerSettlementCard />,
  },
  {
    title: '자금 현황',
    body: '예정된 정산과 지출로 앞으로의 잔액을 미리 봐요.',
    soon: true,
    visual: <OwnerCashflowCard />,
  },
]

const flow: { label: string; title: string; body: string; panel: ReactNode }[] = [
  {
    label: '탐지',
    title: '결제마다 위험 점수를 매겨요',
    body: '탐지 룰이 결제 하나하나를 살펴 점수를 매기고, 확인이 필요한 결제를 골라내요.',
    panel: <DetectPanel />,
  },
  {
    label: '보류',
    title: '확인이 필요하면 정산 전에 멈춰요',
    body: '바로 처리하지 않고 보류해 두니까, 보안 담당자가 확인할 시간이 생겨요.',
    panel: <HoldPanel />,
  },
  {
    label: '조사',
    title: 'AI가 근거를 정리해 둬요',
    body: '고객의 최근 결제, 접속 국가, 걸린 탐지 룰을 보고서로 묶어요. 판정은 하지 않아요.',
    panel: <ReportPreview />,
  },
  {
    label: '판정',
    title: '보안 담당자가 정상 처리할지, 취소할지 정해요',
    body: '근거를 확인한 사람이 사유와 함께 결정하고, 그 기록이 남아요.',
    panel: <ReportPreview decided />,
  },
  {
    label: '결과',
    title: '처리가 끝나면 사장님도 결과를 확인해요',
    body: '언제 보류됐고 어떤 결정이 났는지, 내 가게 결제의 처리 과정을 순서대로 봐요.',
    panel: <OwnerTimelinePreview />,
  },
]

/** 화면 가운데에 걸린 단계를 찾아 오른쪽 고정 화면을 그 단계로 바꾼다. */
function useActiveStep(count: number) {
  const items = useRef<(HTMLLIElement | null)[]>([])
  const [active, setActive] = useState(0)
  useEffect(() => {
    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (!entry.isIntersecting) continue
          setActive(items.current.indexOf(entry.target as HTMLLIElement))
        }
      },
      { rootMargin: '-45% 0px -45% 0px' },
    )
    items.current.slice(0, count).forEach((item) => item && observer.observe(item))
    return () => observer.disconnect()
  }, [count])
  return { items, active }
}

function FlowStory() {
  const { items, active } = useActiveStep(flow.length)
  return (
    <div className="dw-journey">
      <ol className="dw-journey-steps">
        {flow.map((step, index) => (
          <li
            key={step.label}
            ref={(element) => {
              items.current[index] = element
            }}
            className={index === active ? 'is-active' : undefined}
          >
            <span className="dw-journey-label">
              <b>{index + 1}</b>
              {step.label}
            </span>
            <h3>{step.title}</h3>
            <p>{step.body}</p>
            {/* 좁은 화면에서는 고정 화면 대신 단계마다 화면을 바로 아래에 둔다 */}
            <div className="dw-journey-inline">{step.panel}</div>
          </li>
        ))}
      </ol>
      <div className="dw-journey-stage">
        <div className="dw-journey-stage-inner">
          {flow.map((step, index) => (
            <div
              key={step.label}
              className={`dw-journey-panel${index === active ? ' is-active' : ''}${index < active ? ' is-past' : ''}`}
            >
              {step.panel}
            </div>
          ))}
          <div className="dw-journey-progress" aria-hidden="true">
            {flow.map((step, index) => (
              <span key={step.label} className={index <= active ? 'is-on' : undefined} />
            ))}
          </div>
        </div>
      </div>
    </div>
  )
}

// 해시 이동은 라우터 위치 key를 바꿔 세션 확인을 다시 하므로 주소를 바꾸지 않고 스크롤·포커스만 옮긴다.
function moveTo(target: RefObject<HTMLElement | null>) {
  target.current?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  target.current?.focus({ preventScroll: true })
}

function Brand() {
  return (
    <>
      <BrandMark className="dw-mark" />
      돈워리
    </>
  )
}

export function LandingPage({ onRetry }: { onRetry?: () => void }) {
  const main = useRef<HTMLElement>(null)
  const apply = useApplicationConfig()
  const owner = useRef<HTMLElement>(null)
  const features = useRef<HTMLElement>(null)
  const start = useRef<HTMLElement>(null)
  return (
    <div className="dw-landing">
      <a
        className="dw-landing-skip"
        href="#landing-main"
        onClick={(event) => {
          event.preventDefault()
          main.current?.focus()
        }}
      >
        본문 바로가기
      </a>
      <header className="dw-landing-header">
        <div className="dw-landing-container dw-landing-header-inner">
          <Link className="dw-public-brand" to="/" aria-label="돈워리 홈">
            <Brand />
          </Link>
          <nav className="dw-landing-menu" aria-label="페이지 안 이동">
            <button type="button" onClick={() => moveTo(owner)}>
              사장님 화면
            </button>
            <button type="button" onClick={() => moveTo(features)}>
              처리 과정
            </button>
            <button type="button" onClick={() => moveTo(start)}>
              이용 절차
            </button>
          </nav>
          <div className="dw-landing-actions">
            <Link className="dw-btn dw-btn-text" to="/login" viewTransition>
              로그인
            </Link>
            <ApplicationEntry config={apply} variant="small" />
          </div>
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
      <main id="landing-main" ref={main} tabIndex={-1}>
        <section className="dw-landing-hero" aria-labelledby="landing-title">
          <div className="dw-landing-container dw-landing-hero-inner">
            <div className="dw-landing-hero-copy">
              <p className="dw-landing-chip">
                <b>FDS</b>가맹점 결제 보안
              </p>
              <h1 id="landing-title">
                그 결제, 정말
                <br />
                <span className="dw-landing-mark">손님이 한 걸까요?</span>
              </h1>
              <p className="dw-landing-lead">카드 도용이 의심되는 결제, 그냥 넘어가지 않아요.</p>
              <ol className="dw-landing-points">
                {heroPoints.map((point, index) => (
                  <li key={point.label}>
                    <span aria-hidden="true">{index + 1}</span>
                    <b>{point.label}</b>
                    {point.text}
                  </li>
                ))}
              </ol>
              <div className="dw-landing-cta">
                <ApplicationEntry config={apply} />
                <Link className="dw-btn dw-btn-tonal" to="/login" viewTransition>
                  로그인
                </Link>
              </div>
            </div>
            <div className="dw-landing-hero-visual">
              <Mascot size={190} className="dw-landing-mascot dw-mascot-float" />
              <OwnerHeroPreview />
            </div>
          </div>
        </section>

        <section
          className="dw-landing-owner"
          aria-labelledby="landing-owner-title"
          ref={owner}
          tabIndex={-1}
        >
          <div className="dw-landing-container">
            <div className="dw-landing-owner-head">
              <p className="dw-landing-eyebrow">사장님이 쓰는 화면</p>
              <h2 id="landing-owner-title">내 가게 결제와 돈의 흐름을 한 곳에서</h2>
              <p>로그인하면 사장님 화면이 열려요. 보안 담당자가 쓰는 내부 화면과는 따로 있어요.</p>
            </div>
            <div className="dw-landing-owner-grid">
              {ownerScreens.map((screen) => (
                <article
                  key={screen.title}
                  className={`dw-landing-owner-card${screen.soon ? ' is-soon' : ''}`}
                >
                  <div className="dw-landing-owner-copy">
                    <h3>
                      {screen.title}
                      {screen.soon && <span className="dw-landing-soon">준비 중</span>}
                    </h3>
                    <p>{screen.body}</p>
                  </div>
                  {screen.visual}
                </article>
              ))}
            </div>
            <p className="dw-landing-owner-note">
              정산 현황과 자금 현황은 결제 연동과 함께 열릴 예정이에요.
            </p>
          </div>
        </section>

        <section
          className="dw-landing-features"
          aria-labelledby="landing-features-title"
          ref={features}
          tabIndex={-1}
        >
          <div className="dw-landing-container">
            <div className="dw-landing-features-head">
              <p className="dw-landing-eyebrow">뒤에서는 이렇게 처리돼요</p>
              <h2 id="landing-features-title">
                탐지는 시스템이,
                <br />
                판단은 사람이 해요
              </h2>
              <p>
                사장님이 따로 할 일은 없어요. AI가 근거를 정리하고, 정상 처리할지 취소할지는 보안
                담당자가 정해요.
              </p>
            </div>
            <FlowStory />
          </div>
        </section>

        <section
          className="dw-landing-start"
          aria-labelledby="landing-start-title"
          ref={start}
          tabIndex={-1}
        >
          <div className="dw-landing-container">
            <div className="dw-landing-start-head">
              <div>
                <p className="dw-landing-eyebrow">이용 절차</p>
                <h2 id="landing-start-title">이용까지 4단계면 돼요</h2>
                <p>가맹점 사장님이나 담당자가 신청하면, 관리자가 확인하고 계정을 만들어 드려요.</p>
              </div>
              <ApplicationEntry config={apply} />
            </div>
            <div className="dw-landing-prepare">
              <strong>신청할 때 필요한 것</strong>
              <ul>
                {checklist.map((item) => (
                  <li key={item}>
                    <CheckOutlined aria-hidden="true" />
                    {item}
                  </li>
                ))}
              </ul>
            </div>
            <ol className="dw-landing-steps">
              {steps.map((step, index) => (
                <li key={step.title}>
                  <span aria-hidden="true">{index + 1}</span>
                  <strong>{step.title}</strong>
                  <p>{step.detail}</p>
                </li>
              ))}
            </ol>
            <div className="dw-landing-faq">
              <h3>자주 묻는 질문</h3>
              {faqs.map((faq) => (
                <details key={faq.question}>
                  <summary>{faq.question}</summary>
                  <p>{faq.answer}</p>
                </details>
              ))}
            </div>
          </div>
        </section>

        <section className="dw-landing-final" aria-labelledby="landing-final-title">
          <div className="dw-landing-container">
            <div className="dw-landing-final-box">
              <Mascot size={150} className="dw-landing-final-mascot dw-mascot-float" />
              <div>
                <h2 id="landing-final-title">지금 이용을 신청하세요</h2>
                <p>계정이 있다면 바로 로그인하면 돼요.</p>
              </div>
              <div className="dw-landing-cta">
                <ApplicationEntry config={apply} variant="inverse" />
                <Link className="dw-btn dw-btn-ghost" to="/login" viewTransition>
                  로그인
                </Link>
              </div>
            </div>
          </div>
        </section>
      </main>
      <footer className="dw-landing-footer">
        <div className="dw-landing-container">
          <span className="dw-public-brand dw-public-brand-small">
            <Brand />
          </span>
          <span>© 2026 DonWorry</span>
        </div>
      </footer>
    </div>
  )
}
