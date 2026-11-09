import { useEffect, useMemo, useState } from 'react'
import {
  ArrowRightOutlined,
  ClockCircleOutlined,
  FileSearchOutlined,
  ReloadOutlined,
  PauseCircleOutlined,
  PlayCircleOutlined,
  SafetyCertificateOutlined,
  WarningOutlined,
} from '@ant-design/icons'
import { Alert, App, Button, Card, Skeleton, Table } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useNavigate } from 'react-router-dom'
import { getCases } from '../../api/cases'
import { useAuth } from '../../auth/useAuth'
import { PageHeader } from '../../components/common/PageHeader'
import { useCaseListWatch } from '../../hooks/useCaseListWatch'
import type { CaseSummary } from '../../types/case'
import { SLA_HOURS, customerPart, slaState } from '../../utils/caseRules'
import { caseListSearch, type CaseListQuery } from '../../utils/caseListQuery'
import { saveCaseListScope } from '../../utils/caseListScope'
import { homeView, newHomeCaseIds, type PriorityReason } from '../../utils/securityHome'
import { caseStatusLabel, dateTime } from './case/caseLabels'
import { CaseWatchStoppedNotice } from './case/CaseWatchStoppedNotice'
import { CodeTag } from './case/CodeTag'
import { HelpLabel } from './case/CaseTimeLabels'
import { HomeCount } from './HomeCount'
import { HomeWorkflow } from './HomeWorkflow'
import './SecurityHomePage.css'

// 우선 이유가 없으면 현재 업무 상태를 보여 준다.
const reasonLabel: Record<PriorityReason, { label: string; color: string } | null> = {
  ACTION_FAILED: { label: '금융 처리 실패', color: 'red' },
  SLA_EXCEEDED: { label: `${SLA_HOURS}시간 경과`, color: 'volcano' },
  DECISION_PENDING: { label: '판정 필요', color: 'gold' },
  OTHER: null,
}

type Row = CaseSummary & { reason: PriorityReason }

type HomeResult = {
  refreshKey: number
  cases: CaseSummary[]
  failed: boolean
  receivedAt: number | null
  newIds: string[]
}

type DashboardProps = {
  result: HomeResult | null
  loading: boolean
  myId: number | null
  watchStopped: boolean
  refresh: () => void
  openList: (query?: Partial<CaseListQuery>) => void
  openCase: (caseId: string) => void
}

export function SecurityHomePage() {
  const navigate = useNavigate()
  // effect 의존성에는 렌더마다 바뀌지 않는 App의 message를 쓴다
  const { message } = App.useApp()
  const { user } = useAuth()
  const myId = user?.userId ?? null
  const [refreshKey, setRefreshKey] = useState(0)
  // CaseListPage와 같이 어떤 요청에 대한 응답인지 함께 저장하고, 실패는 빈 목록과 구분한다
  const [result, setResult] = useState<HomeResult | null>(null)

  // 새 Case · 상태 변화를 건수로 감지해 바뀌었을 때만 다시 조회한다 (#553). 홈은 내 Case만 보므로 내 건수로 본다 (#640)
  const watch = useCaseListWatch((added) => {
    setRefreshKey((k) => k + 1)
    if (added > 0) message.info(`새 Case ${added}건이 들어왔습니다. 현황을 다시 불러옵니다.`)
  }, 'MINE')
  const { listLoaded } = watch
  const refresh = () => {
    setRefreshKey((k) => k + 1)
    watch.restart()
  }

  useEffect(() => {
    let alive = true
    getCases()
      .then((cases) => {
        if (!alive) return
        setResult((previous) => ({
          refreshKey,
          cases,
          failed: false,
          receivedAt: Date.now(),
          newIds: newHomeCaseIds(previous?.receivedAt != null ? previous.cases : null, cases, myId),
        }))
        // 감지 기준 건수는 목록을 받은 뒤에만 확정한다 (#555 Codex 리뷰)
        listLoaded(true)
      })
      .catch(() => {
        if (!alive) return
        message.error('Case 현황을 불러오지 못했습니다. 잠시 후 다시 시도하세요.')
        setResult((prev) => ({
          refreshKey,
          cases: prev?.cases ?? [],
          failed: true,
          receivedAt: prev?.receivedAt ?? null,
          newIds: [],
        }))
        listLoaded(false)
      })
    return () => {
      alive = false
    }
  }, [refreshKey, message, listLoaded, myId])

  // 카드 조건을 목록 주소 쿼리로 넘긴다 (#243). 목록도 [내 사건만]으로 열어 홈 숫자와 맞춘다 (#640)
  const openList = (query: Partial<CaseListQuery> = {}) => {
    saveCaseListScope('MINE')
    navigate(`/security/cases${caseListSearch(query)}`)
  }
  return (
    <SecurityHomeDashboard
      result={result}
      loading={result?.refreshKey !== refreshKey}
      myId={myId}
      watchStopped={watch.stopped}
      refresh={refresh}
      openList={openList}
      openCase={(id) => navigate(`/security/cases/${id}`)}
    />
  )
}

