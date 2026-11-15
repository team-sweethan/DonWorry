import { useCallback, useEffect, useRef, useState, type ReactNode, type RefObject } from 'react'
import { Alert, Button } from 'antd'
import { CheckOutlined } from '@ant-design/icons'
import { Link } from 'react-router-dom'
import './LandingPage.css'
import { BrandMark, Mascot } from '../../components/brand/BrandMark'
import { useApplicationConfig } from '../../hooks/useApplicationConfig'
import { ApplicationEntry } from './ApplicationEntry'
import { HeroGuardian } from './HeroGuardian'
import { landingScreens, type LandingScreen } from './landingScreens'
import { DetectPanel, HoldPanel, OwnerTimelinePreview, ReportPreview } from './LandingPreview'
import { LandingScreenPreview } from './LandingScreenPreview'

import { LandingIntro } from './LandingIntro'
import { createIntroVisit } from './introPlayback'

const introVisit = createIntroVisit(
  () => window.sessionStorage,
  () =>
    typeof window === 'undefined' || window.matchMedia('(prefers-reduced-motion: reduce)').matches,
)

// 첫 화면 요약 — 한 줄에 하나씩, 짧게
const heroPoints = [
  { label: '결제 보안', text: '의심되는 결제는 멈추고, 담당자가 확인해요' },
  { label: '정산 내역', text: '정산 예정부터 실제 지급까지 살펴봐요' },
  { label: '자금 현황', text: '예정된 지출을 반영해 앞으로의 잔액을 봐요' },
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
  {
    title: '로그인하고 쇼핑몰 연결',
    detail: '첫 비밀번호를 정하고 로그인한 뒤, 시크릿 키를 발급받아 쇼핑몰 서버에 설정하세요.',
  },
]

const checklist = [
  '가맹점명과 사업자등록번호',
  '운영 중인 쇼핑몰 주소',
  '담당자 이름 · 이메일 · 연락처',
  '앞으로 쓸 로그인 아이디',
]

const faqs = [
  {
    question: '의심 결제는 제가 직접 판정해야 하나요?',
    answer:
      '아니요. AI가 근거를 정리하고 보안 담당자가 판정해요. 사장님은 조치 결과에서 담당자의 결정과 실제 결제 결과를 확인할 수 있어요.',
  },
  {
    question: '자금 현황은 실제 은행 잔액인가요?',
    answer:
      '은행 계좌를 직접 조회하지 않아요. 입력한 시작 잔액과 실제 지급·완료 지출로 현재 잔액을 계산하고, 예정된 정산과 등록한 지출을 반영해 앞으로의 잔액을 예상해요.',
  },
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
    question: '결제 연동도 신청과 함께 되나요?',
    answer:
      '신청과는 따로 진행해요. 승인 후 로그인해서 [시크릿 키 발급]에서 키를 받아 쇼핑몰 서버에 설정해 주세요.',
  },
]

