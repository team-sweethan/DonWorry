import { useRef, useState, type ReactNode } from 'react'
import { Alert, Button, Checkbox, Form, Input } from 'antd'
import { ArrowLeftOutlined, LockOutlined, UserOutlined } from '@ant-design/icons'
import { Link } from 'react-router-dom'
import { ApiError } from '../../api/client'
import { InvalidCredentialsError } from '../../api/auth'
import { readLoginPreferences, type LoginValues } from '../../auth/loginPreferences'
import './LoginPage.css'
import { BrandMark, Mascot } from '../../components/brand/BrandMark'
import { useApplicationConfig } from '../../hooks/useApplicationConfig'
import { ApplicationEntry } from '../public/ApplicationEntry'

export type { LoginValues } from '../../auth/loginPreferences'
type LoginPageProps = {
  onSubmit?: (values: LoginValues) => Promise<void>
  /** 세션 만료 같은 안내. 카드 안 입력란 위에 보여 준다. */
  notice?: ReactNode
}
type Feedback = 'ready' | 'invalid' | 'input' | 'network'

const feedbackMessages: Record<Feedback, string> = {
  ready: '로그인 기능 연결 준비 중입니다.',
  input: '아이디와 비밀번호를 확인해 주세요.',
  // 잠긴 계정만 다른 문구를 쓰면 계정 존재가 드러나므로 모든 인증 실패에 같은 안내를 보여 준다 (ADR-0029).
  invalid: '아이디 또는 비밀번호가 올바르지 않습니다. 여러 번 틀렸다면 15분 뒤 다시 시도해 주세요.',
  network: '서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.',
}