// 실제 홈과 개발용 샘플이 같은 화면을 사용한다. 데이터 조회·인증은 실제 홈에서만 수행한다.
export function SecurityHomeDashboard({
  result,
  loading,
  myId,
  watchStopped,
  refresh,
  openList,
  openCase,
}: DashboardProps) {
  // 홈은 항상 내가 담당인 Case만 센다 (#640)
  const { counts, rows, openCases } = useMemo(
    () => homeView(result?.cases ?? [], myId),
    [result, myId],
  )
  const failed = result?.failed === true
  const [motionPaused, setMotionPaused] = useState(false)

  // 내 Case가 한 건이면 그 Case로 바로 간다
  const openFirst = (match: (c: CaseSummary) => boolean, query: Partial<CaseListQuery>) => {
    const hits = openCases.filter(match)
    if (hits.length === 1) openCase(hits[0].caseId)
    else openList(query)
  }

  const updatedAt = result?.receivedAt
  const ready = updatedAt != null
  const flowActive = ready && !failed && !watchStopped && !loading
  const metrics = [
    {
      title: '판정 대기',
      value: counts.decisionPending,
      foot: '결제별 판정 필요',
      tone: 'amber',
      icon: <FileSearchOutlined />,
      onClick: () => openList({ status: 'DECISION_PENDING' }),
    },
    {
      title: '금융 처리 실패',
      value: counts.actionFailed,
      foot: '매입 · 승인 취소 처리 결과 확인',
      tone: 'red',
      icon: <WarningOutlined />,
      onClick: () => openFirst((c) => c.status === 'ACTION_FAILED', { status: 'ACTION_FAILED' }),
    },
    {
      title: `생성 후 ${SLA_HOURS}시간 경과`,
      value: counts.slaExceeded,
      foot: '종료되지 않은 사건 기준',
      tone: 'orange',
      icon: <ClockCircleOutlined />,
      onClick: () => openList({ slaExceeded: true }),
    },
  ]
  const columns: ColumnsType<Row> = [
    {
      title: '사건 · 고객',
      key: 'case',
      width: 210,
      render: (_, c) => (
        <div className="security-home-case">
          <strong>Case {c.caseId}</strong>
          <span>
            {customerPart(c.customerRef)} · {c.paymentCount}건
          </span>
        </div>
      ),
    },
    {
      title: '확인할 항목',
      dataIndex: 'reason',
      width: 150,
      render: (reason: PriorityReason, c) => (
        <CodeTag value={reasonLabel[reason] ?? caseStatusLabel[c.status]} />
      ),
    },
    {
      title: (
        <HelpLabel
          label="최고 위험 점수"
          help="사건에 연결된 결제의 FDS 위험 점수 중 최댓값입니다. 점수를 확인하지 못한 결제가 있으면 표시하지 않습니다."
        />
      ),
      dataIndex: 'maxRiskScore',
      width: 165,
      render: (score: number | null) => (
        <strong className="security-home-risk">{score === null ? '−' : `${score}점`}</strong>
      ),
    },
    {
      title: (
        <HelpLabel
          label="생성 후 경과"
          help={`사건 생성 후 지난 시간입니다. 종료되지 않은 사건이 ${SLA_HOURS}시간 이상 지나면 강조합니다. 시간이 지나도 자동으로 판정하거나 종료하지 않습니다.`}
        />
      ),
      key: 'sla',
      width: 160,
      render: (_, c) => {
        const elapsed = slaState(c.createdAt, c.status)
        if (!elapsed) return '−'
        const hours = elapsed.exceeded ? SLA_HOURS + elapsed.hours : SLA_HOURS - elapsed.hours
        return (
          <span className={elapsed.exceeded ? 'security-home-overdue' : undefined}>
            {hours < 1 ? '1시간 미만' : `${hours}시간`}
          </span>
        )
      },
    },
    {
      title: '상세',
      key: 'open',
      width: 120,
      align: 'right',
      render: (_, c) => (
        <Button
          type="link"
          className="security-home-open"
          onClick={() => openCase(c.caseId)}
          aria-label={`Case ${c.caseId} 사건 보기`}
        >
          사건 보기 <ArrowRightOutlined />
        </Button>
      ),
    },
  ]

  return (
    <div
      className={`security-home${motionPaused ? ' is-motion-paused' : ''}`}
      data-flow-active={flowActive}
    >
      <div className="security-home-enter security-home-heading">
        <PageHeader
          title="내 담당 사건"
          description="종료되지 않은 내 담당 사건의 상태와 확인할 일을 보여줍니다."
          actions={
            <div className="security-home-refresh">
              <Button
                type="text"
                size="small"
                icon={motionPaused ? <PlayCircleOutlined /> : <PauseCircleOutlined />}
                aria-pressed={motionPaused}
                onClick={() => setMotionPaused((paused) => !paused)}
              >
                {motionPaused ? '반복 효과 켜기' : '반복 효과 끄기'}
              </Button>
              {ready && (
                <span title={dateTime(new Date(updatedAt).toISOString())}>
                  {new Date(updatedAt).toLocaleTimeString('ko-KR', {
                    hour: '2-digit',
                    minute: '2-digit',
                    timeZone: 'Asia/Seoul',
                  })}{' '}
                  조회{failed ? ' · 갱신 실패' : ''}
                </span>
              )}
              <Button icon={<ReloadOutlined spin={loading} />} onClick={refresh} disabled={loading}>
                새로고침
              </Button>
            </div>
          }
        />
      </div>
      {watchStopped && <CaseWatchStoppedNotice onRefresh={refresh} />}
      {failed && (
        <Alert
          type="error"
          showIcon
          className="security-home-notice"
          message={
            ready
              ? '현황을 갱신하지 못했습니다. 마지막 조회 결과를 표시합니다.'
              : 'Case 현황을 불러오지 못했습니다.'
          }
          action={
            <Button size="small" onClick={refresh}>
              다시 시도
            </Button>
          }
        />
      )}
      <div className="security-home-enter security-home-overview" aria-busy={loading}>
        <section className="security-home-hero" aria-label="내 담당 사건 요약">
          <div className="security-home-summary">
            {ready ? (
              <>
                <h2>
                  <span className="security-home-total-label">진행 중인 담당 사건</span>
                  <span className="security-home-total">
                    <HomeCount value={openCases.length} />
                    <small>건</small>
                  </span>
                </h2>
                <p>
                  {openCases.length > 0
                    ? '판정 대기와 금융 처리 상태를 확인하세요.'
                    : '현재 종료되지 않은 담당 사건이 없습니다.'}
                </p>
                <Button
                  type="primary"
                  onClick={() => (rows[0] ? openCase(rows[0].caseId) : openList())}
                >
                  {rows[0] ? '먼저 확인할 사건 보기' : '내 사건 목록 보기'} <ArrowRightOutlined />
                </Button>
              </>
            ) : failed ? (
              <>
                <h2>현황 확인이 필요합니다</h2>
                <p>다시 조회하면 담당 사건을 확인할 수 있습니다.</p>
              </>
            ) : (
              <Skeleton active title={{ width: '70%' }} paragraph={{ rows: 2 }} />
            )}
          </div>
          {ready ? (
            <HomeWorkflow
              cases={openCases}
              active={flowActive}
              onOpenFailed={() =>
                openFirst((c) => c.status === 'ACTION_FAILED', { status: 'ACTION_FAILED' })
              }
            />
          ) : failed ? (
            <p className="security-home-unavailable">조회 후 업무 흐름을 확인할 수 있습니다.</p>
          ) : (
            <Skeleton active title={{ width: '40%' }} paragraph={{ rows: 4 }} />
          )}
        </section>
        <div className="security-home-metrics" aria-label="먼저 확인할 현황">
          {metrics.map((metric) => (
            <button
              key={metric.title}
              type="button"
              className={`security-home-metric${ready && metric.value > 0 ? ` is-${metric.tone}` : ''}`}
              onClick={metric.onClick}
              disabled={!ready}
            >
              <div className="security-home-metric-top">
                <span>{metric.title}</span>
                <span aria-hidden="true">{metric.icon}</span>
              </div>
              <div className="security-home-metric-value">
                {ready ? (
                  <>
                    <HomeCount value={metric.value} />
                    <small>건</small>
                  </>
                ) : (
                  <span className="security-home-placeholder">−</span>
                )}
              </div>
              <div className="security-home-metric-foot">
                <span>{metric.foot}</span>
                <ArrowRightOutlined />
              </div>
            </button>
          ))}
        </div>
        <p className="security-home-count-note">한 사건이 여러 현황 항목에 포함될 수 있습니다.</p>
        <div className="security-home-secondary" aria-label="보고서와 금융 처리 현황">
          <button type="button" disabled={!ready} onClick={() => openList({ report: 'running' })}>
            보고서 생성 대기·진행 <strong>{ready ? counts.aiRunning : '−'}</strong>
            <ArrowRightOutlined />
          </button>
          <button
            type="button"
            disabled={!ready}
            className={ready && counts.aiFailed > 0 ? 'has-failure' : ''}
            onClick={() =>
              openFirst((c) => c.latestReportStatus === 'FAILED', { report: 'failed' })
            }
          >
            보고서 생성 실패 <strong>{ready ? counts.aiFailed : '−'}</strong>
            <ArrowRightOutlined />
          </button>
          <button
            type="button"
            disabled={!ready}
            onClick={() => openList({ status: 'ACTION_PENDING' })}
          >
            금융 처리 대기 <strong>{ready ? counts.actionPending : '−'}</strong>
            <ArrowRightOutlined />
          </button>
        </div>
        {ready && counts.aiFailed > 0 && (
          <Alert
            type="warning"
            showIcon
            className="security-home-notice"
            message={`최신 보고서 생성 실패 ${counts.aiFailed}건 · 사건 상세에서 생성 상태와 판정 가능 여부를 확인하세요.`}
          />
        )}
        {ready && counts.actionFailed > 0 && (
          <Alert
            type="error"
            showIcon
            className="security-home-notice"
            message={`금융 처리 실패 ${counts.actionFailed}건 · 실패 사유를 확인하세요.`}
            action={
              <Button
                size="small"
                onClick={() =>
                  openFirst((c) => c.status === 'ACTION_FAILED', { status: 'ACTION_FAILED' })
                }
              >
                실패 사건 확인
              </Button>
            }
          />
        )}
      </div>
      <Card className="dw-table-card security-home-enter security-home-queue">
        <div className="dw-table-toolbar">
          <div>
            <h3>먼저 확인할 사건</h3>
            <p>
              금융 처리 실패 → 생성 후 {SLA_HOURS}시간 경과 → 판정 대기 순 · 같은 순위는 오래된
              사건부터 최대 6건
            </p>
          </div>
          <Button type="link" onClick={() => openList()}>
            내 사건 전체 보기 <ArrowRightOutlined />
          </Button>
        </div>
        <Table
          rowKey="caseId"
          columns={columns}
          dataSource={rows}
          loading={loading}
          size="middle"
          scroll={{ x: 805 }}
          pagination={false}
          rowClassName={(record) =>
            result?.newIds.includes(record.caseId) ? 'security-home-new-case' : ''
          }
          locale={{
            emptyText: (
              <div className="security-home-empty">
                <SafetyCertificateOutlined />
                <strong>
                  {failed && !ready
                    ? '사건 목록을 확인하지 못했습니다'
                    : '현재 진행 중인 담당 사건이 없습니다'}
                </strong>
                <span>
                  {failed && !ready
                    ? '위의 다시 시도 버튼으로 현황을 조회하세요.'
                    : '새 사건이 배정되면 이곳에서 확인할 수 있습니다.'}
                </span>
              </div>
            ),
          }}
        />
      </Card>
    </div>
  )
}
