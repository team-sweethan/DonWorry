import { useRef, useState } from 'react'
import { Alert, Button, Card, Form, Input } from 'antd'
import { changePassword } from '../../api/auth'
import { ApiError } from '../../api/client'
import { PageHeader } from '../../components/common/PageHeader'

type PasswordValues = { currentPassword: string; newPassword: string; confirmation: string }

export function ChangePasswordPage() {
  const [form] = Form.useForm<PasswordValues>()
  const pending = useRef(false)
  const [submitting, setSubmitting] = useState(false)
  const [feedback, setFeedback] = useState<{ success: boolean; message: string } | null>(null)

  const submit = async (values: PasswordValues) => {
    if (pending.current) return
    pending.current = true
    setSubmitting(true)
    setFeedback(null)
    try {
      await changePassword(values.currentPassword, values.newPassword)
      form.resetFields()
      setFeedback({ success: true, message: '비밀번호가 변경되었습니다.' })
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) return
      setFeedback({
        success: false,
        message:
          error instanceof ApiError && error.status === 400
            ? (error.detail ?? '현재 비밀번호와 새 비밀번호를 확인해 주세요.')
            : '비밀번호를 변경하지 못했습니다. 잠시 후 다시 시도해 주세요.',
      })
    } finally {
      pending.current = false
      setSubmitting(false)
    }
  }

  return (
    <>
      <PageHeader title="비밀번호 변경" description="새 비밀번호로 계정을 안전하게 관리하세요." />
      <Card style={{ maxWidth: 560 }}>
        <p>
          8~64자로 영문과 숫자를 모두 포함하고, 아이디와 다르게 입력해 주세요. 한글·특수문자를
          사용할 수 있으며 UTF-8 기준 72바이트까지 가능합니다.
        </p>
        <p>변경 후에도 현재 로그인은 유지됩니다.</p>
        <Form<PasswordValues>
          form={form}
          layout="vertical"
          onFinish={submit}
          disabled={submitting}
          onValuesChange={() => setFeedback(null)}
          requiredMark={false}
        >
          <Form.Item
            name="currentPassword"
            label="현재 비밀번호"
            rules={[
              { required: true, whitespace: true, message: '현재 비밀번호를 입력해 주세요.' },
            ]}
          >
            <Input.Password autoComplete="current-password" />
          </Form.Item>
          <Form.Item
            name="newPassword"
            label="새 비밀번호"
            rules={[{ required: true, whitespace: true, message: '새 비밀번호를 입력해 주세요.' }]}
          >
            <Input.Password autoComplete="new-password" />
          </Form.Item>
          <Form.Item
            name="confirmation"
            label="새 비밀번호 확인"
            dependencies={['newPassword']}
            rules={[
              { required: true, message: '새 비밀번호를 다시 입력해 주세요.' },
              ({ getFieldValue }) => ({
                validator(_, value: string) {
                  return !value || getFieldValue('newPassword') === value
                    ? Promise.resolve()
                    : Promise.reject(new Error('새 비밀번호가 일치하지 않습니다.'))
                },
              }),
            ]}
          >
            <Input.Password autoComplete="new-password" />
          </Form.Item>
          {feedback && (
            <div role={feedback.success ? 'status' : 'alert'} style={{ marginBottom: 16 }}>
              <Alert
                type={feedback.success ? 'success' : 'error'}
                showIcon
                message={feedback.message}
              />
            </div>
          )}
          <Button type="primary" htmlType="submit" loading={submitting}>
            비밀번호 변경
          </Button>
        </Form>
      </Card>
    </>
  )
}
