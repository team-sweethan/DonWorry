import {
  ArrowRightOutlined,
  CheckCircleFilled,
  CheckOutlined,
  FileSearchOutlined,
  LinkOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
  WalletOutlined,
} from '@ant-design/icons'
import { Button, Skeleton, Tag } from 'antd'
import { Link } from 'react-router-dom'
import { PageHeader } from '../../components/common/PageHeader'
import { useAuth } from '../../auth/useAuth'
import { useOwnerIntegration } from '../../hooks/useOwnerIntegration'
import { integrationLabels, type IntegrationStatus } from '../../api/integration'
import { sessionNavigation } from '../../config/navigation'
import './OwnerHomePage.css'

const guides: Record<
  IntegrationStatus,
  { title: string; body: string; action: string; step: number }
> = {
  NO_KEY: {
    title: '쇼핑몰 연결을 시작해 볼까요?',
    body: '시크릿 키를 발급해 쇼핑몰 서버에 설정해 주세요. 연결 확인을 마치면 신규 결제를 요청할 수 있습니다.',
    action: '시크릿 키 발급',
    step: 0,
  },
  PENDING_CONNECTION: {
    title: '이제 쇼핑몰에서 연결을 확인해 주세요',
    body: '키를 쇼핑몰 서버에 설정하고 연결 확인 요청을 보내 주세요. 아직 신규 결제는 요청할 수 없습니다.',
    action: '연동 방법 확인',
    step: 1,
  },
  CONNECTED: {
    title: '쇼핑몰 연동이 완료되었습니다',
    body: '가맹점이 사용 중이면 쇼핑몰에서 신규 결제를 요청할 수 있습니다.',
    action: '시크릿 키 관리',
    step: 3,
  },
}

const descriptions: Record<string, string> = {
  '/owner/actions': '보류된 결제의 처리 결과를 확인하세요.',
  '/owner/settlements': '정산 내역과 입금 흐름을 확인하세요.',
  '/owner/funds': '가용 자금과 예정된 지출을 살펴보세요.',
  '/owner/loans': '운영에 필요한 대출 상품을 살펴보세요.',
}

function connectionTime(value: string) {
  // DB DATETIME은 UTC다. 시간대 없는 응답도 브라우저의 로컬 시각으로 해석하지 않는다.
  const date = new Date(/(Z|[+-]\d{2}:\d{2})$/.test(value) ? value : `${value}Z`)
  return Number.isNaN(date.getTime())
    ? '확인할 수 없음'
    : new Intl.DateTimeFormat('ko-KR', {
        dateStyle: 'medium',
        timeStyle: 'short',
        timeZone: 'Asia/Seoul',
      }).format(date)
}

export function OwnerHomePage() {
  const { user } = useAuth()
  return user ? <OwnerHome key={`${user.userId}:${user.merchantId}`} user={user} /> : null
}

