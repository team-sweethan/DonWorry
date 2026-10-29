import { ReloadOutlined } from '@ant-design/icons'
import { Alert, Button, Card, Skeleton, Table, Tag, Typography } from 'antd'
import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { getAdminHome, type AdminHome } from '../../api/adminHome'
import type { FdsRuleChangeRequest } from '../../api/fds'
import { PageHeader } from '../../components/common/PageHeader'

const requestPath = '/admin/fds/rule-change-requests'
const formatDate = (value: string) =>
  new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'short',
    timeStyle: 'short',
    timeZone: 'Asia/Seoul',
  }).format(new Date(value))

function CountCard({
  title,
  result,
  loading,
  unit,
  to,
  foot,
}: {
  title: string
  result?: PromiseSettledResult<number>
  loading: boolean
  unit: string
  to: string
  foot: string
}) {
  return (
    <Link
      to={to}
      className="dw-panel dw-stat-card dw-stat-card-button"
      style={{ color: 'inherit' }}
    >
      <div className="dw-stat-label">{title}</div>
      {loading ? (
        <Skeleton.Input active size="small" aria-label={`${title} 불러오는 중`} />
      ) : result?.status === 'fulfilled' ? (
        <div className="dw-stat-value">
          {result.value.toLocaleString('ko-KR')}
          <span style={{ fontSize: 13, fontWeight: 500, marginLeft: 4 }}>{unit}</span>
        </div>
      ) : (
        <Typography.Text type="danger">불러오기 실패</Typography.Text>
      )}
      <div className="dw-stat-foot">{foot}</div>
    </Link>
  )
}

export function AdminHomePage() {
  const navigate = useNavigate()
  const [refreshKey, setRefreshKey] = useState(0)
  const [result, setResult] = useState<{ key: number; data: AdminHome } | null>(null)
  useEffect(() => {
    let alive = true
    void getAdminHome().then((data) => {
      if (alive) setResult({ key: refreshKey, data })
    })
    return () => {
      alive = false
    }
  }, [refreshKey])

  const loading = result?.key !== refreshKey
  const data = loading ? undefined : result?.data
  const requests = data?.requests
  const failed = data && Object.values(data).some((value) => value.status === 'rejected')
  const refresh = () => setRefreshKey((key) => key + 1)

  return (
    <>
      <PageHeader
        title="홈"
        description="승인을 기다리는 요청과 관리자 현황을 확인합니다."
        actions={
          <Button icon={<ReloadOutlined />} onClick={refresh} loading={loading}>
            새로고침
          </Button>
        }
      />
      {failed && (
        <Alert
          type="error"
          showIcon
          message="일부 현황을 불러오지 못했습니다. 다시 시도해 주세요."
          action={<Button onClick={refresh}>다시 시도</Button>}
          style={{ marginBottom: 16 }}
        />
      )}
      <div className="dw-stat-grid" aria-label="관리자 현황" aria-busy={loading}>
        <CountCard
          title="승인 대기 요청"
          result={
            requests?.status === 'fulfilled'
              ? { status: 'fulfilled', value: requests.value.count }
              : requests
          }
          loading={loading}
          unit="건"
          to={requestPath}
          foot="룰 변경 요청 확인 →"
        />
        <CountCard
          title="꺼진 Rule"
          result={data?.disabledRules}
          loading={loading}
          unit="개"
          to="/admin/fds/rules"
          foot="Rule 카탈로그 확인 →"
        />
        <CountCard
          title="계정 수"
          result={data?.users}
          loading={loading}
          unit="개"
          to="/admin/users"
          foot="전체 계정 관리 →"
        />
        <CountCard
          title="가맹점 수"
          result={data?.merchants}
          loading={loading}
          unit="개"
          to="/admin/merchants"
          foot="전체 가맹점 관리 →"
        />
      </div>
      <Card className="dw-table-card">
        <div className="dw-table-toolbar">
          <div>
            <h3>최근 승인 대기 요청</h3>
            <p>최근 요청한 순서로 최대 5건입니다. 승인·반려는 룰 변경 요청 화면에서 처리합니다.</p>
          </div>
          <Link to={requestPath}>요청 전체 보기 →</Link>
        </div>
        {requests?.status === 'rejected' ? (
          <Alert type="error" showIcon message="승인 대기 요청 목록을 불러오지 못했습니다." />
        ) : (
          <Table<FdsRuleChangeRequest>
            rowKey="requestId"
            loading={loading}
            dataSource={requests?.status === 'fulfilled' ? requests.value.recent : []}
            pagination={false}
            scroll={{ x: 800 }}
            onRow={() => ({
              onClick: (event) => {
                if (!(event.target instanceof Element && event.target.closest('a')))
                  navigate(requestPath)
              },
              style: { cursor: 'pointer' },
            })}
            columns={[
              {
                title: '요청 ID',
                dataIndex: 'requestId',
                render: (id: number) => <Link to={requestPath}>#{id}</Link>,
              },
              {
                title: 'Rule',
                dataIndex: 'ruleCode',
                render: (code: string) => (
                  <Link to={requestPath}>
                    <Tag>{code}</Tag>
                  </Link>
                ),
              },
              { title: '요청자', dataIndex: 'requestedByName' },
              { title: '요청 사유', dataIndex: 'reason', ellipsis: true },
              { title: '요청 시각 (한국 시간)', dataIndex: 'requestedAt', render: formatDate },
            ]}
            locale={{
              emptyText: loading
                ? '승인 대기 요청을 불러오는 중입니다.'
                : '승인 대기 요청이 없습니다.',
            }}
          />
        )}
      </Card>
    </>
  )
}
