import { useRef, useState } from 'react'
import { Alert, Button, Form, Input } from 'antd'
import { LockOutlined, SafetyCertificateOutlined, UserOutlined } from '@ant-design/icons'
import { InvalidCredentialsError } from '../../api/auth'

export type LoginValues = { loginId: string; password: string }
// 실제 로그인 호출은 TASK 05에서 연결한다. 연결 전에는 onSubmit 없이 준비 중 안내만 보여준다.
type LoginPageProps = { onSubmit?: (values: LoginValues) => Promise<void> }
type Feedback = 'ready' | 'invalid' | 'network'

const feedbackMessages: Record<Feedback, string> = {
  ready: '로그인 기능 연결 준비 중입니다.',
  invalid: '아이디 또는 비밀번호가 올바르지 않습니다.',
  network: '서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.',
}

export function LoginPage({ onSubmit }: LoginPageProps) {
  const submittingRef = useRef(false)
  const [submitting, setSubmitting] = useState(false)
  const [feedback, setFeedback] = useState<Feedback | null>(null)

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
      await onSubmit({ loginId: values.loginId.trim(), password: values.password })
    } catch (error) {
      setFeedback(error instanceof InvalidCredentialsError ? 'invalid' : 'network')
    } finally {
      submittingRef.current = false
      setSubmitting(false)
    }
  }

  return (
    <main className="dw-login-shell">
      <div className="dw-login-layout">
        <section className="dw-login-intro" aria-labelledby="login-intro-title">
          <div className="dw-login-brand">
            돈워리<span>DonWorry</span>
          </div>
          <div className="dw-login-intro-body">
            <div className="dw-login-symbol">
              <SafetyCertificateOutlined />
            </div>
            <p className="dw-login-eyebrow">안심할 수 있는 거래의 시작</p>
            <h1 id="login-intro-title">
              의심 거래는 꼼꼼하게,
              <br />
              가맹점 운영은 편안하게.
            </h1>
            <p className="dw-login-copy">
              이상 거래 확인부터 조치 결과까지.
              <br />
              돈워리에서 한눈에 확인하세요.
            </p>
            <div className="dw-login-features">
              <span>이상 거래 확인</span>
              <span>사건 검토 및 조치</span>
              <span>가맹점 현황 조회</span>
            </div>
          </div>
          <p className="dw-login-intro-footer">거래를 살피고, 안심을 더합니다.</p>
        </section>
        <section className="dw-login-box" aria-labelledby="login-title">
          <h2 id="login-title">로그인</h2>
          <p className="dw-login-description">계정으로 로그인하고 업무를 시작하세요.</p>
          <Form<LoginValues>
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
              label="비밀번호"
              name="password"
              rules={[{ required: true, whitespace: true, message: '비밀번호를 입력해 주세요.' }]}
            >
              <Input.Password
                prefix={<LockOutlined />}
                placeholder="비밀번호를 입력하세요"
                autoComplete="current-password"
              />
            </Form.Item>
            {feedback && (
              <div role={feedback === 'ready' ? 'status' : 'alert'} className="dw-login-error">
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
        </section>
      </div>
    </main>
  )
}
