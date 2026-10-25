import { useEffect, useRef, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Spin,
  Table,
  Tag,
} from 'antd'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { PageHeader } from '../../components/common/PageHeader'
import { ApiError } from '../../api/client'
import {
  createMerchant,
  feeRatePercent,
  getMerchant,
  getMerchants,
  percentToFeeRate,
  setMerchantStatus,
} from '../../api/adminMerchants'
import type { AdminMerchant, MerchantPage, MerchantStatus } from '../../api/adminMerchants'

const labels = { ACTIVE: '사용 중', INACTIVE: '정지' }
// 정산은 아직 settlement의 SettlementFeeCalculator 고정값(0.01)을 쓴다. P6 정산 연동 시 이 안내를 지운다.
const settlementFeeNotice = '정산 미반영, 현재 1% 고정'
const errorMessage = (error: unknown) =>
  error instanceof ApiError
    ? (error.detail ?? '요청을 처리하지 못했습니다. 다시 시도해 주세요.')
    : '서버에 연결할 수 없습니다. 다시 시도해 주세요.'
const statusTag = (status: MerchantStatus) => (
  <Tag color={status === 'ACTIVE' ? 'green' : 'default'}>{labels[status]}</Tag>
)

export function AdminMerchantsPage() {
  const [filter, setFilter] = useState({
    page: 0,
    keyword: '',
    status: undefined as MerchantStatus | undefined,
    revision: 0,
  })
  const [search, setSearch] = useState('')
  const [result, setResult] = useState<{
    filter: typeof filter
    data?: MerchantPage
    error?: string
  } | null>(null)
  useEffect(() => {
    let alive = true
    getMerchants(filter.page, filter.keyword, filter.status)
      .then((data) => {
        if (alive) setResult({ filter, data })
      })
      .catch((error) => {
        if (alive) setResult({ filter, error: errorMessage(error) })
      })
    return () => {
      alive = false
    }
  }, [filter])
  const current = result?.filter === filter ? result : null
  return (
    <>
      <PageHeader
        title="가맹점 관리"
        description="가맹점을 등록하고 사용 상태를 관리합니다."
        actions={
          <Space>
            <Button onClick={() => setFilter((f) => ({ ...f, revision: f.revision + 1 }))}>
              새로고침
            </Button>
            <Link to="/admin/merchants/new">
              <Button type="primary">가맹점 만들기</Button>
            </Link>
          </Space>
        }
      />
      <Card>
        <Space wrap style={{ marginBottom: 20 }}>
          <Input.Search
            aria-label="가맹점 검색"
            placeholder="코드 또는 이름"
            value={search}
            maxLength={100}
            allowClear
            onChange={(event) => setSearch(event.target.value)}
            onSearch={(value) => setFilter((f) => ({ ...f, page: 0, keyword: value }))}
          />
          <Select
            aria-label="가맹점 상태"
            placeholder="전체 상태"
            allowClear
            style={{ width: 140 }}
            value={filter.status}
            options={Object.entries(labels).map(([value, label]) => ({ value, label }))}
            onChange={(status: MerchantStatus | undefined) =>
              setFilter((f) => ({ ...f, page: 0, status }))
            }
          />
        </Space>
        {current?.error && (
          <Alert type="error" showIcon message={current.error} style={{ marginBottom: 16 }} />
        )}
        <Table<AdminMerchant>
          rowKey="merchantId"
          loading={!current}
          dataSource={current?.data?.items ?? []}
          scroll={{ x: 650 }}
          pagination={{
            current: filter.page + 1,
            pageSize: 20,
            total: current?.data?.totalElements ?? 0,
            showSizeChanger: false,
            onChange: (page) => setFilter((f) => ({ ...f, page: page - 1 })),
          }}
          locale={{
            emptyText: current?.error ? '목록을 불러오지 못했습니다.' : '등록된 가맹점이 없습니다.',
          }}
          columns={[
            {
              title: '코드',
              dataIndex: 'merchantCode',
              render: (value: string, merchant) => (
                <Link to={`/admin/merchants/${merchant.merchantId}`}>{value}</Link>
              ),
            },
            { title: '가맹점 이름', dataIndex: 'displayName' },
            { title: '상태', dataIndex: 'status', render: statusTag },
            { title: '수수료율(정산 미반영)', dataIndex: 'pgFeeRate', render: feeRatePercent },
          ]}
        />
      </Card>
    </>
  )
}

