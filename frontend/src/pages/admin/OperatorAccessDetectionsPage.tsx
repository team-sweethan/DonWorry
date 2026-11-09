import { ReloadOutlined } from '@ant-design/icons'
import { Alert, Button, Input, Pagination, Segmented, Select, Tabs, Tag } from 'antd'
import dayjs from 'dayjs'
import { useEffect, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import {
  getOperatorAccessAccounts,
  getOperatorAccessDetections,
  getOperatorAccessSummary,
  type OperatorAccessAccountGroup,
  type OperatorAccessAccountPage,
  type OperatorAccessDetection,
  type OperatorAccessDetectionPage,
  type OperatorAccessSummary,
} from '../../api/fds'
import { ListEmpty } from '../../components/common/ListEmpty'
import { ListLoading } from '../../components/common/ListLoading'
import { PageHeader } from '../../components/common/PageHeader'
import {
  actionView,
  groupByFeature,
  occurredLabel,
  parseListView,
  parseRule,
  ruleOptions,
  type ListView,
} from './operatorAccessView'
import './caseAssignmentShared.css'
import './OperatorAccessDetectionsPage.css'

type Risk = 'ALL' | 'HIGH' | 'LOW'

/** 탐지 한 줄 — 위험도 · 룰이 제목, 아래 줄에 계정 · 소속 · 시각. 오른쪽에 발생 횟수와 조치 */
function DetectionRow({
  value,
  returnPath,
  compact = false,
}: {
  value: OperatorAccessDetection
  returnPath: string
  /** 계정 묶음 안 — 계정 · 소속은 묶음 머리에 있으니 줄에서 뺀다 */
  compact?: boolean
}) {
  const action = actionView(value.actionStatus)
  return (
    <li>
      <Link
        className="oad-row"
        to={
          value.detectionId
            ? `/admin/fds/operator-access-detections/${value.detectionId}`
            : `/admin/fds/operator-access-detections/low/${value.sourceEventId}`
        }
        state={{ operatorAccessDetectionListReturnPath: returnPath }}
      >
        <span className="oad-row-main">
          <span className="oad-row-title">
            <Tag color={value.riskLevel === 'HIGH' ? 'red' : 'gold'}>
              {value.riskLevel === 'HIGH' ? '고위험' : '저위험'}
            </Tag>
            {value.ruleName}
          </span>
          <span className="oad-row-sub">
            {!compact && <strong>{value.loginId}</strong>}
            {!compact && <span>{value.affiliation}</span>}
            {value.blockedPath && <code className="oad-path">{value.blockedPath}</code>}
            <time
              dateTime={value.occurredAt}
              title={dayjs(value.occurredAt).format('YYYY.MM.DD HH:mm:ss')}
            >
              {occurredLabel(value.occurredAt)}
            </time>
          </span>
        </span>
        <span className="oad-row-count">
          <strong>{value.eventCount}</strong>회
        </span>
        <span className="oad-row-action">
          <Tag color={action.color}>{action.label}</Tag>
          {action.color === 'red' && <span className="oad-row-attention">직접 확인 필요</span>}
        </span>
      </Link>
    </li>
  )
}

/** 7일 추이 — 면 + 선, 마지막 날에 점. 값이 모두 0이면 바닥에 평평한 선 */
function Trend({ daily }: { daily: OperatorAccessSummary['daily'] }) {
  const width = 200
  const height = 40
  const max = Math.max(...daily.map((day) => day.count), 1)
  const points = daily.map((day, index) => [
    (index / Math.max(daily.length - 1, 1)) * width,
    height - 3 - (day.count / max) * (height - 8),
  ])
  const line = points.map(([x, y]) => `${x.toFixed(1)},${y.toFixed(1)}`).join(' ')
  const last = points[points.length - 1]
  return (
    <svg
      className="oad-trend"
      viewBox={`0 0 ${width} ${height}`}
      preserveAspectRatio="none"
      role="img"
      aria-label={`최근 7일 거부 요청 ${daily.map((day) => day.count).join(', ')}회`}
    >
      <polygon points={`0,${height} ${line} ${width},${height}`} className="oad-trend-area" />
      <polyline points={line} className="oad-trend-line" />
      {last && <circle cx={last[0]} cy={last[1]} r="2.6" className="oad-trend-dot" />}
    </svg>
  )
}

/** 목록 위 요약 띠 — 고위험 · 저위험 건수와 최근 7일 거부 요청 */
function SummaryStrip({
  high,
  low,
  summary,
  failed,
}: {
  high: number | null
  low: number | null
  summary: OperatorAccessSummary | null
  failed: boolean
}) {
  return (
    <section className="ca-panel-box oad-stats" aria-label="탐지 요약">
      <div className="oad-stat">
        <span className="oad-stat-label">
          <Tag color="red">고위험</Tag>
        </span>
        <span className="oad-stat-number">
          {high ?? '—'}
          <small>건</small>
        </span>
        <span className="dw-caption">기준을 넘어 계정 정지 대상</span>
      </div>
      <div className="oad-stat">
        <span className="oad-stat-label">
          <Tag color="gold">저위험</Tag>
        </span>
        <span className="oad-stat-number">
          {low ?? '—'}
          <small>건</small>
        </span>
        <span className="dw-caption">기준 아래 · 지켜보는 중</span>
      </div>
      <div className="oad-stat oad-stat-trend">
        <span className="oad-stat-label">최근 7일 거부 요청</span>
        <span className="oad-stat-number">
          {summary?.deniedCount ?? '—'}
          <small>회</small>
        </span>
        {summary ? (
          <>
            <Trend daily={summary.daily} />
            <span className="dw-caption">{`사장님 계정 ${summary.accountCount}개에서`}</span>
          </>
        ) : (
          <span className="dw-caption">{failed ? '불러오지 못했어요' : '불러오는 중'}</span>
        )}
      </div>
    </section>
  )
}

/** 오른쪽 순위 — 최근 7일 가장 많이 막힌 기능 */
function TopPaths({ summary, failed }: { summary: OperatorAccessSummary | null; failed: boolean }) {
  const top = groupByFeature(summary?.topPaths ?? [])
  const max = Math.max(...top.map((row) => row.count), 1)
  return (
    <aside className="ca-panel-box oad-top" aria-label="가장 많이 막힌 기능">
      <h2 className="oad-top-title">가장 많이 막힌 기능</h2>
      <span className="dw-caption">최근 7일 · 거부 요청 기준</span>
      {failed ? (
        <p className="dw-caption oad-top-empty">순위를 불러오지 못했어요.</p>
      ) : summary === null ? (
        <ListLoading label="순위를 불러오는 중" rows={3} columns={1} />
      ) : top.length === 0 ? (
        <p className="dw-caption oad-top-empty">최근 7일 동안 막힌 요청이 없어요.</p>
      ) : (
        <ol className="oad-top-list">
          {top.map((row) => (
            <li key={row.name}>
              <span className="oad-top-row">
                <strong>{row.name}</strong>
                <span className="oad-top-count">{`${row.count}회`}</span>
              </span>
              <span className="oad-top-track" aria-hidden="true">
                <span style={{ width: `${(row.count / max) * 100}%` }} />
              </span>
              <code className="oad-path">
                {row.paths.length === 1
                  ? row.paths[0]
                  : `${row.paths[0]} 외 ${row.paths.length - 1}개`}
              </code>
            </li>
          ))}
        </ol>
      )}
    </aside>
  )
}

/** 계정 묶음 — 머리에 계정 · 소속 · 가장 높은 위험도 · 건수, 아래에 최근 탐지 최대 5건 */
function AccountGroupBox({
  group,
  returnPath,
  onShowAll,
}: {
  group: OperatorAccessAccountGroup
  returnPath: string
  onShowAll: (loginId: string) => void
}) {
  const hidden = group.detectionCount - group.signals.length
  return (
    <li className="oad-group">
      <header className="oad-group-head">
        <span className="oad-group-who">
          <strong>{group.loginId}</strong>
          <span className="dw-caption">{group.affiliation}</span>
          <Tag color={group.riskLevel === 'HIGH' ? 'red' : 'gold'}>
            {group.riskLevel === 'HIGH' ? '고위험' : '저위험'}
          </Tag>
        </span>
        <span className="dw-caption">
          {`탐지 ${group.detectionCount}건 · 합계 ${group.eventCount}회 · 최근 ${occurredLabel(group.lastOccurredAt)}`}
        </span>
      </header>
      <ul className="oad-rows">
        {group.signals.map((value) => (
          <DetectionRow
            key={value.detectionId ?? `low-${value.sourceEventId}`}
            value={value}
            returnPath={returnPath}
            compact
          />
        ))}
      </ul>
      {hidden > 0 && (
        <button type="button" className="oad-group-more" onClick={() => onShowAll(group.loginId)}>
          {`이 계정 탐지 ${hidden}건 더 보기 →`}
        </button>
      )}
    </li>
  )
}

export function OperatorAccessDetectionsPage() {
  const location = useLocation()
  const listQuery = new URLSearchParams(location.search)
  const initialPage = Number(listQuery.get('page'))
  const initialRisk = listQuery.get('risk')
  const [page, setPage] = useState(
    Number.isSafeInteger(initialPage) && initialPage >= 0 ? initialPage : 0,
  )
  const [risk, setRisk] = useState<Risk>(
    initialRisk === 'ALL' || initialRisk === 'LOW' ? initialRisk : 'HIGH',
  )
  const [view, setView] = useState<ListView>(() => parseListView(listQuery.get('view')))
  const [rule, setRule] = useState(() => parseRule(listQuery.get('rule')))
  const [search, setSearch] = useState(listQuery.get('search') ?? '')
  const [searchInput, setSearchInput] = useState(search)
  const [detections, setDetections] = useState<OperatorAccessDetectionPage | null>(null)
  const [accounts, setAccounts] = useState<OperatorAccessAccountPage | null>(null)
  const [error, setError] = useState(false)
  const [summary, setSummary] = useState<OperatorAccessSummary | null>(null)
  const [summaryFailed, setSummaryFailed] = useState(false)
  // 새로고침 버튼으로만 다시 부른다 — 타이머로 부르면 세션 무활동 만료가 오지 않는다
  const [reloadKey, setReloadKey] = useState(0)
  const [reloading, setReloading] = useState(false)
  // 요약은 화면을 열 때와 새로고침 때. 실패하면 요약 칸만 비우고 목록은 그대로 쓴다
  useEffect(() => {
    let active = true
    getOperatorAccessSummary()
      .then((value) => {
        if (active) {
          setSummary(value)
          setSummaryFailed(false)
        }
      })
      .catch(() => {
        if (active) setSummaryFailed(true)
      })
    return () => {
      active = false
    }
  }, [reloadKey])
  useEffect(() => {
    let active = true
    const request =
      view === 'ACCOUNT'
        ? getOperatorAccessAccounts(page, risk, search, rule).then((value) => {
            if (active) setAccounts(value)
          })
        : getOperatorAccessDetections(page, risk, search, rule).then((value) => {
            if (active) setDetections(value)
          })
    request
      .then(() => {
        if (active) setError(false)
      })
      .catch(() => {
        if (active) setError(true)
      })
      .finally(() => {
        if (active) setReloading(false)
      })
    return () => {
      active = false
    }
  }, [page, risk, search, rule, view, reloadKey])
  const current = view === 'ACCOUNT' ? accounts : detections
  const count = (value: Risk) =>
    value === 'ALL'
      ? (current?.highRiskCount ?? 0) + (current?.lowRiskCount ?? 0)
      : value === 'HIGH'
        ? (current?.highRiskCount ?? 0)
        : (current?.lowRiskCount ?? 0)
  const listReturnPath = `${location.pathname}?${new URLSearchParams({
    page: String(page),
    risk,
    search,
    view,
    rule,
  }).toString()}`
  const changeView = (next: ListView) => {
    setView(next)
    setPage(0)
  }
  const showAccount = (loginId: string) => {
    setSearch(loginId)
    setSearchInput(loginId)
    changeView('DETECTION')
  }
  const empty = (
    <ListEmpty
      title={search || rule ? '조건에 맞는 탐지가 없어요.' : '탐지된 접근 이상이 없어요.'}
    />
  )
  return (
    <>
      <PageHeader
        title="계정 접근 이상 탐지"
        description="사장님 계정이 권한 밖 관리자 기능을 반복해서 시도하면 자동으로 정지하고 여기에 기록해요."
      />
      {error && (
        <Alert type="error" message="탐지 목록을 불러오지 못했습니다." className="dw-page-alert" />
      )}
      <SummaryStrip
        high={current?.highRiskCount ?? null}
        low={current?.lowRiskCount ?? null}
        summary={summary}
        failed={summaryFailed}
      />
      <div className="oad-layout">
        <section className="ca-panel-box oad-list" aria-label="탐지 목록">
          <div className="oad-toolbar">
            <Tabs
              className="oad-tabs"
              activeKey={risk}
              onChange={(key) => {
                setRisk(key as Risk)
                setPage(0)
              }}
              items={(['HIGH', 'LOW', 'ALL'] as const).map((key) => ({
                key,
                label: (
                  <>
                    {key === 'ALL' ? '전체' : key === 'HIGH' ? '고위험' : '저위험'}
                    <span className="oad-tab-count">{count(key)}</span>
                  </>
                ),
              }))}
            />
            <Segmented<ListView>
              value={view}
              onChange={changeView}
              options={[
                { value: 'ACCOUNT', label: '계정별' },
                { value: 'DETECTION', label: '탐지별' },
              ]}
            />
          </div>
          <div className="oad-filters">
            <Select
              className="oad-rule"
              value={rule}
              options={ruleOptions}
              aria-label="룰 거르기"
              onChange={(value: string) => {
                setRule(value)
                setPage(0)
              }}
            />
            <Input.Search
              className="oad-search"
              placeholder="계정, 고객사, 룰 이름 검색"
              allowClear
              value={searchInput}
              onChange={(event) => setSearchInput(event.target.value)}
              onSearch={(value) => {
                setSearch(value)
                setPage(0)
              }}
            />
            <Button
              className="oad-reload"
              icon={<ReloadOutlined />}
              loading={reloading}
              onClick={() => {
                setReloading(true)
                setReloadKey((value) => value + 1)
              }}
            >
              새로고침
            </Button>
          </div>
          {view === 'ACCOUNT' ? (
            accounts === null ? (
              !error && <ListLoading label="계정별 탐지를 불러오는 중" rows={4} columns={3} />
            ) : accounts.content.length === 0 ? (
              empty
            ) : (
              <ul className="oad-groups">
                {accounts.content.map((group) => (
                  <AccountGroupBox
                    key={group.loginId}
                    group={group}
                    returnPath={listReturnPath}
                    onShowAll={showAccount}
                  />
                ))}
              </ul>
            )
          ) : (
            <>
              <div className="oad-head" aria-hidden="true">
                <span>탐지</span>
                <span>발생</span>
                <span>조치</span>
              </div>
              {detections === null ? (
                !error && <ListLoading label="탐지 목록을 불러오는 중" rows={4} columns={3} />
              ) : detections.content.length === 0 ? (
                empty
              ) : (
                <ul className="oad-rows">
                  {detections.content.map((value) => (
                    <DetectionRow
                      key={value.detectionId ?? `low-${value.sourceEventId}`}
                      value={value}
                      returnPath={listReturnPath}
                    />
                  ))}
                </ul>
              )}
            </>
          )}
          <footer className="oad-foot">
            <span className="dw-caption">
              고위험은 이력 전체, 저위험은 계정·룰별 최신 신호만 보여요. 고위험 근거는 저위험에서
              빠져요.
            </span>
            <Pagination
              current={page + 1}
              total={Math.max(current?.totalElements ?? 0, 1)}
              pageSize={10}
              showSizeChanger={false}
              hideOnSinglePage={false}
              showTotal={() =>
                `총 ${current?.totalElements ?? 0}${view === 'ACCOUNT' ? '개 계정' : '건'}`
              }
              onChange={(value) => setPage(value - 1)}
            />
          </footer>
        </section>
        <TopPaths summary={summary} failed={summaryFailed} />
      </div>
    </>
  )
}
