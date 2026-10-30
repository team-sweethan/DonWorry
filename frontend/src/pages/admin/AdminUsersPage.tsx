import { useEffect, useMemo, useRef, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Checkbox,
  Descriptions,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Spin,
  Table,
  Tabs,
  Tag,
  Typography,
} from 'antd'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import {
  createUser,
  getMerchantOptions,
  getUser,
  getUsers,
  resetUserPassword,
  setUserPermissions,
  setUserStatus,
} from '../../api/adminAccounts'
import type {
  AdminUser,
  CreateUser,
  MerchantOption,
  TemporaryPassword,
  UserPage,
} from '../../api/adminAccounts'
import { ApiError } from '../../api/client'
import { PageHeader } from '../../components/common/PageHeader'
import { useAuth } from '../../auth/useAuth'
import { LoginHistoryTable } from '../auth/LoginHistoryPage'
import {
  adminUserListParams,
  parseAdminUserListQuery,
  type AdminUserListQuery,
} from '../../utils/adminUserListQuery'

const roleLabels = { ADMIN: '관리자', OPERATOR: '사장님', SECURITY_STAFF: '보안 직원' }
const permissionOptions = [
  { label: 'FDS 조회', value: 'FDS_VIEW' },
  { label: '정산 조회', value: 'FINANCE_VIEW' },
]
const roleOptions = Object.entries(roleLabels).map(([value, label]) => ({ value, label }))
const errorMessage = (error: unknown) =>
  error instanceof ApiError
    ? (error.detail ?? '요청을 처리하지 못했습니다. 다시 시도해 주세요.')
    : '서버에 연결할 수 없습니다. 다시 시도해 주세요.'

function TemporaryPasswordModal({
  result,
  onClose,
}: {
  result: TemporaryPassword | null
  onClose: () => void
}) {
  return (
    <Modal
      open={result !== null}
      title="임시 비밀번호"
      onCancel={onClose}
      footer={
        <Button type="primary" onClick={onClose}>
          확인했습니다
        </Button>
      }
      destroyOnHidden
      maskClosable={false}
    >
      {result && (
        <>
          <Alert
            type="warning"
            showIcon
            message="이 비밀번호는 지금 한 번만 표시됩니다. 사용자에게 전달한 뒤 닫아 주세요. 다시 확인하려면 초기화해야 합니다."
          />
          <p>
            아이디: <strong>{result.user.loginId}</strong>
          </p>
          <Typography.Text code style={{ fontSize: 20, userSelect: 'all' }}>
            {result.temporaryPassword}
          </Typography.Text>
          <p>로그인 후 비밀번호를 변경해야 합니다.</p>
        </>
      )}
    </Modal>
  )
}

