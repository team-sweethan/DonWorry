import { ReloadOutlined, SearchOutlined } from '@ant-design/icons'
import { Alert, Button, Card, Input, Space, Spin, Table } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { type MouseEvent, useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  createMockFdsEvaluations,
  getFdsEvaluations,
  type FdsEvaluationPage,
  type FdsEvaluationSummary,
  type FdsResult,
} from '../../api/fds'
import { PageHeader } from '../../components/common/PageHeader'
import { ResultTag } from './alert/AlertTags'

type Filter = 'NEEDS_ATTENTION' | FdsResult | 'ALL'

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

export function AlertListPage() {
  const navigate = useNavigate()
  const [filter, setFilter] = useState<Filter>('NEEDS_ATTENTION')
  const [query, setQuery] = useState('')
  const [evaluations, setEvaluations] = useState<FdsEvaluationSummary[]>([])
  const [evaluationPage, setEvaluationPage] = useState<FdsEvaluationPage | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [currentPage, setCurrentPage] = useState(1)
  const [mockCreating, setMockCreating] = useState(false)
  const requestSequence = useRef(0)

  const load = (page = 0, nextFilter = filter, nextQuery = query) => {
    const sequence = ++requestSequence.current
    setLoading(true)
    setError(null)
    getFdsEvaluations({
      result:
        nextFilter === 'REVIEW' || nextFilter === 'DENY' || nextFilter === 'ERROR'
          ? nextFilter
          : undefined,
      attentionOnly: nextFilter === 'NEEDS_ATTENTION',
      search: nextQuery,
      page,
      size: 10,
    })
      .then((response) => {
        if (sequence !== requestSequence.current) return
        setEvaluations(response.content)
        setEvaluationPage(response)
        setCurrentPage(response.page + 1)
      })
      .catch(() => {
        if (sequence === requestSequence.current) setError('경보 목록을 불러오지 못했습니다.')
      })
      .finally(() => {
        if (sequence === requestSequence.current) setLoading(false)
      })
  }

  const createMock = () => {
    setMockCreating(true)
    setError(null)
    createMockFdsEvaluations()
      .then(() => load(0, filter))
      .catch(() => setError('Mock 평가를 생성하지 못했습니다.'))
      .finally(() => setMockCreating(false))
  }

  const selectFilter = (nextFilter: Filter) => {
    setFilter(nextFilter)
    load(0, nextFilter)
  }

  useEffect(() => {
    load(0, 'NEEDS_ATTENTION')
    return () => {
      requestSequence.current += 1
    }
  }, [])

  const count = (result: FdsResult) => evaluationPage?.resultCounts[result] ?? 0
  const totalCount = Object.values(evaluationPage?.resultCounts ?? {}).reduce(
    (total, resultCount) => total + resultCount,
    0,
  )
  const attentionCount = totalCount - count('ALLOW')
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
  ]

  return (
    <div className="dw-alert-list-page">
      <PageHeader
        title="경보"
        description="FDS가 결제마다 평가한 위험 점수와 판정입니다. 기본으로 REVIEW · DENY · ERROR만 보여 줍니다."
      />
      <div className="dw-stat-grid" aria-label="경보 판정 요약">
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
                load(0, filter, nextQuery)
              }}
              allowClear
              className="dw-alert-search"
            />
            <Button icon={<ReloadOutlined />} onClick={() => load(currentPage - 1, filter)}>
              새로고침
            </Button>
            <Button onClick={createMock} loading={mockCreating}>
              Mock 평가 생성
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
        {loading ? (
          <div className="dw-alert-loading">
            <Spin />
          </div>
        ) : (
          <Table
            rowKey="evaluationId"
            columns={columns}
            dataSource={evaluations}
            size="middle"
            scroll={{ x: 1000 }}
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
              onChange: (page) => load(page - 1, filter),
            }}
            locale={{ emptyText: '표시할 경보가 없습니다.' }}
          />
        )}
      </Card>
      <div className="dw-api-note">
        점수 기준: 0~49점 ALLOW · 50~79점 REVIEW · 80점 이상 DENY. 1차 MVP 규칙: 고액 결제(15만 원
        이상 +35) · 단시간 반복 결제(동일 구매자 10분 내 3회 이상 +25).
      </div>
    </div>
  )
}
