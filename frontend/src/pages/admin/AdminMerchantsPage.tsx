import { DirectoryDetailSkeleton, DirectoryTableSkeleton } from './DirectoryLoading'
import { useEffect, useMemo, useRef, useState } from 'react'
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
  Table,
  Tag,
} from 'antd'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { ListEmpty } from '../../components/common/ListEmpty'
import { ListSearch } from '../../components/common/ListSearch'
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

import { IntegrationPanel } from '../owner/IntegrationPreviewPage'

import {
  adminMerchantListParams,
  isAdminMerchantListFiltered,
  parseAdminMerchantListQuery,
  type AdminMerchantListQuery,
} from '../../utils/adminMerchantListQuery'
import {
  LIST_PAGE_SIZE,
  lastListPage,
  listResultForDisplay,
  withQuery,
} from '../../utils/listQuery'

const labels = { ACTIVE: '사용 중', INACTIVE: '정지' }
// 신규 정산은 등록된 요율을 적용하고 기존 정산의 계산 결과는 유지한다.
const settlementFeeNotice = '신규 정산에 등록 요율 적용 · 기존 정산 유지'
const errorMessage = (error: unknown) =>
  error instanceof ApiError
    ? (error.detail ?? '요청을 처리하지 못했습니다. 다시 시도해 주세요.')
    : '서버에 연결할 수 없습니다. 다시 시도해 주세요.'
const statusTag = (status: MerchantStatus) => (
  <Tag color={status === 'ACTIVE' ? 'green' : 'default'}>{labels[status]}</Tag>
)

