import { ReloadOutlined, SearchOutlined } from '@ant-design/icons'
import { Alert, Button, Card, Input, Select, Skeleton, Space, Table } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { type MouseEvent, useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { getCaseLinksByPayments } from '../../api/cases'
import { getMerchantDirectory, type MerchantDirectoryEntry } from '../../api/merchants'
import {
  createMockFdsEvaluations,
  createMockFdsErrorEvaluation,
  getFdsEvaluations,
  reevaluateMockFdsError,
  type FdsEvaluationPage,
  type FdsEvaluationSummary,
  type FdsResult,
} from '../../api/fds'
import { PageHeader } from '../../components/common/PageHeader'
import { useMinimumLoadingIndicator } from '../../hooks/useMinimumLoadingIndicator'
import { ResultTag } from './alert/AlertTags'

type Filter = 'NEEDS_ATTENTION' | FdsResult | 'ALL'
type ListConditions = { filter: Filter; query: string; merchantId?: number; page: number }

const filterOptions: Array<{ value: Filter; label: string }> = [
  { value: 'NEEDS_ATTENTION', label: '확인 필요' },
  { value: 'REVIEW', label: 'REVIEW' },
  { value: 'DENY', label: 'DENY' },
  { value: 'ERROR', label: 'ERROR' },
  { value: 'ALL', label: '전체 (ALLOW 포함)' },
]

function formatDate(value: string) {
  return new Intl.DateTimeFormat('ko-KR', { dateStyle: 'short', timeStyle: 'medium' }).format(
    new Date(value),
  )
}

function AlertListSkeleton() {
  return (
    <div aria-label="경보 목록을 불러오는 중" className="dw-alert-list-skeleton">
      <Skeleton active paragraph={{ rows: 5 }} title={{ width: '30%' }} />
    </div>
  )
}

export function AlertListPage() {
  const navigate = useNavigate()
  const [filter, setFilter] = useState<Filter>('NEEDS_ATTENTION')
  const [query, setQuery] = useState('')
  const [merchantId, setMerchantId] = useState<number | undefined>()
  const [merchants, setMerchants] = useState<MerchantDirectoryEntry[]>([])
  const [evaluations, setEvaluations] = useState<FdsEvaluationSummary[]>([])
  const [evaluationPage, setEvaluationPage] = useState<FdsEvaluationPage | null>(null)
  const [resultCounts, setResultCounts] = useState<FdsEvaluationPage['resultCounts'] | null>(null)
  const [listLoading, setListLoading] = useState(true)
  const [summaryLoading, setSummaryLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [currentPage, setCurrentPage] = useState(1)
  const [mockCreating, setMockCreating] = useState(false)
  const [mockErrorCreating, setMockErrorCreating] = useState(false)
  const [mockReevaluating, setMockReevaluating] = useState(false)
  const [caseIdsByPaymentId, setCaseIdsByPaymentId] = useState<Map<string, string>>(new Map())
  const requestSequence = useRef(0)
  const resultCountsRef = useRef<FdsEvaluationPage['resultCounts'] | null>(null)
  const summaryRefreshPending = useRef(true)
  const conditions = useRef<ListConditions>({
    filter: 'NEEDS_ATTENTION',
    query: '',
    merchantId: undefined,
    page: 0,
  })
  const successfulConditions = useRef<ListConditions | null>(null)
  const searchTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const initialListLoading = listLoading && evaluationPage === null
  const showInitialListSkeleton = useMinimumLoadingIndicator(initialListLoading)

  const cancelScheduledSearch = () => {
    if (searchTimer.current === null) return
    clearTimeout(searchTimer.current)
    searchTimer.current = null
  }

  const load = useCallback(
    (
      page: number,
      nextFilter: Filter,
      nextQuery: string,
      nextMerchantId: number | undefined,
      refreshSummary = false,
    ) => {
      const sequence = ++requestSequence.current
      if (refreshSummary || resultCountsRef.current === null) summaryRefreshPending.current = true
      const shouldRefreshSummary = summaryRefreshPending.current
      setListLoading(true)
      setSummaryLoading(shouldRefreshSummary)
      setError(null)
      setCaseIdsByPaymentId(new Map())
      setCurrentPage(page + 1)
      getFdsEvaluations({
        result:
          nextFilter === 'REVIEW' || nextFilter === 'DENY' || nextFilter === 'ERROR'
            ? nextFilter
            : undefined,
        attentionOnly: nextFilter === 'NEEDS_ATTENTION',
        search: nextQuery,
        merchantId: nextMerchantId,
        page,
        size: 10,
      })
        .then((response) => {
          if (sequence !== requestSequence.current) return
          setEvaluations(response.content)
          setEvaluationPage(response)
          setCurrentPage(response.page + 1)
          successfulConditions.current = {
            filter: nextFilter,
            query: nextQuery,
            merchantId: nextMerchantId,
            page: response.page,
          }
          if (shouldRefreshSummary) {
            resultCountsRef.current = response.resultCounts
            setResultCounts(response.resultCounts)
            summaryRefreshPending.current = false
          }
          const paymentIds = [
            ...new Set(response.content.map((evaluation) => String(evaluation.paymentId))),
          ]
          if (paymentIds.length === 0) return
          void getCaseLinksByPayments(paymentIds)
            .then((links) => {
              if (sequence !== requestSequence.current) return
              setCaseIdsByPaymentId(new Map(links.map((link) => [link.paymentId, link.caseId])))
            })
            .catch(() => {
              if (sequence === requestSequence.current) setCaseIdsByPaymentId(new Map())
            })
        })
        .catch(() => {
          if (sequence !== requestSequence.current) return
          const current = conditions.current
          if (
            current.filter !== nextFilter ||
            current.query !== nextQuery ||
            current.merchantId !== nextMerchantId ||
            current.page !== page
          ) {
            return
          }
          setError('경보 목록을 불러오지 못했습니다.')
          const previous = successfulConditions.current
          if (previous === null) return
          conditions.current = previous
          setFilter(previous.filter)
          setQuery(previous.query)
          setMerchantId(previous.merchantId)
          setCurrentPage(previous.page + 1)
        })
        .finally(() => {
          if (sequence === requestSequence.current) {
            setListLoading(false)
            setSummaryLoading(false)
          }
        })
    },
    [],
  )

  const createMock = () => {
    setMockCreating(true)
    setError(null)
    createMockFdsEvaluations()
      .then(() => {
        const current = conditions.current
        load(0, current.filter, current.query, current.merchantId, true)
      })
      .catch(() => setError('Mock 평가를 생성하지 못했습니다.'))
      .finally(() => setMockCreating(false))
  }

  const createMockError = () => {
    setMockErrorCreating(true)
    setError(null)
    createMockFdsErrorEvaluation()
      .then(() => {
        const current = conditions.current
        load(0, current.filter, current.query, current.merchantId, true)
      })
      .catch(() => setError('Mock ERROR 평가를 생성하지 못했습니다.'))
      .finally(() => setMockErrorCreating(false))
  }

  const reevaluateMockError = () => {
    setMockReevaluating(true)
    setError(null)
    reevaluateMockFdsError()
      .then(() => {
        conditions.current = { filter: 'ALL', query: '', merchantId, page: 0 }
        setFilter('ALL')
        setQuery('')
        load(0, 'ALL', '', merchantId, true)
      })
      .catch(() =>
        setError('ERROR 평가 재탐지를 실행하지 못했습니다. 먼저 ERROR 케이스를 생성해 주세요.'),
      )
      .finally(() => setMockReevaluating(false))
  }

  const selectFilter = (nextFilter: Filter) => {
    cancelScheduledSearch()
    setFilter(nextFilter)
    conditions.current = { filter: nextFilter, query, merchantId, page: 0 }
    load(0, nextFilter, query, merchantId)
  }

  const selectMerchant = (nextMerchantId: number) => {
    cancelScheduledSearch()
    setMerchantId(nextMerchantId)
    conditions.current = { filter, query, merchantId: nextMerchantId, page: 0 }
    load(0, filter, query, nextMerchantId, true)
  }

  useEffect(() => {
    let cancelled = false
    void getMerchantDirectory()
      .then((result) => {
        if (cancelled) return
        setMerchants(result)
        const initialMerchant = result.find((merchant) => merchant.status === 'ACTIVE') ?? result[0]
        if (!initialMerchant) {
          setListLoading(false)
          setSummaryLoading(false)
          setError('선택할 가맹점이 없습니다.')
          return
        }
        setMerchantId(initialMerchant.merchantId)
        conditions.current = {
          filter: 'NEEDS_ATTENTION',
          query: '',
          merchantId: initialMerchant.merchantId,
          page: 0,
        }
        load(0, 'NEEDS_ATTENTION', '', initialMerchant.merchantId, true)
      })
      .catch(() => {
        if (!cancelled) {
          setListLoading(false)
          setSummaryLoading(false)
          setError('가맹점 목록을 불러오지 못했습니다.')
        }
      })
    return () => {
      cancelled = true
      cancelScheduledSearch()
      requestSequence.current += 1
    }
  }, [load])

  const count = (result: FdsResult) => resultCounts?.[result] ?? 0
  const totalCount = Object.values(resultCounts ?? {}).reduce(
    (total, resultCount) => total + resultCount,
    0,
  )
  const attentionCount = totalCount - count('ALLOW')
  const merchantNames = new Map(
    merchants.map((merchant) => [merchant.merchantId, merchant.displayName]),
  )
  const columns: ColumnsType<FdsEvaluationSummary> = [
    { title: '평가 시각', dataIndex: 'evaluatedAt', width: 190, render: formatDate },
    {
      title: '평가 ID',
      dataIndex: 'evaluationId',
      width: 120,
      render: (value: number) => (
        <Button
          type="link"
          className="dw-alert-payment-link"
          onClick={(event: MouseEvent<HTMLElement>) => {
            event.stopPropagation()
            navigate(`/security/alerts/${value}`)
          }}
        >
          {value}
        </Button>
      ),
    },
    { title: '결제 ID', dataIndex: 'paymentId', width: 130 },
    {
      title: '가맹점',
      dataIndex: 'merchantId',
      width: 150,
      render: (value: number) => merchantNames.get(value) ?? `ID ${value}`,
    },
    { title: '고객 식별자', dataIndex: 'customerRef', width: 180 },
    {
      title: '위험 점수',
      dataIndex: 'riskScore',
      align: 'right',
      width: 110,
      render: (value: number | null) => <strong>{value ?? '—'}</strong>,
    },
    {
      title: '판정',
      dataIndex: 'result',
      width: 110,
      render: (value: FdsResult) => <ResultTag result={value} />,
    },
    {
      title: '오류 코드',
      dataIndex: 'errorCode',
      width: 160,
      render: (value: string | null) => (value ? <code>{value}</code> : '—'),
    },
    {
      title: '사건',
      key: 'case',
      width: 130,
      render: (_, evaluation) => {
        const caseId = caseIdsByPaymentId.get(String(evaluation.paymentId))
        if (!caseId) return '—'
        return (
          <Button
            type="link"
            className="dw-alert-payment-link"
            onClick={(event: MouseEvent<HTMLElement>) => {
              event.stopPropagation()
              navigate(`/security/cases/${caseId}`)
            }}
          >
            CASE-{caseId}
          </Button>
        )
      },
    },
  ]

  return (
    <div className="dw-alert-list-page">
      <PageHeader
        title="경보"
        description="FDS가 결제마다 평가한 위험 점수와 판정입니다. 기본으로 REVIEW · DENY · ERROR만 보여 줍니다."
      />
      <div className="dw-stat-grid" aria-busy={summaryLoading} aria-label="경보 판정 요약">
        <button
          type="button"
          className="dw-panel dw-stat-card dw-stat-card-button"
          onClick={() => selectFilter('REVIEW')}
        >
          <div className="dw-stat-label">REVIEW · 추가 조사</div>
          <div className="dw-stat-value dw-alert-stat-value--review">
            {count('REVIEW')}
            <small>건</small>
          </div>
          <div className="dw-stat-foot">50~79점 · 승인 성공 시 HELD → 사건</div>
        </button>
        <button
          type="button"
          className="dw-panel dw-stat-card dw-stat-card-button"
          onClick={() => selectFilter('DENY')}
        >
          <div className="dw-stat-label">DENY · 결제 차단</div>
          <div className="dw-stat-value dw-alert-stat-value--deny">
            {count('DENY')}
            <small>건</small>
          </div>
          <div className="dw-stat-foot">80점 이상 · 카드 승인 요청 안 함</div>
        </button>
        <button
          type="button"
          className="dw-panel dw-stat-card dw-stat-card-button"
          onClick={() => selectFilter('ERROR')}
        >
          <div className="dw-stat-label">ERROR · 평가 실패</div>
          <div className="dw-stat-value dw-alert-stat-value--error">
            {count('ERROR')}
            <small>건</small>
          </div>
          <div className="dw-stat-foot">위험 판정 아님 · 자동 ALLOW 하지 않음</div>
        </button>
        <button
          type="button"
          className="dw-panel dw-stat-card dw-stat-card-button"
          onClick={() => selectFilter('ALL')}
        >
          <div className="dw-stat-label">전체 평가</div>
          <div className="dw-stat-value">
            {totalCount}
            <small>건</small>
          </div>
          <div className="dw-stat-foot">ALLOW 포함</div>
        </button>
      </div>
      <Card className="dw-table-card dw-alert-card">
        <div className="dw-table-toolbar">
          <div>
            <h3>평가 결과</h3>
            <p>
              행을 누르면 평가 근거를 봅니다. 같은 결제에 평가가 여러 건이면 ERROR를 뺀 최신 평가가
              유효 판정입니다.
            </p>
          </div>
          <Space>
            <Input
              aria-label="경보 검색"
              placeholder="평가 ID, 결제 ID, 고객 식별자"
              prefix={<SearchOutlined />}
              value={query}
              onChange={(event) => {
                const nextQuery = event.target.value
                setQuery(nextQuery)
                conditions.current = { filter, query: nextQuery, merchantId, page: 0 }
                cancelScheduledSearch()
                searchTimer.current = setTimeout(() => {
                  searchTimer.current = null
                  const current = conditions.current
                  load(0, current.filter, current.query, current.merchantId)
                }, 300)
              }}
              allowClear
              className="dw-alert-search"
            />
            <Select
              aria-label="가맹점 필터"
              value={merchantId}
              placeholder="가맹점 선택"
              style={{ width: 180 }}
              options={merchants.map((merchant) => ({
                value: merchant.merchantId,
                label: `${merchant.displayName} (${merchant.merchantId})${merchant.status === 'INACTIVE' ? ' · 비활성' : ''}`,
              }))}
              onChange={(value: number) => selectMerchant(value)}
            />
            <Button
              icon={<ReloadOutlined />}
              loading={summaryLoading && evaluationPage !== null}
              onClick={() => load(currentPage - 1, filter, query, merchantId, true)}
            >
              새로고침
            </Button>
            <Button onClick={createMock} loading={mockCreating}>
              Mock 평가 생성
            </Button>
            <Button danger onClick={createMockError} loading={mockErrorCreating}>
              ERROR 케이스 생성
            </Button>
            <Button onClick={reevaluateMockError} loading={mockReevaluating}>
              재탐지 실행
            </Button>
          </Space>
        </div>
        <div className="dw-alert-tabs">
          {filterOptions.map((option) => {
            const optionCount =
              option.value === 'NEEDS_ATTENTION'
                ? attentionCount
                : option.value === 'ALL'
                  ? totalCount
                  : count(option.value)
            return (
              <button
                key={option.value}
                type="button"
                className={`dw-alert-tab ${filter === option.value ? 'dw-alert-tab--active' : ''}`}
                onClick={() => selectFilter(option.value)}
              >
                {option.label}
                <span>{optionCount}</span>
              </button>
            )
          })}
        </div>
        {error && <Alert type="error" showIcon message={error} className="dw-alert-load-error" />}
        {evaluationPage === null || showInitialListSkeleton ? (
          <div className="dw-alert-list-loading">
            {showInitialListSkeleton && <AlertListSkeleton />}
          </div>
        ) : (
          <Table
            rowKey="evaluationId"
            columns={columns}
            dataSource={evaluations}
            loading={listLoading}
            size="middle"
            scroll={{ x: 1250 }}
            onRow={(record) => ({
              onClick: () => navigate(`/security/alerts/${record.evaluationId}`),
              style: { cursor: 'pointer' },
            })}
            pagination={{
              current: currentPage,
              pageSize: 10,
              showSizeChanger: false,
              showLessItems: false,
              showTotal: () => `총 ${evaluationPage?.totalElements ?? 0}건`,
              total: evaluationPage?.totalElements ?? 0,
              onChange: (page) => {
                conditions.current = { filter, query, merchantId, page: page - 1 }
                load(page - 1, filter, query, merchantId)
              },
            }}
            locale={{ emptyText: '표시할 경보가 없습니다.' }}
          />
        )}
      </Card>
    </div>
  )
}
