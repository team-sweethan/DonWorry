import {
  applicationEmptyKind,
  applicationListStatus,
  normalizeApplicationListStatusQuery,
} from '../../utils/applicationListStatusQuery.ts'
import { isShopUrlValid } from '../../utils/shopUrl.ts'
import { IntegrationStatusSummary } from './IntegrationStatusSummary'
import { useEffect, useRef, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  Modal,
  Segmented,
  Spin,
  Switch,
  Table,
  Tag,
  Typography,
} from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { ListEmpty } from '../../components/common/ListEmpty'
import { PageHeader } from '../../components/common/PageHeader'
import { lastListPage } from '../../utils/listQuery'
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
import { mailTag, statusTag } from './applicationStatusTag'
// 탭 순서. 기본 목록인 검토 대기를 맨 앞에 둔다.
const statusTabs: ApplicationStatus[] = [
  'REVIEW_PENDING',
  'EMAIL_VERIFICATION_PENDING',
  'APPROVED',
  'REJECTED',
]
type StatusCounts = Record<ApplicationStatus, number>
/** 탭 숫자용. 검색어·메일 필터와 상관없이 상태별 전체 건수를 센다. 실패하면 숫자만 숨긴다. */
function useStatusCounts(revision: number) {
  const [counts, setCounts] = useState<{ revision: number; value: StatusCounts | null } | null>(
    null,
  )
  useEffect(() => {
    let active = true
    Promise.all(statusTabs.map((status) => getApplications(0, '', status)))
      .then((pages) => {
        if (!active) return
        const value = Object.fromEntries(
          statusTabs.map((status, index) => [status, pages[index].totalElements]),
        ) as StatusCounts
        setCounts({ revision, value })
      })
      .catch(() => {
        if (active) setCounts({ revision, value: null })
      })
    return () => {
      active = false
    }
  }, [revision])
  return counts?.revision === revision ? counts.value : null
}
export function AdminApplicationsPage() {
  const [query, setQuery] = useSearchParams()
  const mailAttention = query.get('mailAttention') === 'true'
  const statusValue = applicationListStatus(query)
  const status = statusValue === 'ALL' ? undefined : statusValue
  useEffect(() => {
    const normalized = normalizeApplicationListStatusQuery(query)
    if (normalized.toString() !== query.toString()) setQuery(normalized, { replace: true })
  }, [query, setQuery])
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
  const counts = useStatusCounts(revision)
  const countTotal = counts ? statusTabs.reduce((sum, status) => sum + counts[status], 0) : null
  const tabLabel = (label: string, count: number | null) => (
    <span className="ar-tab">
      {label}
      {count !== null && <span className="ar-tab-count">{count.toLocaleString('ko-KR')}</span>}
    </span>
  )
  const emptyKind = applicationEmptyKind(statusValue, keyword, mailAttention)
  // 이 화면의 page는 0부터 시작한다.
  const lastPage = current?.data ? lastListPage(current.data.totalElements) - 1 : null
  const pageOutOfRange = lastPage !== null && page > lastPage
  useEffect(() => {
    // 마지막 행을 처리하고 돌아오면 빈 페이지에 갇히지 않게 있는 페이지로 옮긴다.
    if (lastPage === null || page <= lastPage) return
    const next = new URLSearchParams(query)
    next.set('page', String(lastPage))
    setQuery(next, { replace: true })
  }, [lastPage, page, query, setQuery])
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
        description="가게 사장님이 보낸 이용 신청을 검토하고, 승인하면 가맹점과 사장님 계정을 만듭니다."
        actions={
          <Button icon={<ReloadOutlined />} onClick={() => setRevision((v) => v + 1)}>
            새로고침
          </Button>
        }
      />
      <ol className="ar-process" aria-label="이용 시작 절차">
        <li>사장님 이메일 확인</li>
        <li className="is-current" aria-current="step">
          관리자 검토·사장님 계정 만들기
        </li>
        <li>사장님 첫 비밀번호 설정</li>
      </ol>
      <Card className="ar-list">
        <div className="ar-toolbar">
          <Segmented
            className="ar-status-tabs"
            aria-label="신청 상태"
            value={statusValue}
            onChange={(value) => change({ status: String(value) })}
            options={[
              ...statusTabs.map((value) => ({
                value,
                label: tabLabel(applicationLabels[value], counts?.[value] ?? null),
              })),
              { value: 'ALL', label: tabLabel('전체', countTotal) },
            ]}
          />
          <div className="ar-filters">
            <Button
              className={mailAttention ? 'ar-mail-toggle is-on' : 'ar-mail-toggle'}
              aria-pressed={mailAttention}
              onClick={() =>
                change({
                  mailAttention: !mailAttention,
                  status: !mailAttention ? 'ALL' : statusValue,
                })
              }
            >
              <span aria-hidden="true">{mailAttention ? '✓' : '+'}</span>메일 발송 문제만
            </Button>
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
              emptyText:
                !current || pageOutOfRange ? (
                  <span aria-hidden="true" />
                ) : emptyKind === 'no-result' ? (
                  <ListEmpty
                    title="검색 조건에 맞는 신청이 없어요"
                    description="검색어나 필터를 바꿔 보세요."
                    action={<Button onClick={() => setQuery({})}>조건 지우기</Button>}
                  />
                ) : (
                  <ListEmpty
                    mascot
                    title={
                      emptyKind === 'all-done' ? '검토할 신청이 없어요' : '아직 이용 신청이 없어요'
                    }
                    description="새 신청이 들어오면 여기에 표시돼요."
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
              { title: '신청한 사장님', dataIndex: 'contactName' },
              { title: '상태', dataIndex: 'status', render: statusTag },
              {
                title: '보낸 메일',
                render: (_, row) =>
                  row.latestMail ? (
                    mailTag(row.latestMail.effectiveStatus)
                  ) : (
                    <span className="ar-muted">—</span>
                  ),
              },
              {
                title: '신청일 (한국 시간)',
                dataIndex: 'createdAt',
                render: (value: string) => (
                  <span className="ar-time">{formatApplicationTime(value)}</span>
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
        이메일 확인은 메일 주소가 실제로 쓰이는지만 확인합니다. 진짜 사업자인지, 승인해도 되는지는
        관리자가 직접 판단해 주세요.
      </p>
    </div>
  )
}
type ApprovalFields = {
  merchantCode: string
  feePercent: string
  permissions: string[]
}
const permissionOptions = [
  {
    value: 'FDS_VIEW',
    label: 'FDS',
  },
  {
    value: 'FINANCE_VIEW',
    label: '정산·자금관리',
  },
]
/** Form.Item이 value · onChange를 넣어 준다. 체크박스 대신 권한마다 스위치 한 줄. */
function PermissionSwitches({
  value = [],
  onChange,
  disabled,
}: {
  value?: string[]
  onChange?: (value: string[]) => void
  disabled?: boolean
}) {
  return (
    <div className="ar-switches">
      {permissionOptions.map((option) => (
        <label key={option.value} className="ar-switch">
          <strong>{option.label}</strong>
          <Switch
            size="small"
            disabled={disabled}
            checked={value.includes(option.value)}
            onChange={(checked) =>
              onChange?.(
                checked
                  ? permissionOptions
                      .map((item) => item.value)
                      .filter((item) => item === option.value || value.includes(item))
                  : value.filter((item) => item !== option.value),
              )
            }
          />
        </label>
      ))}
    </div>
  )
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
  const [reviewTab, setReviewTab] = useState<'approve' | 'reject'>('approve')
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
  const mailPanel = data ? (
    <ApplicationMailPanel
      id={Number(id)}
      detail={data}
      primary={app?.status === 'EMAIL_VERIFICATION_PENDING'}
      onDetail={(next) => {
        setResult((current) => applyApplicationDetailSnapshot(current, key, next))
      }}
    />
  ) : null
  return (
    <div className="application-review">
      <nav className="ar-crumb" aria-label="위치">
        <Link to={`/admin/applications?${query}`}>이용 신청</Link>
        <span aria-hidden="true">›</span>
        <span className="ar-crumb-current">
          {app ? `${app.merchantName} · #${app.applicationId}` : '신청 상세'}
        </span>
      </nav>
      <PageHeader
        title={app?.merchantName ?? '신청 상세'}
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
            <div className="ar-summary">
              <div className="ar-meta">
                {statusTag(app.status)}
                <span className="ar-meta-sep" aria-hidden="true">
                  ·
                </span>
                <span>신청 #{app.applicationId}</span>
                <span className="ar-meta-sep" aria-hidden="true">
                  ·
                </span>
                <span>{formatApplicationTime(app.createdAt)} 신청</span>
              </div>
              {app.status !== 'APPROVED' && (
                <p className="ar-lead">
                  {app.status === 'REVIEW_PENDING'
                    ? '신청 내용을 보고 승인하거나 반려해 주세요.'
                    : app.status === 'EMAIL_VERIFICATION_PENDING'
                      ? '사장님이 메일 속 확인 링크를 눌러야 검토할 수 있습니다.'
                      : '사장님은 사유를 보고 새로 신청할 수 있습니다.'}
                </p>
              )}
            </div>
            <div className="ar-detail">
              <div className="ar-body">
                {data.duplicateMerchants.length > 0 && (
                  <Alert
                    className="ar-duplicate"
                    type="warning"
                    showIcon
                    message="같은 사업자등록번호로 이미 등록된 가맹점이 있습니다"
                    description={
                      <>
                        <p>
                          같은 가게의 중복 신청인지 확인해 주세요. 지점처럼 가맹점을 따로 둬야 하면
                          승인해도 됩니다.
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
                  <section className="ar-primary" aria-labelledby="ar-issued-title">
                    <h2 className="ar-primary-title" id="ar-issued-title">
                      만든 사장님 계정 <span className="ar-role is-owner">사장님</span>
                    </h2>
                    <dl className="ar-issued">
                      <div>
                        <dt>사장님 아이디</dt>
                        <dd className="ar-issued-main">
                          <Link to={`/admin/users/${data.userId}`}>{data.loginId}</Link>
                        </dd>
                      </div>
                      <div>
                        <dt>비밀번호</dt>
                        <dd>
                          {data.passwordChangeRequired ? (
                            <Tag color="gold">설정 필요</Tag>
                          ) : (
                            <Tag color="green">설정 완료</Tag>
                          )}
                        </dd>
                      </div>
                      <div>
                        <dt>가맹점</dt>
                        <dd>
                          <Link to={`/admin/merchants/${data.merchantId}`}>가맹점 상세 보기</Link>
                        </dd>
                      </div>
                    </dl>
                    <p className="ar-primary-note">
                      사장님이 아직 비밀번호를 정하지 않았거나, 관리자가 초기화하면 ‘설정 필요’로
                      보입니다.
                    </p>
                  </section>
                )}
                {app.status === 'APPROVED' && data.merchantId !== null && (
                  <IntegrationStatusSummary key={data.merchantId} merchantId={data.merchantId} />
                )}
                {app.status === 'REJECTED' && (
                  <section className="ar-primary" aria-labelledby="ar-rejected-title">
                    <h2 className="ar-primary-title" id="ar-rejected-title">
                      반려 사유
                    </h2>
                    <p className="ar-reject-reason">
                      {data.rejectionReason || '기록된 반려 사유가 없습니다.'}
                    </p>
                    {data.reviewedAt && (
                      <p className="ar-primary-note">
                        <span className="ar-role is-admin">관리자</span>{' '}
                        {data.reviewerName ?? `계정 #${data.reviewerUserId}`} ·{' '}
                        {formatApplicationTime(data.reviewedAt)} · 이 사유가 사장님 메일로 나갑니다
                      </p>
                    )}
                  </section>
                )}
                {app.status === 'EMAIL_VERIFICATION_PENDING' && mailPanel}
                <section className="ar-section" aria-labelledby="ar-inquiry-title">
                  <h2 className="ar-section-title" id="ar-inquiry-title">
                    사장님이 남긴 말
                  </h2>
                  <p className={data.inquiry ? 'ar-quote' : 'ar-quote is-empty'}>
                    {data.inquiry || '남긴 말이 없습니다.'}
                  </p>
                </section>
                {app.status === 'REVIEW_PENDING' && !reviewResult && (
                  <section className="ar-primary" aria-labelledby="ar-review-title">
                    <h2 className="ar-primary-title" id="ar-review-title">
                      검토
                    </h2>
                    <div className="ar-box ar-review">
                      <div className="ar-review-tabs">
                        <Segmented
                          aria-label="검토 방식"
                          value={reviewTab}
                          onChange={(value) => setReviewTab(value as 'approve' | 'reject')}
                          options={[
                            { value: 'approve', label: '승인' },
                            { value: 'reject', label: '반려' },
                          ]}
                        />
                      </div>
                      <div className="ar-review-body" hidden={reviewTab !== 'approve'}>
                        {!data.requestedLoginId ? (
                          <Alert
                            type="warning"
                            showIcon
                            message="아이디를 받지 않은 예전 양식의 신청입니다"
                            description="관리자가 사장님 아이디를 대신 정할 수 없습니다. ‘반려’ 탭에서 새 양식으로 다시 신청해 달라고 적어 반려해 주세요."
                          />
                        ) : (
                          <Form<ApprovalFields>
                            form={approvalForm}
                            layout="vertical"
                            requiredMark={false}
                            initialValues={{
                              feePercent: '1',
                              permissions: ['FDS_VIEW', 'FINANCE_VIEW'],
                            }}
                            disabled={
                              sending ||
                              needsRefresh ||
                              data.resendBlockedReason === 'FEATURE_DISABLED'
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
                            <div className="ar-fixed">
                              <span>사장님이 고른 아이디</span>
                              <strong>{data.requestedLoginId}</strong>
                            </div>
                            <p className="ar-help">
                              이 아이디로 사장님 계정을 만듭니다. 비밀번호는 사장님이 승인 메일에서
                              직접 정합니다.
                            </p>
                            <div className="ar-field-row">
                              <Form.Item
                                name="merchantCode"
                                label="가맹점 코드"
                                extra="가맹점을 구분하는 코드입니다. 영문 대문자·숫자 3~40자, 다른 가맹점과 겹치면 안 됩니다."
                                rules={[
                                  { required: true, message: '가맹점 코드를 입력해 주세요.' },
                                  {
                                    pattern: /^[A-Z0-9]{3,40}$/,
                                    message: '영문 대문자와 숫자로 3~40자를 입력해 주세요.',
                                  },
                                ]}
                              >
                                <Input
                                  autoComplete="off"
                                  maxLength={40}
                                  placeholder="예: SWEET001"
                                />
                              </Form.Item>
                              <Form.Item
                                name="feePercent"
                                label="PG 수수료율"
                                extra="앞으로 생기는 정산부터 적용됩니다. 이미 계산된 정산금은 그대로입니다."
                                rules={[
                                  {
                                    validator: (_, value: string) =>
                                      percentToFeeRate(value ?? '') !== null
                                        ? Promise.resolve()
                                        : Promise.reject(
                                            new Error(
                                              '0 이상 100 미만, 소수 4자리까지 입력해 주세요.',
                                            ),
                                          ),
                                  },
                                ]}
                              >
                                <Input suffix="%" inputMode="decimal" maxLength={7} />
                              </Form.Item>
                            </div>
                            <Form.Item
                              name="permissions"
                              label="사장님 기능 권한"
                              extra={
                                selectedPermissions?.length === 0 ? (
                                  <span className="ar-help-warn">
                                    하나도 켜지 않으면 사장님이 들어갈 수 있는 화면이 없습니다.
                                  </span>
                                ) : undefined
                              }
                            >
                              <PermissionSwitches />
                            </Form.Item>
                            <div className="ar-actions">
                              <p>
                                가맹점 이름 <strong>{app.merchantName}</strong> · 사장님 이름{' '}
                                <strong>{app.contactName}</strong>
                              </p>
                              <Button type="primary" htmlType="submit">
                                승인하고 사장님 계정 만들기
                              </Button>
                            </div>
                          </Form>
                        )}
                      </div>
                      <div className="ar-review-body" hidden={reviewTab !== 'reject'}>
                        <Form<{ reason: string }>
                          form={rejectionForm}
                          layout="vertical"
                          requiredMark={false}
                          disabled={
                            sending ||
                            needsRefresh ||
                            data.resendBlockedReason === 'FEATURE_DISABLED'
                          }
                          onFinish={(values) =>
                            setConfirmation({ kind: 'reject', reason: values.reason.trim() })
                          }
                        >
                          <Form.Item
                            name="reason"
                            label="반려 사유"
                            extra="적은 사유는 사장님 메일로 그대로 갑니다. 무엇을 고치면 되는지 적어 주세요."
                            rules={[
                              {
                                required: true,
                                whitespace: true,
                                message: '사장님에게 보낼 반려 사유를 입력해 주세요.',
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
                          <div className="ar-actions">
                            <p>반려하면 가맹점과 사장님 계정을 만들지 않습니다.</p>
                            <Button danger htmlType="submit">
                              반려하기
                            </Button>
                          </div>
                        </Form>
                      </div>
                    </div>
                  </section>
                )}
                {app.status !== 'EMAIL_VERIFICATION_PENDING' && mailPanel}
                <section className="ar-section" aria-labelledby="ar-history-title">
                  <h2 className="ar-section-title" id="ar-history-title">
                    기록
                  </h2>
                  <ol className="ar-timeline">
                    <li>
                      <span className="ar-timeline-mark" aria-hidden="true" />
                      <span>
                        <span className="ar-role is-owner">사장님</span>{' '}
                        <strong>{app.contactName}</strong> 님이 이용을 신청했습니다
                      </span>
                      <time>{formatApplicationTime(app.createdAt)}</time>
                    </li>
                    {app.emailVerifiedAt && (
                      <li>
                        <span className="ar-timeline-mark" aria-hidden="true" />
                        <span>
                          <span className="ar-role is-owner">사장님</span> 메일의 확인 링크를
                          눌렀습니다
                        </span>
                        <time>{formatApplicationTime(app.emailVerifiedAt)}</time>
                      </li>
                    )}
                    {data.reviewedAt && (
                      <li>
                        <span className="ar-timeline-mark is-strong" aria-hidden="true" />
                        <span className="ar-timeline-text">
                          <span className="ar-role is-admin">관리자</span>
                          <strong>
                            {data.reviewerName ?? `계정 #${data.reviewerUserId}`}
                          </strong>{' '}
                          님이 처리했습니다 {statusTag(app.status)}
                        </span>
                        <time>{formatApplicationTime(data.reviewedAt)}</time>
                      </li>
                    )}
                  </ol>
                </section>
              </div>
              <aside className="ar-props" aria-label="신청 정보">
                <section>
                  <h2>
                    신청한 사장님 <span className="ar-role is-owner">사장님</span>
                  </h2>
                  <dl>
                    <dt>이름</dt>
                    <dd>{app.contactName}</dd>
                    <dt>이메일</dt>
                    <dd>
                      <span>{data.email}</span>
                      {app.emailVerifiedAt && <Tag color="green">확인됨</Tag>}
                    </dd>
                    <dt>연락처</dt>
                    <dd className="ar-num">{data.phone}</dd>
                    <dt>원하는 아이디</dt>
                    <dd>{data.requestedLoginId ?? '예전 양식이라 받지 않음'}</dd>
                  </dl>
                </section>
                <section>
                  <h2>가게·사업자</h2>
                  <dl>
                    <dt>사업자번호</dt>
                    <dd className="ar-num">
                      <Typography.Text
                        copyable={{
                          text: app.businessRegistrationNumber,
                          tooltips: ['복사', '복사됨'],
                        }}
                      >
                        {formatBusinessNumber(app.businessRegistrationNumber)}
                      </Typography.Text>
                    </dd>
                    <dt>쇼핑몰 주소</dt>
                    <dd>
                      {data.shopUrl && isShopUrlValid(data.shopUrl) ? (
                        <a href={data.shopUrl} target="_blank" rel="noopener noreferrer">
                          {data.shopUrl.replace(/^https?:\/\//, '')} ↗
                        </a>
                      ) : (
                        <span className={data.shopUrl ? undefined : 'ar-dim'}>
                          {data.shopUrl ?? '예전 양식이라 받지 않음'}
                        </span>
                      )}
                    </dd>
                  </dl>
                </section>
                <section>
                  <h2>진행 시각</h2>
                  <dl>
                    <dt>신청</dt>
                    <dd className="ar-num">{formatApplicationTime(app.createdAt)}</dd>
                    <dt>이메일 확인</dt>
                    <dd className="ar-num">
                      {app.emailVerifiedAt ? (
                        formatApplicationTime(app.emailVerifiedAt)
                      ) : (
                        <span className="ar-dim">아직 확인 전</span>
                      )}
                    </dd>
                    <dt>개인정보 동의</dt>
                    <dd className="ar-num ar-stack">
                      <span>{formatApplicationTime(data.consentedAt)}</span>
                      <span className="ar-dim">동의 문구 {data.consentVersion}</span>
                    </dd>
                    {data.reviewedAt && (
                      <>
                        <dt>처리한 관리자</dt>
                        <dd>
                          {data.reviewerName ?? `계정 #${data.reviewerUserId}`} ·{' '}
                          {formatApplicationTime(data.reviewedAt)}
                        </dd>
                      </>
                    )}
                  </dl>
                </section>
              </aside>
            </div>
          </>
        )
      )}
      <Modal
        title={
          confirmation?.kind === 'approve'
            ? '이 정보로 승인하고 사장님 계정을 만들까요?'
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
        okText={
          confirmation?.kind === 'approve' ? '승인하고 계정 만들기' : '반려하고 사유 메일 보내기'
        }
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
                { key: 'login', label: '사장님 아이디', children: data?.requestedLoginId },
                { key: 'fee', label: 'PG 수수료율', children: `${confirmation.fee}%` },
                {
                  key: 'permissions',
                  label: '사장님 권한',
                  children:
                    confirmation.input.permissions
                      .map((p) => (p === 'FDS_VIEW' ? 'FDS' : '정산·자금관리'))
                      .join(', ') || '없음',
                },
              ]}
            />
            <Alert
              type="info"
              showIcon
              message="사장님 메일로 첫 비밀번호 설정 링크를 보냅니다."
              description="승인한 뒤에는 되돌릴 수 없습니다. 쇼핑몰 결제 연동은 따로 진행해야 합니다."
            />
          </>
        ) : (
          <>
            <p>
              가맹점과 사장님 계정은 만들지 않습니다. 사장님은 사유를 보고 새로 신청할 수 있습니다.
            </p>
            <p className="ar-reason">{confirmation?.reason}</p>
            <p>위 사유를 사장님 메일({data?.email})로 보냅니다.</p>
          </>
        )}
      </Modal>
    </div>
  )
}
