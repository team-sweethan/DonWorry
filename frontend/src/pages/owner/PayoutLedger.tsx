import { FinanceMoney } from './FinanceMoney'
import { useState } from 'react'
import { Button, Empty, Modal, Pagination, Tag } from 'antd'
import type { SettlementReport } from '../../api/finance'
import { koreanTime } from './financeView'
import './payout-ledger.css'
import { latestSelection } from './latestSelection'

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
}: {
  data: PayoutSummaryData
  scheduledAmount: number
}) {
  return (
    <>
      <section aria-labelledby="payout-summary-title">
        <h2 id="payout-summary-title" className="payout-summary-title">
          조회 기간 정산 요약
        </h2>
        <div className="payout-summary-grid">
          {[
            {
              label: '실제 지급액',
              value: data.paidAmount,
              note: '지급일 기준 · 환불 회수 차감 후',
              variant: 'paid',
            },
            {
              label: '정산 예정금',
              value: scheduledAmount,
              note: '예정일 기준 · 환불 회수 차감 전',
              variant: 'original',
            },
            {
              label: '환불 차감 전 지급액',
              value: data.paidSettlementAmount,
              note: '지급일 기준 · 환불 회수 차감 전',
              variant: 'before-deduction',
            },
            {
              label: '환불 회수 차감액',
              value: data.refundDeductionAmount,
              note: '선택 기간에 지급하면서 차감한 금액',
              variant: 'deduction',
            },
          ].map(({ label, value, note, variant }) => (
            <section key={variant} className={'payout-stat payout-stat-' + variant}>
              <span>{label}</span>
              <strong>
                <FinanceMoney value={value} countUp />
              </strong>
              <small>{note}</small>
            </section>
          ))}
        </div>
        <p className="payout-summary-formula">
          실제 지급액 = 환불 차감 전 지급액 − 환불 회수 차감액
        </p>
      </section>
      <div className="payout-policy-note payout-current-recovery">
        <span>현재 미회수 환불액</span>
        <strong>
          <FinanceMoney value={data.outstandingRecoveryAmount} />
        </strong>
        <small>조회 기간과 별개로, 현재 남아 있는 환불 회수 잔액</small>
      </div>
    </>
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
