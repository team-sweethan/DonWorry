import { useEffect, useMemo, useState, useSyncExternalStore } from 'react'
import {
  ArrowRightOutlined,
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
import { HomeCharts } from './HomeCharts'
import { HomeCount } from './HomeCount'
import { HomeWorkflow } from './HomeWorkflow'
import './SecurityHomePage.css'

// OS 설정으로 모션이 꺼진 경우를 표시하고, 사용자가 이 화면에서 명시적으로 재생할 수 있게 한다.
const motionQuery = '(prefers-reduced-motion: reduce)'
const subscribeMotion = (listener: () => void) => {
  const query = window.matchMedia(motionQuery)
  query.addEventListener('change', listener)
  return () => query.removeEventListener('change', listener)
}
const motionSnapshot = () => window.matchMedia(motionQuery).matches
const serverMotionSnapshot = () => false

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
  const [motionForced, setMotionForced] = useState(false)
  const reducedMotion = useSyncExternalStore(subscribeMotion, motionSnapshot, serverMotionSnapshot)
  const effectivePaused = motionPaused || (reducedMotion && !motionForced)

  // 내 Case가 한 건이면 그 Case로 바로 간다
  const openFirst = (match: (c: CaseSummary) => boolean, query: Partial<CaseListQuery>) => {
    const hits = openCases.filter(match)
    if (hits.length === 1) openCase(hits[0].caseId)
    else openList(query)
  }

  const updatedAt = result?.receivedAt
  const ready = updatedAt != null
  const flowActive = ready && !failed && !watchStopped && !loading
  const attentionItems = [
    {
      label: '금융 처리 실패',
      count: counts.actionFailed,
      tone: 'red',
      onClick: () => openFirst((c) => c.status === 'ACTION_FAILED', { status: 'ACTION_FAILED' }),
      help: '사건 상세에서 매입·승인 취소 처리 결과를 확인하세요.',
    },
    {
      label: '보고서 생성 실패',
      count: counts.aiFailed,
      tone: 'amber',
      onClick: () => openFirst((c) => c.latestReportStatus === 'FAILED', { report: 'failed' }),
      help: '사건 상세에서 보고서 상태와 판정 가능 여부를 확인하세요.',
    },
    {
      label: `생성 후 ${SLA_HOURS}시간 경과`,
      count: counts.slaExceeded,
      tone: 'orange',
      onClick: () => openList({ slaExceeded: true }),
      help: '종료되지 않은 담당 사건 기준입니다. 시간이 지나도 자동으로 판정하거나 종료하지 않습니다.',
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
      className={`security-home${effectivePaused ? ' is-motion-paused' : ''}${motionForced ? ' is-motion-forced' : ''}`}
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
                icon={effectivePaused ? <PlayCircleOutlined /> : <PauseCircleOutlined />}
                aria-pressed={!effectivePaused}
                onClick={() => {
                  if (reducedMotion && !motionForced) {
                    setMotionForced(true)
                    setMotionPaused(false)
                  } else setMotionPaused((paused) => !paused)
                }}
              >
                {effectivePaused ? '움직임 켜기' : '움직임 끄기'}
              </Button>
              {reducedMotion && !motionForced && <span>시스템 설정으로 움직임 꺼짐</span>}
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
                <p className="security-home-summary-scope">
                  {openCases.length > 0
                    ? '종료된 사건 제외'
                    : '현재 진행 중인 담당 사건이 없습니다.'}
                </p>
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
            <HomeWorkflow cases={openCases} active={flowActive} />
          ) : failed ? (
            <p className="security-home-unavailable">조회 후 업무 흐름을 확인할 수 있습니다.</p>
          ) : (
            <Skeleton active title={{ width: '40%' }} paragraph={{ rows: 4 }} />
          )}
        </section>
        <div className="security-home-attention" aria-label="확인 필요한 항목">
          <span className="security-home-attention-label">
            <WarningOutlined /> 확인 필요
          </span>
          {attentionItems.map((item) => (
            <button
              type="button"
              key={item.label}
              title={item.help}
              disabled={!ready}
              className={ready && item.count > 0 ? `is-${item.tone}` : undefined}
              onClick={item.onClick}
            >
              {item.label} <strong>{ready ? `${item.count}건` : '−'}</strong>
              <ArrowRightOutlined />
            </button>
          ))}
          <HelpLabel
            label=""
            help="한 사건이 여러 항목에 포함될 수 있습니다. 항목을 누르면 해당 사건이나 목록으로 이동합니다."
          />
        </div>
      </div>
      {ready && <HomeCharts cases={openCases} openList={openList} />}
      <Card className="dw-table-card security-home-enter security-home-queue">
        <div className="dw-table-toolbar">
          <div>
            <h3>먼저 확인할 사건</h3>
            <p>금융 처리 실패 → {SLA_HOURS}시간 경과 → 판정 대기 순 · 최대 3건</p>
          </div>
          <Button type="link" onClick={() => openList()}>
            내 사건 전체 보기 <ArrowRightOutlined />
          </Button>
        </div>
        <Table
          rowKey="caseId"
          columns={columns}
          dataSource={rows.slice(0, 3)}
          loading={loading}
          size="middle"
          scroll={{ x: 640 }}
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