export function AdminUsersPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const filter = useMemo(() => parseAdminUserListQuery(searchParams), [searchParams])
  const [revision, setRevision] = useState(0)
  const updateFilter = (changes: Partial<AdminUserListQuery>) =>
    setSearchParams(adminUserListParams({ ...filter, ...changes }))
  const refresh = () => setRevision((value) => value + 1)
  const [result, setResult] = useState<{
    filter: typeof filter
    revision: number
    data?: UserPage
    error?: string
  } | null>(null)
  useEffect(() => {
    let alive = true
    getUsers(
      filter.page - 1,
      filter.keyword,
      filter.role,
      filter.status,
      filter.passwordChangeRequired,
    )
      .then((data) => {
        if (alive) setResult({ filter, revision, data })
      })
      .catch((error) => {
        if (alive) setResult({ filter, revision, error: errorMessage(error) })
      })
    return () => {
      alive = false
    }
  }, [filter, revision])
  const current = result?.filter === filter && result.revision === revision ? result : null
  return (
    <>
      <PageHeader
        title="계정 관리"
        description="계정을 만들고 조회 권한과 사용 상태를 관리합니다."
        actions={
          <Space>
            <Button onClick={refresh}>새로고침</Button>
            <Link to="/admin/users/new">
              <Button type="primary">계정 만들기</Button>
            </Link>
          </Space>
        }
      />
      <Card>
        <Space wrap style={{ marginBottom: 20 }}>
          <Input.Search
            aria-label="계정 검색"
            placeholder="아이디 또는 이름"
            key={filter.keyword}
            defaultValue={filter.keyword}
            maxLength={100}
            onSearch={(keyword) => updateFilter({ keyword, page: 1 })}
            allowClear
          />
          <Select
            aria-label="역할 필터"
            placeholder="전체 역할"
            allowClear
            style={{ width: 150 }}
            options={roleOptions}
            value={filter.role}
            onChange={(role) => updateFilter({ role, page: 1 })}
          />
          <Select
            aria-label="상태 필터"
            placeholder="전체 상태"
            allowClear
            style={{ width: 130 }}
            value={filter.status}
            options={[
              { value: 'ACTIVE', label: '사용 중' },
              { value: 'DISABLED', label: '정지' },
            ]}
            onChange={(status) => updateFilter({ status, page: 1 })}
          />
          <Select
            aria-label="비밀번호 필터"
            placeholder="전체 비밀번호"
            allowClear
            style={{ width: 170 }}
            value={filter.passwordChangeRequired}
            options={[{ value: true, label: '임시 비밀번호 미변경' }]}
            onChange={(passwordChangeRequired) => updateFilter({ passwordChangeRequired, page: 1 })}
          />
        </Space>
        {current?.error ? (
          <Alert
            type="error"
            showIcon
            message={current.error}
            action={<Button onClick={refresh}>다시 시도</Button>}
          />
        ) : (
          <Table<AdminUser>
            rowKey="userId"
            loading={!current}
            dataSource={current?.data?.items ?? []}
            scroll={{ x: 750 }}
            columns={[
              {
                title: '아이디',
                dataIndex: 'loginId',
                render: (value: string, user) => (
                  <Link to={`/admin/users/${user.userId}`}>{value}</Link>
                ),
              },
              { title: '이름', dataIndex: 'displayName' },
              {
                title: '역할',
                dataIndex: 'role',
                render: (role: AdminUser['role']) => roleLabels[role],
              },
              {
                title: '가맹점',
                dataIndex: 'merchantName',
                render: (value: string | null) => value ?? '—',
              },
              {
                title: '상태',
                dataIndex: 'status',
                render: (status: string) => (
                  <Tag color={status === 'ACTIVE' ? 'green' : 'red'}>
                    {status === 'ACTIVE' ? '사용 중' : '정지'}
                  </Tag>
                ),
              },
              {
                title: '비밀번호',
                dataIndex: 'passwordChangeRequired',
                render: (required: boolean) => (required ? '변경 필요' : '설정됨'),
              },
            ]}
            pagination={{
              current: filter.page,
              pageSize: 20,
              total: current?.data?.totalElements ?? 0,
              showSizeChanger: false,
              showTotal: (total) => `총 ${total}명`,
              onChange: (page) => updateFilter({ page }),
            }}
          />
        )}
      </Card>
    </>
  )
}

