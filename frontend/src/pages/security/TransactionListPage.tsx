import {
  DownloadOutlined,
  DownOutlined,
  InfoCircleOutlined,
  ReloadOutlined,
  RightOutlined,
} from '@ant-design/icons'
import {
  Alert,
  App,
  Badge,
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
import { getMlShadowScores } from '../../api/mlShadowScores'
import { exportPaymentTransactionsCsv, getPaymentTransactions } from '../../api/paymentTransactions'
import { useAuth } from '../../auth/useAuth'
import { PageHeader } from '../../components/common/PageHeader'
import type { FdsResult, PaymentStatus } from '../../types/case'
import type { PaymentTransaction, PaymentTransactionPage } from '../../types/paymentTransaction'
import {
  MAX_PERIOD_DAYS,
  TRANSACTION_PAGE_SIZE,
  caseCell,
  customerNeedsMerchant,
  defaultTransactionListQuery,
  merchantChange,
  mlCell,
  parseTransactionListQuery,
  periodDays,
  transactionApiParams,
  transactionListSearch,
  type CaseLinks,
  type MlScores,
  type TransactionListQuery,
} from '../../utils/transactionList'
import { customerPart } from '../../utils/caseRules'
import { paymentStatusLabel } from './case/caseLabels'
import './TransactionListPage.css'

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
    '가맹점이 손님마다 붙인 번호예요. 이름 대신 이 번호로 같은 손님의 결제를 모아 봐요. 이메일은 줄을 펼치면 보여요.',
  device: '결제한 기기의 식별값 앞부분만 보여요. 개인정보 보호를 위해 나머지는 가렸어요.',
  ip: '결제한 나라와 IP 앞부분이에요. 별표(*)는 가린 부분이에요.',
  // 판정처럼 읽히지 않게 "높음 · 위험" 대신 기준 이상만 표시한다 (#441, ADR-0061)
  ml: 'ML이 매긴 참고 점수예요. 실제 사기 확률이 아니라 시연용 학습 데이터에서 나온 점수라서, 결제 결과는 바뀌지 않아요. 학습 때 정한 기준 이상이면 한 번 더 볼 만한 결제로 표시해요. 기준값은 점수에 마우스를 올리면 보여요.',
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

// IPv6는 가린 값(예: 0:0:*)만 보면 고장처럼 보여서 화면에서만 종류를 앞에 붙인다 (#539)
const ipText = (masked: string | null) => (masked?.includes(':') ? `IPv6 · ${masked}` : masked)

type Loaded = { key: string; page: PaymentTransactionPage | null; error: string | null }

// 받은 파일을 브라우저 다운로드로 넘긴다 — 서버가 이미 UTF-8 BOM을 붙였다
function saveFile(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = name
  link.click()
  URL.revokeObjectURL(url)
}

export function TransactionListPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const { message, modal } = App.useApp()
  const { store } = useAuth()
  const [exporting, setExporting] = useState(false)
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
  const [ml, setMl] = useState<{ key: string; scores: MlScores } | null>(null)

  const tooLong = periodDays(query) > MAX_PERIOD_DAYS
  // 가맹점 없이 고객 번호만 넣으면 다른 가맹점의 같은 번호와 섞여서 조회하지 않고 안내한다 (#462)
  const needsMerchant = customerNeedsMerchant(query)
  const blocked = tooLong || needsMerchant
  const apiSearch = transactionApiParams(query).toString()
  // CSV 내보내기 (#651) — 화면과 같은 조건. 차단되면 계정이 이미 정지 · 로그아웃된 상태라 안내 뒤 로그인 화면으로 보낸다
  // 안내 문구는 서버가 준 중립 문구를 그대로 쓴다 (탐지 내용을 알리지 않음)
  const exportCsv = () => {
    setExporting(true)
    exportPaymentTransactionsCsv(new URLSearchParams(apiSearch))
      .then((blob) => {
        saveFile(blob, `거래내역_${dayjs().format('YYYYMMDD_HHmm')}.csv`)
        message.success('CSV 파일을 내려받았습니다.')
      })
      .catch((error: unknown) => {
        if (error instanceof ApiError && error.code === 'INSIDER_EXPORT_BLOCKED') {
          modal.error({
            title: '계정 사용이 정지되었습니다',
            content: error.detail ?? '관리자에게 문의해 주세요.',
            okText: '확인',
            onOk: () => store.expire(),
          })
          return
        }
        if (error instanceof ApiError && error.code === 'PAYMENT_TRANSACTIONS_EXPORT_TOO_LARGE') {
          message.warning(error.detail ?? '내보낼 거래가 너무 많습니다. 조건을 좁혀 주세요.')
          return
        }
        message.error('CSV 파일을 내려받지 못했습니다. 잠시 후 다시 시도해 주세요.')
      })
      .finally(() => setExporting(false))
  }
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
    if (blocked) return
    let alive = true
    getPaymentTransactions(new URLSearchParams(apiSearch))
      .then((page) => {
        if (!alive) return
        setLoaded({ key: requestKey, page, error: null })
        const paymentIds = page.items.map((item) => item.paymentId)
        if (paymentIds.length === 0) {
          setLinks({ key: requestKey, links: { status: 'loaded', caseIdByPaymentId: new Map() } })
          setMl({ key: requestKey, scores: { status: 'loaded', scoreByPaymentId: new Map() } })
          return
        }
        // ML 참고 칸은 사건 칸과 따로 부른다 — 한쪽이 실패해도 다른 칸은 그대로 보인다
        setMl({ key: requestKey, scores: { status: 'loading' } })
        getMlShadowScores(paymentIds)
          .then((found) => {
            if (!alive) return
            setMl({
              key: requestKey,
              scores: {
                status: 'loaded',
                scoreByPaymentId: new Map(found.map((score) => [score.paymentId, score])),
              },
            })
          })
          .catch(() => {
            if (alive) setMl({ key: requestKey, scores: { status: 'failed' } })
          })
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
  }, [apiSearch, requestKey, blocked])

  // 막힌 조건은 서버 요청이 "조건 없음"과 같아져서, 전에 받은 전체 목록을 검색 결과처럼 그리지 않게 버린다
  const current = !blocked && loaded?.key === requestKey ? loaded : null
  const loading = !blocked && current === null
  const page = current?.page ?? null
  const caseLinks: CaseLinks = links?.key === requestKey ? links.links : { status: 'loading' }
  const mlScores: MlScores = ml?.key === requestKey ? ml.scores : { status: 'loading' }

  const merchantNames = new Map(
    (merchants ?? []).map((merchant) => [merchant.merchantId, merchant.displayName]),
  )
  const openAlert = (evaluationId: string) => navigate(`/security/alerts/${evaluationId}`)
  const openCase = (caseId: string) => navigate(`/security/cases/${caseId}`)

  // 목록을 훑을 때 보는 칸만 한 줄에 둔다 — "얼마 → 어떻게 됐나 → 왜(판정) → 사건 → 누구" 순서 (#445)
  const columns: ColumnsType<PaymentTransaction> = [
    {
      title: '결제 시각',
      dataIndex: 'createdAt',
      width: 100,
      render: (value: string) => time(value),
    },
    {
      title: '금액',
      dataIndex: 'amount',
      align: 'right',
      width: 110,
      render: (value: number) => `${value.toLocaleString('ko-KR')}원`,
    },
    {
      // 결제 상태는 "점 + 글자", FDS 판정은 배지 — 같은 색이어도 생김새로 다른 정보임을 구분한다 (#539)
      title: <span className="tx-gap">결제 상태</span>,
      dataIndex: 'paymentStatus',
      width: 112,
      render: (value: PaymentStatus) => {
        const { label, color } = paymentStatusLabel[value]
        return (
          <Badge
            className="tx-gap"
            {...(color === 'default' ? { status: 'default' as const } : { color })}
            text={label}
          />
        )
      },
    },
    {
      title: 'FDS 판정',
      key: 'fds',
      width: 100,
      render: (_, t) => {
        if (t.fdsResult === null) return <Typography.Text type="secondary">평가 전</Typography.Text>
        const text = fdsResultText[t.fdsResult]
        if (t.evaluationId === null) return <Tag color={text.color}>{text.label}</Tag>
        const evaluationId = t.evaluationId
        // ↗를 배지 안에 넣어 "누르면 경보 상세로 간다"를 한 덩어리로 보여 준다
        return (
          <Button
            type="link"
            size="small"
            className="tx-tag-link"
            aria-label={`FDS 판정 ${text.label} — 경보 상세 보기`}
            onClick={() => openAlert(evaluationId)}
          >
            <Tag color={text.color}>{text.label} ↗</Tag>
          </Button>
        )
      },
    },
    {
      title: <HelpTitle label="ML 참고" text={help.ml} />,
      key: 'ml',
      width: 130,
      render: (_, t) => {
        const cell = mlCell(t.paymentId, mlScores)
        if (cell.kind === 'loading') return <Typography.Text type="secondary">…</Typography.Text>
        if (cell.kind === 'unknown')
          return (
            <Tooltip title="ML 참고 점수를 조회하지 못했습니다. 새로고침해 보세요.">
              <Typography.Text type="secondary">확인 불가</Typography.Text>
            </Tooltip>
          )
        if (cell.kind === 'none')
          return (
            <Tooltip title="아직 채점 전이거나(결제 후 약 30초 뒤) ML 채점이 꺼져 있어요.">
              <Typography.Text type="secondary">기록 없음</Typography.Text>
            </Tooltip>
          )
        if (cell.kind === 'failed')
          return (
            <Tooltip title="ML 채점 중 오류가 났어요. 결제 결과와는 상관없어요.">
              <Typography.Text type="secondary">채점 실패</Typography.Text>
            </Tooltip>
          )
        if (cell.kind === 'skipped')
          return (
            <Tooltip title="이전 결제 기록이 너무 많거나 입력을 찾지 못해 채점하지 않았어요.">
              <Typography.Text type="secondary">채점 안 함</Typography.Text>
            </Tooltip>
          )
        const detail = `기준 ${cell.thresholdPercent}% · 모델 ${cell.modelVersion}`
        return (
          <Tooltip title={detail}>
            {cell.aboveThreshold ? (
              <Tag color="orange">{cell.percent}% · 기준 이상</Tag>
            ) : (
              <Typography.Text type="secondary">{cell.percent}%</Typography.Text>
            )}
          </Tooltip>
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
      title: <HelpTitle label="구매자" text={help.customer} />,
      key: 'buyer',
      width: 190,
      render: (_, t) => {
        // 저장값은 "가맹점ID:번호"라 직원이 검색창에 넣을 고객 번호만 보이고 복사된다 (#462)
        const customer = customerPart(t.customerRef)
        // 번호를 누르면 그 줄의 가맹점 + 고객으로 거른다 (#487). 기간은 두고 상태 · 주문번호 조건은 푼다
        return (
          <Space size={2}>
            <Tooltip title="이 고객 결제만 보기">
              <Button
                type="link"
                size="small"
                // 금액 · 판정보다 먼저 눈에 띄지 않게 기본 글자색으로 두고, 마우스를 올리면 링크 색이 된다
                className="tx-customer-link"
                onClick={() =>
                  setQuery({
                    merchantId: t.merchantId,
                    customerRef: customer,
                    status: null,
                    orderRef: '',
                  })
                }
              >
                {customer}
              </Button>
            </Tooltip>
            <Typography.Text copyable={copyable(customer)} />
          </Space>
        )
      },
    },
  ]

  // 특정 결제를 깊게 볼 때만 필요한 값은 줄을 펼쳐 본다 (#445). 가맹점 문의용 주문번호 복사는 여기에 둔다
  // 주문 정보 / 결제 환경 두 묶음으로 나누고 라벨 폭을 맞춘다 (#539)
  const renderDetail = (t: PaymentTransaction) => (
    <div className="tx-detail">
      <section aria-label="주문 정보">
        <p className="tx-detail-title">주문 정보</p>
        <dl className="tx-detail-list">
          <dt>
            <HelpTitle label="주문번호" text={help.order} />
          </dt>
          <dd>
            <Typography.Text code copyable={copyable(t.orderRef)}>
              {t.orderRef}
            </Typography.Text>
            {t.attemptNo !== null && t.attemptNo > 1 && (
              <Typography.Text type="secondary"> {t.attemptNo}번째 시도</Typography.Text>
            )}
          </dd>
          <dt>가맹점</dt>
          <dd>{merchantNames.get(t.merchantId) ?? `가맹점 #${t.merchantId}`}</dd>
          <dt>이메일</dt>
          <dd>{t.maskedBuyerEmail ?? missing}</dd>
        </dl>
      </section>
      <section aria-label="결제 환경">
        <p className="tx-detail-title">결제 환경</p>
        <dl className="tx-detail-list">
          <dt>카드</dt>
          <dd>{t.cardDisplayLabel ?? missing}</dd>
          <dt>
            <HelpTitle label="접속" text={help.ip} />
          </dt>
          <dd>
            {[t.ipCountryCode, ipText(t.maskedIpAddress)].filter((v) => v !== null).join(' · ') ||
              missing}
          </dd>
          <dt>
            <HelpTitle label="기기" text={help.device} />
          </dt>
          <dd>{t.maskedDeviceId ? <code>{t.maskedDeviceId}</code> : missing}</dd>
        </dl>
      </section>
    </div>
  )

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
              onChange={(value: number | undefined) => setQuery(merchantChange(value ?? null))}
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
              // 고객 번호는 가맹점마다 따로라 가맹점을 고르기 전에는 잠근다 (#487)
              disabled={query.merchantId === null}
              placeholder={query.merchantId === null ? '가맹점 선택 후 입력' : '고객 번호 (정확히)'}
              defaultValue={query.customerRef}
              onSearch={(value) => setQuery({ customerRef: value.trim() })}
            />
            <Button onClick={() => setQuery(defaultTransactionListQuery)}>조건 초기화</Button>
            <Button icon={<ReloadOutlined />} onClick={() => setRefreshKey((key) => key + 1)}>
              새로고침
            </Button>
            <Button
              icon={<DownloadOutlined />}
              loading={exporting}
              disabled={blocked}
              onClick={exportCsv}
            >
              CSV 내보내기
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
        {needsMerchant && (
          <Alert
            type="info"
            showIcon
            message="고객 번호로 찾으려면 가맹점을 먼저 골라 주세요. 고객 번호는 가맹점마다 따로 매겨져요."
          />
        )}
        {current?.error && <Alert type="error" showIcon message={current.error} />}
        <Table
          rowKey="paymentId"
          className="dw-tx-table"
          columns={columns}
          dataSource={page?.items ?? []}
          loading={loading}
          size="middle"
          // 아이콘(▸)을 눌러야 펼친다 — 줄을 눌러 펼치면 경보 · 사건 링크를 누를 때도 같이 펼쳐진다
          expandable={{
            expandedRowRender: renderDetail,
            columnWidth: 48,
            // ⊕/⊖는 더하기 · 빼기처럼 보여서 꺾쇠(▸ 닫힘 · ▾ 열림)로 바꾼다 (#539)
            expandIcon: ({ expanded, onExpand, record }) => (
              <Button
                type="text"
                size="small"
                aria-label={expanded ? '상세 접기' : '상세 펼치기'}
                aria-expanded={expanded}
                icon={expanded ? <DownOutlined /> : <RightOutlined />}
                onClick={(event) => onExpand(record, event)}
              />
            ),
          }}
          scroll={{ x: 920 }}
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
                : needsMerchant
                  ? '가맹점을 고르면 이 고객의 결제가 보입니다.'
                  : '조건에 맞는 결제가 없습니다.',
          }}
        />
      </Card>
    </div>
  )
}
