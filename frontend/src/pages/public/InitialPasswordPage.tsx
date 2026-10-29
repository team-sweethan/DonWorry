import { useRef, useState } from 'react'
import { Alert, Button, Form, Input, Result } from 'antd'
import { LockOutlined } from '@ant-design/icons'
import { Link, useLoaderData } from 'react-router-dom'
import { PublicShell } from './ApplicationPage'
import { Mascot } from '../../components/brand/BrandMark'
import { setupInitialPassword } from '../../api/merchantApplications'
import { ApiError } from '../../api/client'
import './InitialPasswordPage.css'
export function InitialPasswordPage() {
  const { token } = useLoaderData() as { token: string | null }
  const busy = useRef(false)
  const [form] = Form.useForm<{ newPassword: string; confirmation: string }>()
  const [sending, setSending] = useState(false)
  const [done, setDone] = useState(false)
  const [error, setError] = useState<{ message: string; terminal: boolean; used: boolean } | null>(
    null,
  )
  const feedbackRef = useRef<HTMLDivElement>(null)
  const submit = async ({ newPassword }: { newPassword: string }) => {
    if (!token || busy.current) return
    busy.current = true
    setSending(true)
    setError(null)
    try {
      await setupInitialPassword(token, newPassword)
      form.resetFields()
      setDone(true)
    } catch (e) {
      const code = e instanceof ApiError ? e.code : undefined
      setError({
        message:
          e instanceof ApiError && e.detail
            ? e.detail
            : '설정 결과를 확인하지 못했습니다. 다시 시도해 주세요. 이미 설정되었다면 로그인 안내가 표시됩니다.',
        terminal:
          !!code &&
          [
            'APPLICATION_011',
            'APPLICATION_012',
            'APPLICATION_013',
            'APPLICATION_014',
            'APPLICATION_015',
          ].includes(code),
        used: code === 'APPLICATION_013',
      })
      requestAnimationFrame(() => feedbackRef.current?.focus())
    } finally {
      busy.current = false
      setSending(false)
    }
  }
  return (
    <PublicShell step={4}>
      {done ? (
        <Result
          status="success"
          icon={<Mascot size={140} className="dw-mascot-float" />}
          title="비밀번호 설정이 완료되었습니다"
          subTitle="승인 안내 메일에 적힌 로그인 아이디와 방금 설정한 비밀번호로 로그인해 주세요."
          extra={
            <Link to="/login">
              <Button type="primary" size="large">
                로그인하기
              </Button>
            </Link>
          }
        />
      ) : !token ? (
        <Result
          status="warning"
          title="승인 안내 메일에서 다시 열어 주세요"
          subTitle="이 화면은 이메일의 전용 링크로 열어야 합니다. 창을 새로 열었거나 새로고침했다면 승인 안내 메일의 링크를 다시 눌러 주세요."
          extra={<Link to="/login">이미 비밀번호를 설정했다면 로그인</Link>}
        />
      ) : (
        <>
          <div className="initial-password-icon">
            <LockOutlined />
          </div>
          <p className="initial-password-eyebrow">돈워리 이용 시작 · 마지막 단계</p>
          <h1>
            처음 사용할 비밀번호를
            <br />
            설정해 주세요
          </h1>
          <p>
            승인 안내 메일로 받은 계정의 비밀번호를 설정합니다.
            <br />
            본인만 아는 비밀번호를 설정하면 로그인할 수 있습니다.
          </p>
          {error && (
            <div ref={feedbackRef} tabIndex={-1} role="alert">
              <Alert type={error.used ? 'info' : 'error'} showIcon message={error.message} />
            </div>
          )}
          {!error?.terminal ? (
            <Form
              form={form}
              layout="vertical"
              onFinish={submit}
              requiredMark={false}
              disabled={sending}
              className="initial-password-form"
            >
              <Form.Item
                name="newPassword"
                label="새 비밀번호"
                extra="영문과 숫자를 포함한 8~64자. 로그인 아이디와 다르게 입력해 주세요. 한글·특수문자도 사용할 수 있으며 UTF-8 기준 72바이트까지입니다."
                rules={[
                  { required: true, message: '새 비밀번호를 입력해 주세요.' },
                  {
                    validator: (_, value: string) => {
                      if (!value) return Promise.resolve()
                      const length = Array.from(value).length
                      return length >= 8 &&
                        length <= 64 &&
                        new TextEncoder().encode(value).length <= 72 &&
                        /[a-zA-Z]/.test(value) &&
                        /[0-9]/.test(value)
                        ? Promise.resolve()
                        : Promise.reject(
                            new Error('영문·숫자를 포함한 8~64자, 72바이트 이하로 입력해 주세요.'),
                          )
                    },
                  },
                ]}
              >
                <Input.Password autoComplete="new-password" size="large" />
              </Form.Item>
              <Form.Item
                name="confirmation"
                label="새 비밀번호 확인"
                dependencies={['newPassword']}
                rules={[
                  { required: true, message: '비밀번호를 한 번 더 입력해 주세요.' },
                  ({ getFieldValue }) => ({
                    validator: (_, value: string) =>
                      !value || value === getFieldValue('newPassword')
                        ? Promise.resolve()
                        : Promise.reject(new Error('비밀번호가 일치하지 않습니다.')),
                  }),
                ]}
              >
                <Input.Password autoComplete="new-password" size="large" />
              </Form.Item>
              <Button type="primary" htmlType="submit" size="large" block loading={sending}>
                비밀번호 설정 완료
              </Button>
            </Form>
          ) : (
            <div className="initial-password-next">
              {error.used ? (
                <Link to="/login">
                  <Button type="primary">로그인하기</Button>
                </Link>
              ) : (
                <p>
                  새 안내 링크나 계정 상태 확인이 필요하면 이용 신청을 검토한 관리자에게 문의해
                  주세요.
                </p>
              )}
            </div>
          )}
          <p className="initial-password-note">
            설정 링크는 발급 후 72시간 동안 한 번만 사용할 수 있습니다. 결제 연동은 계정 발급과
            별도로 진행합니다.
          </p>
        </>
      )}
    </PublicShell>
  )
}
