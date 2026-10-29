import {
  ArrowRightOutlined,
  CheckOutlined,
  FileSearchOutlined,
  PauseCircleOutlined,
  ReloadOutlined,
  ShopOutlined,
  TeamOutlined,
} from '@ant-design/icons'
import { Alert, Button, Skeleton } from 'antd'
import { useEffect, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { getAdminHome, type AdminHome } from '../../api/adminHome'
import { PageHeader } from '../../components/common/PageHeader'
import './AdminHomePage.css'

const requestPath = '/admin/fds/rule-change-requests'
const formatDate = (value: string) =>
  new Intl.DateTimeFormat('ko-KR', {
    month: 'long',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    timeZone: 'Asia/Seoul',
  }).format(new Date(value))

function Count({
  result,
  loading,
  unit,
}: {
  result?: PromiseSettledResult<number>
  loading: boolean
  unit: string
}) {
  if (loading)
    return (
      <Skeleton.Input
        active
        size="small"
        style={{ width: 64, minWidth: 64 }}
        aria-label="현황 불러오는 중"
      />
    )
  if (result?.status !== 'fulfilled') return <span className="ah-count-error">확인할 수 없음</span>
  return (
    <span className="ah-count">
      {result.value.toLocaleString('ko-KR')}
      <small>{unit}</small>
    </span>
  )
}

function ManagementLink({
  title,
  result,
  loading,
  icon,
  to,
}: {
  title: string
  result?: PromiseSettledResult<number>
  loading: boolean
  icon: ReactNode
  to: string
}) {
  return (
    <Link to={to} className="ah-management-link">
      <span className="ah-small-icon" aria-hidden="true">
        {icon}
      </span>
      <span className="ah-management-label">
        {title}
        <span>전체 {title === '계정 관리' ? '계정' : '가맹점'}</span>
      </span>
      <Count result={result} loading={loading} unit="개" />
      <ArrowRightOutlined className="ah-arrow" aria-hidden="true" />
    </Link>
  )
}

export function AdminHomePage() {
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
  const pending = requests?.status === 'fulfilled' ? requests.value : undefined
  const failed = data && Object.values(data).some((value) => value.status === 'rejected')
  const refresh = () => setRefreshKey((key) => key + 1)

  return (
    <div className="ah-home">
      <PageHeader
        title="관리자 홈"
        description="승인할 요청을 먼저 살펴보고, 운영 현황을 확인하세요."
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
          message="일부 정보를 불러오지 못했습니다."
          description="확인할 수 없는 항목은 아래에 표시했습니다. 잠시 후 다시 시도해 주세요."
          action={<Button onClick={refresh}>다시 시도</Button>}
        />
      )}
      <div className="ah-layout">
        <section
          className="ah-panel ah-approvals"
          aria-labelledby="ah-approval-title"
          aria-busy={loading}
        >
          <div className="ah-approval-summary">
            <div
              className={
                pending?.count === 0 ? 'ah-feature-icon ah-feature-icon-clear' : 'ah-feature-icon'
              }
              aria-hidden="true"
            >
              {pending?.count === 0 ? <CheckOutlined /> : <FileSearchOutlined />}
            </div>
            <div className="ah-summary-copy">
              <h2 id="ah-approval-title">승인 대기 요청</h2>
              <Link to={requestPath} title="승인 대기 요청 목록으로 이동">
                <Count
                  loading={loading}
                  result={
                    requests?.status === 'fulfilled'
                      ? { status: 'fulfilled', value: requests.value.count }
                      : requests
                  }
                  unit="건"
                />
              </Link>
              <p>
                {pending?.count === 0
                  ? '새 요청이 들어오면 이곳에 표시됩니다.'
                  : '보안 직원이 요청한 탐지 룰 끄기를 검토해 주세요.'}
              </p>
            </div>
            <Link to={requestPath} className="ah-primary-link">
              {pending?.count === 0 ? '요청 목록 보기' : '승인 요청 검토'}{' '}
              <ArrowRightOutlined aria-hidden="true" />
            </Link>
          </div>
          <div className="ah-list-heading">
            <h3>최근 들어온 요청</h3>
            <span>최신순 · 최대 5건</span>
          </div>
          {loading ? (
            <div className="ah-list-state" role="status" aria-label="승인 대기 요청 불러오는 중">
              <Skeleton active paragraph={{ rows: 4 }} />
            </div>
          ) : !pending ? (
            <div className="ah-list-state ah-empty" role="status">
              <FileSearchOutlined aria-hidden="true" />
              <h3>요청 목록을 확인할 수 없습니다</h3>
              <p>연결 상태를 확인하고 다시 불러와 주세요.</p>
              <Button onClick={refresh} icon={<ReloadOutlined />}>
                다시 불러오기
              </Button>
            </div>
          ) : pending.recent.length === 0 ? (
            <div className="ah-list-state ah-empty" role="status">
              <span className="ah-empty-check">
                <CheckOutlined aria-hidden="true" />
              </span>
              <h3>대기 중인 승인 요청이 없습니다</h3>
              <p>새 요청이 들어오면 이곳에서 확인할 수 있습니다.</p>
              <Link to={requestPath}>
                룰 변경 요청으로 이동 <ArrowRightOutlined aria-hidden="true" />
              </Link>
            </div>
          ) : (
            <ul className="ah-request-list">
              {pending.recent.map((request) => (
                <li key={request.requestId}>
                  <Link to={requestPath} className="ah-request-link">
                    <div className="ah-request-content">
                      <div className="ah-request-title">
                        <span className="ah-rule-code">{request.ruleCode}</span>
                        <strong>탐지 룰 끄기 요청</strong>
                      </div>
                      <p className="ah-request-reason" title={request.reason}>
                        {request.reason}
                      </p>
                      <div className="ah-request-meta">
                        <span>{request.requestedByName}</span>
                        <span aria-hidden="true">·</span>
                        <time dateTime={request.requestedAt}>
                          {formatDate(request.requestedAt)}
                        </time>
                        <span className="ah-request-id">#{request.requestId}</span>
                      </div>
                    </div>
                    <span className="ah-request-action">
                      목록으로 <ArrowRightOutlined aria-hidden="true" />
                    </span>
                  </Link>
                </li>
              ))}
            </ul>
          )}
          <div className="ah-list-footer">
            <span>승인·반려는 룰 변경 요청 화면에서 처리합니다.</span>
            <Link to={requestPath}>
              전체 요청 보기 <ArrowRightOutlined aria-hidden="true" />
            </Link>
          </div>
        </section>
        <aside className="ah-side" aria-label="운영 현황">
          <section
            className="ah-panel ah-rule-panel"
            aria-labelledby="ah-rule-title"
            aria-busy={loading}
          >
            <div className="ah-section-heading">
              <h2 id="ah-rule-title">탐지 룰 현황</h2>
              <PauseCircleOutlined aria-hidden="true" />
            </div>
            <Link to="/admin/fds/rules" className="ah-rule-stat">
              <span>꺼져 있는 룰</span>
              <Count result={data?.disabledRules} loading={loading} unit="개" />
            </Link>
            <p>
              {data?.disabledRules.status === 'fulfilled' && data.disabledRules.value === 0
                ? '모든 탐지 룰이 켜져 있습니다.'
                : '꺼진 룰은 이상 거래 탐지에 사용되지 않습니다. 필요한 룰이 켜져 있는지 확인하세요.'}
            </p>
            <Link to="/admin/fds/rules" className="ah-secondary-link">
              Rule 카탈로그 보기 <ArrowRightOutlined aria-hidden="true" />
            </Link>
          </section>
          <section
            className="ah-panel ah-management"
            aria-labelledby="ah-management-title"
            aria-busy={loading}
          >
            <div className="ah-section-heading">
              <h2 id="ah-management-title">계정 · 가맹점</h2>
            </div>
            <ManagementLink
              title="계정 관리"
              icon={<TeamOutlined />}
              result={data?.users}
              loading={loading}
              to="/admin/users"
            />
            <ManagementLink
              title="가맹점 관리"
              icon={<ShopOutlined />}
              result={data?.merchants}
              loading={loading}
              to="/admin/merchants"
            />
            <p className="ah-management-note">사용 중지된 계정과 가맹점도 포함한 전체 수입니다.</p>
          </section>
        </aside>
      </div>
    </div>
  )
}
