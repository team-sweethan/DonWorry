import { ClockCircleOutlined, InfoCircleOutlined, RightOutlined } from '@ant-design/icons'
import { Link } from 'react-router-dom'
import { FinanceMoney } from './FinanceMoney'
import './funds-experience.css'

export function FundsForecastNotices({
  batchEnabled,
  excluded,
}: {
  batchEnabled: boolean
  excluded: { label: string; amount: number }[]
}) {
  if (batchEnabled && !excluded.length) return null
  return (
    <aside className="funds-forecast-notices" aria-label="자금 예측에 참고할 안내">
      {!batchEnabled && (
        <div className="funds-payment-caution" role="note">
          <span className="funds-notice-icon">
            <ClockCircleOutlined aria-hidden />
          </span>
          <div>
            <strong>자동 지급이 꺼져 있어요</strong>
            <p>
              정산 예정금은 예정대로 지급된다고 가정해 계산했어요. 실제 지급 완료 여부는 정산
              내역에서 확인해 주세요.
            </p>
          </div>
          <Link to="/owner/settlements">
            정산 내역 <RightOutlined aria-hidden />
          </Link>
        </div>
      )}
      {!!excluded.length && (
        <details className="funds-excluded-details">
          <summary>
            <span className="funds-notice-icon">
              <InfoCircleOutlined aria-hidden />
            </span>
            <span>
              <b>잔액 예측에 포함하지 않은 정산이 있어요</b>
              <small>지급 대기·일정 미정·취소 내역을 확인할 수 있어요.</small>
            </span>
            <span className="funds-excluded-count">{excluded.length}개 항목 확인</span>
          </summary>
          <div className="funds-excluded-body">
            <ul>
              {excluded.map((item) => (
                <li key={item.label}>
                  <span>{item.label}</span>
                  <strong>
                    <FinanceMoney value={item.amount} compact={false} />
                  </strong>
                </li>
              ))}
            </ul>
            <p>
              선택 기간의 합계가 아니라, 조회 시점에 가게 전체에서 확인된 금액이에요. 취소된 주문도
              예측에서 제외해요.
            </p>
          </div>
        </details>
      )}
    </aside>
  )
}