export function LoginPage({ onSubmit, notice }: LoginPageProps) {
  const submittingRef = useRef(false)
  const [submitting, setSubmitting] = useState(false)
  const [feedback, setFeedback] = useState<Feedback | null>(null)
  const [initialValues] = useState(readLoginPreferences)
  const [capsLock, setCapsLock] = useState(false)
  const [findHelp, setFindHelp] = useState(false)
  const [bookOpened, setBookOpened] = useState(false)
  const apply = useApplicationConfig()

  const submit = async (values: LoginValues) => {
    if (submittingRef.current) return
    if (!onSubmit) {
      setFeedback('ready')
      return
    }
    submittingRef.current = true
    setSubmitting(true)
    setFeedback(null)
    try {
      await onSubmit({ ...values, loginId: values.loginId.trim() })
    } catch (error) {
      setFeedback(
        error instanceof InvalidCredentialsError
          ? 'invalid'
          : error instanceof ApiError && error.status === 400
            ? 'input'
            : 'network',
      )
    } finally {
      submittingRef.current = false
      setSubmitting(false)
    }
  }

  return (
    <div className="dw-signin">
      <div className="dw-signin-glow" aria-hidden="true" />
      <header className="dw-signin-top">
        <Link className="dw-public-brand" to="/" aria-label="돈워리 서비스 소개" viewTransition>
          <BrandMark className="dw-mark" />
          돈워리
        </Link>
        <Link className="dw-signin-back" to="/" viewTransition>
          <ArrowLeftOutlined aria-hidden="true" />
          서비스 소개
        </Link>
      </header>
      <main className="dw-signin-main">
        <div
          className={`dw-signin-book${bookOpened ? ' is-open' : ''}`}
          onFocusCapture={() => setBookOpened(true)}
          onAnimationEnd={(event) => {
            if (event.target === event.currentTarget) setBookOpened(true)
          }}
        >
          <aside className="dw-signin-page dw-signin-welcome" aria-labelledby="welcome-title">
            <div className="dw-signin-edition">
              <span>돈워리 업무 노트</span>
              <span>01</span>
            </div>
            <div className="dw-signin-welcome-copy">
              <p className="dw-signin-eyebrow">가게 운영의 다음 장</p>
              <h2 id="welcome-title">
                오늘의 장사도,
                <br />
                돈워리와 함께.
              </h2>
              <p>
                매일의 결제와 정산을
                <br />
                한곳에서 차근차근.
              </p>
            </div>
            <div className="dw-signin-illustration" aria-hidden="true">
              <span className="dw-signin-orbit" />
              <Mascot size={220} />
              <span className="dw-signin-illustration-note">걱정은 덜고, 장사에 더 가까이.</span>
            </div>
            <div className="dw-signin-page-note">
              <span>당신의 가게 곁에, 돈워리</span>
              <span>시작하는 페이지</span>
            </div>
          </aside>
          <section className="dw-signin-page dw-signin-card" aria-labelledby="login-title">
            <div className="dw-signin-body">
              <div className="dw-signin-edition">
                <span>나의 업무 공간</span>
                <span>02</span>
              </div>
              <h1 id="login-title">로그인</h1>
              <p className="dw-signin-description">관리자에게 발급받은 아이디로 로그인해 주세요.</p>
              {notice && <div className="dw-signin-notice">{notice}</div>}
              <Form<LoginValues>
                initialValues={initialValues}
                layout="vertical"
                onFinish={submit}
                onValuesChange={() => setFeedback(null)}
                requiredMark={false}
                size="large"
                disabled={submitting}
              >
                <Form.Item
                  label="아이디"
                  name="loginId"
                  rules={[
                    { required: true, whitespace: true, message: '아이디를 입력해 주세요.' },
                    {
                      max: 100,
                      transform: (value: string) => value?.trim(),
                      message: '아이디는 100자 이하로 입력해 주세요.',
                    },
                  ]}
                >
                  <Input
                    prefix={<UserOutlined />}
                    placeholder="아이디를 입력하세요"
                    autoComplete="username"
                    autoCapitalize="none"
                    spellCheck={false}
                  />
                </Form.Item>
                <Form.Item
                  label={
                    <span className="dw-signin-label">
                      비밀번호
                      <small>5번 틀리면 15분 동안 잠겨요</small>
                    </span>
                  }
                  name="password"
                  rules={[
                    { required: true, whitespace: true, message: '비밀번호를 입력해 주세요.' },
                  ]}
                  // 상태 영역을 미리 두어야 스크린 리더가 문구 변경을 읽는다.
                  extra={<span role="status">{capsLock ? 'Caps Lock이 켜져 있습니다.' : ''}</span>}
                >
                  <Input.Password
                    prefix={<LockOutlined />}
                    placeholder="비밀번호를 입력하세요"
                    autoComplete="current-password"
                    onKeyDown={(event) => setCapsLock(event.getModifierState('CapsLock'))}
                    onKeyUp={(event) => setCapsLock(event.getModifierState('CapsLock'))}
                    onBlur={() => setCapsLock(false)}
                  />
                </Form.Item>
                <Form.Item name="rememberLoginId" valuePropName="checked">
                  <Checkbox>아이디 저장</Checkbox>
                </Form.Item>
                {feedback && (
                  <div role={feedback === 'ready' ? 'status' : 'alert'} className="dw-signin-error">
                    <Alert
                      type={feedback === 'ready' ? 'info' : 'error'}
                      showIcon
                      message={feedbackMessages[feedback]}
                    />
                  </div>
                )}
                <Button type="primary" htmlType="submit" loading={submitting} block>
                  로그인
                </Button>
              </Form>
              {/* 찾기 기능은 만들지 않는다 (#144). 눌러도 반응 없는 버튼이 되지 않게 실제 경로를 안내한다. */}
              <Button
                type="link"
                className="dw-signin-find"
                aria-expanded={findHelp}
                onClick={() => setFindHelp((open) => !open)}
              >
                아이디/비밀번호 찾기
              </Button>
              {findHelp && (
                <p className="dw-signin-help">
                  아이디나 비밀번호를 잊었다면 관리자에게 문의해 주세요.
                </p>
              )}
            </div>
            <div className="dw-signin-foot">
              돈워리가 처음이신가요?
              <ApplicationEntry config={apply} variant="link" />
            </div>
          </section>
          <div className="dw-signin-cover" aria-hidden="true">
            <div className="dw-signin-cover-front">
              <span className="dw-signin-cover-edition">가게 운영을 위한 한 권</span>
              <BrandMark size={64} />
              <strong>돈워리</strong>
              <span className="dw-signin-cover-line" />
              <span>오늘의 장사도, 함께.</span>
              <span className="dw-signin-cover-bottom">DONWORRY</span>
            </div>
            <div className="dw-signin-cover-back" />
          </div>
        </div>
      </main>
      <footer className="dw-signin-footer">© 2026 DonWorry</footer>
    </div>
  )
}
