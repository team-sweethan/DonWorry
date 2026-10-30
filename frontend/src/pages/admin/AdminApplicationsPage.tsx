import { isShopUrlValid } from '../../utils/shopUrl.ts'
import { useEffect, useRef, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Checkbox,
  Descriptions,
  Empty,
  Form,
  Input,
  Modal,
  Select,
  Spin,
  Table,
  Tag,
} from 'antd'
import {
  ArrowLeftOutlined,
  CheckCircleOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
} from '@ant-design/icons'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { PageHeader } from '../../components/common/PageHeader'
import { ApiError } from '../../api/client'
import {
  applicationLabels,
  approveApplication,
  formatApplicationTime,
  formatBusinessNumber,
  getApplication,
  getApplications,
  rejectApplication,
  reviewError,
  reviewMailMessage,
} from '../../api/adminApplications'
import type {
  ApplicationPage,
  ApplicationStatus,
  ApprovalInput,
  ReviewResult,
} from '../../api/adminApplications'
import { percentToFeeRate } from '../../api/adminMerchants'
import './AdminApplicationsPage.css'
import { ApplicationMailPanel } from './ApplicationMailPanel'
import {
  applyApplicationDetailSnapshot,
  requireApplicationDetailRefresh,
  type ApplicationDetailSnapshot,
} from './applicationDetailSnapshot'
import { mailStatusLabels } from '../../api/adminApplications'
const colors = {
  EMAIL_PENDING: 'default',
  REVIEW_PENDING: 'blue',
  APPROVED: 'green',
  REJECTED: 'red',
}
const statusTag = (status: ApplicationStatus) => (
  <Tag color={colors[status]}>{applicationLabels[status]}</Tag>
)
export function AdminApplicationsPage() {
  const [query, setQuery] = useSearchParams()
  const mailAttention = query.get('mailAttention') === 'true'
  const statusValue = query.get('status') ?? (mailAttention ? 'ALL' : 'REVIEW_PENDING')
  const status = statusValue in applicationLabels ? (statusValue as ApplicationStatus) : undefined
  const pageValue = Number(query.get('page') ?? 0)
  const page =
    Number.isSafeInteger(pageValue) && pageValue >= 0 && pageValue < 100000 ? pageValue : 0
  const keyword = (query.get('keyword') ?? '').slice(0, 100)
  const [revision, setRevision] = useState(0)
  const [result, setResult] = useState<{
    key: string
    data?: ApplicationPage
    error?: string
  } | null>(null)
  const key = JSON.stringify([page, keyword, status, mailAttention, revision])
  useEffect(() => {
    let active = true
    getApplications(page, keyword, status, mailAttention)
      .then((data) => {
        if (active) setResult({ key, data })
      })
      .catch(() => {
        if (active)
          setResult({ key, error: '이용 신청 목록을 불러오지 못했습니다. 다시 불러와 주세요.' })
      })
    return () => {
      active = false
    }
  }, [page, keyword, status, mailAttention, revision, key])
  const current = result?.key === key ? result : null
  const change = (next: {
    page?: number
    keyword?: string
    status?: string
    mailAttention?: boolean
  }) =>
    setQuery({
      page: String(next.page ?? 0),
      keyword: next.keyword ?? keyword,
      status: next.status ?? statusValue,
      mailAttention: String(
        next.status === 'REVIEW_PENDING' ? false : (next.mailAttention ?? mailAttention),
      ),
    })
  return (
    <div className="application-review">
      <PageHeader
        title="이용 신청"
        description="이메일 확인이 끝난 신청을 검토하고, 가맹점과 사장님 계정을 발급합니다."
        actions={
          <Button icon={<ReloadOutlined />} onClick={() => setRevision((v) => v + 1)}>
            새로고침
          </Button>
        }
      />
      <div className="ar-process" aria-label="이용 시작 절차">
        <span>01 이메일 확인</span>
        <strong>02 관리자 검토·계정 발급</strong>
        <span>03 본인 비밀번호 설정</span>
      </div>
      <Card>
        <div className="ar-filters">
          <Checkbox
            checked={mailAttention}
            onChange={(event) =>
              change({
                mailAttention: event.target.checked,
                status: event.target.checked ? 'ALL' : statusValue,
              })
            }
          >
            메일 확인 필요
          </Checkbox>
          <Select
            aria-label="신청 상태"
            value={statusValue}
            onChange={(value) => change({ status: value })}
            options={[
              { value: 'ALL', label: '전체 상태' },
              ...Object.entries(applicationLabels).map(([value, label]) => ({ value, label })),
            ]}
          />
          <Input.Search
            key={keyword}
            aria-label="가맹점명 또는 사업자등록번호 검색"
            placeholder="가맹점명 또는 사업자등록번호"
            defaultValue={keyword}
            maxLength={100}
            allowClear
            onSearch={(value) => change({ keyword: value.trim() })}
            enterButton="검색"
          />
        </div>
        {current?.error ? (
          <Alert
            type="error"
            showIcon
            message={current.error}
            action={<Button onClick={() => setRevision((v) => v + 1)}>다시 불러오기</Button>}
          />
        ) : (
          <Table
            rowKey="applicationId"
            loading={!current}
            dataSource={current?.data?.items ?? []}
            scroll={{ x: 800 }}
            locale={{
              emptyText: (
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description={
                    keyword
                      ? '검색 조건에 맞는 신청이 없습니다.'
                      : '이 상태의 이용 신청이 없습니다.'
                  }
                />
              ),
            }}
            columns={[
              {
                title: '가맹점',
                dataIndex: 'merchantName',
                render: (name: string, row) => (
                  <Link
                    className="ar-store-link"
                    to={`/admin/applications/${row.applicationId}?${query}`}
                  >
                    {name}
                  </Link>
                ),
              },
              {
                title: '사업자등록번호',
                dataIndex: 'businessRegistrationNumber',
                render: formatBusinessNumber,
              },
              { title: '담당자', dataIndex: 'contactName' },
              { title: '상태', dataIndex: 'status', render: statusTag },
              {
                title: '안내 메일',
                render: (_, row) =>
                  row.latestMail ? mailStatusLabels[row.latestMail.effectiveStatus] : '—',
              },
              {
                title: '접수일 · 한국 시간',
                dataIndex: 'createdAt',
                render: formatApplicationTime,
              },
              {
                title: '검토',
                render: (_, row) => (
                  <Link to={`/admin/applications/${row.applicationId}?${query}`}>상세 보기</Link>
                ),
              },
            ]}
            pagination={{
              current: page + 1,
              pageSize: 20,
              total: current?.data?.totalElements ?? 0,
              showSizeChanger: false,
              onChange: (value) => change({ page: value - 1 }),
              showTotal: (total) => `총 ${total}건`,
            }}
          />
        )}
      </Card>
      <p className="ar-footnote">
        이메일 확인은 주소 사용 여부를 확인하는 절차입니다. 사업자 자격과 이용 승인 여부는 관리자가
        검토해 주세요.
      </p>
    </div>
  )
}
type ApprovalFields = {
  merchantCode: string
  feePercent: string
  permissions: string[]
}
export function AdminApplicationDetailPage() {
  const { id } = useParams()
  return <ApplicationDetail key={id} id={id} />
}
function ApplicationDetail({ id }: { id: string | undefined }) {
  const [query] = useSearchParams()
  const [revision, setRevision] = useState(0)
  const [result, setResult] = useState<ApplicationDetailSnapshot | null>(null)
  const [feedback, setFeedback] = useState<{ warning: boolean; message: string } | null>(null)
  const [reviewResult, setReviewResult] = useState<ReviewResult | null>(null)
  const [confirmation, setConfirmation] = useState<
    | { kind: 'approve'; input: ApprovalInput; fee: string }
    | { kind: 'reject'; reason: string }
    | null
  >(null)
  const [sending, setSending] = useState(false)
  const pending = useRef(false)
  const [approvalForm] = Form.useForm<ApprovalFields>()
  const [rejectionForm] = Form.useForm<{ reason: string }>()
  const selectedPermissions = Form.useWatch('permissions', approvalForm) as string[] | undefined
  const feedbackElement = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (feedback) {
      feedbackElement.current?.focus()
      feedbackElement.current?.scrollIntoView({ block: 'start' })
    }
  }, [feedback])
  const key = `${id}:${revision}`
  useEffect(() => {
    let active = true
    getApplication(Number(id))
      .then((data) => {
        if (active) {
          setResult({ key, data })
        }
      })
      .catch(() => {
        if (active)
          setResult({
            key,
            error:
              '신청 정보를 불러오지 못했습니다. 신청이 존재하는지 확인하거나 다시 불러와 주세요.',
          })
      })
    return () => {
      active = false
    }
  }, [id, revision, key])
  const current = result?.key === key ? result : null
  const needsRefresh = current?.needsRefresh === true
  const data = current?.data
  const app = data?.application
  const reload = () => setRevision((v) => v + 1)
  const canReview =
    !needsRefresh &&
    app?.status === 'REVIEW_PENDING' &&
    data?.resendBlockedReason !== 'FEATURE_DISABLED'
  const perform = async () => {
    if (!confirmation || pending.current || !canReview) return
    pending.current = true
    setSending(true)
    setFeedback(null)
    try {
      const outcome =
        confirmation.kind === 'approve'
          ? await approveApplication(Number(id), confirmation.input)
          : await rejectApplication(Number(id), confirmation.reason)
      setReviewResult(outcome)
      setFeedback({ warning: outcome.mailStatus !== 'SENT', message: reviewMailMessage(outcome) })
      setConfirmation(null)
      reload()
    } catch (error) {
      setFeedback({ warning: true, message: reviewError(error) })
      setConfirmation(null)
      if (!(error instanceof ApiError) || error.status !== 400)
        setResult((current) => requireApplicationDetailRefresh(current, key))
    } finally {
      pending.current = false
      setSending(false)
    }
  }
  return (
    <div className="application-review">
      <Link className="ar-back" to={`/admin/applications?${query}`}>
        <ArrowLeftOutlined /> 신청 목록
      </Link>
      <PageHeader
        title={app?.merchantName ?? '신청 상세'}
        description={
          app?.status === 'REVIEW_PENDING'
            ? '신청 정보를 확인한 뒤 승인 또는 반려해 주세요.'
            : '신청 정보와 현재 처리 상태를 확인합니다.'
        }
        actions={
          <Button disabled={sending} icon={<ReloadOutlined />} onClick={reload}>
            최신 상태 확인
          </Button>
        }
      />
      {feedback && (
        <div ref={feedbackElement} tabIndex={-1} role="status" aria-live="polite">
          <Alert
            type={feedback.warning ? 'warning' : 'success'}
            showIcon
            message={feedback.message}
          />
        </div>
      )}
      {needsRefresh && (
        <Alert
          type="info"
          showIcon
          message="계속 처리하기 전에 최신 상태를 확인해 주세요. 입력 내용은 유지됩니다."
          action={<Button onClick={reload}>최신 상태 확인</Button>}
        />
      )}
      {!current ? (
        <div className="ar-loading">
          <Spin tip="신청 정보를 불러오는 중">
            <div style={{ height: 80 }} />
          </Spin>
        </div>
      ) : current.error ? (
        <Alert
          type="error"
          showIcon
          message={current.error}
          action={<Button onClick={reload}>다시 불러오기</Button>}
        />
      ) : (
        data &&
        app && (
          <>
            <div className={`ar-state ar-state-${app.status.toLowerCase()}`}>
              <div>
                {statusTag(app.status)}
                <span>신청 #{app.applicationId}</span>
              </div>
              <h2>
                {app.status === 'REVIEW_PENDING'
                  ? '이메일 확인이 끝났습니다. 신청 내용을 검토해 주세요.'
                  : app.status === 'EMAIL_PENDING'
                    ? '신청자가 이메일을 확인해야 검토할 수 있습니다.'
                    : app.status === 'APPROVED'
                      ? '이용 승인이 완료되었습니다.'
                      : '신청이 반려되었습니다.'}
              </h2>
              <p>
                {app.status === 'REVIEW_PENDING'
                  ? '승인하면 새 가맹점과 사장님 계정을 함께 만들고, 첫 비밀번호 설정 메일을 보냅니다.'
                  : app.status === 'EMAIL_PENDING'
                    ? '확인 메일 발송만으로 이메일 확인이 완료되지는 않습니다.'
                    : app.status === 'APPROVED'
                      ? '계정 발급과 결제 연동은 별도 절차입니다. 결제 연동은 담당자와 따로 진행해 주세요.'
                      : '이번 신청으로 계정은 발급되지 않았습니다. 신청자는 사유를 확인한 뒤 새로 신청할 수 있습니다.'}
              </p>
            </div>
            <ApplicationMailPanel
              id={Number(id)}
              detail={data}
              onDetail={(next) => {
                setResult((current) => applyApplicationDetailSnapshot(current, key, next))
              }}
            />
            <div className="ar-detail-grid">
              <div className="ar-information">
                <Card title="신청 정보">
                  <Descriptions
                    column={1}
                    size="small"
                    items={[
                      {
                        key: 'business',
                        label: '사업자등록번호',
                        children: formatBusinessNumber(app.businessRegistrationNumber),
                      },
                      {
                        key: 'shopUrl',
                        label: '쇼핑몰 주소',
                        children:
                          data.shopUrl && isShopUrlValid(data.shopUrl) ? (
                            <a href={data.shopUrl} target="_blank" rel="noopener noreferrer">
                              {data.shopUrl}
                            </a>
                          ) : (
                            (data.shopUrl ?? '기존 신청으로 수집하지 않은 정보입니다.')
                          ),
                      },
                      { key: 'contact', label: '담당자', children: app.contactName },
                      {
                        key: 'email',
                        label: '이메일',
                        children: (
                          <span>
                            {data.email} {app.emailVerifiedAt && <Tag color="green">확인 완료</Tag>}
                          </span>
                        ),
                      },
                      { key: 'phone', label: '연락처', children: data.phone },
                      {
                        key: 'requestedLoginId',
                        label: '신청 로그인 아이디',
                        children: data.requestedLoginId ?? '이전 양식 · 미입력',
                      },
                      {
                        key: 'created',
                        label: '접수일',
                        children: formatApplicationTime(app.createdAt),
                      },
                      {
                        key: 'verified',
                        label: '이메일 확인일',
                        children: formatApplicationTime(app.emailVerifiedAt),
                      },
                      {
                        key: 'consent',
                        label: '개인정보 동의',
                        children: `${formatApplicationTime(data.consentedAt)} · 문구 버전 ${data.consentVersion}`,
                      },
                    ]}
                  />
                  <div className="ar-inquiry">
                    <h3>문의·요청 사항</h3>
                    <p>{data.inquiry || '남긴 문의가 없습니다.'}</p>
                  </div>
                </Card>
                {data.duplicateMerchants.length > 0 && (
                  <Alert
                    type="warning"
                    showIcon
                    message="같은 사업자등록번호의 가맹점이 있습니다"
                    description={
                      <>
                        <p>
                          이미 등록된 가맹점인지 확인해 주세요. 별도 가맹점이 필요하면 승인할 수
                          있습니다.
                        </p>
                        <ul>
                          {data.duplicateMerchants.map((m) => (
                            <li key={m.merchantId}>
                              <Link to={`/admin/merchants/${m.merchantId}`}>
                                {m.displayName} · {m.merchantCode}
                              </Link>{' '}
                              ({m.status === 'ACTIVE' ? '사용 중' : '정지'})
                            </li>
                          ))}
                        </ul>
                      </>
                    }
                  />
                )}
                {app.status === 'APPROVED' && (
                  <Card title="발급된 가맹점·계정">
                    <Descriptions
                      column={1}
                      items={[
                        {
                          key: 'merchant',
                          label: '가맹점',
                          children: (
                            <Link to={`/admin/merchants/${data.merchantId}`}>가맹점 상세 보기</Link>
                          ),
                        },
                        {
                          key: 'user',
                          label: '로그인 아이디',
                          children: <Link to={`/admin/users/${data.userId}`}>{data.loginId}</Link>,
                        },
                        {
                          key: 'password',
                          label: '비밀번호',
                          children: data.passwordChangeRequired
                            ? '설정 또는 변경 필요'
                            : '설정 완료',
                        },
                      ]}
                    />
                    <p className="ar-footnote">
                      관리자가 비밀번호를 초기화한 경우에도 ‘설정 또는 변경 필요’로 표시됩니다.
                    </p>
                  </Card>
                )}
                {data.reviewedAt && (
                  <Card title="검토 기록">
                    <Descriptions
                      column={1}
                      items={[
                        {
                          key: 'reviewer',
                          label: '처리 관리자',
                          children: data.reviewerName ?? `계정 #${data.reviewerUserId}`,
                        },
                        {
                          key: 'reviewed',
                          label: '처리일',
                          children: formatApplicationTime(data.reviewedAt),
                        },
                        ...(data.rejectionReason
                          ? [
                              {
                                key: 'reason',
                                label: '반려 사유',
                                children: <p className="ar-reason">{data.rejectionReason}</p>,
                              },
                            ]
                          : []),
                      ]}
                    />
                  </Card>
                )}
              </div>
              {app.status === 'REVIEW_PENDING' && !reviewResult && (
                <aside className="ar-decisions" aria-label="신청 검토">
                  <Card
                    title={
                      <>
                        <SafetyCertificateOutlined /> 승인·계정 발급
                      </>
                    }
                  >
                    <p className="ar-card-intro">
                      가맹점명은 <strong>{app.merchantName}</strong>, 계정 이름은{' '}
                      <strong>{app.contactName}</strong>으로 등록합니다.
                    </p>
                    {!data.requestedLoginId && (
                      <Alert
                        type="warning"
                        showIcon
                        message="로그인 아이디가 없는 기존 신청입니다"
                        description="관리자가 임의로 아이디를 정할 수 없습니다. 아래에서 새 양식으로 재신청이 필요하다는 사유를 적어 반려해 주세요."
                      />
                    )}
                    {data.requestedLoginId && (
                      <Form<ApprovalFields>
                        form={approvalForm}
                        layout="vertical"
                        requiredMark={false}
                        initialValues={{
                          feePercent: '1',
                          permissions: ['FDS_VIEW', 'FINANCE_VIEW'],
                        }}
                        disabled={
                          sending || needsRefresh || data.resendBlockedReason === 'FEATURE_DISABLED'
                        }
                        onFinish={(values) => {
                          const fee = percentToFeeRate(values.feePercent)
                          if (fee)
                            setConfirmation({
                              kind: 'approve',
                              fee: values.feePercent,
                              input: {
                                merchantCode: values.merchantCode,
                                pgFeeRate: fee,
                                permissions: values.permissions,
                              },
                            })
                        }}
                      >
                        <Form.Item
                          name="merchantCode"
                          label="가맹점 코드"
                          extra="영문 대문자·숫자 3~40자. 기존 코드와 달라야 합니다."
                          rules={[
                            { required: true, message: '가맹점 코드를 입력해 주세요.' },
                            {
                              pattern: /^[A-Z0-9]{3,40}$/,
                              message: '영문 대문자와 숫자로 3~40자를 입력해 주세요.',
                            },
                          ]}
                        >
                          <Input autoComplete="off" maxLength={40} placeholder="예: SWEET001" />
                        </Form.Item>
                        <Form.Item
                          name="feePercent"
                          label="수수료율"
                          extra="신규 정산에 등록 수수료율을 적용합니다. 기존 정산금은 유지됩니다."
                          rules={[
                            {
                              validator: (_, value: string) =>
                                percentToFeeRate(value ?? '') !== null
                                  ? Promise.resolve()
                                  : Promise.reject(
                                      new Error('0 이상 100 미만, 소수 4자리까지 입력해 주세요.'),
                                    ),
                            },
                          ]}
                        >
                          <Input suffix="%" inputMode="decimal" maxLength={7} />
                        </Form.Item>
                        <div className="ar-selected-login">
                          <span>신청자가 선택한 로그인 아이디</span>
                          <strong>{data.requestedLoginId}</strong>
                          <p>
                            이 아이디로 계정을 발급합니다. 비밀번호는 신청자가 승인 메일에서 직접
                            설정합니다.
                          </p>
                        </div>
                        <Form.Item
                          name="permissions"
                          label="사장님 기능 권한"
                          extra={
                            selectedPermissions?.length === 0
                              ? '기능 권한을 선택하지 않으면 업무 화면에 접근할 수 없습니다.'
                              : '사장님 계정으로 발급됩니다. 관리자 권한은 부여되지 않습니다.'
                          }
                        >
                          <Checkbox.Group
                            options={[
                              { label: '조치 결과 조회', value: 'FDS_VIEW' },
                              { label: '정산·자금관리', value: 'FINANCE_VIEW' },
                            ]}
                          />
                        </Form.Item>
                        <Button
                          type="primary"
                          htmlType="submit"
                          block
                          icon={<CheckCircleOutlined />}
                        >
                          승인하고 계정 발급
                        </Button>
                      </Form>
                    )}
                  </Card>
                  <Card title="신청 반려" className="ar-reject">
                    <p className="ar-card-intro">
                      신청자가 다음 신청을 준비할 수 있도록 사유를 구체적으로 적어 주세요. 입력한
                      사유는 이메일로 전달됩니다.
                    </p>
                    <Form<{ reason: string }>
                      form={rejectionForm}
                      layout="vertical"
                      requiredMark={false}
                      disabled={
                        sending || needsRefresh || data.resendBlockedReason === 'FEATURE_DISABLED'
                      }
                      onFinish={(values) =>
                        setConfirmation({ kind: 'reject', reason: values.reason.trim() })
                      }
                    >
                      <Form.Item
                        name="reason"
                        label="반려 사유"
                        rules={[
                          {
                            required: true,
                            whitespace: true,
                            message: '신청자에게 전달할 반려 사유를 입력해 주세요.',
                          },
                          { max: 1000, message: '1,000자 이하로 입력해 주세요.' },
                        ]}
                      >
                        <Input.TextArea
                          rows={4}
                          maxLength={1000}
                          showCount
                          placeholder="예: 제출한 사업자등록번호를 확인할 수 없습니다. 번호를 확인한 뒤 새로 신청해 주세요."
                        />
                      </Form.Item>
                      <Button htmlType="submit" block>
                        반려 내용 확인
                      </Button>
                    </Form>
                  </Card>
                </aside>
              )}
            </div>
          </>
        )
      )}
      <Modal
        title={
          confirmation?.kind === 'approve'
            ? '이 정보로 승인하고 계정을 발급할까요?'
            : '신청을 반려할까요?'
        }
        open={!!confirmation && canReview}
        onCancel={() => {
          if (!sending) setConfirmation(null)
        }}
        closable={!sending}
        maskClosable={!sending}
        keyboard={!sending}
        confirmLoading={sending}
        cancelButtonProps={{ disabled: sending }}
        okText={confirmation?.kind === 'approve' ? '승인·계정 발급' : '반려하고 사유 메일 발송'}
        cancelText="돌아가서 수정"
        onOk={() => void perform()}
      >
        {confirmation?.kind === 'approve' ? (
          <>
            <Descriptions
              column={1}
              items={[
                { key: 'merchant', label: '가맹점', children: app?.merchantName },
                { key: 'code', label: '가맹점 코드', children: confirmation.input.merchantCode },
                { key: 'login', label: '로그인 아이디', children: data?.requestedLoginId },
                { key: 'fee', label: '수수료율', children: `${confirmation.fee}%` },
                {
                  key: 'permissions',
                  label: '기능 권한',
                  children:
                    confirmation.input.permissions
                      .map((p) => (p === 'FDS_VIEW' ? '조치 결과 조회' : '정산·자금관리'))
                      .join(', ') || '없음',
                },
              ]}
            />
            <Alert
              type="info"
              showIcon
              message="신청한 이메일로 첫 비밀번호 설정 링크를 보냅니다."
              description="승인 후 신청 상태를 되돌릴 수 없습니다. 결제 연동은 별도로 진행해야 합니다."
            />
          </>
        ) : (
          <>
            <p>
              이번 신청으로 가맹점과 계정은 생성되지 않습니다. 신청자는 새로 신청할 수 있습니다.
            </p>
            <p className="ar-reason">{confirmation?.reason}</p>
            <p>위 사유를 {data?.email}로 보냅니다.</p>
          </>
        )}
      </Modal>
    </div>
  )
}
