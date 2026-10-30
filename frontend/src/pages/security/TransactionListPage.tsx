import { InfoCircleOutlined, ReloadOutlined } from '@ant-design/icons'
import {
  Alert,
  Button,
  Card,
  DatePicker,
  Input,
  Select,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import dayjs from 'dayjs'
import { useEffect, useState } from 'react'
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { getCaseLinksByPayments } from '../../api/cases'
import { ApiError } from '../../api/client'
import { getMerchantDirectory, type MerchantDirectoryEntry } from '../../api/merchants'
import { getPaymentTransactions } from '../../api/paymentTransactions'
import { PageHeader } from '../../components/common/PageHeader'
import type { FdsResult, PaymentStatus } from '../../types/case'
import type { PaymentTransaction, PaymentTransactionPage } from '../../types/paymentTransaction'
import {
  MAX_PERIOD_DAYS,
  TRANSACTION_PAGE_SIZE,
  caseCell,
  defaultTransactionListQuery,
  parseTransactionListQuery,
  periodDays,
  transactionApiParams,
  transactionListSearch,
  type CaseLinks,
  type TransactionListQuery,
} from '../../utils/transactionList'
import { paymentStatusLabel } from './case/caseLabels'

// 경보 화면과 같은 판정이지만, 처음 보는 직원도 읽게 한국어로 쓴다
const fdsResultText: Record<FdsResult, { label: string; color: string }> = {
  ALLOW: { label: '통과', color: 'green' },
  REVIEW: { label: '검토', color: 'gold' },
  DENY: { label: '차단', color: 'red' },
  ERROR: { label: '평가 오류', color: 'default' },
}

const statusOptions = (Object.keys(paymentStatusLabel) as PaymentStatus[]).map((status) => ({
  value: status,
  label: paymentStatusLabel[status].label,
}))

// 처음 보는 직원이 칸 뜻을 알 수 있게 제목 옆 ⓘ에 설명을 둔다
const help = {
  order: '가맹점이 손님 영수증에 찍은 번호예요. 가맹점에 문의할 때 이 번호로 찾아요.',
  customer:
    '가맹점이 손님마다 붙인 번호예요. 이름 대신 이 번호로 같은 손님의 결제를 모아 봐요. 아래 줄은 일부를 가린 이메일이에요.',
  device: '결제한 기기의 식별값 앞부분만 보여요. 개인정보 보호를 위해 나머지는 가렸어요.',
  ip: '결제한 나라와 IP 앞부분이에요. 별표(*)는 가린 부분이에요.',
}

function HelpTitle({ label, text }: { label: string; text: string }) {
  return (
    <span>
      {label}{' '}
      <Tooltip title={text}>
        <InfoCircleOutlined aria-label={`${label} 설명`} style={{ color: 'rgba(0, 0, 0, 0.45)' }} />
      </Tooltip>
    </span>
  )
}

// 가맹점에 전달하거나 검색칸에 붙여 넣을 때 쓰는 번호는 복사 버튼을 단다
const copyable = (text: string) => ({ text, tooltips: ['복사', '복사됨'] })

const missing = <Typography.Text type="secondary">정보 없음</Typography.Text>
const time = (iso: string) => dayjs(iso).format('MM/DD HH:mm')

type Loaded = { key: string; page: PaymentTransactionPage | null; error: string | null }

export function TransactionListPage() {
  const navigate = useNavigate()
  const location = useLocation()
  // 조건은 주소 쿼리에 둔다 (#243 방식) — 경보 · 사건으로 갔다가 뒤로 와도 같은 조건으로 열린다
  const [searchParams] = useSearchParams()
  const [edited, setEdited] = useState<{
    routerSearch: string
    query: TransactionListQuery
  } | null>(null)
  const query =
    edited?.routerSearch === location.search
      ? edited.query
      : parseTransactionListQuery(searchParams)
  // 라우터로 주소를 바꾸면 ServerSession이 본문을 다시 그려 입력 중인 값이 사라진다 → 브라우저 주소만 바꾼다
  const setQuery = (patch: Partial<TransactionListQuery>) => {
    const next = { ...query, page: 1, ...patch }
    setEdited({ routerSearch: location.search, query: next })
    window.history.replaceState(
      window.history.state,
      '',
      location.pathname + transactionListSearch(next),
    )
  }

  const [merchants, setMerchants] = useState<MerchantDirectoryEntry[] | null>(null)
  const [merchantsFailed, setMerchantsFailed] = useState(false)
  const [refreshKey, setRefreshKey] = useState(0)
  const [loaded, setLoaded] = useState<Loaded | null>(null)
  const [links, setLinks] = useState<{ key: string; links: CaseLinks } | null>(null)

  const tooLong = periodDays(query) > MAX_PERIOD_DAYS
  const apiSearch = transactionApiParams(query).toString()
  // 어떤 요청에 대한 응답인지 함께 저장해 늦게 온 응답이 새 조건의 화면을 덮지 않게 한다
  const requestKey = `${apiSearch}#${refreshKey}`

  useEffect(() => {
    let alive = true
    getMerchantDirectory()
      .then((entries) => {
        if (alive) setMerchants(entries)
      })
      .catch(() => {
        if (alive) setMerchantsFailed(true)
      })
    return () => {
      alive = false
    }
  }, [])

  useEffect(() => {
    if (tooLong) return
    let alive = true
    getPaymentTransactions(new URLSearchParams(apiSearch))
      .then((page) => {
        if (!alive) return
        setLoaded({ key: requestKey, page, error: null })
        const paymentIds = page.items.map((item) => item.paymentId)
        if (paymentIds.length === 0) {
          setLinks({ key: requestKey, links: { status: 'loaded', caseIdByPaymentId: new Map() } })
          return
        }
        setLinks({ key: requestKey, links: { status: 'loading' } })
        getCaseLinksByPayments(paymentIds)
          .then((found) => {
            if (!alive) return
            setLinks({
              key: requestKey,
              links: {
                status: 'loaded',
                caseIdByPaymentId: new Map(found.map((link) => [link.paymentId, link.caseId])),
              },
            })
          })
          .catch(() => {
            if (alive) setLinks({ key: requestKey, links: { status: 'failed' } })
          })
      })
      .catch((error: unknown) => {
        if (!alive) return
        // 실패를 빈 목록으로 그리면 "결제 없음"으로 읽히므로 이전 목록도 지우고 오류만 보여 준다
        const message =
          error instanceof ApiError && error.status === 400
            ? '조회 조건을 확인해 주세요. 기간은 최대 31일입니다.'
            : '거래 내역을 불러오지 못했습니다. 잠시 후 다시 시도하세요.'
        setLoaded({ key: requestKey, page: null, error: message })
      })
    return () => {
      alive = false
    }
  }, [apiSearch, requestKey, tooLong])

  const current = loaded?.key === requestKey ? loaded : null
  const loading = !tooLong && current === null
  const page = current?.page ?? null
  const caseLinks: CaseLinks = links?.key === requestKey ? links.links : { status: 'loading' }

  const merchantNames = new Map(
    (merchants ?? []).map((merchant) => [merchant.merchantId, merchant.displayName]),
  )
  const openAlert = (evaluationId: string) => navigate(`/security/alerts/${evaluationId}`)
  const openCase = (caseId: string) => navigate(`/security/cases/${caseId}`)

  const columns: ColumnsType<PaymentTransaction> = [
    {
      title: '결제 시각',
      dataIndex: 'createdAt',
      width: 110,
      render: (value: string) => time(value),
    },
    {
      title: '가맹점',
      dataIndex: 'merchantId',
      width: 140,
      render: (value: number) => merchantNames.get(value) ?? `가맹점 #${value}`,
    },
    {
      title: <HelpTitle label="구매자" text={help.customer} />,
      key: 'buyer',
      width: 190,
      render: (_, t) => (
        <div>
          <div>
            <Typography.Text copyable={copyable(t.customerRef)}>{t.customerRef}</Typography.Text>
          </div>
          <div>
            <Typography.Text type="secondary">
              {t.maskedBuyerEmail ?? '이메일 정보 없음'}
            </Typography.Text>
          </div>
        </div>
      ),
    },
    {
      title: <HelpTitle label="주문번호" text={help.order} />,
      key: 'order',
      width: 200,
      render: (_, t) => (
        <div>
          <Typography.Text code copyable={copyable(t.orderRef)}>
            {t.orderRef}
          </Typography.Text>
          {t.attemptNo !== null && t.attemptNo > 1 && (
            <div>
              <Typography.Text type="secondary">{t.attemptNo}번째 시도</Typography.Text>
            </div>
          )}
        </div>
      ),
    },
    {
      title: '금액',
      dataIndex: 'amount',
      align: 'right',
      width: 120,
      render: (value: number) => `${value.toLocaleString('ko-KR')}원`,
    },
    {
      title: '결제 상태',
      dataIndex: 'paymentStatus',
      width: 110,
      render: (value: PaymentStatus) => (
        <Tag color={paymentStatusLabel[value].color}>{paymentStatusLabel[value].label}</Tag>
      ),
    },
    {
      title: 'FDS 판정',
      key: 'fds',
      width: 110,
      render: (_, t) => {
        if (t.fdsResult === null) return <Typography.Text type="secondary">평가 전</Typography.Text>
        const text = fdsResultText[t.fdsResult]
        const tag = <Tag color={text.color}>{text.label}</Tag>
        if (t.evaluationId === null) return tag
        const evaluationId = t.evaluationId
        return (
          <Button type="link" size="small" onClick={() => openAlert(evaluationId)}>
            {tag}↗
          </Button>
        )
      },
    },
    {
      title: '사건',
      key: 'case',
      width: 110,
      render: (_, t) => {
        const cell = caseCell(t.paymentId, caseLinks)
        if (cell.kind === 'loading') return <Typography.Text type="secondary">…</Typography.Text>
        if (cell.kind === 'none') return '-'
        if (cell.kind === 'unknown')
          return (
            <Tooltip title="사건 연결을 조회하지 못했습니다. 새로고침해 보세요.">
              <Typography.Text type="secondary">확인 불가</Typography.Text>
            </Tooltip>
          )
        return (
          <Button type="link" size="small" onClick={() => openCase(cell.caseId)}>
            Case {cell.caseId} ↗
          </Button>
        )
      },
    },
    {
      title: '카드',
      dataIndex: 'cardDisplayLabel',
      width: 130,
      render: (value: string | null) => value ?? missing,
    },
    {
      title: <HelpTitle label="접속" text={help.ip} />,
      key: 'ip',
      width: 150,
      render: (_, t) => {
        const parts = [t.ipCountryCode, t.maskedIpAddress].filter((v) => v !== null)
        return parts.length > 0 ? parts.join(' · ') : missing
      },
    },
    {
      title: <HelpTitle label="기기" text={help.device} />,
      dataIndex: 'maskedDeviceId',
      width: 110,
      render: (value: string | null) => (value ? <code>{value}</code> : missing),
    },
  ]

  const periodText = query.from
    ? `${query.from} ~ ${query.to} (한국 시간)`
    : page
      ? `최근 7일 (${time(page.from)} ~ ${time(page.to)})`
      : '최근 7일'

  return (
    <div>
      <PageHeader
        title="거래 내역"
        description="사건에 걸리지 않은 결제까지 모든 결제를 최신순으로 보여 줍니다. 판정 · 사건을 누르면 경보 · 사건 상세로 갑니다."
      />
      <Card className="dw-table-card">
        <div className="dw-table-toolbar">
          <div>
            <h3>결제 {page ? `${page.totalElements.toLocaleString('ko-KR')}건` : ''}</h3>
            <p>기간: {periodText}</p>
          </div>
          <Space wrap>
            <DatePicker.RangePicker
              aria-label="기간"
              placeholder={['시작일', '종료일']}
              value={query.from ? [dayjs(query.from), dayjs(query.to)] : null}
              onChange={(dates) =>
                setQuery(
                  dates?.[0] && dates[1]
                    ? { from: dates[0].format('YYYY-MM-DD'), to: dates[1].format('YYYY-MM-DD') }
                    : { from: '', to: '' },
                )
              }
            />
            <Select
              aria-label="가맹점"
              allowClear
              style={{ width: 180 }}
              placeholder={merchantsFailed ? '가맹점 목록을 불러오지 못함' : '전체 가맹점'}
              value={query.merchantId ?? undefined}
              options={(merchants ?? []).map((merchant) => ({
                value: merchant.merchantId,
                label: `${merchant.displayName}${merchant.status === 'INACTIVE' ? ' · 비활성' : ''}`,
              }))}
              onChange={(value: number | undefined) => setQuery({ merchantId: value ?? null })}
            />
            <Select
              aria-label="결제 상태"
              allowClear
              style={{ width: 140 }}
              placeholder="전체 상태"
              value={query.status ?? undefined}
              options={statusOptions}
              onChange={(value: PaymentStatus | undefined) => setQuery({ status: value ?? null })}
            />
            <Input.Search
              key={`order-${query.orderRef}`}
              aria-label="주문번호"
              allowClear
              style={{ width: 200 }}
              placeholder="주문번호 (정확히)"
              defaultValue={query.orderRef}
              onSearch={(value) => setQuery({ orderRef: value.trim() })}
            />
            <Input.Search
              key={`customer-${query.customerRef}`}
              aria-label="고객 번호"
              allowClear
              style={{ width: 200 }}
              placeholder="고객 번호 (정확히)"
              defaultValue={query.customerRef}
              onSearch={(value) => setQuery({ customerRef: value.trim() })}
            />
            <Button onClick={() => setQuery(defaultTransactionListQuery)}>조건 초기화</Button>
            <Button icon={<ReloadOutlined />} onClick={() => setRefreshKey((key) => key + 1)}>
              새로고침
            </Button>
          </Space>
        </div>
        {tooLong && (
          <Alert
            type="warning"
            showIcon
            message={`기간은 최대 ${MAX_PERIOD_DAYS}일까지 볼 수 있습니다. 기간을 줄여 주세요.`}
          />
        )}
        {current?.error && <Alert type="error" showIcon message={current.error} />}
        <Table
          rowKey="paymentId"
          columns={columns}
          dataSource={page?.items ?? []}
          loading={loading}
          size="middle"
          scroll={{ x: 1480 }}
          pagination={{
            current: query.page,
            pageSize: TRANSACTION_PAGE_SIZE,
            total: page?.totalElements ?? 0,
            showSizeChanger: false,
            showTotal: (total) => `총 ${total.toLocaleString('ko-KR')}건`,
            onChange: (next) => setQuery({ page: next }),
          }}
          locale={{
            emptyText: current?.error
              ? '조회하지 못했습니다.'
              : tooLong
                ? '기간을 줄이면 결제가 보입니다.'
                : '조건에 맞는 결제가 없습니다.',
          }}
        />
      </Card>
    </div>
  )
}