export function AdminCreateUserPage() {
  const [form] = Form.useForm<CreateUser>()
  const role = Form.useWatch('role', form) as CreateUser['role'] | undefined
  const [keyword, setKeyword] = useState('')
  const [options, setOptions] = useState<MerchantOption[]>([])
  const [merchantError, setMerchantError] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const pending = useRef(false)
  const [secret, setSecret] = useState<TemporaryPassword | null>(null)
  const navigate = useNavigate()
  useEffect(() => {
    let alive = true
    // 입력이 멈춘 뒤에만 조회해 글자마다 요청하지 않는다.
    const timer = setTimeout(
      () => {
        getMerchantOptions(keyword)
          .then((data) => {
            if (alive) {
              setOptions(data)
              setMerchantError(null)
            }
          })
          .catch((error) => {
            if (alive) setMerchantError(errorMessage(error))
          })
      },
      keyword ? 300 : 0,
    )
    return () => {
      alive = false
      clearTimeout(timer)
    }
  }, [keyword])
  const submit = async (values: CreateUser) => {
    if (pending.current) return
    pending.current = true
    setBusy(true)
    setError(null)
    try {
      setSecret(
        await createUser({
          ...values,
          merchantId: values.role === 'OPERATOR' ? values.merchantId : undefined,
        }),
      )
      form.resetFields()
    } catch (error) {
      setError(errorMessage(error))
      if (error instanceof ApiError && error.code === 'ADMIN_010') {
        form.setFields([
          {
            name: 'merchantId',
            errors: ['이미 사장님 계정이 있습니다. 기존 계정의 상태를 확인해 주세요.'],
          },
        ])
      }
    } finally {
      pending.current = false
      setBusy(false)
    }
  }
  return (
    <>
      <PageHeader
        title="계정 만들기"
        description="처음에는 임시 비밀번호가 발급됩니다. 관리자 계정은 만들 수 없습니다."
        actions={<Link to="/admin/users">목록으로</Link>}
      />
      <Card style={{ maxWidth: 640 }}>
        {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 16 }} />}
        <Form<CreateUser>
          form={form}
          layout="vertical"
          initialValues={{ role: 'OPERATOR' }}
          onFinish={submit}
          disabled={busy || secret !== null}
        >
          <Form.Item
            name="loginId"
            label="아이디"
            extra="영소문자·숫자·밑줄(_)로 3~30자"
            rules={[
              {
                required: true,
                pattern: /^[a-z0-9_]{3,30}$/,
                message: '영소문자·숫자·밑줄로 3~30자를 입력해 주세요.',
              },
            ]}
          >
            <Input autoComplete="off" maxLength={30} />
          </Form.Item>
          <Form.Item
            name="displayName"
            label="이름"
            rules={[
              {
                required: true,
                whitespace: true,
                max: 100,
                message: '이름을 100자 이내로 입력해 주세요.',
              },
            ]}
          >
            <Input maxLength={100} />
          </Form.Item>
          <Form.Item name="role" label="역할" rules={[{ required: true }]}>
            <Select options={roleOptions.filter((o) => o.value !== 'ADMIN')} />
          </Form.Item>
          {role === 'OPERATOR' && (
            <>
              {merchantError && <Alert type="error" message={merchantError} />}
              <Form.Item
                name="merchantId"
                label="소속 가맹점"
                preserve={false}
                extra={
                  <span>
                    사용 중인 가맹점만 선택할 수 있습니다. 가맹점당 사장님 계정은 1개만 생성할 수
                    있습니다. 이름으로 검색하세요.{' '}
                    <Link to="/admin/merchants/new">가맹점 만들기</Link>
                  </span>
                }
                rules={[{ required: true, message: '가맹점을 선택해 주세요.' }]}
              >
                <Select
                  showSearch
                  filterOption={false}
                  onSearch={setKeyword}
                  options={options.map((m) => ({ value: m.merchantId, label: m.displayName }))}
                  notFoundContent="선택 가능한 가맹점이 없습니다."
                />
              </Form.Item>
            </>
          )}
          <Button type="primary" htmlType="submit" loading={busy}>
            계정 만들기
          </Button>
        </Form>
      </Card>
      <TemporaryPasswordModal
        result={secret}
        onClose={() => {
          const id = secret?.user.userId
          setSecret(null)
          if (id) navigate(`/admin/users/${id}`, { replace: true })
        }}
      />
    </>
  )
}

