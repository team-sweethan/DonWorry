import { type MouseEvent, useEffect, useState } from 'react'
import { ReloadOutlined } from '@ant-design/icons'
import { Alert, App, Button, Card, Table, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useNavigate } from 'react-router-dom'
import { getMerchantCases } from '../../api/merchantCases'
import { ApiError } from '../../api/client'
import { PageHeader } from '../../components/common/PageHeader'
import { ScreenResult } from '../../components/common/ScreenResult'
import type {
  MerchantCaseList,
  MerchantCaseRow,
  MerchantCaseStatus,
} from '../../types/merchantCase'
import { dateTime } from '../security/case/caseLabels'
import { CodeTag } from '../security/case/CodeTag'
import { merchantCaseStatusLabel } from './ownerLabels'

/**
 * 고객사 "보안 조치 결과" 목록 (fds 정본 02 §2.12 ②, #205).
 * 로그인한 사장님 가맹점의 Case만 서버가 돌려준다. 화면의 메뉴 권한은 편의이고 인가는 서버가 한다.
 */
export function MerchantCaseListPage() {
  const navigate = useNavigate()
  // useFeedback()은 렌더마다 새 객체라 effect 의존성에는 App이 주는 message를 쓴다
  const { message } = App.useApp()
  const [refreshKey, setRefreshKey] = useState(0)
  // 어떤 새로고침에 대한 응답인지 함께 저장한다. failed는 조회 실패 — 빈 목록과 구분하고 직전 목록은 유지한다.
  const [result, setResult] = useState<{
    refreshKey: number
    list: MerchantCaseList | null
    failed: boolean
    forbidden: boolean
  } | null>(null)

  useEffect(() => {
    let alive = true
    getMerchantCases()
      .then((list) => {
        if (alive) setResult({ refreshKey, list, failed: false, forbidden: false })
      })
      .catch((error: unknown) => {
        if (!alive) return
        const forbidden = error instanceof ApiError && error.status === 403
        if (!forbidden) message.error('조치 결과를 불러오지 못했습니다. 잠시 후 다시 시도하세요.')
        setResult((prev) => ({ refreshKey, list: prev?.list ?? null, failed: true, forbidden }))
      })
    return () => {
      alive = false
    }
  }, [refreshKey, message])

  if (result?.forbidden) return <ScreenResult kind="403" homePath="/owner" />

  const loading = result?.refreshKey !== refreshKey
  const list = result?.list
  const open = (id: string) => navigate(`/owner/actions/${id}`)
  const columns: ColumnsType<MerchantCaseRow> = [
    {
      title: 'Case ID',
      dataIndex: 'caseId',
      width: 120,
      render: (id: string) => (
        <Button
          type="link"
          style={{ padding: 0, fontWeight: 700 }}
          // 행 클릭과 겹쳐 두 번 이동하지 않게 막는다. 버튼은 키보드로 여는 경로로 남긴다.
          onClick={(event: MouseEvent<HTMLElement>) => {
            event.stopPropagation()
            open(id)
          }}
        >
          {id}
        </Button>
      ),
    },
    {
      title: '상태',
      dataIndex: 'displayStatus',
      width: 180,
      render: (s: MerchantCaseStatus) => <CodeTag value={merchantCaseStatusLabel[s]} />,
    },
    { title: '연관 결제', dataIndex: 'paymentCount', width: 100, render: (n: number) => `${n}건` },
    { title: '발생 시각', dataIndex: 'createdAt', width: 150, render: dateTime },
    {
      title: '조사 종료',
      dataIndex: 'closedAt',
      width: 150,
      render: (v: string | null) => (v ? dateTime(v) : '-'),
    },
  ]

  return (
    <>
      <PageHeader
        title="조치 결과"
        description="이상 거래로 보류된 결제가 어떻게 조사되고 처리되었는지 확인합니다."
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
          message="조치 결과를 불러오지 못했습니다. 아래 건수와 목록은 실제와 다를 수 있습니다."
          action={
            <Button size="small" onClick={() => setRefreshKey((k) => k + 1)}>
              다시 시도
            </Button>
          }
        />
      )}
      <div className="dw-stat-grid">
        <StatCard
          title="조사 중"
          value={list?.overview.investigatingCount}
          foot="보안 담당자가 조사 중인 Case"
        />
        <StatCard
          title="정상 처리 결정"
          value={list?.overview.acceptDecisionCount}
          foot="매입을 진행하기로 한 결제"
          color="#15803D"
        />
        <StatCard
          title="취소 결정"
          value={list?.overview.rejectDecisionCount}
          foot="승인을 취소하기로 한 결제"
          color="#B91C1C"
        />
      </div>
      <Card className="dw-table-card" style={{ borderColor: '#E2E8F0' }}>
        <div className="dw-table-toolbar">
          <div>
            <h3>이상 거래 Case</h3>
            <p>최근 발생 순입니다. Case를 누르면 결제별 결과를 볼 수 있습니다.</p>
          </div>
        </div>
        <Table
          rowKey="caseId"
          columns={columns}
          dataSource={list?.cases ?? []}
          loading={loading}
          size="middle"
          scroll={{ x: 700 }}
          onRow={(record) => ({
            onClick: () => open(record.caseId),
            style: { cursor: 'pointer' },
          })}
          locale={{
            emptyText: (
              <Typography.Text type="secondary" style={{ display: 'block', padding: 36 }}>
                이상 거래로 조사된 Case가 없습니다.
              </Typography.Text>
            ),
          }}
          pagination={{
            pageSize: 20,
            showSizeChanger: false,
            showTotal: (total) => `총 ${total}건`,
          }}
        />
      </Card>
    </>
  )
}

function StatCard({
  title,
  value,
  foot,
  color = '#0F172A',
}: {
  title: string
  value: number | undefined
  foot: string
  color?: string
}) {
  return (
    <div className="dw-panel dw-stat-card">
      <div className="dw-stat-label">{title}</div>
      <div className="dw-stat-value" style={{ color }}>
        {value ?? '-'}
        <span style={{ fontSize: 13, fontWeight: 500, marginLeft: 3 }}>건</span>
      </div>
      <div className="dw-stat-foot">{foot}</div>
    </div>
  )
}
