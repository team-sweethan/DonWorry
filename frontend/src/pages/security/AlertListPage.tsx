import { PauseOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons'
import { Alert, Button, Card, Input, Select, Skeleton, Space, Table } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { type MouseEvent, useCallback, useEffect, useRef, useState } from 'react'
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { getCaseLinksByPayments } from '../../api/cases'
import { getMerchantDirectory, type MerchantDirectoryEntry } from '../../api/merchants'
import {
  getFdsEvaluations,
  getFdsRulePolicies,
  type FdsEvaluationPage,
  type FdsEvaluationSummary,
  type FdsResult,
} from '../../api/fds'
import { PageHeader } from '../../components/common/PageHeader'
import { useMinimumLoadingIndicator } from '../../hooks/useMinimumLoadingIndicator'
import { WATCH_LIMIT_MS, WATCH_MS } from '../../utils/caseListWatch'
import { customerPart } from '../../utils/caseRules'
import { AlertScoreField, type AlertFilter } from './alert/AlertScoreField'
import { ResultTag } from './alert/AlertTags'
import { scoreToX } from './alert/scoreScale'

type Filter = AlertFilter
type ListConditions = { filter: Filter; query: string; merchantId?: number; page: number }

// 점수 눈금자(AlertScoreField)가 탭을 대신한다. 주소의 filter 값은 이 목록으로 검사한다
const filterValues: Filter[] = ['NEEDS_ATTENTION', 'ALLOW', 'REVIEW', 'DENY', 'ERROR', 'ALL']

// 자동 확인 상태. stopped는 5분 동안 조작이 없어 멈춘 상태다 (30분 무활동 만료를 막지 않도록, ADR-0014)
type LiveState = 'live' | 'paused' | 'stopped'

function alertListSearch({ filter, query, merchantId, page }: ListConditions) {
  const params = new URLSearchParams()
  if (filter !== 'NEEDS_ATTENTION') params.set('filter', filter)
  if (query) params.set('query', query)
  if (merchantId !== undefined) params.set('merchantId', String(merchantId))
  if (page > 0) params.set('page', String(page + 1))
  const search = params.toString()
  return search ? `?${search}` : ''
}

function parseAlertListConditions(searchParams: URLSearchParams): ListConditions {
  const filterValue = searchParams.get('filter')
  const filter = filterValues.includes(filterValue as Filter)
    ? (filterValue as Filter)
    : 'NEEDS_ATTENTION'
  const merchantId = Number(searchParams.get('merchantId'))
  const page = Number(searchParams.get('page'))
  return {
    filter,
    query: searchParams.get('query') ?? '',
    merchantId: Number.isSafeInteger(merchantId) && merchantId > 0 ? merchantId : undefined,
    page: Number.isSafeInteger(page) && page > 1 ? page - 1 : 0,
  }
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat('ko-KR', { dateStyle: 'short', timeStyle: 'medium' }).format(
    new Date(value),
  )
}

function formatClock(value: number) {
  return new Intl.DateTimeFormat('ko-KR', { timeStyle: 'medium' }).format(new Date(value))
}

const DAY_MS = 24 * 60 * 60 * 1000

function relativeTime(value: string, now: number) {
  const seconds = Math.max(0, Math.round((now - new Date(value).getTime()) / 1000))
  if (seconds < 60) return '방금'
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes}분 전`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}시간 전`
  // 하루가 넘으면 "8일 전"보다 날짜가 읽기 쉽다
  return new Intl.DateTimeFormat('ko-KR', {
    month: 'numeric',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(new Date(value))
}

// 점수 눈금자에 찍을 최근 평가 수 (목록 API 최대 100)
const RECENT_SIZE = 100

function fetchEvaluations(filter: Filter, query: string, merchantId: number, page: number) {
  return getFdsEvaluations({
    result: filter === 'NEEDS_ATTENTION' || filter === 'ALL' ? undefined : filter,
    attentionOnly: filter === 'NEEDS_ATTENTION',
    search: query,
    merchantId,
    page,
    size: 10,
  })
}

function attentionOf(counts: FdsEvaluationPage['resultCounts']) {
  return (counts.REVIEW ?? 0) + (counts.DENY ?? 0) + (counts.ERROR ?? 0)
}

// 가맹점별 건수는 자동 확인 4번에 한 번(약 1분)만 다시 받는다. 보고 있는 가맹점은 매번 목록 응답으로 맞춘다
const MERCHANT_COUNT_EVERY = 4

/** 점수 막대. 위 눈금자와 같은 눈금(ALLOW 28 · REVIEW 42 · DENY 30)과 기준선을 쓴다 */
function ScoreMeter({ score, result }: { score: number | null; result: FdsResult }) {
  if (score === null) return <span className="dw-alert-muted">—</span>
  return (
    <span className="dw-alert-score-cell">
      <strong className={`dw-alert-score-number dw-alert-score-number--${result.toLowerCase()}`}>
        {score}
      </strong>
      <span className="dw-alert-meter" aria-hidden="true">
        <i
          className={`dw-alert-meter-fill dw-alert-meter-fill--${result.toLowerCase()}`}
          style={{ width: `${scoreToX(score)}%` }}
        />
      </span>
    </span>
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
  const location = useLocation()
  const [searchParams] = useSearchParams()
  const initialConditions = parseAlertListConditions(searchParams)
  const initialMerchantId = useRef(initialConditions.merchantId)
  const [filter, setFilter] = useState<Filter>(initialConditions.filter)
  const [query, setQuery] = useState(initialConditions.query)
  const [merchantId, setMerchantId] = useState<number | undefined>(initialConditions.merchantId)
  const [merchants, setMerchants] = useState<MerchantDirectoryEntry[]>([])
  const [evaluations, setEvaluations] = useState<FdsEvaluationSummary[]>([])
  const [evaluationPage, setEvaluationPage] = useState<FdsEvaluationPage | null>(null)
  const [resultCounts, setResultCounts] = useState<FdsEvaluationPage['resultCounts'] | null>(null)
  const [listLoading, setListLoading] = useState(true)
  const [summaryLoading, setSummaryLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [currentPage, setCurrentPage] = useState(initialConditions.page + 1)
  const [caseIdsByPaymentId, setCaseIdsByPaymentId] = useState<Map<string, string>>(new Map())
  const requestSequence = useRef(0)
  const resultCountsRef = useRef<FdsEvaluationPage['resultCounts'] | null>(null)
  const summaryRefreshPending = useRef(true)
  const conditions = useRef<ListConditions>(initialConditions)
  const successfulConditions = useRef<ListConditions | null>(null)
  const searchTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const [liveState, setLiveState] = useState<LiveState>('live')
  const [checkedAt, setCheckedAt] = useState<number | null>(null)
  // 가맹점마다 확인 필요(REVIEW · DENY · ERROR) 건수. 한 가맹점만 보더라도 다른 곳에 쌓인 경보를 알 수 있게 한다
  const [attentionByMerchant, setAttentionByMerchant] = useState<Map<number, number>>(new Map())
  const merchantCountSequence = useRef(0)
  const merchantsRef = useRef<MerchantDirectoryEntry[]>([])
  // 점수 눈금자: 가맹점의 최근 평가(검색 · 판정 필터와 무관). 새로 들어온 평가는 위에서 떨어진다
  const [recentEvaluations, setRecentEvaluations] = useState<FdsEvaluationSummary[] | null>(null)
  const [freshDotIds, setFreshDotIds] = useState<Set<number>>(new Set())
  const recentSequence = useRef(0)
  const recentOf = useRef<{ merchantId: number; ids: Set<number> } | null>(null)
  const freshDotTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const silentCheckCount = useRef(0)
  const [freshIds, setFreshIds] = useState<Set<number>>(new Set())
  // 첫 조회(load)에서 현재 시각으로 채운다
  const lastActivityAt = useRef(0)
  const shownIds = useRef<Set<number>>(new Set())
  const freshTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const initialListLoading = listLoading && evaluationPage === null
  const showInitialListSkeleton = useMinimumLoadingIndicator(initialListLoading)

  const cancelScheduledSearch = () => {
    if (searchTimer.current === null) return
    clearTimeout(searchTimer.current)
    searchTimer.current = null
  }

  const persistConditions = useCallback(
    (next: ListConditions) => {
      window.history.replaceState(
        window.history.state,
        '',
        `${location.pathname}${alertListSearch(next)}`,
      )
    },
    [location.pathname],
  )

  const loadRecent = useCallback((targetMerchantId: number) => {
    const sequence = ++recentSequence.current
    void getFdsEvaluations({ merchantId: targetMerchantId, page: 0, size: RECENT_SIZE })
      .then((response) => {
        if (sequence !== recentSequence.current) return
        const previous =
          recentOf.current?.merchantId === targetMerchantId ? recentOf.current.ids : null
        const added = previous
          ? response.content
              .map((evaluation) => evaluation.evaluationId)
              .filter((evaluationId) => !previous.has(evaluationId))
          : []
        recentOf.current = {
          merchantId: targetMerchantId,
          ids: new Set(response.content.map((evaluation) => evaluation.evaluationId)),
        }
        setRecentEvaluations(response.content)
        if (added.length > 0) {
          setFreshDotIds(new Set(added))
          if (freshDotTimer.current !== null) clearTimeout(freshDotTimer.current)
          freshDotTimer.current = setTimeout(() => {
            freshDotTimer.current = null
            setFreshDotIds(new Set())
          }, 2600)
        }
      })
      // 눈금자는 보조 정보다. 실패하면 이전 점을 그대로 두고 다음 확인에서 다시 받는다
      .catch(() => undefined)
  }, [])

  const load = useCallback(
    (
      page: number,
      nextFilter: Filter,
      nextQuery: string,
      nextMerchantId: number | undefined,
      refreshSummary = false,
    ) => {
      if (nextMerchantId === undefined) {
        return
      }
      const sequence = ++requestSequence.current
      if (refreshSummary || resultCountsRef.current === null) summaryRefreshPending.current = true
      const shouldRefreshSummary = summaryRefreshPending.current
      setListLoading(true)
      setSummaryLoading(shouldRefreshSummary)
      setError(null)
      setCaseIdsByPaymentId(new Map())
      setCurrentPage(page + 1)
      setLiveState((current) => (current === 'stopped' ? 'live' : current))
      fetchEvaluations(nextFilter, nextQuery, nextMerchantId, page)
        .then((response) => {
          if (sequence !== requestSequence.current) return
          // 화면 조작으로 다시 받은 목록이다. 자동 확인 제한 시간을 다시 센다
          const loadedAt = Date.now()
          lastActivityAt.current = loadedAt
          setFreshIds(new Set())
          setCheckedAt(loadedAt)
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
            setAttentionByMerchant((previous) =>
              new Map(previous).set(nextMerchantId, attentionOf(response.resultCounts)),
            )
          }
          loadRecent(nextMerchantId)
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
          persistConditions(previous)
        })
        .finally(() => {
          if (sequence === requestSequence.current) {
            setListLoading(false)
            setSummaryLoading(false)
          }
        })
    },
    [loadRecent, persistConditions],
  )

  /** 가맹점마다 1건만 조회해 판정별 건수를 받는다. 실패한 가맹점은 건수를 비워 둔다 */
  const loadMerchantCounts = useCallback((list: MerchantDirectoryEntry[]) => {
    const sequence = ++merchantCountSequence.current
    void Promise.allSettled(
      list.map((merchant) =>
        getFdsEvaluations({
          attentionOnly: true,
          merchantId: merchant.merchantId,
          page: 0,
          size: 1,
        }),
      ),
    ).then((results) => {
      if (sequence !== merchantCountSequence.current) return
      const next = new Map<number, number>()
      results.forEach((result, index) => {
        if (result.status !== 'fulfilled') return
        const counts = result.value.resultCounts
        next.set(list[index].merchantId, attentionOf(counts))
      })
      setAttentionByMerchant(next)
    })
  }, [])

  const loadMerchantDirectory = useCallback(
    (preferredMerchantId?: number) => {
      const sequence = ++requestSequence.current
      setListLoading(true)
      setSummaryLoading(true)
      setError(null)
      void getMerchantDirectory()
        .then((result) => {
          if (sequence !== requestSequence.current) return
          setMerchants(result)
          merchantsRef.current = result
          loadMerchantCounts(result)
          const selectedMerchant =
            result.find((merchant) => merchant.merchantId === preferredMerchantId) ??
            result.find((merchant) => merchant.status === 'ACTIVE') ??
            result[0]
          if (!selectedMerchant) {
            setListLoading(false)
            setSummaryLoading(false)
            setError('선택할 가맹점이 없습니다.')
            return
          }
          const current = conditions.current
          const page = current.merchantId === selectedMerchant.merchantId ? current.page : 0
          setMerchantId(selectedMerchant.merchantId)
          conditions.current = {
            filter: current.filter,
            query: current.query,
            merchantId: selectedMerchant.merchantId,
            page,
          }
          persistConditions(conditions.current)
          load(page, current.filter, current.query, selectedMerchant.merchantId, true)
        })
        .catch(() => {
          if (sequence !== requestSequence.current) return
          setListLoading(false)
          setSummaryLoading(false)
          setError('가맹점 목록을 불러오지 못했습니다.')
        })
    },
    [load, loadMerchantCounts, persistConditions],
  )

  const selectFilter = (nextFilter: Filter) => {
    cancelScheduledSearch()
    setFilter(nextFilter)
    conditions.current = { filter: nextFilter, query, merchantId, page: 0 }
    persistConditions(conditions.current)
    load(0, nextFilter, query, merchantId)
  }

  const selectMerchant = (nextMerchantId: number) => {
    cancelScheduledSearch()
    setRecentEvaluations(null)
    setMerchantId(nextMerchantId)
    conditions.current = { filter, query, merchantId: nextMerchantId, page: 0 }
    persistConditions(conditions.current)
    load(0, filter, query, nextMerchantId, true)
  }

  useEffect(() => {
    const initialLoad = window.setTimeout(() => loadMerchantDirectory(initialMerchantId.current), 0)
    return () => {
      window.clearTimeout(initialLoad)
      cancelScheduledSearch()
      requestSequence.current += 1
    }
  }, [loadMerchantDirectory])

  // 엔진 상태 줄에 쓰는 탐지 룰 수. 화면을 열 때 한 번만 받는다
  const [ruleSummary, setRuleSummary] = useState<{ active: number; total: number } | null>(null)
  useEffect(() => {
    let active = true
    void getFdsRulePolicies()
      .then((policies) => {
        if (active)
          setRuleSummary({
            active: policies.filter((policy) => policy.active).length,
            total: policies.length,
          })
      })
      .catch(() => undefined)
    return () => {
      active = false
    }
  }, [])

  const listLoadingRef = useRef(listLoading)
  const liveStateRef = useRef(liveState)
  useEffect(() => {
    listLoadingRef.current = listLoading
    liveStateRef.current = liveState
  }, [listLoading, liveState])
  useEffect(() => {
    shownIds.current = new Set(evaluations.map((evaluation) => evaluation.evaluationId))
  }, [evaluations])
  // 자동 확인이 한 번 끝날 때마다 1씩 올라 다음 확인을 예약한다
  const [checkRound, setCheckRound] = useState(0)
  // "3분 전" 같은 상대 시각을 멈춤 중에도 맞게 유지한다
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 30_000)
    return () => window.clearInterval(timer)
  }, [])

  /**
   * 화면 조건 그대로 목록을 조용히 다시 받는다. 표 로딩 표시 · 오류 안내 · 조건 되돌리기를 하지 않는다.
   * 첫 페이지에서 처음 보는 평가는 새 경보로 잠깐 강조한다.
   */
  const silentCheck = useCallback(() => {
    const current = conditions.current
    const shown = successfulConditions.current
    const sameAsShown =
      shown !== null &&
      shown.filter === current.filter &&
      shown.query === current.query &&
      shown.merchantId === current.merchantId &&
      shown.page === current.page
    if (
      current.merchantId === undefined ||
      !sameAsShown ||
      searchTimer.current !== null ||
      listLoadingRef.current
    ) {
      setCheckRound((round) => round + 1)
      return
    }
    const sequence = ++requestSequence.current
    fetchEvaluations(current.filter, current.query, current.merchantId, current.page)
      .then((response) => {
        if (sequence !== requestSequence.current) return
        const added =
          response.page === 0
            ? response.content
                .map((evaluation) => evaluation.evaluationId)
                .filter((evaluationId) => !shownIds.current.has(evaluationId))
            : []
        setEvaluations(response.content)
        setEvaluationPage(response)
        resultCountsRef.current = response.resultCounts
        setResultCounts(response.resultCounts)
        setCheckedAt(Date.now())
        loadRecent(current.merchantId!)
        silentCheckCount.current += 1
        if (silentCheckCount.current % MERCHANT_COUNT_EVERY === 0) {
          loadMerchantCounts(merchantsRef.current)
        } else {
          setAttentionByMerchant((previous) =>
            new Map(previous).set(current.merchantId!, attentionOf(response.resultCounts)),
          )
        }
        if (added.length > 0) {
          setFreshIds(new Set(added))
          if (freshTimer.current !== null) clearTimeout(freshTimer.current)
          freshTimer.current = setTimeout(() => {
            freshTimer.current = null
            setFreshIds(new Set())
          }, 2600)
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
          .catch(() => undefined)
      })
      // 실패하면 화면은 그대로 두고 다음 주기에 다시 받는다
      .catch(() => undefined)
      .finally(() => setCheckRound((round) => round + 1))
  }, [loadMerchantCounts, loadRecent])

  // 마지막 확인에서 WATCH_MS 뒤에 다시 확인한다. 탭이 숨어 있으면 쉬고, 돌아오면 바로 확인한다
  useEffect(() => {
    if (liveState !== 'live' || checkedAt === null) return
    const timer = window.setTimeout(() => {
      if (document.visibilityState !== 'visible') return
      if (Date.now() - lastActivityAt.current > WATCH_LIMIT_MS) {
        setLiveState('stopped')
        return
      }
      silentCheck()
    }, WATCH_MS)
    return () => window.clearTimeout(timer)
  }, [liveState, checkedAt, checkRound, silentCheck])

  useEffect(() => {
    const onVisibility = () => {
      if (document.visibilityState !== 'visible') return
      // 탭 복귀는 사용자 동작이다
      lastActivityAt.current = Date.now()
      if (liveStateRef.current === 'paused') return
      setLiveState('live')
      silentCheck()
    }
    document.addEventListener('visibilitychange', onVisibility)
    return () => document.removeEventListener('visibilitychange', onVisibility)
  }, [silentCheck])

  useEffect(
    () => () => {
      if (freshTimer.current !== null) clearTimeout(freshTimer.current)
      if (freshDotTimer.current !== null) clearTimeout(freshDotTimer.current)
    },
    [],
  )

  const resumeLive = () => {
    lastActivityAt.current = Date.now()
    setLiveState('live')
    silentCheck()
  }

  const relativeBase = Math.max(now, checkedAt ?? 0)
  // 확인할 경보가 있는 가맹점과 지금 보는 가맹점만 버튼으로 꺼내고, 나머지는 검색 상자에 둔다
  const attentionMerchants = merchants
    .filter(
      (merchant) =>
        merchant.merchantId === merchantId ||
        (attentionByMerchant.get(merchant.merchantId) ?? 0) > 0,
    )
    .sort(
      (left, right) =>
        (attentionByMerchant.get(right.merchantId) ?? 0) -
        (attentionByMerchant.get(left.merchantId) ?? 0),
    )
  const otherMerchants = merchants
    .filter((merchant) => !attentionMerchants.includes(merchant))
    .sort(
      (left, right) =>
        Number(left.status === 'INACTIVE') - Number(right.status === 'INACTIVE') ||
        left.displayName.localeCompare(right.displayName, 'ko'),
    )
  const openAlert = (evaluationId: number) =>
    navigate(`/security/alerts/${evaluationId}`, {
      state: { alertListReturnPath: location.pathname + alertListSearch(conditions.current) },
    })
  // 가맹점은 위에서 하나만 골라 보므로 열에서 뺐다. 평가 ID는 줄을 누르면 열리므로 결제 ID 아래로 옮겼다
  const columns: ColumnsType<FdsEvaluationSummary> = [
    {
      title: '시각',
      dataIndex: 'evaluatedAt',
      width: 150,
      render: (value: string) => (
        <span className="dw-alert-when">
          <span>{relativeTime(value, relativeBase)}</span>
          {/* 하루가 넘으면 위 줄이 이미 날짜라 아래 줄을 두지 않는다 */}
          {relativeBase - new Date(value).getTime() < DAY_MS && <small>{formatDate(value)}</small>}
        </span>
      ),
    },
    {
      title: '판정',
      dataIndex: 'result',
      width: 150,
      render: (value: FdsResult, evaluation) => (
        <span className="dw-alert-result-cell">
          <ResultTag result={value} />
          {evaluation.errorCode && <code>{evaluation.errorCode}</code>}
        </span>
      ),
    },
    {
      title: '위험 점수',
      dataIndex: 'riskScore',
      width: 170,
      render: (value: number | null, evaluation) => (
        <ScoreMeter score={value} result={evaluation.result} />
      ),
    },
    // 저장값 "가맹점ID:고객"에서 고객 부분만 보인다 (#462)
    {
      title: '고객',
      dataIndex: 'customerRef',
      width: 180,
      render: (value: string) => <span className="dw-alert-mono">{customerPart(value)}</span>,
    },
    {
      title: '결제 ID',
      dataIndex: 'paymentId',
      width: 150,
      render: (value: number, evaluation) => (
        <span className="dw-alert-ids">
          <span className="dw-alert-mono">{value}</span>
          <Button
            type="link"
            className="dw-alert-payment-link"
            onClick={(event: MouseEvent<HTMLElement>) => {
              event.stopPropagation()
              openAlert(evaluation.evaluationId)
            }}
          >
            평가 #{evaluation.evaluationId}
          </Button>
        </span>
      ),
    },
    {
      title: '사건',
      key: 'case',
      width: 130,
      render: (_, evaluation) => {
        const caseId = caseIdsByPaymentId.get(String(evaluation.paymentId))
        if (!caseId) return <span className="dw-alert-muted">—</span>
        return (
          <Button
            type="link"
            className="dw-alert-payment-link dw-alert-mono"
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

  const liveLabel =
    liveState === 'live'
      ? '실시간 확인 중'
      : liveState === 'paused'
        ? '자동 확인 멈춤'
        : '5분 동안 조작이 없어 자동 확인을 멈췄습니다'

  return (
    <div className="dw-alert-list-page">
      <PageHeader
        title="경보"
        description="경보는 FDS가 결제마다 탐지 룰로 점수를 매긴 기록입니다. 점수가 50점을 넘었거나 채점에 실패한 결제를 먼저 보여 줍니다."
      />
      {merchants.length > 0 && (
        <div className="dw-alert-merchants">
          <div className="dw-alert-merchant-list" role="radiogroup" aria-label="경보가 있는 가맹점">
            {attentionMerchants.map((merchant) => {
              const selected = merchant.merchantId === merchantId
              const attention = attentionByMerchant.get(merchant.merchantId)
              return (
                <button
                  key={merchant.merchantId}
                  type="button"
                  role="radio"
                  aria-checked={selected}
                  className={[
                    'dw-alert-merchant',
                    selected ? 'dw-alert-merchant--selected' : '',
                    merchant.status === 'INACTIVE' ? 'dw-alert-merchant--inactive' : '',
                  ].join(' ')}
                  title={`${merchant.displayName} · 가맹점 ID ${merchant.merchantId}`}
                  onClick={() => {
                    if (!selected) selectMerchant(merchant.merchantId)
                  }}
                >
                  <span className="dw-alert-merchant-name">{merchant.displayName}</span>
                  {merchant.status === 'INACTIVE' && (
                    <small className="dw-alert-merchant-state">비활성</small>
                  )}
                  <strong
                    key={attention ?? 'none'}
                    className={`dw-alert-merchant-count ${attention ? 'dw-alert-merchant-count--on' : ''}`}
                    aria-label={
                      attention === undefined ? '건수 확인 못 함' : `확인 필요 ${attention}건`
                    }
                  >
                    {attention ?? '–'}
                  </strong>
                </button>
              )
            })}
          </div>
          {otherMerchants.length > 0 && (
            <Select
              aria-label="다른 가맹점 찾기"
              className="dw-alert-merchant-search"
              showSearch
              value={null}
              placeholder={`다른 가맹점 ${otherMerchants.length}곳 찾기`}
              optionFilterProp="searchText"
              popupMatchSelectWidth={320}
              options={otherMerchants.map((merchant) => {
                const attention = attentionByMerchant.get(merchant.merchantId)
                return {
                  value: merchant.merchantId,
                  searchText: `${merchant.displayName} ${merchant.merchantId}`,
                  label: (
                    <span className="dw-alert-merchant-option">
                      <span>
                        {merchant.displayName}
                        {merchant.status === 'INACTIVE' && <small> · 비활성</small>}
                      </span>
                      <span className="dw-alert-merchant-option-count">{attention ?? '–'}</span>
                    </span>
                  ),
                }
              })}
              onChange={(value: number | null) => {
                if (value !== null) selectMerchant(value)
              }}
            />
          )}
        </div>
      )}
      <Card className="dw-table-card dw-alert-card">
        <div className="dw-table-toolbar">
          <div className={`dw-alert-live dw-alert-live--${liveState}`}>
            <span className="dw-alert-live-dot" aria-hidden="true" />
            <strong>{liveLabel}</strong>
            {liveState === 'live' && checkedAt !== null && (
              <svg
                key={`${checkedAt}-${checkRound}`}
                className="dw-alert-live-ring"
                viewBox="0 0 20 20"
                aria-hidden="true"
                style={{ animationDuration: `${WATCH_MS}ms` }}
              >
                <circle className="dw-alert-live-ring-track" cx="10" cy="10" r="8" />
                <circle
                  className="dw-alert-live-ring-fill"
                  cx="10"
                  cy="10"
                  r="8"
                  style={{ animationDuration: `${WATCH_MS}ms` }}
                />
              </svg>
            )}
            <span className="dw-alert-live-meta">
              {liveState === 'live' && `${WATCH_MS / 1000}초마다 · `}
              {checkedAt !== null && `마지막 확인 ${formatClock(checkedAt)}`}
            </span>
            {liveState === 'live' ? (
              <Button size="small" icon={<PauseOutlined />} onClick={() => setLiveState('paused')}>
                멈춤
              </Button>
            ) : (
              <Button size="small" onClick={resumeLive}>
                다시 시작
              </Button>
            )}
          </div>
          <Space wrap>
            <Input
              aria-label="경보 검색"
              placeholder="평가 ID, 결제 ID, 고객 식별자"
              prefix={<SearchOutlined />}
              value={query}
              onChange={(event) => {
                const nextQuery = event.target.value
                setQuery(nextQuery)
                conditions.current = { filter, query: nextQuery, merchantId, page: 0 }
                persistConditions(conditions.current)
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
            <Button
              icon={<ReloadOutlined />}
              loading={summaryLoading && evaluationPage !== null}
              onClick={() => loadMerchantDirectory(merchantId)}
            >
              새로고침
            </Button>
          </Space>
        </div>
        <AlertScoreField
          counts={resultCounts}
          recent={recentEvaluations}
          freshIds={freshDotIds}
          filter={filter}
          busy={summaryLoading}
          rules={ruleSummary}
          onSelect={selectFilter}
          onOpen={openAlert}
        />
        {error && <Alert type="error" showIcon message={error} className="dw-alert-load-error" />}
        {evaluationPage === null || showInitialListSkeleton ? (
          <div className="dw-alert-list-loading">
            {showInitialListSkeleton && <AlertListSkeleton />}
          </div>
        ) : (
          <Table
            className="dw-alert-table"
            rowKey="evaluationId"
            columns={columns}
            dataSource={evaluations}
            loading={listLoading}
            size="middle"
            scroll={{ x: 960 }}
            rowClassName={(record) =>
              freshIds.has(record.evaluationId) ? 'dw-alert-row dw-alert-row--new' : 'dw-alert-row'
            }
            onRow={(record) => ({
              onClick: () => openAlert(record.evaluationId),
              style: { cursor: 'pointer' },
            })}
            pagination={{
              current: currentPage,
              pageSize: 10,
              showSizeChanger: false,
              showLessItems: false,
              hideOnSinglePage: false,
              showTotal: () => `총 ${evaluationPage?.totalElements ?? 0}건`,
              // Render the page-1 shell even for an empty alert result. This
              // keeps pagination consistent while leaving both arrows disabled.
              total: Math.max(evaluationPage?.totalElements ?? 0, 1),
              onChange: (page) => {
                conditions.current = { filter, query, merchantId, page: page - 1 }
                persistConditions(conditions.current)
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
