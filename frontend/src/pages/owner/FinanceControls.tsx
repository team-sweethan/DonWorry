import { Alert, Button, Skeleton } from 'antd'
import type { ReactNode } from 'react'
import { ReloadOutlined } from '@ant-design/icons'
import type { FinancePeriod } from './financeView'
import { financeErrorMessage, koreanTime } from './financeView'
export function FinanceRefresh({
  at,
  loading,
  onRefresh,
}: {
  at: string | null
  loading: boolean
  onRefresh: () => void
}) {
  return (
    <div className="finance-refresh-bar">
      <span>
        마지막 성공 조회: {at ? <time dateTime={at}>{koreanTime(at)}</time> : '아직 없음'}
      </span>
      <Button icon={<ReloadOutlined />} loading={loading} onClick={onRefresh}>
        새로고침
      </Button>
    </div>
  )
}
export function FinanceFailure({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  return (
    <Alert
      type="error"
      showIcon
      message="조회하지 못했습니다"
      description={financeErrorMessage(error)}
      action={<Button onClick={onRetry}>다시 조회</Button>}
    />
  )
}
/** 기간을 바꿔 다시 조회하는 동안 이전 기간 숫자가 흐리게 남아 있음을 알린다. 빠른 응답에는 보이지 않는다. */
export const FinanceStaleNotice = () => (
  <div className="finance-stale-notice dw-loading-reveal" role="status" aria-live="polite">
    새 기간을 조회하고 있어요. 흐린 숫자는 이전 기간 기준입니다.
  </div>
)
export const FinanceLoading = () => (
  <div role="status" aria-live="polite">
    <p>서버에서 최신 자금 정보를 조회하고 있습니다.</p>
    <Skeleton active />
  </div>
)
export function FinancePeriodControl({
  value,
  onChange,
  min,
  max,
  actions,
  presets,
}: {
  value: FinancePeriod
  onChange: (value: FinancePeriod) => void
  min?: string
  max?: string
  actions?: ReactNode
  presets?: ReactNode
}) {
  return (
    <div className="finance-period">
      {presets && <div className="finance-period-presets">{presets}</div>}
      <label>
        조회 시작일
        <input
          aria-label="조회 시작일"
          type="date"
          value={value.from}
          min={min}
          max={max}
          onChange={(e) => onChange({ ...value, from: e.target.value })}
        />
      </label>
      <span>~</span>
      <label>
        조회 종료일
        <input
          aria-label="조회 종료일"
          type="date"
          value={value.to}
          min={min}
          max={max}
          onChange={(e) => onChange({ ...value, to: e.target.value })}
        />
      </label>
      {actions && <div className="finance-period-actions">{actions}</div>}
    </div>
  )
}