type MerchantForm = { merchantCode: string; displayName: string; feePercent: string }
export function AdminCreateMerchantPage() {
  const [form] = Form.useForm<MerchantForm>()
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const pending = useRef(false)
  const navigate = useNavigate()
  const submit = async (values: MerchantForm) => {
    if (pending.current) return
    const rate = percentToFeeRate(values.feePercent)
    if (rate === null) return
    pending.current = true
    setBusy(true)
    setError(null)
    try {
      const merchant = await createMerchant({
        merchantCode: values.merchantCode,
        displayName: values.displayName.trim(),
        pgFeeRate: rate,
      })
      navigate(`/admin/merchants/${merchant.merchantId}`, { replace: true })
    } catch (error) {
      setError(errorMessage(error))
    } finally {
      pending.current = false
      setBusy(false)
    }
  }
  return (
    <>
      <PageHeader
        title="가맹점 만들기"
        description="가맹점을 만든 뒤 사장님 계정을 연결할 수 있습니다."
        actions={<Link to="/admin/merchants">목록으로</Link>}
      />
      <Card style={{ maxWidth: 640 }}>
        {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 16 }} />}
        <Form<MerchantForm>
          form={form}
          layout="vertical"
          initialValues={{ feePercent: '1' }}
          disabled={busy}
          onFinish={submit}
        >
          <Form.Item
            name="merchantCode"
            label="가맹점 코드"
            extra="대문자·숫자 3~40자. 만든 뒤에는 바꿀 수 없습니다. 예: CAKE01"
            rules={[
              {
                required: true,
                pattern: /^[A-Z0-9]{3,40}$/,
                message: '대문자와 숫자로 3~40자를 입력해 주세요.',
              },
            ]}
          >
            <Input maxLength={40} autoComplete="off" />
          </Form.Item>
          <Form.Item
            name="displayName"
            label="가맹점 이름"
            rules={[
              {
                required: true,
                whitespace: true,
                max: 100,
                message: '가맹점 이름을 100자 이내로 입력해 주세요.',
              },
            ]}
          >
            <Input maxLength={100} />
          </Form.Item>
          <Form.Item
            name="feePercent"
            label="수수료율"
            extra={`0% 이상 100% 미만, 소수 4자리까지. 기본값 1%입니다. (${settlementFeeNotice})`}
            rules={[
              {
                validator: (_, value: unknown) =>
                  typeof value === 'string' && percentToFeeRate(value) !== null
                    ? Promise.resolve()
                    : Promise.reject(
                        new Error('0 이상 100 미만의 숫자를 소수 4자리까지 입력해 주세요.'),
                      ),
              },
            ]}
          >
            <Input suffix="%" inputMode="decimal" maxLength={7} />
          </Form.Item>
          <Button type="primary" htmlType="submit" loading={busy}>
            가맹점 만들기
          </Button>
        </Form>
      </Card>
    </>
  )
}

