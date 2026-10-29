import { useEffect, useMemo, useState } from 'react'
import { ReloadOutlined } from '@ant-design/icons'
import { Alert, App, Button, Card, Table, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { getHandoffs } from '../../api/cases'
import { PageHeader } from '../../components/common/PageHeader'
import type { HandoffListItem } from '../../types/case'
import { won } from '../../utils/format'
import {
  handoffCounts,
  handoffListSearch,
  matchesHandoffTab,
  parseHandoffListQuery,
  type HandoffListQuery,
  type HandoffTab,
} from '../../utils/handoffListQuery'
import {
  actionTypeLabel,
  dateTime,
  decisionLabel,
  handoffErrorLabel,
  handoffStatusLabel,
} from './case/caseLabels'
import { CodeTag } from './case/CodeTag'
import { StatButton } from './case/StatButton'

const PAGE_SIZE = 20

// Case 목록 카드(Case 수)와 헷갈리지 않게 "조치"를 붙인다 — 여기는 결제별 조치 수다
const cards: Array<{ tab: HandoffTab; title: string; foot: string; color?: string }> = [
  { tab: 'ALL', title: '전체 조치', foot: '판정한 결제마다 1건' },
  {
    tab: 'REQUESTED',
    title: '처리 대기 조치',
    foot: '결제별 · 매입 · 승인 취소 완료 대기',
    color: '#1D4ED8',
  },
  {
    tab: 'ACCEPTED',
    title: '완료된 조치',
    foot: '결제별 · 실제 매입 · 취소 완료',
    color: '#15803D',
  },
  { tab: 'FAILED', title: '실패한 조치', foot: '결제별 · 실패 사유 확인 필요', color: '#B91C1C' },
]

/**
 * 조치 내역 (#314) — 판정 뒤 어떤 조치를 했고 어떻게 끝났는지 결제(인계) 단위로 모아 본다. 조회만 한다.
 * 상태는 완료 기반 인계(ADR-0027) 그대로 — 대기 · 완료 · 실패.
 */
export function ActionHistoryPage() {
  const navigate = useNavigate()
  const { message } = App.useApp()
  const location = useLocation()
  // 탭 · 페이지는 주소 쿼리에 둔다. Case 목록(#243)과 같은 이유로 라우터가 아니라 브라우저 주소만 바꾼다
  const [searchParams] = useSearchParams()
  const [edited, setEdited] = useState<{ routerSearch: string; query: HandoffListQuery } | null>(
    null,
  )
  const query =
    edited?.routerSearch === location.search ? edited.query : parseHandoffListQuery(searchParams)
  const setQuery = (patch: Partial<HandoffListQuery>) => {
    const next = { ...query, page: 1, ...patch }
    setEdited({ routerSearch: location.search, query: next })
    window.history.replaceState(
      window.history.state,
      '',
      location.pathname + handoffListSearch(next),
    )
  }

  const [refreshKey, setRefreshKey] = useState(0)
  // 어떤 새로고침에 대한 응답인지 함께 저장한다. failed는 조회 실패 — 빈 목록과 구분하고 직전 목록은 유지한다
  const [result, setResult] = useState<{
    refreshKey: number
    items: HandoffListItem[]
    failed: boolean
  } | null>(null)

  useEffect(() => {
    let alive = true
    getHandoffs()
      .then((items) => {
        if (alive) setResult({ refreshKey, items, failed: false })
      })
      .catch(() => {
        if (!alive) return
        message.error('조치 내역을 불러오지 못했습니다. 잠시 후 다시 시도하세요.')
        setResult((prev) => ({ refreshKey, items: prev?.items ?? [], failed: true }))
      })
    return () => {
      alive = false
    }
  }, [refreshKey, message])

  const loading = result?.refreshKey !== refreshKey
  const items = useMemo(() => result?.items ?? [], [result])
  const counts = handoffCounts(items)
  const filtered = useMemo(
    () => items.filter((h) => matchesHandoffTab(h, query.status)),
    [items, query.status],
  )
  const page = Math.min(query.page, Math.max(1, Math.ceil(filtered.length / PAGE_SIZE)))
  const openCase = (caseId: string) => navigate(`/security/cases/${caseId}`)

  const columns: ColumnsType<HandoffListItem> = [
    {
      title: 'Case ID',
      dataIndex: 'caseId',
      width: 90,
      render: (id: string) => (
        <Button
          type="link"
          style={{ padding: 0, fontWeight: 700 }}
          // 행 클릭과 겹쳐 두 번 이동하지 않게 막는다. 버튼은 키보드로 여는 경로로 남긴다
          onClick={(event) => {
            event.stopPropagation()
            openCase(id)
          }}
        >
          {id}
        </Button>
      ),
    },
    { title: '결제 ID', dataIndex: 'paymentId', width: 110 },
    {
      title: '금액',
      dataIndex: 'amount',
      align: 'right',
      width: 110,
      render: (v: HandoffListItem['amount']) => (v === null ? '-' : won(v)),
    },
    {
      title: '판정',
      dataIndex: 'decision',
      width: 150,
      render: (v: HandoffListItem['decision']) => <CodeTag value={decisionLabel[v]} />,
    },
    {
      title: '조치',
      dataIndex: 'actionType',
      width: 190,
      render: (v: HandoffListItem['actionType']) => actionTypeLabel[v],
    },
    {
      title: '상태',
      dataIndex: 'status',
      width: 200,
      render: (v: HandoffListItem['status'], h) => (
        <>
          <CodeTag value={handoffStatusLabel[v]} />
          {h.lastErrorCode && (
            <div style={{ fontSize: 12, color: '#dc2626', marginTop: 4 }}>
              {handoffErrorLabel[h.lastErrorCode] ?? h.lastErrorCode}
            </div>
          )}
        </>
      ),
    },
    {
      title: '판정 직원',
      dataIndex: 'staffId',
      width: 90,
      render: (v: HandoffListItem['staffId']) => (v === null ? '-' : `직원 ${v}`),
    },
    { title: '요청 시각', dataIndex: 'requestedAt', width: 150, render: dateTime },
    {
      title: '완료 시각',
      dataIndex: 'acceptedAt',
      width: 150,
      render: (v: HandoffListItem['acceptedAt']) => (v ? dateTime(v) : '-'),
    },
  ]

  return (
    <>
      <PageHeader
        title="조치 내역"
        description="판정 뒤 결제마다 어떤 금융 조치를 했고 어떻게 끝났는지 봅니다. Case가 아니라 결제별 조치를 셉니다 — 한 Case에 결제가 여러 건이면 여러 줄입니다."
        actions={
          <Button icon={<ReloadOutlined />} onClick={() => setRefreshKey((k) => k + 1)}>
            새로고침
          </Button>
        }
      />
      {result?.failed && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          message="조치 내역을 불러오지 못했습니다. 아래 건수와 목록은 실제와 다를 수 있습니다."
          action={
            <Button size="small" onClick={() => setRefreshKey((k) => k + 1)}>
              다시 시도
            </Button>
          }
        />
      )}
      <div
        className="dw-stat-grid"
        style={{ gridTemplateColumns: 'repeat(4, minmax(0, 1fr))' }}
        aria-label="조치 상태별 건수"
      >
        {cards.map((card) => (
          <StatButton
            key={card.tab}
            title={card.title}
            value={counts[card.tab]}
            foot={card.foot}
            color={card.color}
            selected={query.status === card.tab}
            onClick={() => setQuery({ status: card.tab })}
          />
        ))}
      </div>
      <Card className="dw-table-card" style={{ borderColor: '#E2E8F0' }}>
        <div className="dw-table-toolbar">
          <div>
            <h3>조치 현황</h3>
            <p>
              최근 요청 순입니다. 완료 시각은 P3가 완료를 반영한 시각이고, 실제 완료 시각은 Case
              이력에 있습니다.
            </p>
          </div>
        </div>
        <Table
          rowKey="handoffId"
          columns={columns}
          dataSource={filtered}
          loading={loading}
          size="middle"
          scroll={{ x: 1240 }}
          onRow={(record) => ({
            onClick: () => openCase(record.caseId),
            style: { cursor: 'pointer' },
          })}
          locale={{
            emptyText: (
              <Typography.Text type="secondary" style={{ display: 'block', padding: 36 }}>
                {query.status === 'ALL'
                  ? '아직 판정한 결제가 없습니다.'
                  : '이 상태의 조치가 없습니다.'}
              </Typography.Text>
            ),
          }}
          pagination={{
            current: page,
            pageSize: PAGE_SIZE,
            onChange: (next) => setQuery({ page: next }),
            showSizeChanger: false,
            showTotal: (total) => `총 ${total}건`,
          }}
        />
      </Card>
    </>
  )
}
