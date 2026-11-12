import { FinanceMoney } from './FinanceMoney'
import { useState } from 'react'
import { Button, Empty, Modal, Pagination, Tag } from 'antd'
import type { SettlementReport } from '../../api/finance'
import { koreanTime } from './financeView'
import './payout-ledger.css'
import './settlement-experience.css'
import { latestSelection } from './latestSelection'
import { ArrowRightOutlined, CheckOutlined } from '@ant-design/icons'

export type PayoutSummaryData = Pick<
  SettlementReport,
  | 'paidSettlementAmount'
  | 'refundDeductionAmount'
  | 'paidAmount'
  | 'outstandingRecoveryAmount'
  | 'payouts'
>
export function PayoutSummary({
  data,
  scheduledAmount,
  nextScheduled,
  onScheduledSelect,
  resultsPending = false,
  todayValue,
}: {
  data: PayoutSummaryData
  scheduledAmount: number
  nextScheduled?: { date: string; amount: number; count: number }
  onScheduledSelect?: (date: string) => void
  resultsPending?: boolean
  todayValue?: string
}) {
  return (
    <section
      className="stl-overview ow-rise"
      aria-labelledby="payout-summary-title"
      inert={resultsPending}
    >
      <div className="stl-section-heading">
        <h2 id="payout-summary-title">조회 기간 정산 요약</h2>
        <span>지급 기록 기준</span>
      </div>
      <div className="stl-overview-grid">
        <div className="stl-paid-hero">
          <div className="stl-hero-eyebrow">
            <span>
              <CheckOutlined /> 지급 완료
            </span>
            <small>{data.payouts.length}건의 지급 기록</small>
          </div>
          <p>조회 기간에 지급된 금액</p>
          <strong className="stl-hero-amount">
            <FinanceMoney value={data.paidAmount} compact={false} countUp />
          </strong>
          <small className="stl-hero-footnote">
            환불 회수 차감 후 금액 · 은행 이체 조회와는 별개
          </small>
          <div className="stl-settlement-art" aria-hidden="true">
            <i />
            <i />
            <i />
            <i />
            <i />
            <i />
            <i />
          </div>
        </div>
        <div className="stl-upcoming">
          {nextScheduled ? (
            <>
              <div className="stl-date-tile" aria-hidden="true">
                <span>{Number(nextScheduled.date.slice(5, 7))}월</span>
                <b>{Number(nextScheduled.date.slice(8))}</b>
              </div>
              <h3>
                {nextScheduled.date === todayValue
                  ? '오늘'
                  : `${Number(nextScheduled.date.slice(5, 7))}월 ${Number(nextScheduled.date.slice(8))}일`}{' '}
                <span>정산 예정</span>
              </h3>
              <strong className="stl-upcoming-amount">
                <FinanceMoney value={nextScheduled.amount} compact={false} countUp />
              </strong>
              <div className="stl-upcoming-meta">
                <p>조회 기간 내 가장 가까운 예정일 · 주문 {nextScheduled.count}건</p>
                <Button
                  type="text"
                  className="stl-upcoming-action"
                  onClick={() => onScheduledSelect?.(nextScheduled.date)}
                >
                  예정 내역 확인 <ArrowRightOutlined />
                </Button>
              </div>
            </>
          ) : (
            <>
              <h3>다가오는 정산이 없어요</h3>
              <p>선택한 기간에 오늘 이후의 정산 예정 내역이 없습니다.</p>
            </>
          )}
          <div className="stl-scheduled-total">
            <span>조회 기간 내 미완료 정산금</span>
            <b>
              <FinanceMoney value={scheduledAmount} />
            </b>
          </div>
          <small>환불 회수 차감 전 금액 · 실제 지급액은 달라질 수 있어요.</small>
        </div>
      </div>
      <details className="stl-calculation-details">
        <summary>
          지급액은 어떻게 계산되나요?<span>차감 내역 보기</span>
        </summary>
        <div className="stl-payment-equation" aria-label="실제 지급액 계산">
          <div>
            <span>환불 차감 전 지급액</span>
            <b>
              <FinanceMoney value={data.paidSettlementAmount} />
            </b>
          </div>
          <span className="stl-equation-sign" aria-label="빼기">
            −
          </span>
          <div className="stl-equation-deduction">
            <span>환불 회수 차감액</span>
            <b>
              <FinanceMoney value={data.refundDeductionAmount} />
            </b>
          </div>
          <span className="stl-equation-sign" aria-label="같음">
            =
          </span>
          <div>
            <span>실제 지급액</span>
            <b>
              <FinanceMoney value={data.paidAmount} />
            </b>
          </div>
        </div>
        {data.outstandingRecoveryAmount === 0 && (
          <p className="stl-calculation-note">
            현재 미회수 환불액 0원 · 남아 있는 환불 회수 잔액이 없어요.
          </p>
        )}
      </details>
      {data.outstandingRecoveryAmount > 0 && (
        <div
          className={`stl-recovery-strip${data.outstandingRecoveryAmount > 0 ? ' has-recovery' : ''}`}
        >
          <span>
            현재 미회수 환불액{' '}
            <b>
              <FinanceMoney value={data.outstandingRecoveryAmount} />
            </b>
          </span>
          <small>
            {data.outstandingRecoveryAmount > 0
              ? '조회 기간과 별개인 현재 잔액 · 이후 정산에서 회수될 수 있어요.'
              : '현재 남아 있는 환불 회수 잔액이 없어요.'}
          </small>
        </div>
      )}
    </section>
  )
}
export function PayoutLedger({ payouts }: { payouts: SettlementReport['payouts'] }) {
  const [page, setPage] = useState(1)
  const [detail, setDetail] = useState<SettlementReport['payouts'][number] | null>(null)
  // 같은 기간을 새로고침해도 열린 지급 명세는 최신 결과에서 다시 찾는다. 없으면 닫는다 (#803 리뷰)
  const shownDetail = latestSelection(payouts, detail, (p) => p.payoutId)
  if (detail !== null && shownDetail === null) setDetail(null)
  const ordered = [...payouts].sort(
    (a, b) => b.payoutDate.localeCompare(a.payoutDate) || b.payoutId - a.payoutId,
  )
  const current = Math.min(page, Math.max(1, Math.ceil(ordered.length / 20)))
  return (
    <section className="payout-ledger" aria-label="가맹점 일별 지급 내역">
      <p className="payout-caption">
        가맹점별 하루 한 건의 지급 기록입니다. 주문별 정산금과 중복 합산하지 않습니다.
      </p>
      {!ordered.length ? (
        <Empty description="선택한 범위에 완료된 지급이 없습니다." />
      ) : (
        <>
          <div className="stl-table-scroll">
            <table className="stl-readable-table">
              <thead>
                <tr>
                  <th>지급일 / 번호</th>
                  <th className="stl-money-cell">환불 차감 전 지급액</th>
                  <th className="stl-money-cell">환불 회수 차감</th>
                  <th className="stl-money-cell">실제 지급액</th>
                  <th>상세</th>
                </tr>
              </thead>
              <tbody>
                {ordered.slice((current - 1) * 20, current * 20).map((p) => (
                  <tr key={p.payoutId}>
                    <td>
                      <b>{p.payoutDate}</b>
                      <small>
                        지급 #{p.payoutId}
                        {p.origin === 'LEGACY_BACKFILL' ? ' · 과거 이관 기록' : ''}
                      </small>
                    </td>
                    <td className="stl-money-cell">
                      <FinanceMoney value={p.settlementAmount} />
                    </td>
                    <td className="stl-money-cell payout-deduction">
                      <FinanceMoney value={-p.refundDeductionAmount} />
                    </td>
                    <td className="stl-money-cell">
                      <strong className="payout-actual">
                        <FinanceMoney value={p.payoutAmount} />
                      </strong>
                      {p.payoutAmount === 0 && p.refundDeductionAmount > 0 && (
                        <small>환불 회수로 전액 상계</small>
                      )}
                    </td>
                    <td>
                      <Button onClick={() => setDetail(p)}>지급 상세</Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="stl-report-pagination">
            <span>총 {ordered.length}건 · 20건씩 표시</span>
            <Pagination
              current={current}
              pageSize={20}
              total={ordered.length}
              onChange={setPage}
              showSizeChanger={false}
            />
          </div>
        </>
      )}
      <Modal
        title="일별 지급 명세"
        open={shownDetail !== null}
        onCancel={() => setDetail(null)}
        footer={<Button onClick={() => setDetail(null)}>닫기</Button>}
      >
        {shownDetail && (
          <div className="payout-statement">
            <Tag color={shownDetail.origin === 'LEGACY_BACKFILL' ? 'blue' : 'green'}>
              {shownDetail.origin === 'LEGACY_BACKFILL' ? '과거 정산 이관 기록' : '지급 확정'}
            </Tag>
            <h2>
              {shownDetail.payoutDate} 지급 #{shownDetail.payoutId}
            </h2>
            <p>
              {koreanTime(shownDetail.completedAt)}{' '}
              {shownDetail.origin === 'LEGACY_BACKFILL' ? '기록 기준' : '확정'}
            </p>
            <dl>
              <dt>환불 차감 전 지급액</dt>
              <dd>
                <FinanceMoney value={shownDetail.settlementAmount} />
              </dd>
              <dt>환불 회수 차감</dt>
              <dd className="payout-deduction">
                <FinanceMoney value={-shownDetail.refundDeductionAmount} />
              </dd>
              <dt>실제 지급액</dt>
              <dd className="payout-actual">
                <FinanceMoney value={shownDetail.payoutAmount} />
              </dd>
            </dl>
            <p>
              {shownDetail.payoutAmount === 0
                ? shownDetail.refundDeductionAmount > 0
                  ? '이번 정산금이 환불 회수에 모두 반영되어 입금으로 간주하는 금액은 0원입니다.'
                  : '지급 대상 순정산금이 0원이므로 실제 지급액도 0원입니다.'
                : '환불 회수 후 이 금액이 입금된 것으로 간주합니다.'}{' '}
              이미 확정된 과거 지급 기록은 변경하지 않습니다.
            </p>
            {shownDetail.origin === 'LEGACY_BACKFILL' && (
              <p>
                과거 여러 정산을 날짜별로 묶어 이관한 금액이며, 당시에도 하루 한 번 지급했다는
                의미는 아닙니다.
              </p>
            )}
            <small>
              은행 이체 내역을 조회하는 기능은 아닙니다. 주문별 환불 회수 배분을 임의로 추정하지
              않습니다.
            </small>
          </div>
        )}
      </Modal>
    </section>
  )
}
