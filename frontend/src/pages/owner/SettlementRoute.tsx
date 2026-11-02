import { lazy, Suspense } from 'react'
import { useLocation } from 'react-router-dom'

// 정산 코드가 공용 라우터의 초기 번들에 포함되지 않도록 경계를 둔다.
const FinancePreview = lazy(() =>
  import('./FinancePreview').then((module) => ({ default: module.FinancePreview })),
)

const FinanceConnected = lazy(() =>
  import('./FinanceConnected').then((module) => ({ default: module.FinanceConnected })),
)

export function SettlementRoute() {
  const { pathname } = useLocation()
  const preview = import.meta.env.DEV && pathname.startsWith('/dev/')
  return (
    <Suspense fallback={<div role="status">정산 화면을 불러오는 중입니다.</div>}>
      {preview ? <FinancePreview /> : <FinanceConnected />}
    </Suspense>
  )
}

const LoanProductsPage = lazy(() =>
  import('./LoanProductsPage').then((module) => ({ default: module.LoanProductsPage })),
)
export function LoanProductsRoute() {
  return (
    <Suspense fallback={<div role="status">상품 안내를 불러오는 중입니다.</div>}>
      <LoanProductsPage />
    </Suspense>
  )
}
