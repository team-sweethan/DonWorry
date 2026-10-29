import { useEffect, useMemo, useState } from 'react'
import { ReloadOutlined } from '@ant-design/icons'
import { Alert, App, Button, Card, Space, Table, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useNavigate } from 'react-router-dom'
import { getCases } from '../../api/cases'
import { PageHeader } from '../../components/common/PageHeader'
import { useDemo } from '../../demo/DemoContext'
import type { CaseStatus, CaseSummary } from '../../types/case'
import { slaState } from '../../utils/caseRules'
import { won } from '../../utils/format'
import { caseListSearch, type CaseListQuery } from '../../utils/caseListQuery'
import { homeCounts, priorityCases, type PriorityReason } from '../../utils/securityHome'
import { caseStatusLabel, dateTime, reportStatusLabel } from './case/caseLabels'
import { CodeTag } from './case/CodeTag'
import { StatButton } from './case/StatButton'

/** 먼저 볼 이유 — 표의 첫 열. OTHER는 진행 중이라 따로 표시하지 않는다. */
const reasonLabel: Record<PriorityReason, { label: string; color: string } | null> = {
  ACTION_FAILED: { label: '금융 처리 실패', color: 'red' },
  SLA_EXCEEDED: { label: '24시간 초과', color: 'volcano' },
  DECISION_PENDING: { label: '판정 필요', color: 'gold' },
  OTHER: null,
}

type Row = CaseSummary & { reason: PriorityReason }

