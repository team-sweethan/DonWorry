import {
  ArrowRightOutlined,
  CheckOutlined,
  ExclamationCircleOutlined,
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
  if (result?.status !== 'fulfilled') return <span className="ah-count-error">불러오기 실패</span>
  return (
    <span className="ah-count">
      {result.value.toLocaleString('ko-KR')}
      <small>{unit}</small>
    </span>
  )
}

function ManagementCard({
  title,
  description,
  result,
  loading,
  icon,
  to,
  unit,
  children,
}: {
  title: string
  description: string
  result?: PromiseSettledResult<number>
  loading: boolean
  icon: ReactNode
  to: string
  unit: string
  children?: ReactNode
}) {
  // 카드 안에 다른 링크를 둘 수 있게 제목 링크를 카드 전체로 넓힌다 (링크 안 링크 금지).
  return (
    <div className="ah-panel ah-management-card">
      <div className="ah-management-heading">
        <span className="ah-management-icon" aria-hidden="true">
          {icon}
        </span>
        <h2>
          <Link to={to} className="ah-management-link">
            {title}
          </Link>
        </h2>
        <ArrowRightOutlined className="ah-arrow" aria-hidden="true" />
      </div>
      <p>{description}</p>
      <div className="ah-management-total">
        <span>전체</span>
        <Count result={result} loading={loading} unit={unit} />
      </div>
      {children}
    </div>
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
        actions={
          <Button icon={<ReloadOutlined />} onClick={refresh} loading={loading}>
            새로고침
          </Button>
        }
      />
      <section className="ah-application-entry" aria-label="신규 가맹점 이용 신청">
        <div>
          <h2>신규 이용 신청 검토</h2>
          <p>이메일 확인이 끝난 신청을 검토하고 가맹점·사장님 계정을 발급합니다.</p>
        </div>
        <Link to="/admin/applications">
          <Button type="primary" icon={<ArrowRightOutlined />}>
            이용 신청 보기
          </Button>
        </Link>
      </section>
      {failed && (
        <Alert
          type="error"
          showIcon
          message="일부 현황을 불러오지 못했습니다."
          action={<Button onClick={refresh}>다시 불러오기</Button>}
        />
      )}
      <section aria-label="계정·가맹점 관리" className="ah-management-overview" aria-busy={loading}>
        <div className="ah-management-cards">
          <ManagementCard
            title="계정 관리"
            description="사용자 권한과 계정 상태를 관리합니다."
            icon={<TeamOutlined />}
            result={data?.users}
            loading={loading}
            to="/admin/users"
            unit="명"
          >
            {!loading &&
              data?.passwordPendingUsers.status === 'fulfilled' &&
              data.passwordPendingUsers.value > 0 && (
                <Link
                  to="/admin/users?status=ACTIVE&passwordChangeRequired=true"
                  className="ah-management-alert"
                >
                  <ExclamationCircleOutlined aria-hidden="true" />
                  <span>
                    임시 비밀번호 미변경{' '}
                    <strong>{data.passwordPendingUsers.value.toLocaleString('ko-KR')}명</strong>
                  </span>
                  <ArrowRightOutlined aria-hidden="true" />
                </Link>
              )}
          </ManagementCard>
          <ManagementCard
            title="가맹점 관리"
            description="가맹점 정보와 운영 상태를 관리합니다."
            icon={<ShopOutlined />}
            result={data?.merchants}
            loading={loading}
            to="/admin/merchants"
            unit="곳"
          />
        </div>
        <p className="ah-management-note">사용 중지된 계정·가맹점을 포함한 전체 수입니다.</p>
      </section>
      <div className="ah-layout">
        <section
          className={`ah-panel ah-approvals${pending?.count ? ' is-pending' : ''}`}
          aria-labelledby="ah-approval-title"
          aria-busy={loading}
        >
          <div className="ah-approval-summary">
            <div className="ah-feature-icon" aria-hidden="true">
              <FileSearchOutlined />
            </div>
            <div className="ah-summary-copy">
              <h2 id="ah-approval-title">룰 끄기 승인 대기</h2>
              <Link to={requestPath} title="룰 끄기 요청 목록으로 이동">
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
            </div>
            <Link to={requestPath} className="ah-review-link">
              {pending?.count === 0 ? '요청 목록' : '요청 검토'}{' '}
              <ArrowRightOutlined aria-hidden="true" />
            </Link>
          </div>
          <div className="ah-list-heading">
            <h3>최근 요청</h3>
            <span>최신순 · 최대 5건</span>
          </div>
          {loading ? (
            <div className="ah-list-state" role="status" aria-label="룰 끄기 요청을 불러오는 중">
              <Skeleton active paragraph={{ rows: 4 }} />
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
                    <span className="ah-rule-code">{request.ruleCode}</span>
                    <div className="ah-request-content">
                      <p className="ah-request-reason" title={request.reason}>
                        {request.reason}
                      </p>
                      <div className="ah-request-meta">
                        <span>{request.requestedByName}</span>
                        <span aria-hidden="true">·</span>
                        <time dateTime={request.requestedAt}>
                          {formatDate(request.requestedAt)}
                        </time>
                      </div>
                    </div>
                    <ArrowRightOutlined className="ah-request-action" aria-hidden="true" />
                  </Link>
                </li>
              ))}
              {pending.count > pending.recent.length && (
                <li>
                  <Link to={requestPath} className="ah-request-more">
                    외 {(pending.count - pending.recent.length).toLocaleString('ko-KR')}건 더 보기{' '}
                    <ArrowRightOutlined aria-hidden="true" />
                  </Link>
                </li>
              )}
            </ul>
          )}
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
            <Link to="/admin/fds/rules" className="ah-rule-stat" title="탐지 룰 목록으로 이동">
              <span>사용 중지된 탐지 룰</span>
              <Count result={data?.disabledRules} loading={loading} unit="개" />
              <ArrowRightOutlined className="ah-arrow" aria-hidden="true" />
            </Link>
            <p>
              {data?.disabledRules.status === 'fulfilled' && data.disabledRules.value === 0
                ? '모든 탐지 룰이 사용 중입니다.'
                : '사용 중지된 룰은 이상 거래 탐지에서 제외됩니다.'}
            </p>
          </section>
        </aside>
      </div>
    </div>
  )
}
