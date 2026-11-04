import { lazy, Suspense } from 'react'
import type { ReactNode } from 'react'
import { useLocation } from 'react-router-dom'
import { ConfigProvider } from 'antd'
import koKR from 'antd/es/locale/ko_KR'
import './finance-page-header.css'

// 정산 코드가 공용 라우터의 초기 번들에 포함되지 않도록 경계를 둔다.
const FinancePreview = lazy(() =>
  import('./FinancePreview').then((module) => ({ default: module.FinancePreview })),
)

const FinanceConnected = lazy(() =>
  import('./FinanceConnected').then((module) => ({ default: module.FinanceConnected })),
)

function FinanceSurface({ children }: { children: ReactNode }) {
  return (
    <ConfigProvider
      locale={koKR}
      theme={{
        token: {
          colorPrimary: '#ad7c18',
          colorInfo: '#ad7c18',
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
      <Suspense fallback={<div role="status">정산 화면을 불러오는 중입니다.</div>}>
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
      <Suspense fallback={<div role="status">상품 안내를 불러오는 중입니다.</div>}>
        <LoanProductsPage />
      </Suspense>
    </FinanceSurface>
  )
}
