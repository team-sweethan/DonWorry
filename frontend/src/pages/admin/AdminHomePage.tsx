import {
  ArrowRightOutlined,
  CheckOutlined,
  ExclamationCircleOutlined,
  FileSearchOutlined,
  ReloadOutlined,
  RobotOutlined,
  SafetyOutlined,
  UserSwitchOutlined,
  WarningOutlined,
} from '@ant-design/icons'
import { Alert, Button, Skeleton } from 'antd'
import { useEffect, useState, type CSSProperties, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { getAdminHome, type AdminHome } from '../../api/adminHome'
import type { OperatorAccessSummary } from '../../api/fds'
import { PageHeader } from '../../components/common/PageHeader'
import {
  refreshApplicationReviewBadge,
  useApplicationReviewCount,
} from '../../hooks/useApplicationReviewBadge'
import {
  refreshCaseAssignmentRequestBadge,
  useCaseAssignmentRequestCount,
} from '../../hooks/useCaseAssignmentRequestBadge'
import { refreshInsiderAlertBadge, useInsiderAlertCount } from '../../hooks/useInsiderAlertBadge'
import { refreshInsiderCaseBadge, useInsiderCaseCount } from '../../hooks/useInsiderCaseBadge'
import {
  refreshOperatorAccessBadge,
  useOperatorAccessCount,
} from '../../hooks/useOperatorAccessBadge'
import { refreshRawViewReviewBadge, useRawViewReviewCount } from '../../hooks/useRawViewReviewBadge'
import { refreshRuleChangeRequestBadge } from '../../hooks/useRuleChangeRequestBadge'
import {
  aiStatusView,
  attentionItems,
  chartDayLabel,
  insiderStatusView,
  operatorAccessView,
  ruleStatusView,
  seoulToday,
  todoCounting,
  todoFailed,
  todoTotal,
  type HomeTone,
  type TodoGroup,
  type TodoItem,
} from '../../utils/adminHomeView'
import { displayRuleCode } from '../../utils/ruleDisplayCode'
import { HomeCount } from '../security/HomeCount'
import './AdminHomePage.css'

const requestPath = '/admin/fds/rule-change-requests'
const accessPath = '/admin/fds/operator-access-detections'

const formatDate = (value: string) =>
  new Intl.DateTimeFormat('ko-KR', {
    month: 'long',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    timeZone: 'Asia/Seoul',
  }).format(new Date(value))

const formatTime = (value: Date) =>
  new Intl.DateTimeFormat('ko-KR', {
    hour: '2-digit',
    minute: '2-digit',
    timeZone: 'Asia/Seoul',
  }).format(value)

const number = (value: number) => value.toLocaleString('ko-KR')

/** 홈에서 한 번에 불러온 값을 처리할 일 칸 값으로 — 불러오는 중이면 null, 실패면 'error' */
function countOf<T>(
  result: PromiseSettledResult<T> | undefined,
  pick: (value: T) => number,
): TodoItem['count'] {
  if (!result) return null
  return result.status === 'fulfilled' ? pick(result.value) : 'error'
}

type StatusNode = {
  key: string
  title: string
  icon: ReactNode
  to: string
  view: { tone: HomeTone; label: string; detail: string } | null
  loading: boolean
}

/**
 * 운영 상태 한 칸. 아이콘 원 색으로 정상(초록) · 주의(주황) · 멈춤(빨강)을 먼저 보이고,
 * 빨강이면 원 둘레가 퍼져 나가 관제 화면처럼 눈에 띄게 한다.
 */
function StatusNodeView({ node }: { node: StatusNode }) {
  const tone = node.view?.tone
  return (
    <li className={`ah-node${tone ? ` is-${tone}` : ''}`}>
      <Link to={node.to} className="ah-node-link">
        <span className="ah-node-icon" aria-hidden="true">
          {node.icon}
        </span>
        <span className="ah-node-title">{node.title}</span>
        {node.loading ? (
          <Skeleton.Input active size="small" style={{ width: 96 }} aria-label="불러오는 중" />
        ) : !node.view ? (
          <span className="ah-count-error">불러오기 실패</span>
        ) : (
          <>
            <strong className="ah-node-value">{node.view.label}</strong>
            <span className="ah-node-detail">{node.view.detail}</span>
          </>
        )}
      </Link>
    </li>
  )
}

/** 처리할 일 한 줄. 0건도 "없음"으로 보여 무엇을 확인했는지 한눈에 보이게 한다 */
function TodoRow({ item }: { item: TodoItem }) {
  const open = typeof item.count === 'number' && item.count > 0
  const state = open ? (item.urgent ? ' is-open is-urgent' : ' is-open') : ''
  return (
    <li>
      <Link to={item.to} className={`ah-board-row${state}`}>
        <span className="ah-board-dot" aria-hidden="true" />
        <span className="ah-board-label">{item.label}</span>
        {item.count === null ? (
          <Skeleton.Input
            active
            size="small"
            style={{ width: 48, minWidth: 48 }}
            aria-label="세는 중"
          />
        ) : item.count === 'error' ? (
          <span className="ah-count-error">불러오기 실패</span>
        ) : open ? (
          <strong className="ah-board-count">
            {number(item.count)}
            <small>건</small>
          </strong>
        ) : (
          <span className="ah-board-none">
            <CheckOutlined aria-hidden="true" /> 없음
          </span>
        )}
      </Link>
    </li>
  )
}

type BarRow = { key: string; label: ReactNode; count: number; strong?: boolean; title?: string }

// 막대 길이는 그래프 안 최댓값 기준. 0건은 그리지 않고, 1건 이상은 최소 길이를 둬 보이게 한다 (보안 홈과 같은 규칙)
function Bars({ rows, label }: { rows: BarRow[]; label: string }) {
  const max = Math.max(1, ...rows.map((row) => row.count))
  return (
    <ul className="ah-bars" aria-label={label}>
      {rows.map((row, index) => (
        <li key={row.key} className={row.strong ? 'is-strong' : undefined} title={row.title}>
          <span className="ah-bar-label">{row.label}</span>
          <span className="ah-bar-track" aria-hidden="true">
            {row.count > 0 && (
              <i
                style={
                  {
                    width: `${Math.max(3, (row.count / max) * 100)}%`,
                    '--bar-delay': `${300 + index * 80}ms`,
                  } as CSSProperties
                }
              />
            )}
          </span>
          <strong className="ah-bar-value">{number(row.count)}건</strong>
        </li>
      ))}
    </ul>
  )
}

/** 사장님 계정 접근 거부 — 최근 7일 날짜별 · 많이 막힌 경로 (#515, #746 요약 API) */
function AccessCharts({
  summary,
  today,
  loading,
}: {
  summary: OperatorAccessSummary | null
  today: string
  loading: boolean
}) {
  const days: BarRow[] = (summary?.daily ?? []).map((day) => {
    const isToday = day.date === today
    return {
      key: day.date,
      label: chartDayLabel(day.date, today),
      count: day.count,
      strong: isToday,
    }
  })
  const paths: BarRow[] = (summary?.topPaths ?? []).slice(0, 5).map((path) => ({
    key: `${path.httpMethod} ${path.path}`,
    label: (
      <>
        <code>{path.httpMethod}</code> {path.path}
      </>
    ),
    title: `${path.httpMethod} ${path.path}`,
    count: path.count,
  }))
  const body = (rows: BarRow[], label: string, empty: string) =>
    loading ? (
      <Skeleton active title={false} paragraph={{ rows: 4 }} />
    ) : !summary ? (
      <p className="ah-chart-empty">불러오지 못했습니다.</p>
    ) : rows.length === 0 || rows.every((row) => row.count === 0) ? (
      <p className="ah-chart-empty">
        <CheckOutlined aria-hidden="true" /> {empty}
      </p>
    ) : (
      <Bars rows={rows} label={label} />
    )
  return (
    <div className="ah-charts ah-enter">
      <section className="ah-panel ah-chart" aria-labelledby="ah-chart-days">
        <Link to={accessPath} className="ah-chart-heading">
          <h3 id="ah-chart-days">사장님 계정 접근 거부</h3>
          <ArrowRightOutlined className="ah-arrow" aria-hidden="true" />
        </Link>
        <p>최근 7일 · 한국 날짜 기준 · 권한 밖 요청이 막힌 횟수</p>
        {body(days, '날짜별 접근 거부 수', '최근 7일 동안 거부된 요청이 없습니다')}
      </section>
      <section className="ah-panel ah-chart" aria-labelledby="ah-chart-paths">
        <Link to={accessPath} className="ah-chart-heading">
          <h3 id="ah-chart-paths">많이 막힌 경로</h3>
          <ArrowRightOutlined className="ah-arrow" aria-hidden="true" />
        </Link>
        <p>최근 7일 · 거부가 많은 순 · 최대 5개</p>
        {body(paths, '경로별 접근 거부 수', '막힌 경로가 없습니다')}
      </section>
    </div>
  )
}

export function AdminHomePage() {
  const [refreshKey, setRefreshKey] = useState(0)
  // today · checkedAt: 불러온 시점. 사장님 접근 이상 "오늘" 칸을 그 날짜로 세고, 조회 시각을 보인다
  const [result, setResult] = useState<{
    key: number
    data: AdminHome
    today: string
    checkedAt: Date
  } | null>(null)

  useEffect(() => {
    let alive = true
    void getAdminHome().then((data) => {
      const now = new Date()
      if (alive) setResult({ key: refreshKey, data, today: seoulToday(now), checkedAt: now })
    })
    return () => {
      alive = false
    }
  }, [refreshKey])

  const loading = result?.key !== refreshKey
  const data = loading ? undefined : result?.data
  const today = result?.today ?? ''
  const requests = data?.requests
  const pending = requests?.status === 'fulfilled' ? requests.value : undefined
  const failed = data && Object.values(data).some((value) => value.status === 'rejected')
  // 새로고침은 홈 값과 사이드바 배지를 함께 다시 센다
  const refresh = () => {
    setRefreshKey((key) => key + 1)
    refreshApplicationReviewBadge()
    refreshCaseAssignmentRequestBadge()
    refreshRawViewReviewBadge()
    refreshInsiderAlertBadge()
    refreshInsiderCaseBadge()
    refreshOperatorAccessBadge()
    refreshRuleChangeRequestBadge()
  }

  // 사이드바 배지가 센 값은 읽기만 한다(서버 요청 없음). 배지가 없는 값은 홈에서 한 번에 불러온다
  const insiderAlerts = useInsiderAlertCount()
  const insiderCases = useInsiderCaseCount()
  const groups: TodoGroup[] = [
    {
      title: '가맹점 관리',
      items: [
        {
          label: '이용 신청 검토 대기',
          to: '/admin/applications?status=REVIEW_PENDING',
          count: useApplicationReviewCount(),
        },
      ],
    },
    {
      title: '직원 관리',
      items: [
        {
          label: '사건 담당 변경 요청',
          to: '/admin/case-assignment-requests',
          count: useCaseAssignmentRequestCount(),
        },
        {
          label: '전화번호 열람 검토',
          to: '/admin/raw-view-reviews',
          count: useRawViewReviewCount(),
        },
      ],
    },
    {
      title: '접근 감시',
      items: [
        { label: '내부자 사건', to: '/admin/insider-cases', count: insiderCases, urgent: true },
        { label: '내부자 경보', to: '/admin/insider-alerts', count: insiderAlerts, urgent: true },
        {
          label: '미확인 고위험 접근 탐지',
          to: accessPath,
          count: useOperatorAccessCount(),
          urgent: true,
        },
      ],
    },
    {
      title: '시스템 설정',
      items: [
        {
          label: '룰 끄기 승인 대기',
          to: requestPath,
          count: countOf(data?.requests, (value) => value.count),
        },
        {
          label: '임시 비밀번호 미변경',
          to: '/admin/users?status=ACTIVE&passwordChangeRequired=true',
          count: countOf(data?.passwordPendingUsers, (value) => value),
        },
      ],
    },
  ]
  const total = todoTotal(groups)
  const counting = todoCounting(groups)
  // 하나라도 못 불러왔으면 0건이어도 "없음"이라고 하지 않는다
  const incomplete = todoFailed(groups)
  const attention = attentionItems(groups)

  const summary = data?.operatorAccess.status === 'fulfilled' ? data.operatorAccess.value : null
  const access = summary ? operatorAccessView(summary, today) : null
  const nodes: StatusNode[] = [
    {
      key: 'ai',
      title: 'AI 조사',
      icon: <RobotOutlined />,
      to: '/admin/ai-switch',
      view: data?.aiSwitch.status === 'fulfilled' ? aiStatusView(data.aiSwitch.value) : null,
      loading,
    },
    {
      key: 'rules',
      title: '탐지 룰',
      icon: <SafetyOutlined />,
      to: '/admin/fds/rules',
      view:
        data?.disabledRules.status === 'fulfilled'
          ? ruleStatusView(data.disabledRules.value)
          : null,
      loading,
    },
    {
      key: 'insider',
      title: '내부자 보안',
      icon: <UserSwitchOutlined />,
      to: '/admin/insider-monitor',
      view: insiderStatusView(insiderAlerts, insiderCases),
      // 사이드바 배지가 센 값이라 홈 조회와 따로 세는 중을 보인다
      loading: insiderAlerts === null || insiderCases === null,
    },
    {
      key: 'access',
      title: '사장님 계정 접근',
      icon: <WarningOutlined />,
      to: accessPath,
      view: access && {
        tone: access.tone,
        label: `오늘 거부 ${number(access.today)}건`,
        detail: `최근 7일 ${number(access.week)}건 · 계정 ${number(access.accounts)}개`,
      },
      loading,
    },
  ]
  const users = data?.users.status === 'fulfilled' ? data.users.value : null
  const merchants = data?.merchants.status === 'fulfilled' ? data.merchants.value : null

  return (
    <div className="ah-home">
      <PageHeader
        title="관리자 홈"
        description="운영 상태와 관리자가 처리할 일을 한눈에 보여 줍니다."
        actions={
          <div className="ah-refresh">
            {result && <span>{formatTime(result.checkedAt)} 조회</span>}
            <Button icon={<ReloadOutlined />} onClick={refresh} loading={loading}>
              새로고침
            </Button>
          </div>
        }
      />

      {failed && (
        <Alert
          type="error"
          showIcon
          message="일부 현황을 불러오지 못했습니다."
          action={<Button onClick={refresh}>다시 불러오기</Button>}
        />
      )}

      <section className="ah-hero ah-enter" aria-label="관리자 현황">
        <div className="ah-hero-summary">
          <span className="ah-hero-label">처리할 일</span>
          <h2 className={`ah-hero-total${total > 0 ? ' is-open' : ''}`}>
            {counting && total === 0 ? (
              <Skeleton.Input active style={{ width: 96, height: 48 }} aria-label="세는 중" />
            ) : (
              <>
                <HomeCount value={total} />
                <small>건</small>
              </>
            )}
          </h2>
          <p>
            {incomplete
              ? '일부 건수를 불러오지 못했습니다. 새로고침해 주세요.'
              : total > 0
                ? '아래 칸에서 바로 처리 화면으로 이동합니다.'
                : counting
                  ? '처리할 일을 세는 중입니다.'
                  : '지금 처리할 일이 없습니다.'}
          </p>
          {users !== null && merchants !== null && (
            // 정지 · 중지 계정을 포함한 전체 수라 목록도 전체 상태로 연다 (목록 기본은 사용 중)
            <Link to="/admin/users?status=ALL" className="ah-hero-scale">
              전체 계정 {number(users)}명 · 가맹점 {number(merchants)}곳
              <ArrowRightOutlined aria-hidden="true" />
            </Link>
          )}
        </div>
        <div className="ah-hero-status">
          <strong className="ah-hero-heading">운영 상태</strong>
          <ul className="ah-nodes">
            {nodes.map((node) => (
              <StatusNodeView key={node.key} node={node} />
            ))}
          </ul>
        </div>
      </section>

      <div
        className={`ah-attention${attention.length > 0 || incomplete ? ' is-open' : ''}`}
        role="status"
      >
        <span className="ah-attention-label">
          {attention.length > 0 || incomplete ? (
            <ExclamationCircleOutlined aria-hidden="true" />
          ) : (
            <CheckOutlined aria-hidden="true" />
          )}
          확인 필요
        </span>
        {attention.length > 0 ? (
          attention.map((item) => (
            <Link key={item.to} to={item.to} className={item.urgent ? 'is-red' : 'is-amber'}>
              {item.label} <strong>{number(item.count)}건</strong>
              <ArrowRightOutlined aria-hidden="true" />
            </Link>
          ))
        ) : (
          <span className="ah-attention-none">
            {incomplete
              ? '일부 건수를 불러오지 못해 확인할 일을 모두 셀 수 없습니다.'
              : counting
                ? '세는 중입니다.'
                : '먼저 확인할 일이 없습니다.'}
          </span>
        )}
      </div>

      <section className="ah-panel ah-board ah-enter" aria-labelledby="ah-board-title">
        <div className="ah-board-heading">
          <h2 id="ah-board-title">처리할 일</h2>
          {total > 0 ? (
            <span className="ah-board-total is-open">
              {number(total)}건{incomplete && ' 이상'}
            </span>
          ) : incomplete ? (
            <span className="ah-board-total is-error">일부 불러오기 실패</span>
          ) : counting ? null : (
            <span className="ah-board-total">
              <CheckOutlined aria-hidden="true" /> 모두 처리됨
            </span>
          )}
          <span className="ah-board-hint">메뉴 묶음 순서 · 0건도 함께 보입니다</span>
        </div>
        <div className="ah-board-groups">
          {groups.map((group) => (
            <div key={group.title} className="ah-board-group">
              <h3>{group.title}</h3>
              <ul>
                {group.items.map((item) => (
                  <TodoRow key={item.to} item={item} />
                ))}
              </ul>
            </div>
          ))}
        </div>
      </section>

      <AccessCharts summary={summary} today={today} loading={loading} />

      <section
        className={`ah-panel ah-approvals ah-enter${pending?.count ? ' is-pending' : ''}`}
        aria-labelledby="ah-approval-title"
        aria-busy={loading}
      >
        <div className="ah-approval-summary">
          <div className="ah-feature-icon" aria-hidden="true">
            <FileSearchOutlined />
          </div>
          <div className="ah-summary-copy">
            <h2 id="ah-approval-title">최근 룰 끄기 요청</h2>
          </div>
          <Link to={requestPath} className="ah-review-link">
            {pending?.count === 0 ? '요청 목록' : '요청 검토'}{' '}
            <ArrowRightOutlined aria-hidden="true" />
          </Link>
        </div>
        {loading ? (
          <div className="ah-list-state" role="status" aria-label="룰 끄기 요청을 불러오는 중">
            <Skeleton active paragraph={{ rows: 3 }} />
          </div>
        ) : !pending ? (
          <div className="ah-list-state ah-empty" role="status">
            <FileSearchOutlined aria-hidden="true" />
            <h3>룰 끄기 요청을 불러오지 못했습니다</h3>
          </div>
        ) : pending.recent.length === 0 ? (
          <div className="ah-list-state ah-empty" role="status">
            <span className="ah-empty-check">
              <CheckOutlined aria-hidden="true" />
            </span>
            <h3>대기 중인 요청이 없습니다</h3>
          </div>
        ) : (
          <ul className="ah-request-list">
            {pending.recent.map((request) => (
              <li key={request.requestId}>
                <Link
                  to={`${requestPath}?requestId=${request.requestId}`}
                  className="ah-request-link"
                >
                  <span className="ah-rule-code">{displayRuleCode(request.ruleCode)}</span>
                  <div className="ah-request-content">
                    <p className="ah-request-reason" title={request.reason}>
                      {request.reason}
                    </p>
                    <div className="ah-request-meta">
                      <span>{request.requestedByName}</span>
                      <span aria-hidden="true">·</span>
                      <time dateTime={request.requestedAt}>{formatDate(request.requestedAt)}</time>
                    </div>
                  </div>
                  <ArrowRightOutlined className="ah-request-action" aria-hidden="true" />
                </Link>
              </li>
            ))}
            {pending.count > pending.recent.length && (
              <li>
                <Link to={requestPath} className="ah-request-more">
                  외 {number(pending.count - pending.recent.length)}건 더 보기{' '}
                  <ArrowRightOutlined aria-hidden="true" />
                </Link>
              </li>
            )}
          </ul>
        )}
      </section>
    </div>
  )
}