function OwnerHome({ user }: { user: NonNullable<ReturnType<typeof useAuth>['user']> }) {
  const { state, refresh } = useOwnerIntegration(user.userId, user.merchantId)
  const data = state.kind === 'ready' ? state.data : null
  const guide = data ? guides[data.status] : null
  const connected = data?.status === 'CONNECTED'
  const menus = sessionNavigation(user)
    .groups.flatMap((group) => group.items)
    .filter((item) => item.permission)
  return (
    <div className="oh-home">
      <PageHeader
        title="홈"
        description={`${user.displayName}님, 쇼핑몰 연동 상태와 이용할 메뉴를 확인하세요.`}
        actions={
          <Button
            icon={<ReloadOutlined />}
            loading={state.kind === 'loading'}
            onClick={() => void refresh()}
          >
            새로고침
          </Button>
        }
      />
      <section
        className={`oh-connection${connected ? ' is-connected' : ''}`}
        aria-label="쇼핑몰 연동"
        aria-busy={state.kind === 'loading'}
      >
        <div className="oh-section-heading">
          <span className="oh-eyebrow">
            <LinkOutlined /> 쇼핑몰 연동
          </span>
          {data && (
            <Tag color={connected ? 'success' : 'gold'}>{integrationLabels[data.status]}</Tag>
          )}
        </div>
        {state.kind === 'loading' ? (
          <div role="status" aria-label="연동 상태를 불러오는 중">
            <Skeleton active paragraph={{ rows: 3 }} />
          </div>
        ) : state.kind === 'error' ? (
          <div className="oh-error" role="status">
            <h2 id="oh-connection-title">
              {state.inactive
                ? '가맹점이 사용 중지되어 있습니다'
                : '연동 상태를 확인하지 못했습니다'}
            </h2>
            <p>
              {state.inactive
                ? '돈워리 담당자에게 가맹점 상태를 확인해 주세요.'
                : '잠시 후 다시 시도해 주세요. 현재 연동 상태는 확인되지 않았습니다.'}
            </p>
            <Button onClick={() => void refresh()}>다시 시도</Button>
          </div>
        ) : (
          data &&
          guide && (
            <>
              <div className="oh-connection-main">
                <span className="oh-connection-icon" aria-hidden="true">
                  {connected ? <CheckCircleFilled /> : <LinkOutlined />}
                </span>
                <div className="oh-connection-copy">
                  <h2 id="oh-connection-title">{guide.title}</h2>
                  <p>{guide.body}</p>
                </div>
                <Link className="oh-primary-link" to="/owner/integration">
                  {guide.action}
                  <ArrowRightOutlined />
                </Link>
              </div>
              <dl className="oh-details">
                <div>
                  <dt>가맹점 코드</dt>
                  <dd className="oh-merchant-code">{data.merchantCode}</dd>
                </div>
                {data.connectedAt && (
                  <div>
                    <dt>연결 확인 (한국 시간)</dt>
                    <dd>{connectionTime(data.connectedAt)}</dd>
                  </div>
                )}
              </dl>
              {!connected && (
                <ol className="oh-steps" aria-label="쇼핑몰 연동 순서">
                  {['키 발급', '쇼핑몰 서버 설정', '연결 확인'].map((label, index) => (
                    <li
                      key={label}
                      className={
                        index < guide.step ? 'is-done' : index === guide.step ? 'is-current' : ''
                      }
                      aria-current={index === guide.step ? 'step' : undefined}
                    >
                      <span aria-hidden="true">
                        {index < guide.step ? <CheckOutlined /> : index + 1}
                      </span>
                      {label}
                    </li>
                  ))}
                </ol>
              )}
            </>
          )
        )}
      </section>
      <section className="oh-menu-section" aria-labelledby="oh-menu-title">
        <div className="oh-section-heading">
          <h2 id="oh-menu-title">이용 메뉴</h2>
          <span>내 계정에서 이용할 수 있는 메뉴</span>
        </div>
        {menus.length ? (
          <div className="oh-menu-grid">
            {menus.map((item) => (
              <Link key={item.path} className="oh-menu-card" to={item.path}>
                <span className="oh-menu-icon" aria-hidden="true">
                  {item.path === '/owner/actions' ? <FileSearchOutlined /> : <WalletOutlined />}
                </span>
                <h3>{item.label}</h3>
                <p>{descriptions[item.path]}</p>
                <ArrowRightOutlined className="oh-menu-arrow" aria-hidden="true" />
              </Link>
            ))}
          </div>
        ) : (
          <div className="oh-no-menus">
            <SafetyCertificateOutlined aria-hidden="true" />
            <div>
              <h3>업무 메뉴 권한이 아직 없습니다</h3>
              <p>
                쇼핑몰 연동과 시크릿 키 관리는 지금 이용할 수 있습니다. 업무 메뉴가 필요하면 돈워리
                담당자에게 문의해 주세요.
              </p>
            </div>
          </div>
        )}
      </section>
      <p className="oh-security-note">
        <SafetyCertificateOutlined /> 시크릿 키는 쇼핑몰 서버에만 보관하고, 다른 사람에게 공유하지
        마세요.
      </p>
    </div>
  )
}