export function SecurityHomePage() {
  const navigate = useNavigate()
  const { merchant } = useDemo()
  // useFeedback()은 렌더마다 새 객체라 effect 의존성에는 App이 주는 message를 쓴다
  const { message } = App.useApp()
  const [refreshKey, setRefreshKey] = useState(0)
  // CaseListPage와 같이 어떤 요청에 대한 응답인지 함께 저장하고, 실패는 빈 목록과 구분한다
  const [result, setResult] = useState<{
    merchant: string
    refreshKey: number
    cases: CaseSummary[]
    failed: boolean
  } | null>(null)

  useEffect(() => {
    let alive = true
    getCases(merchant)
      .then((cases) => {
        if (alive) setResult({ merchant, refreshKey, cases, failed: false })
      })
      .catch(() => {
        if (!alive) return
        message.error('Case 현황을 불러오지 못했습니다. 잠시 후 다시 시도하세요.')
        setResult((prev) => ({
          merchant,
          refreshKey,
          cases: prev?.merchant === merchant ? prev.cases : [],
          failed: true,
        }))
      })
    return () => {
      alive = false
    }
  }, [merchant, refreshKey, message])

  const loading = result?.merchant !== merchant || result.refreshKey !== refreshKey
  const cases = useMemo(
    () => (result?.merchant === merchant ? result.cases : []),
    [result, merchant],
  )
  const counts = useMemo(() => homeCounts(cases), [cases])
  const rows = useMemo(() => priorityCases(cases), [cases])
  const failed = result?.merchant === merchant && result.failed

  // 카드 조건을 목록 주소 쿼리로 넘긴다 (#243)
  const openList = (query: Partial<CaseListQuery> = {}) =>
    navigate(`/security/cases${caseListSearch(query)}`)
  // 한 건이면 그 Case로 바로 간다
  const openFirst = (match: (c: CaseSummary) => boolean, query: Partial<CaseListQuery>) => {
    const hits = cases.filter((c) => c.status !== 'CLOSED' && match(c))
    if (hits.length === 1) navigate(`/security/cases/${hits[0].caseId}`)
    else openList(query)
  }

  const columns: ColumnsType<Row> = [
    {
      title: '먼저 볼 이유',
      dataIndex: 'reason',
      width: 130,
      render: (r: PriorityReason) => {
        const label = reasonLabel[r]
        return label ? <CodeTag value={label} /> : <span style={{ color: '#94a3b8' }}>진행 중</span>
      },
    },
    { title: 'Case ID', dataIndex: 'caseId', width: 100 },
    {
      title: '상태',
      dataIndex: 'status',
      width: 120,
      render: (s: CaseStatus) => <CodeTag value={caseStatusLabel[s]} />,
    },
    { title: '고객', dataIndex: 'customerRef', width: 120 },
    {
      title: '위험도',
      key: 'risk',
      width: 110,
      render: (_, c) => (
        <span>
          <strong>{c.maxRiskScore ?? '-'}점</strong>
          <span style={{ color: '#64748b' }}> · {c.paymentCount}건</span>
        </span>
      ),
    },
    {
      title: '총액',
      dataIndex: 'totalAmount',
      align: 'right',
      width: 120,
      render: (v: CaseSummary['totalAmount']) => (v === null ? '-' : won(v)),
    },
    { title: '생성 시각', dataIndex: 'createdAt', width: 150, render: dateTime },
    {
      title: 'SLA',
      key: 'sla',
      width: 110,
      render: (_, c) => {
        const sla = slaState(c.createdAt, c.status)
        if (!sla) return <span style={{ color: '#94a3b8' }}>-</span>
        return sla.exceeded ? (
          <span style={{ color: '#dc2626', fontWeight: 700 }}>{sla.hours}시간 초과</span>
        ) : (
          <span>{sla.hours}시간 남음</span>
        )
      },
    },
    {
      title: 'AI 보고서',
      dataIndex: 'latestReportStatus',
      width: 110,
      render: (s: CaseSummary['latestReportStatus']) =>
        s ? <CodeTag value={reportStatusLabel[s]} /> : '-',
    },
  ]

  return (
    <>
      <PageHeader
        title="홈"
        description="지금 처리해야 할 Case를 모아 봅니다. 숫자는 종료되지 않은 Case 기준입니다."
        actions={
          <Button icon={<ReloadOutlined />} onClick={() => setRefreshKey((k) => k + 1)}>
            새로고침
          </Button>
        }
      />
      {failed && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          message="Case 현황을 불러오지 못했습니다. 아래 숫자와 목록은 실제와 다를 수 있습니다."
          action={
            <Button size="small" onClick={() => setRefreshKey((k) => k + 1)}>
              다시 시도
            </Button>
          }
        />
      )}
      {!loading && (counts.actionFailed > 0 || counts.aiFailed > 0) && (
        <Space direction="vertical" size={8} style={{ width: '100%', marginBottom: 16 }}>
          {counts.actionFailed > 0 && (
            <Alert
              type="error"
              showIcon
              message={`금융 처리 실패 ${counts.actionFailed}건 — 실패 사유를 확인하세요.`}
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
          {counts.aiFailed > 0 && (
            <Alert
              type="warning"
              showIcon
              message={`AI 보고서 실패 ${counts.aiFailed}건 — 수동 조사 · 판정이 필요합니다.`}
              action={
                <Button
                  size="small"
                  onClick={() =>
                    openFirst((c) => c.latestReportStatus === 'FAILED', { report: 'failed' })
                  }
                >
                  확인
                </Button>
              }
            />
          )}
        </Space>
      )}
      <div
        className="dw-stat-grid"
        style={{ gridTemplateColumns: 'repeat(3, minmax(0, 1fr))' }}
        aria-busy={loading}
        aria-label="Case 처리 현황"
      >
        <StatButton
          title="판정 대기"
          value={counts.decisionPending}
          foot="직원 판정 필요"
          color="#B45309"
          onClick={() => openList({ status: 'DECISION_PENDING' })}
        />
        <StatButton
          title="AI 조사 중"
          value={counts.aiRunning}
          foot="보고서 생성 대기 · 생성 중"
          onClick={() => openList({ report: 'running' })}
        />
        <StatButton
          title="AI 보고서 실패"
          value={counts.aiFailed}
          foot="수동 조사 · 판정 필요"
          color="#B91C1C"
          onClick={() => openFirst((c) => c.latestReportStatus === 'FAILED', { report: 'failed' })}
        />
        <StatButton
          title="금융 처리 대기"
          value={counts.actionPending}
          foot="매입 · 승인 취소 완료 대기 중"
          color="#1D4ED8"
          onClick={() => openList({ status: 'ACTION_PENDING' })}
        />
        <StatButton
          title="금융 처리 실패"
          value={counts.actionFailed}
          foot="실패 사유 확인 필요"
          color="#B91C1C"
          onClick={() => openList({ status: 'ACTION_FAILED' })}
        />
        <StatButton
          title="24시간 초과"
          value={counts.slaExceeded}
          foot="권장 처리 시간(SLA) 경과"
          color="#C2410C"
          onClick={() => openList({ slaExceeded: true })}
        />
      </div>
      <Card className="dw-table-card" style={{ borderColor: '#E2E8F0' }}>
        <div className="dw-table-toolbar">
          <div>
            <h3>지금 처리할 사건</h3>
            <p>
              금융 처리 실패 → 24시간 초과 → 판정 대기 순, 같은 순위는 오래된 Case부터 6건입니다.
            </p>
          </div>
          <Button type="link" onClick={() => openList()}>
            Case 목록 전체 보기
          </Button>
        </div>
        <Table
          rowKey="caseId"
          columns={columns}
          dataSource={rows}
          loading={loading}
          size="middle"
          scroll={{ x: 1000 }}
          pagination={false}
          onRow={(record) => ({
            onClick: () => navigate(`/security/cases/${record.caseId}`),
            onKeyDown: (event) => {
              if (event.key === 'Enter') navigate(`/security/cases/${record.caseId}`)
            },
            tabIndex: 0,
            style: { cursor: 'pointer' },
          })}
          locale={{
            emptyText: (
              <Typography.Text type="secondary" style={{ display: 'block', padding: 36 }}>
                처리할 Case가 없습니다.
              </Typography.Text>
            ),
          }}
        />
      </Card>
    </>
  )
}
