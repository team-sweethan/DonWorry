import { isShopUrlValid } from '../../utils/shopUrl.ts'
import { useRef, useState, type ReactNode } from 'react'
import { Alert, Button, Checkbox, Descriptions, Form, Input, Result, Space } from 'antd'
import { Link, useLoaderData } from 'react-router-dom'
import { BrandMark, Mascot } from '../../components/brand/BrandMark'
import {
  submitApplication,
  verifyApplicationEmail,
  resendApplicationEmail,
  normalizeApplication,
  type ApplicationInput,
  type MailStatus,
} from '../../api/merchantApplications'
import { ApiError } from '../../api/client'
import { applicationError } from './applicationFeedback'
import './ApplicationPage.css'

const journey = ['신청 및 이메일 확인', '관리자 검토', '승인 후 계정 발급', '첫 비밀번호 설정']

/** 신청 · 이메일 확인 · 첫 비밀번호 화면의 공통 틀. step을 주면 4단계 중 지금 위치를 보여 준다. */
export function PublicShell({ children, step }: { children: ReactNode; step?: 1 | 2 | 3 | 4 }) {
  return (
    <div className="dw-application-shell">
      <header className="dw-application-header">
        <Link to="/" className="dw-public-brand" aria-label="돈워리 서비스 소개" viewTransition>
          <BrandMark className="dw-mark" />
          돈워리
        </Link>
        <Link className="dw-btn dw-btn-text" to="/login" viewTransition>
          로그인
        </Link>
      </header>
      {step && (
        <ol className="dw-application-journey" aria-label="이용 시작 절차">
          {journey.map((title, index) => {
            const state = index + 1 < step ? 'done' : index + 1 === step ? 'now' : 'next'
            return (
              <li
                key={title}
                className={`is-${state}`}
                aria-current={state === 'now' ? 'step' : undefined}
              >
                <span aria-hidden="true">{index + 1}</span>
                {title}
              </li>
            )
          })}
        </ol>
      )}
      <main className="dw-application-main">{children}</main>
    </div>
  )
}
function ResendEmail({ initialEmail = '' }: { initialEmail?: string }) {
  const busy = useRef(false)
  const [sending, setSending] = useState(false)
  const [feedback, setFeedback] = useState<string | null>(null)
  const [failed, setFailed] = useState(false)
  const resend = async ({ email }: { email: string }) => {
    if (busy.current) return
    busy.current = true
    setSending(true)
    setFeedback(null)
    try {
      await resendApplicationEmail(email)
      setFailed(false)
      setFeedback(
        '이메일 미확인 신청이 있으면 확인 메일을 발송합니다. 받은편지함과 스팸함을 확인해 주세요. 가장 최근 링크만 사용할 수 있습니다.',
      )
    } catch (error) {
      setFailed(true)
      setFeedback(applicationError(error, 'resend'))
    } finally {
      busy.current = false
      setSending(false)
    }
  }
  return (
    <section className="dw-application-resend" aria-labelledby="resend-title">
      <h2 id="resend-title">확인 메일을 받지 못하셨나요?</h2>
      <p>1분에 1회, 24시간에 5회까지 다시 요청할 수 있습니다.</p>
      <Form
        name="application-resend"
        layout="vertical"
        onFinish={resend}
        initialValues={{ email: initialEmail }}
        disabled={sending}
        requiredMark={false}
      >
        <Form.Item
          name="email"
          label="신청한 이메일"
          rules={[
            { required: true, message: '이메일을 입력해 주세요.' },
            { type: 'email', message: '이메일 형식을 확인해 주세요.' },
            { max: 254, message: '254자 이하로 입력해 주세요.' },
          ]}
        >
          <Input type="email" autoComplete="email" maxLength={254} />
        </Form.Item>
        <Button htmlType="submit" loading={sending}>
          확인 메일 다시 요청
        </Button>
      </Form>
      <div role={failed ? 'alert' : 'status'}>
        {feedback && <Alert type={failed ? 'error' : 'info'} message={feedback} showIcon />}
      </div>
    </section>
  )
}
function ConsentNotice() {
  return (
    <section className="dw-application-consent" aria-labelledby="consent-title">
      <h2 id="consent-title">개인정보 수집·이용 안내</h2>
      <dl>
        <dt>수집 항목</dt>
        <dd>
          가맹점명, 사업자등록번호, 쇼핑몰 주소, 담당자명, 이메일, 연락처, 로그인 아이디, 선택
          입력한 문의·요청 사항
        </dd>
        <dt>이용 목적</dt>
        <dd>이용 신청 확인, 쇼핑몰 운영 여부 확인, 검토 결과 안내, 계정 발급</dd>
        <dt>보관 기간</dt>
        <dd>이메일 미확인 신청 7일, 반려 신청 30일, 승인 신청은 가맹점 이용 기간 동안</dd>
      </dl>
      <p>동의하지 않으면 이용 신청을 할 수 없습니다.</p>
    </section>
  )
}
export function ApplicationPage() {
  const [form] = Form.useForm<ApplicationInput>()
  const [review, setReview] = useState<ApplicationInput | null>(null)
  const [receipt, setReceipt] = useState<{
    email: string
    requestedLoginId: string
    mailStatus: MailStatus
  } | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const busy = useRef(false)
  const heading = useRef<HTMLHeadingElement>(null)
  const focusHeading = () => requestAnimationFrame(() => heading.current?.focus())
  const submit = async () => {
    if (!review || busy.current) return
    busy.current = true
    setSubmitting(true)
    setError(null)
    try {
      const result = await submitApplication(review)
      setReceipt({
        email: normalizeApplication(review).email,
        requestedLoginId: normalizeApplication(review).requestedLoginId,
        mailStatus: result.mailStatus,
      })
      setReview(null)
      form.resetFields()
      focusHeading()
    } catch (cause) {
      setError(applicationError(cause))
      if (cause instanceof ApiError && cause.code === 'APPLICATION_018') {
        setReview(null)
        form.setFields([
          {
            name: 'requestedLoginId',
            errors: [cause.detail ?? '다른 로그인 아이디를 입력해 주세요.'],
          },
        ])
        requestAnimationFrame(() => form.scrollToField('requestedLoginId', { focus: true }))
      }
    } finally {
      busy.current = false
      setSubmitting(false)
    }
  }
  return (
    <PublicShell step={1}>
      {receipt && <Mascot size={120} className="dw-application-mascot dw-mascot-float" />}
      <h1 ref={heading} tabIndex={-1}>
        {receipt
          ? '신청이 접수되었습니다'
          : review
            ? '신청 내용을 확인해 주세요'
            : '돈워리 이용 신청'}
      </h1>
      {receipt ? (
        <>
          <p>
            이메일 확인 후 관리자가 신청을 검토합니다. 아직 이용 승인·계정 발급이 완료된 상태는
            아닙니다.
          </p>
          <Alert
            showIcon
            type={receipt.mailStatus === 'SENT' ? 'success' : 'warning'}
            message={
              receipt.mailStatus === 'SENT'
                ? `${receipt.email}로 확인 메일을 보냈습니다. 링크는 24시간 동안 유효합니다.`
                : '접수는 완료되었지만 메일 발송 결과를 확인하지 못했습니다. 메일함을 확인하고 필요하면 다시 요청해 주세요.'
            }
          />
          <p>
            선택한 로그인 아이디는 <strong>{receipt.requestedLoginId}</strong>입니다. 승인 후 이
            아이디로 계정을 발급하고 비밀번호 설정 방법을 안내합니다.
          </p>
          <ResendEmail initialEmail={receipt.email} />
        </>
      ) : (
        <>
          <p>
            가맹점·담당자 정보와 사용할 로그인 아이디를 입력해 주세요. 관리자 승인 후 계정을
            발급하며, 결제 연동은 별도로 진행합니다.
          </p>
          <Form<ApplicationInput>
            name="merchant-application"
            form={form}
            layout="vertical"
            requiredMark={false}
            disabled={submitting}
            initialValues={{ consent: false }}
            onFinish={(values) => {
              setReview(values)
              setError(null)
              focusHeading()
            }}
            scrollToFirstError
          >
            <div hidden={review !== null}>
              <h2>가맹점 정보</h2>
              <Form.Item
                name="merchantName"
                label="가맹점명"
                rules={[
                  { required: true, whitespace: true, message: '가맹점명을 입력해 주세요.' },
                  { max: 100, message: '100자 이하로 입력해 주세요.' },
                ]}
              >
                <Input maxLength={100} autoComplete="organization" />
              </Form.Item>
              <Form.Item
                name="businessRegistrationNumber"
                label="사업자등록번호"
                rules={[
                  { required: true, message: '사업자등록번호를 입력해 주세요.' },
                  {
                    pattern: /^[0-9]{10}$/,
                    transform: (value: string) => value?.replace(/[- ]/g, ''),
                    message: '사업자등록번호 10자리를 입력해 주세요.',
                  },
                ]}
              >
                <Input maxLength={20} inputMode="numeric" placeholder="000-00-00000" />
              </Form.Item>
              <Form.Item
                name="shopUrl"
                label="쇼핑몰 주소"
                extra="현재 운영 중인 쇼핑몰 주소를 입력해 주세요. 이용 신청 검토에 사용합니다."
                rules={[
                  { required: true, whitespace: true, message: '쇼핑몰 주소를 입력해 주세요.' },
                  {
                    validator: (_, value: string | undefined) =>
                      !value?.trim() || isShopUrlValid(value)
                        ? Promise.resolve()
                        : Promise.reject(
                            new Error(
                              'http:// 또는 https://로 시작하는 쇼핑몰 주소를 입력해 주세요.',
                            ),
                          ),
                  },
                ]}
              >
                <Input
                  maxLength={2048}
                  inputMode="url"
                  autoComplete="url"
                  placeholder="https://shop.example.com"
                />
              </Form.Item>
              <h2>담당자 정보</h2>
              <Form.Item
                name="contactName"
                label="담당자명"
                rules={[
                  { required: true, whitespace: true, message: '담당자명을 입력해 주세요.' },
                  { max: 100, message: '100자 이하로 입력해 주세요.' },
                ]}
              >
                <Input maxLength={100} autoComplete="name" />
              </Form.Item>
              <Form.Item
                name="email"
                label="이메일"
                extra="이메일 확인과 검토 결과를 받을 주소입니다."
                rules={[
                  { required: true, message: '이메일을 입력해 주세요.' },
                  {
                    type: 'email',
                    transform: (value: string) => value?.trim(),
                    message: '이메일 형식을 확인해 주세요.',
                  },
                  { max: 254, message: '254자 이하로 입력해 주세요.' },
                ]}
              >
                <Input type="email" maxLength={254} autoComplete="email" />
              </Form.Item>
              <Form.Item
                name="phone"
                label="연락처"
                rules={[
                  { required: true, message: '연락처를 입력해 주세요.' },
                  {
                    pattern: /^[0-9]{9,15}$/,
                    transform: (value: string) => value?.replace(/[- ]/g, ''),
                    message: '연락처 숫자 9~15자리를 입력해 주세요.',
                  },
                ]}
              >
                <Input type="tel" maxLength={25} autoComplete="tel" />
              </Form.Item>
              <h2>사용할 계정</h2>
              <Form.Item
                name="requestedLoginId"
                label="로그인 아이디"
                extra="영문 소문자·숫자·밑줄(_) 3~30자. 사용 중이거나 다른 신청에서 선택한 아이디는 사용할 수 없습니다."
                rules={[
                  {
                    required: true,
                    whitespace: true,
                    message: '사용할 로그인 아이디를 입력해 주세요.',
                  },
                  {
                    pattern: /^[a-z0-9_]{3,30}$/,
                    transform: (value: string) => value?.trim(),
                    message: '영문 소문자·숫자·밑줄로 3~30자를 입력해 주세요.',
                  },
                ]}
              >
                <Input
                  maxLength={30}
                  autoComplete="username"
                  autoCapitalize="none"
                  spellCheck={false}
                  placeholder="예: sweet_owner"
                />
              </Form.Item>
              <p className="dw-application-account-note">
                승인되면 선택한 아이디로 계정을 발급합니다. 비밀번호는 승인 안내 메일의 링크에서
                직접 설정합니다.
              </p>
              <Form.Item
                name="inquiry"
                label="문의·요청 사항 (선택)"
                rules={[{ max: 1000, message: '1,000자 이하로 입력해 주세요.' }]}
              >
                <Input.TextArea maxLength={1000} showCount rows={4} />
              </Form.Item>
              <ConsentNotice />
              <Form.Item
                name="consent"
                valuePropName="checked"
                rules={[
                  {
                    validator: (_, value: boolean) =>
                      value
                        ? Promise.resolve()
                        : Promise.reject(new Error('개인정보 수집·이용 동의가 필요합니다.')),
                  },
                ]}
              >
                <Checkbox>[필수] 개인정보 수집·이용에 동의합니다.</Checkbox>
              </Form.Item>
              <Button type="primary" htmlType="submit">
                입력 내용 확인
              </Button>
            </div>
          </Form>
          {review && (
            <>
              <Descriptions
                bordered
                column={1}
                size="small"
                items={[
                  { key: 'merchant', label: '가맹점명', children: review.merchantName },
                  { key: 'shopUrl', label: '쇼핑몰 주소', children: review.shopUrl.trim() },
                  {
                    key: 'business',
                    label: '사업자등록번호',
                    children: review.businessRegistrationNumber,
                  },
                  {
                    key: 'login',
                    label: '로그인 아이디',
                    children: normalizeApplication(review).requestedLoginId,
                  },
                  {
                    key: 'password',
                    label: '비밀번호 설정',
                    children: '승인 후 메일의 링크에서 직접 설정',
                  },
                  { key: 'contact', label: '담당자명', children: review.contactName },
                  { key: 'email', label: '이메일', children: normalizeApplication(review).email },
                  { key: 'phone', label: '연락처', children: review.phone },
                  { key: 'inquiry', label: '문의·요청 사항', children: review.inquiry || '없음' },
                  { key: 'consent', label: '개인정보 동의', children: '동의함' },
                ]}
              />
              <Space className="dw-application-actions" wrap>
                <Button
                  disabled={submitting}
                  onClick={() => {
                    setReview(null)
                    setError(null)
                    focusHeading()
                  }}
                >
                  입력 내용 수정
                </Button>
                <Button type="primary" loading={submitting} onClick={submit}>
                  신청 제출
                </Button>
              </Space>
            </>
          )}
          <div role="alert">{error && <Alert type="error" message={error} showIcon />}</div>
          <ResendEmail />
        </>
      )}
      <p className="dw-application-bottom">
        <Link to="/">서비스 소개로 돌아가기</Link>
      </p>
    </PublicShell>
  )
}
export function VerifyEmailPage() {
  const { token } = useLoaderData() as { token: string | null }
  const [verified, setVerified] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const busy = useRef(false)
  const resultHeading = useRef<HTMLHeadingElement>(null)
  const verify = async () => {
    if (!token || busy.current) return
    busy.current = true
    setSubmitting(true)
    setError(null)
    try {
      await verifyApplicationEmail(token)
      setVerified(true)
      requestAnimationFrame(() => resultHeading.current?.focus())
    } catch (cause) {
      setError(applicationError(cause, 'verify'))
    } finally {
      busy.current = false
      setSubmitting(false)
    }
  }
  return (
    <PublicShell step={verified ? 2 : 1}>
      {verified ? (
        <Result
          status="success"
          icon={<Mascot size={140} className="dw-mascot-float" />}
          title={
            <h1 ref={resultHeading} tabIndex={-1}>
              이메일 확인이 완료되었습니다
            </h1>
          }
          subTitle="관리자 검토 후 결과를 이메일로 안내합니다. 계정 발급과 결제 연동은 아직 완료되지 않았습니다."
        />
      ) : (
        <>
          <h1>이메일 확인</h1>
          <p>
            {token
              ? '본인이 신청한 이메일이라면 아래 버튼을 눌러 확인을 완료해 주세요.'
              : '메일의 확인 링크를 다시 열어 주세요. 링크가 만료되었다면 아래에서 새 메일을 요청할 수 있습니다.'}
          </p>
          {token && (
            <Button type="primary" loading={submitting} onClick={verify}>
              이메일 확인
            </Button>
          )}
          <div role="alert">{error && <Alert type="error" showIcon message={error} />}</div>
          <ResendEmail />
        </>
      )}
      <p className="dw-application-bottom">
        <Link to="/">서비스 소개로 돌아가기</Link>
      </p>
    </PublicShell>
  )
}
