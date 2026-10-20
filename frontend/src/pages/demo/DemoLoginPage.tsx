import { useState } from 'react'
import { Alert, Button, Form, Input } from 'antd'
import { LockOutlined, SafetyCertificateOutlined, UserOutlined } from '@ant-design/icons'
import { Navigate, useNavigate } from 'react-router-dom'
import { roles, type Role } from '../../config/navigation'
import { useDemo } from '../../demo/DemoContext'

type LoginValues = { username: string; password: string }
const demoAccounts: Record<Role, LoginValues> = {
  security: { username: 'security', password: 'demo1234' },
  owner: { username: 'owner', password: 'demo1234' },
}

export function DemoLoginPage() {
  const { loggedIn, role, login } = useDemo()
  const navigate = useNavigate()
  const [form] = Form.useForm<LoginValues>()
  const [error, setError] = useState(false)

  if (loggedIn) return <Navigate to={roles[role].landing} replace />

  const submit = (values: LoginValues) => {
    // 공개된 시연 계정만 확인한다. 실제 인증 API 연결 시 교체한다.
    const selected = (Object.keys(demoAccounts) as Role[]).find(
      (key) =>
        demoAccounts[key].username === values.username.trim() &&
        demoAccounts[key].password === values.password,
    )
    if (!selected) {
      setError(true)
      return
    }
    form.resetFields()
    login(selected)
    navigate(roles[selected].landing, { replace: true })
  }

  const fillAccount = (selected: Role) => {
    form.setFieldsValue(demoAccounts[selected])
    setError(false)
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
          <span className="dw-demo-label">시연용 로그인</span>
          <h2 id="login-title">로그인</h2>
          <p className="dw-login-description">계정으로 로그인하고 업무를 시작하세요.</p>
          <Form<LoginValues>
            form={form}
            layout="vertical"
            onFinish={submit}
            onValuesChange={() => setError(false)}
            requiredMark={false}
            size="large"
          >
            <Form.Item
              label="아이디"
              name="username"
              rules={[{ required: true, whitespace: true, message: '아이디를 입력해 주세요.' }]}
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
              rules={[{ required: true, message: '비밀번호를 입력해 주세요.' }]}
            >
              <Input.Password
                prefix={<LockOutlined />}
                placeholder="비밀번호를 입력하세요"
                autoComplete="current-password"
              />
            </Form.Item>
            {error && (
              <div role="alert" className="dw-login-error">
                <Alert type="error" showIcon message="아이디 또는 비밀번호를 확인해 주세요." />
              </div>
            )}
            <Button type="primary" htmlType="submit" block>
              로그인
            </Button>
          </Form>
          <div className="dw-login-demo">
            <h3>시연 계정으로 둘러보기</h3>
            <p>계정을 선택하면 입력란이 채워집니다.</p>
            <div className="dw-login-demo-actions">
              <Button onClick={() => fillAccount('security')}>보안 직원</Button>
              <Button onClick={() => fillAccount('owner')}>사장님</Button>
            </div>
            <p className="dw-login-credentials">아이디 security / owner · 비밀번호 demo1234</p>
          </div>
          <p className="dw-login-notice">
            실제 인증은 연결 전입니다. 공개된 시연 계정만 사용하며, 입력한 아이디와 비밀번호는
            서버에 전송하거나 저장하지 않습니다.
          </p>
        </section>
      </div>
    </main>
  )
}