export function AdminMerchantDetailPage() {
  const { id } = useParams()
  const merchantId = Number(id)
  const [revision, setRevision] = useState(0)
  const [result, setResult] = useState<{
    id: number
    revision: number
    data?: AdminMerchant
    error?: string
  } | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [confirming, setConfirming] = useState(false)
  const [busy, setBusy] = useState(false)
  const pending = useRef(false)
  useEffect(() => {
    let alive = true
    if (!Number.isSafeInteger(merchantId) || merchantId <= 0) return
    getMerchant(merchantId)
      .then((data) => {
        if (alive) setResult({ id: merchantId, revision, data })
      })
      .catch((error) => {
        if (alive) setResult({ id: merchantId, revision, error: errorMessage(error) })
      })
    return () => {
      alive = false
    }
  }, [merchantId, revision])
  const current = result?.id === merchantId && result.revision === revision ? result : null
  const merchant = current?.data
  const changeStatus = async () => {
    if (!merchant || pending.current) return
    pending.current = true
    setBusy(true)
    setError(null)
    try {
      await setMerchantStatus(
        merchant.merchantId,
        merchant.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE',
      )
      setConfirming(false)
      setRevision((value) => value + 1)
    } catch (error) {
      setError(errorMessage(error))
    } finally {
      pending.current = false
      setBusy(false)
    }
  }
  const invalidId = !Number.isSafeInteger(merchantId) || merchantId <= 0
  return (
    <>
      <PageHeader
        title="가맹점 상세"
        actions={
          <Space>
            <Button disabled={busy} onClick={() => setRevision((value) => value + 1)}>
              새로고침
            </Button>
            <Link to="/admin/merchants">목록으로</Link>
          </Space>
        }
      />
      {/* 상태 변경 오류는 확인 모달 안에서만 보여준다. */}
      {(current?.error || invalidId) && (
        <Alert
          type="error"
          showIcon
          message={current?.error ?? '올바른 가맹점 주소가 아닙니다.'}
          style={{ marginBottom: 16 }}
        />
      )}
      {!invalidId && !current && <Spin />}
      {merchant && (
        <Card>
          <Descriptions
            column={1}
            items={[
              { key: 'code', label: '가맹점 코드', children: merchant.merchantCode },
              { key: 'name', label: '가맹점 이름', children: merchant.displayName },
              { key: 'status', label: '상태', children: statusTag(merchant.status) },
              {
                key: 'fee',
                label: '수수료율',
                children: `${feeRatePercent(merchant.pgFeeRate)} (${settlementFeeNotice})`,
              },
              {
                key: 'created',
                label: '등록 시각 (UTC)',
                children: merchant.createdAt.replace('T', ' '),
              },
            ]}
          />
          <Space wrap style={{ marginTop: 20 }}>
            <Button
              danger={merchant.status === 'ACTIVE'}
              loading={busy}
              onClick={() => {
                setError(null)
                setConfirming(true)
              }}
            >
              {merchant.status === 'ACTIVE' ? '가맹점 정지' : '다시 활성화'}
            </Button>
            {merchant.status === 'ACTIVE' && (
              <Link to="/admin/users/new">
                <Button>사장님 계정 만들기</Button>
              </Link>
            )}
          </Space>
        </Card>
      )}
      <Modal
        open={confirming && !!merchant}
        title={
          merchant?.status === 'ACTIVE' ? '가맹점을 정지할까요?' : '가맹점을 다시 활성화할까요?'
        }
        okText={merchant?.status === 'ACTIVE' ? '정지' : '활성화'}
        cancelText="취소"
        confirmLoading={busy}
        okButtonProps={{ danger: merchant?.status === 'ACTIVE' }}
        cancelButtonProps={{ disabled: busy }}
        closable={!busy}
        maskClosable={!busy}
        onCancel={() => {
          if (pending.current) return
          setConfirming(false)
          setError(null)
        }}
        onOk={changeStatus}
      >
        <p>{merchant?.displayName}</p>
        <p>
          {merchant?.status === 'ACTIVE'
            ? '소속 사장님의 로그인이 종료되며, 새로 로그인할 수 없습니다. 결제·정산 기록은 보존됩니다.'
            : '사용 중인 사장님 계정은 새로 로그인할 수 있습니다. 별도로 정지된 계정은 계속 차단됩니다.'}
        </p>
        {error && <Alert type="error" showIcon message={error} />}
      </Modal>
    </>
  )
}
