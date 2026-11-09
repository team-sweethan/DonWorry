import { useEffect, useMemo, useState } from 'react'
import {
  ArrowRightOutlined,
  ClockCircleOutlined,
  FileSearchOutlined,
  ReloadOutlined,
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
import { SLA_HOURS, customerPart } from '../../utils/caseRules'
import { caseListSearch, type CaseListQuery } from '../../utils/caseListQuery'
import { saveCaseListScope } from '../../utils/caseListScope'
import {
  homeDistribution,
  homeView,
  newHomeCaseIds,
  type PriorityReason,
} from '../../utils/securityHome'
import { caseStatusLabel, dateTime } from './case/caseLabels'
import { CaseWatchStoppedNotice } from './case/CaseWatchStoppedNotice'
import { CodeTag } from './case/CodeTag'
import { DeadlineValue, HelpLabel, deadlineHelp } from './case/CaseTimeLabels'
import { HomeCount } from './HomeCount'
import './SecurityHomePage.css'

// 우선 이유가 없으면 현재 업무 상태를 보여 준다.
const reasonLabel: Record<PriorityReason, { label: string; color: string } | null> = {
  ACTION_FAILED: { label: '금융 처리 실패', color: 'red' },
  SLA_EXCEEDED: { label: '조사 기한 지남', color: 'volcano' },
  DECISION_PENDING: { label: '판정 필요', color: 'gold' },
  OTHER: null,
}

type Row = CaseSummary & { reason: PriorityReason }

export function SecurityHomePage() {
  const navigate = useNavigate()
  // effect 의존성에는 렌더마다 바뀌지 않는 App의 message를 쓴다
  const { message } = App.useApp()
  const { user } = useAuth()
  const myId = user?.userId ?? null
  const [refreshKey, setRefreshKey] = useState(0)
  // CaseListPage와 같이 어떤 요청에 대한 응답인지 함께 저장하고, 실패는 빈 목록과 구분한다
  const [result, setResult] = useState<{
    refreshKey: number
    cases: CaseSummary[]
    failed: boolean
    receivedAt: number | null
    newIds: string[]
  } | null>(null)

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

  const loading = result?.refreshKey !== refreshKey
  // 홈은 항상 내가 담당인 Case만 센다 (#640)
  const { counts, rows, openCases } = useMemo(
    () => homeView(result?.cases ?? [], myId),
    [result, myId],
  )
  const failed = result?.failed === true

  // 카드 조건을 목록 주소 쿼리로 넘긴다 (#243). 목록도 [내 사건만]으로 열어 홈 숫자와 맞춘다 (#640)
  const openList = (query: Partial<CaseListQuery> = {}) => {
    saveCaseListScope('MINE')
    navigate(`/security/cases${caseListSearch(query)}`)
  }
  // 내 Case가 한 건이면 그 Case로 바로 간다
  const openFirst = (match: (c: CaseSummary) => boolean, query: Partial<CaseListQuery>) => {
    const hits = openCases.filter(match)
    if (hits.length === 1) navigate(`/security/cases/${hits[0].caseId}`)
    else openList(query)
  }

  const distribution = homeDistribution(openCases)
  const updatedAt = result?.receivedAt
  const ready = updatedAt != null
  const metrics = [
    {
      title: '판정 대기',
      value: counts.decisionPending,
      foot: '직원 판정 필요',
      tone: 'amber',
      icon: <FileSearchOutlined />,
      onClick: () => openList({ status: 'DECISION_PENDING' }),
    },
    {
      title: '금융 처리 실패',
      value: counts.actionFailed,
      foot: '실패 사유 확인 필요',
      tone: 'red',
      icon: <WarningOutlined />,
      onClick: () => openFirst((c) => c.status === 'ACTION_FAILED', { status: 'ACTION_FAILED' }),
    },
    {
      title: '조사 기한 지남',
      value: counts.slaExceeded,
      foot: `생성 후 ${SLA_HOURS}시간 경과`,
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
      title: '먼저 볼 이유',
      dataIndex: 'reason',
      width: 150,
      render: (reason: PriorityReason, c) => (
        <CodeTag value={reasonLabel[reason] ?? caseStatusLabel[c.status]} />
      ),
    },
    {
      title: '위험도',
      dataIndex: 'maxRiskScore',
      width: 100,
      render: (score: number | null) => (
        <strong className="security-home-risk">{score === null ? '−' : `${score}점`}</strong>
      ),
    },
    {
      title: <HelpLabel label="조사 기한" help={deadlineHelp} />,
      key: 'sla',
      width: 160,
      render: (_, c) => <DeadlineValue createdAt={c.createdAt} status={c.status} />,
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
          onClick={() => navigate(`/security/cases/${c.caseId}`)}
          aria-label={`Case ${c.caseId} 사건 보기`}
        >
          사건 보기 <ArrowRightOutlined />
        </Button>
      ),
    },
  ]

  return (
    <div className="security-home">
      <div className="security-home-enter security-home-heading">
        <PageHeader
          title="내 사건 현황"
          description="내가 맡은 사건의 진행 상황과 먼저 확인할 일을 모았습니다."
          actions={
            <div className="security-home-refresh">
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
      {watch.stopped && <CaseWatchStoppedNotice onRefresh={refresh} />}
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
            <span className="security-home-eyebrow">
              <SafetyCertificateOutlined /> 나의 보안 업무
            </span>
            {ready ? (
              <>
                <h2>
                  진행 중인 사건{' '}
                  <span>
                    <HomeCount value={openCases.length} />건
                  </span>
                </h2>
                <p>
                  {openCases.length > 0
                    ? '먼저 볼 사건부터 차례로 확인하세요.'
                    : '현재 종료되지 않은 담당 사건이 없습니다.'}
                </p>
                <Button
                  type="primary"
                  onClick={() =>
                    rows[0] ? navigate(`/security/cases/${rows[0].caseId}`) : openList()
                  }
                >
                  {rows[0] ? '우선 사건 보기' : '내 사건 목록 보기'} <ArrowRightOutlined />
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
          <div className="security-home-distribution">
            <div className="security-home-distribution-title">
              <strong>업무 진행 현황</strong>
              <span>종료되지 않은 내 사건</span>
            </div>
            {ready ? (
              <>
                <div className="security-home-bar" aria-hidden="true">
                  {distribution
                    .filter((group) => group.count > 0)
                    .map((group) => (
                      <span
                        key={group.key}
                        className={`security-home-segment is-${group.key}`}
                        style={{ flexGrow: group.count }}
                      />
                    ))}
                </div>
                <ul>
                  {distribution.map((group) => (
                    <li key={group.key}>
                      <span>
                        <i className={`is-${group.key}`} aria-hidden="true" />
                        {group.label}
                      </span>
                      <strong>
                        {group.count}
                        <small>건</small>
                      </strong>
                    </li>
                  ))}
                </ul>
              </>
            ) : failed ? (
              <p className="security-home-unavailable">조회 후 업무 상태를 확인할 수 있습니다.</p>
            ) : (
              <Skeleton active title={false} paragraph={{ rows: 4 }} />
            )}
          </div>
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
        <div className="security-home-secondary" aria-label="조사와 금융 처리 현황">
          <button type="button" disabled={!ready} onClick={() => openList({ report: 'running' })}>
            AI 조사 중 <strong>{ready ? counts.aiRunning : '−'}</strong>
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
            AI 보고서 실패 <strong>{ready ? counts.aiFailed : '−'}</strong>
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
            message={`AI 보고서 실패 ${counts.aiFailed}건 · 수동 조사와 판정이 필요합니다.`}
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
                확인
              </Button>
            }
          />
        )}
      </div>
      <Card className="dw-table-card security-home-enter security-home-queue">
        <div className="dw-table-toolbar">
          <div>
            <h3>먼저 볼 사건</h3>
            <p>
              금융 처리 실패 → 조사 기한 지남 → 판정 대기 순 · 같은 순위는 오래된 사건부터 최대 6건
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
          scroll={{ x: 740 }}
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
                    : '현재 처리할 담당 사건이 없습니다'}
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