export function AdminUserDetailPage() {
  const { id } = useParams()
  const userId = Number(id)
  const { user: actor, store } = useAuth()
  const navigate = useNavigate()
  const [revision, setRevision] = useState(0)
  const [result, setResult] = useState<{
    id: number
    revision: number
    user?: AdminUser
    error?: string
  } | null>(null)
  const [selected, setSelected] = useState<string[]>([])
  const [feedback, setFeedback] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const pending = useRef(false)
  const [secret, setSecret] = useState<TemporaryPassword | null>(null)
  const [confirmation, setConfirmation] = useState<'status' | 'reset' | null>(null)
  useEffect(() => {
    let alive = true
    if (!Number.isSafeInteger(userId) || userId <= 0) return
    getUser(userId)
      .then((user) => {
        if (alive) {
          setResult({ id: userId, revision, user })
          setSelected(user.permissions)
        }
      })
      .catch((error) => {
        if (alive) setResult({ id: userId, revision, error: errorMessage(error) })
      })
    return () => {
      alive = false
    }
  }, [userId, revision])
  const current = result?.id === userId && result.revision === revision ? result : null
  const user = current?.user
  const perform = async (action: 'permissions' | 'status' | 'reset') => {
    if (!user || pending.current) return
    pending.current = true
    setBusy(true)
    setFeedback(null)
    try {
      if (action === 'reset') {
        const value = await resetUserPassword(userId)
        setSecret(value)
        setResult({ id: userId, revision, user: value.user })
      } else {
        if (action === 'permissions') await setUserPermissions(userId, selected)
        else await setUserStatus(userId, user.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE')
        setRevision((value) => value + 1)
      }
    } catch (error) {
      setFeedback(errorMessage(error))
    } finally {
      pending.current = false
      setBusy(false)
      setConfirmation(null)
    }
  }
  if (!Number.isSafeInteger(userId) || userId <= 0)
    return <Alert type="error" message="계정 주소를 확인해 주세요." />
  return (
    <>
      <PageHeader title="계정 상세" actions={<Link to="/admin/users">목록으로</Link>} />
      {current?.error ? (
        <Alert
          type="error"
          message={current.error}
          action={<Button onClick={() => setRevision((r) => r + 1)}>다시 시도</Button>}
        />
      ) : !user ? (
        <Spin />
      ) : (
        <Tabs
          destroyOnHidden
          items={[
            {
              key: 'account',
              label: '계정 정보',
              children: (
                <Space direction="vertical" size={20} style={{ width: '100%' }}>
                  {feedback && <Alert type="error" showIcon message={feedback} />}
                  <Card title={user.displayName}>
                    <Descriptions
                      column={{ xs: 1, sm: 2 }}
                      items={[
                        { key: 'login', label: '아이디', children: user.loginId },
                        { key: 'role', label: '역할', children: roleLabels[user.role] },
                        {
                          key: 'merchant',
                          label: '소속 가맹점',
                          children: user.merchantName ?? '—',
                        },
                        {
                          key: 'status',
                          label: '상태',
                          children: user.status === 'ACTIVE' ? '사용 중' : '정지',
                        },
                        {
                          key: 'password',
                          label: '비밀번호',
                          children: user.passwordChangeRequired ? '변경 필요' : '설정됨',
                        },
                        {
                          key: 'created',
                          label: '생성 시각',
                          children: new Date(`${user.createdAt}Z`).toLocaleString('ko-KR'),
                        },
                      ]}
                    />
                  </Card>
                  <Card title="조회 권한">
                    {user.role === 'OPERATOR' ? (
                      <Space direction="vertical">
                        <Checkbox.Group
                          options={permissionOptions}
                          value={selected}
                          onChange={(values) => setSelected(values.map(String))}
                          disabled={busy}
                        />
                        <Typography.Text type="secondary">
                          변경하면 해당 사용자가 로그아웃됩니다.
                        </Typography.Text>
                        <Button
                          type="primary"
                          loading={busy}
                          onClick={() => void perform('permissions')}
                        >
                          권한 저장
                        </Button>
                      </Space>
                    ) : (
                      <Typography.Text>
                        {user.role === 'ADMIN'
                          ? '관리자의 조회 권한은 역할에 따라 자동 부여됩니다.'
                          : '보안 직원은 역할에 따라 업무 화면을 사용합니다.'}
                      </Typography.Text>
                    )}
                  </Card>
                  <Card title="계정 사용 및 비밀번호">
                    <p>
                      정지·복구 또는 비밀번호 초기화 시 해당 사용자의 기존 로그인이 종료됩니다.
                      계정은 삭제하지 않습니다.
                    </p>
                    <Space wrap>
                      <Button
                        danger={user.status === 'ACTIVE'}
                        disabled={busy || (actor?.userId === userId && user.status === 'ACTIVE')}
                        onClick={() => setConfirmation('status')}
                      >
                        {user.status === 'ACTIVE' ? '계정 정지' : '정지 해제'}
                      </Button>
                      <Button disabled={busy} onClick={() => setConfirmation('reset')}>
                        비밀번호 초기화
                      </Button>
                    </Space>
                    {actor?.userId === userId && (
                      <p>
                        자기 자신은 정지할 수 없습니다. 본인 비밀번호를 초기화하면 임시 비밀번호를
                        확인한 뒤 다시 로그인해야 합니다.
                      </p>
                    )}
                  </Card>
                </Space>
              ),
            },
            {
              key: 'login-history',
              label: '로그인 이력',
              children: <LoginHistoryTable key={userId} userId={userId} />,
            },
          ]}
        />
      )}
      <Modal
        open={confirmation !== null}
        title={
          confirmation === 'reset'
            ? '비밀번호를 초기화할까요?'
            : user?.status === 'ACTIVE'
              ? '계정을 정지할까요?'
              : '정지를 해제할까요?'
        }
        onCancel={() => {
          if (!busy) setConfirmation(null)
        }}
        confirmLoading={busy}
        okText="진행"
        cancelText="취소"
        onOk={() => {
          if (confirmation) void perform(confirmation)
        }}
      >
        <p>
          {confirmation === 'reset'
            ? '기존 비밀번호를 사용할 수 없게 되고 새 임시 비밀번호가 발급됩니다.'
            : '계정 상태를 변경하고 기존 로그인을 종료합니다.'}
        </p>
      </Modal>
      <TemporaryPasswordModal
        result={secret}
        onClose={() => {
          const self = secret?.user.userId === actor?.userId
          setSecret(null)
          if (self) {
            store.expire()
            navigate('/login', { replace: true })
          }
        }}
      />
    </>
  )
}