const flow: { label: string; title: string; body: string; panel: ReactNode }[] = [
  {
    label: '탐지',
    title: '결제마다 위험 신호를 살펴봐요',
    body: '탐지 룰이 결제를 살펴 위험 점수를 매겨요. 확인이 필요한 결제를 골라내고, 위험이 아주 높으면 바로 거절해요.',
    panel: <DetectPanel />,
  },
  {
    label: '보류',
    title: '확인이 필요하면 매입 전에 멈춰요',
    body: '카드 승인은 됐지만 매입은 잠시 보류해요. 보안 담당자가 확인할 시간을 확보해요.',
    panel: <HoldPanel />,
  },
  {
    label: 'AI 조사',
    title: 'AI가 확인할 근거를 정리해요',
    body: '결제와 접속 기록을 조사 보고서로 묶어요. AI는 근거를 정리하고, 판정은 하지 않아요.',
    panel: <ReportPreview />,
  },
  {
    label: '담당자 판정',
    title: '정상 처리할지, 취소할지는 사람이 정해요',
    body: '보안 담당자가 보고서와 근거를 확인한 뒤 결정해요. 판정 사유도 기록으로 남아요.',
    panel: <ReportPreview decided />,
  },
  {
    label: '처리 결과',
    title: '결정이 실제로 반영됐는지 확인해요',
    body: '담당자의 결정과 실제 결제 결과를 구분해서 보여 드려요. 사장님은 조치 결과 화면에서 처리 과정을 확인하면 돼요.',
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
/** 고정 폭 화면을 자르지 않고, 들어갈 폭에 맞춰 통째로 줄여 보여 준다 (첫 화면 연출 520px). */
function FitWidth({
  width,
  className,
  children,
}: {
  width: number
  className: string
  children: ReactNode
}) {
  const box = useRef<HTMLDivElement>(null)
  const [scale, setScale] = useState(1)
  useEffect(() => {
    const el = box.current
    if (!el) return
    const observer = new ResizeObserver(([entry]) => {
      setScale(Math.min(1, entry.contentRect.width / width))
    })
    observer.observe(el)
    return () => observer.disconnect()
  }, [width])
  return (
    <div className={className} ref={box}>
      <div style={{ zoom: scale }}>{children}</div>
    </div>
  )
}

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
  const content = useRef<HTMLDivElement>(null)
  const [showIntro, setShowIntro] = useState(() => introVisit.shouldShow())
  const restoreFocus = useRef(false)
  const finishIntro = useCallback(() => {
    introVisit.complete()
    restoreFocus.current = true
    setShowIntro(false)
  }, [])
  useEffect(() => {
    if (!showIntro && restoreFocus.current) {
      restoreFocus.current = false
      content.current?.querySelector<HTMLElement>('#landing-main')?.focus({ preventScroll: true })
    }
  }, [showIntro])
  return (
    <>
      {showIntro && <LandingIntro onComplete={finishIntro} />}
      <div ref={content} inert={showIntro} aria-hidden={showIntro || undefined}>
        <LandingContent onRetry={onRetry} />
      </div>
    </>
  )
}

function LandingContent({ onRetry }: { onRetry?: () => void }) {
  const [screen, setScreen] = useState<LandingScreen>('actions')
  const selectedScreen = landingScreens.find((item) => item.key === screen)!

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
                <b>돈워리</b>사장님을 위한 결제 · 자금 관리
              </p>
              <h1 id="landing-title">
                결제는 안전하게,
                <br />
                <span className="dw-landing-mark">돈의 흐름은 한눈에.</span>
              </h1>
              <p className="dw-landing-lead">
                의심 결제 확인부터 정산과 자금 관리까지. 가게 운영에 필요한 흐름을 돈워리에서
                확인하세요.
              </p>
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
            <FitWidth width={520} className="dw-landing-hero-visual">
              <HeroGuardian />
            </FitWidth>
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
              <h2 id="landing-owner-title">내 가게의 결제와 정산, 이렇게 보여요</h2>
              <p>조치 결과 · 정산 내역 · 자금 현황을 눌러 사장님 화면을 미리 살펴보세요.</p>
            </div>
            <div className="dw-landing-screen-tabs" role="group" aria-label="미리 볼 화면 선택">
              {landingScreens.map((item) => (
                <button
                  key={item.key}
                  type="button"
                  aria-pressed={screen === item.key}
                  aria-controls="landing-screen"
                  onClick={() => setScreen(item.key)}
                >
                  {item.title}
                </button>
              ))}
            </div>
            <div className="dw-landing-screen-copy" aria-live="polite">
              <h3>{selectedScreen.text}</h3>
              <p>{selectedScreen.detail}</p>
            </div>
            <div id="landing-screen">
              <LandingScreenPreview screen={screen} />
            </div>
            <p className="dw-landing-owner-note">
              현재 서비스의 화면 구성에 예시 데이터를 넣었어요. 실제 내역은 로그인 후 확인할 수
              있어요.
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
                <h2 id="landing-start-title">신청부터 쇼핑몰 연결까지</h2>
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
                <h2 id="landing-final-title">내 가게의 돈 흐름을 확인할 준비, 되셨나요?</h2>
                <p>이용 신청 후 관리자 승인을 받으면 사장님 공간이 열려요.</p>
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