export function AdminMerchantsPage() {
  const [query, setQuery] = useSearchParams()
  const filter = useMemo(() => parseAdminMerchantListQuery(query), [query])
  const listQuery = adminMerchantListParams(filter).toString()
  const [revision, setRevision] = useState(0)
  const updateFilter = (changes: Partial<AdminMerchantListQuery>) => {
    const next = adminMerchantListParams({ ...filter, ...changes })
    // 같은 조건을 다시 검색하면 방문 기록을 늘리지 않고 최신 목록만 읽는다.
    if (next.toString() === listQuery) setRevision((value) => value + 1)
    else setQuery(next)
  }
  const [result, setResult] = useState<{
    filter: typeof filter
    revision: number
    data?: MerchantPage
    error?: string
  } | null>(null)
  useEffect(() => {
    let alive = true
    getMerchants(filter.page - 1, filter.keyword, filter.status)
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
  // 같은 조건의 재조회·페이지 이동은 기존 행을 유지해 표 높이가 줄며 스크롤이 당겨지지 않게 한다.
  // 조건이 바뀌면 이전 결과를 새 조건의 결과처럼 보여 주지 않는다.
  const visible = listResultForDisplay(result, filter, adminMerchantListParams)
  const lastPage = current?.data ? lastListPage(current.data.totalElements) : null
  useEffect(() => {
    // 마지막 행이 사라진 페이지로 돌아오면 빈 목록에 갇히지 않게 있는 페이지로 옮긴다.
    if (lastPage !== null && filter.page > lastPage)
      setQuery(adminMerchantListParams({ ...filter, page: lastPage }), { replace: true })
  }, [filter, lastPage, setQuery])
  return (
    <div className="admin-directory">
      <PageHeader
        title="가맹점 관리"
        description="가맹점을 등록하고 사용 상태를 관리합니다."
        actions={
          <Space>
            <Button onClick={() => setRevision((value) => value + 1)}>새로고침</Button>
            <Link to={withQuery('/admin/merchants/new', listQuery)}>
              <Button type="primary">가맹점 만들기</Button>
            </Link>
          </Space>
        }
      />
      <Card className="directory-surface">
        <Space wrap style={{ marginBottom: 20 }}>
          <ListSearch
            label="가맹점 검색"
            placeholder="코드 또는 이름"
            keyword={filter.keyword}
            onSearch={(keyword) => updateFilter({ page: 1, keyword })}
          />
          <Select
            aria-label="가맹점 상태"
            placeholder="전체 상태"
            allowClear
            style={{ width: 140 }}
            value={filter.status}
            options={Object.entries(labels).map(([value, label]) => ({ value, label }))}
            onChange={(status: MerchantStatus | undefined) => updateFilter({ page: 1, status })}
          />
        </Space>
        {current?.error && (
          <Alert type="error" showIcon message={current.error} style={{ marginBottom: 16 }} />
        )}
        <Table<AdminMerchant>
          rowKey="merchantId"
          loading={!current && (visible?.data?.items.length ?? 0) > 0}
          dataSource={visible?.data?.items ?? []}
          scroll={{ x: 650, scrollToFirstRowOnChange: false }}
          pagination={
            visible?.data
              ? {
                  current: filter.page,
                  pageSize: LIST_PAGE_SIZE,
                  total: visible.data.totalElements,
                  showSizeChanger: false,
                  onChange: (page) => updateFilter({ page }),
                }
              : false
          }
          locale={{
            emptyText: !current ? (
              <DirectoryTableSkeleton kind="merchants" />
            ) : lastPage !== null && filter.page > lastPage ? (
              <span aria-hidden="true" />
            ) : current.error ? (
              '목록을 불러오지 못했습니다.'
            ) : isAdminMerchantListFiltered(filter) ? (
              <ListEmpty
                title="검색 조건에 맞는 가맹점이 없어요"
                description="검색어나 필터를 바꿔 보세요."
                action={<Button onClick={() => setQuery({})}>조건 지우기</Button>}
              />
            ) : (
              <ListEmpty
                mascot
                title="아직 등록된 가맹점이 없어요"
                description="이용 신청을 승인하거나 직접 만들면 여기에 표시돼요."
                action={
                  <Link to="/admin/applications">
                    <Button>이용 신청 보기</Button>
                  </Link>
                }
              />
            ),
          }}
          columns={[
            {
              title: '코드',
              dataIndex: 'merchantCode',
              render: (value: string, merchant) =>
                visible ? (
                  <Link to={withQuery(`/admin/merchants/${merchant.merchantId}`, visible.query)}>
                    {value}
                  </Link>
                ) : (
                  value
                ),
            },
            { title: '가맹점 이름', dataIndex: 'displayName' },
            { title: '상태', dataIndex: 'status', render: statusTag },
            { title: 'PG 수수료율', dataIndex: 'pgFeeRate', render: feeRatePercent },
          ]}
        />
      </Card>
    </div>
  )
}

type MerchantForm = { merchantCode: string; displayName: string; feePercent: string }
export function AdminCreateMerchantPage() {
  const [query] = useSearchParams()
  const listQuery = adminMerchantListParams(parseAdminMerchantListQuery(query)).toString()
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
      navigate(withQuery(`/admin/merchants/${merchant.merchantId}`, listQuery), { replace: true })
    } catch (error) {
      setError(errorMessage(error))
    } finally {
      pending.current = false
      setBusy(false)
    }
  }
  return (
    <div className="admin-directory">
      <PageHeader
        title="가맹점 만들기"
        description="가맹점을 만든 뒤 사장님 계정을 연결할 수 있습니다."
        actions={<Link to={withQuery('/admin/merchants', listQuery)}>목록으로</Link>}
      />
      <Card className="directory-surface" style={{ maxWidth: 640 }}>
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
    </div>
  )
}

export function AdminMerchantDetailPage() {
  const [query] = useSearchParams()
  const listQuery = adminMerchantListParams(parseAdminMerchantListQuery(query)).toString()
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
    <div className="admin-directory">
      <PageHeader
        title="가맹점 상세"
        actions={
          <Space>
            <Button disabled={busy} onClick={() => setRevision((value) => value + 1)}>
              새로고침
            </Button>
            <Link to={withQuery('/admin/merchants', listQuery)}>목록으로</Link>
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
      {!invalidId && !current && <DirectoryDetailSkeleton kind="merchant" />}
      {merchant && (
        <Card className="directory-surface">
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
      {merchant && <IntegrationPanel key={merchant.merchantId} merchantId={merchant.merchantId} />}
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
    </div>
  )
}
