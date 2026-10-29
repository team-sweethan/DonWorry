import { lazy, Suspense } from 'react'

// 정산 코드가 공용 라우터의 초기 번들에 포함되지 않도록 경계를 둔다.
const FinancePreview = lazy(() =>
  import('./FinancePreview').then((module) => ({ default: module.FinancePreview })),
)

export function SettlementRoute() {
  return (
    <Suspense fallback={<div role="status">정산 화면을 불러오는 중입니다.</div>}>
      <FinancePreview />
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
