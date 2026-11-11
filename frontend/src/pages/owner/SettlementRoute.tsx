import { lazy, Suspense } from 'react'
import type { ReactNode } from 'react'
import { useLocation } from 'react-router-dom'
import { ConfigProvider, Skeleton } from 'antd'
import koKR from 'antd/es/locale/ko_KR'
import { PageHeader } from '../../components/common/PageHeader'
import { loadFinanceConnected } from './financePreload'
import './finance-page-header.css'

// 정산 코드가 공용 라우터의 초기 번들에 포함되지 않도록 경계를 둔다.
const FinancePreview = lazy(() =>
  import('./FinancePreview').then((module) => ({ default: module.FinancePreview })),
)

const FinanceConnected = lazy(() =>
  loadFinanceConnected().then((module) => ({ default: module.FinanceConnected })),
)

/*
 * 코드를 받는 동안에도 실제 화면과 같은 제목을 같은 자리에 둔다.
 * 그다음 데이터 로딩 단계와 모양이 같아서, 화면이 바뀌는 것이 한 번만 보인다 (#803).
 */
function FinanceRouteFallback({ funds }: { funds: boolean }) {
  return (
    <div className="finance-preview finance-connected">
      <PageHeader
        title={funds ? '자금 현황' : '정산 내역'}
        description={
          funds
            ? '실제 완료 기록과 등록한 정산·지출로 앞으로의 잔액을 확인합니다.'
            : '매입일 기준 영업일 D+3(주말·한국 공휴일 제외) 정산과 환불 차감 후 실제 지급액을 확인합니다.'
        }
      />
      <div role="status" aria-label="정산 화면을 불러오는 중">
        <Skeleton active />
      </div>
    </div>
  )
}

function FinanceSurface({ children }: { children: ReactNode }) {
  return (
    <ConfigProvider
      locale={koKR}
      theme={{
        token: {
          // 다른 사장님 화면(router.tsx ownerWarmTheme)과 같은 색을 써서 메뉴를 옮길 때 색이 바뀌지 않게 한다 (#803)
          colorPrimary: '#886211',
          colorInfo: '#886211',
          colorLink: '#886211',
          colorBorderSecondary: '#e7e1d5',
          colorFillAlter: '#fffaf0',
          colorBgLayout: '#faf8f2',
          colorText: '#30291f',
          colorTextSecondary: '#756b5b',
          colorBorder: '#e5dfd2',
        },
      }}
    >
      <div className="finance-surface">{children}</div>
    </ConfigProvider>
  )
}
export function SettlementRoute() {
  const { pathname } = useLocation()
  const preview = import.meta.env.DEV && pathname.startsWith('/dev/')
  return (
    <FinanceSurface>
      <Suspense
        fallback={<FinanceRouteFallback funds={pathname.replace(/\/+$/, '').endsWith('/funds')} />}
      >
        {preview ? <FinancePreview /> : <FinanceConnected />}
      </Suspense>
    </FinanceSurface>
  )
}

const LoanProductsPage = lazy(() =>
  import('./LoanProductsPage').then((module) => ({ default: module.LoanProductsPage })),
)
export function LoanProductsRoute() {
  return (
    <FinanceSurface>
      <Suspense
        fallback={
          <div role="status" className="dw-loading-reveal">
            상품 안내를 불러오는 중입니다.
          </div>
        }
      >
        <LoanProductsPage />
      </Suspense>
    </FinanceSurface>
  )
}
