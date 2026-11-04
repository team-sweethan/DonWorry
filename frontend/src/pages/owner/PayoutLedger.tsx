import { useState } from 'react'
import { Button, Empty, Modal, Pagination, Tag } from 'antd'
import type { SettlementReport } from '../../api/finance'
import { koreanTime, money } from './financeView'
import './payout-ledger.css'

export type PayoutSummaryData = Pick<
  SettlementReport,
  | 'paidSettlementAmount'
  | 'refundDeductionAmount'
  | 'paidAmount'
  | 'outstandingRecoveryAmount'
  | 'payouts'
>
export function PayoutSummary({ data }: { data: PayoutSummaryData }) {
  return (
    <div className="payout-summary-grid" aria-label="지급·환불 차감 요약">
      {[
        ['실제 지급액', data.paidAmount, '환불 회수를 차감한 입금 간주 금액', 'paid'],
        [
          '지급 대상 정산금',
          data.paidSettlementAmount,
          '수수료를 뺀 금액 · 환불 회수 전',
          'original',
        ],
        [
          '환불 회수 차감액',
          data.refundDeductionAmount,
          '이번 조회 기간의 지급에서 차감',
          'deduction',
        ],
        [
          '앞으로 회수할 금액',
          data.outstandingRecoveryAmount,
          '환불로 다음 정산에서 차감될 잔액 · 현재 기준',
          'remaining',
        ],
      ].map(([label, value, note, variant]) => (
        <section key={String(label)} className={`payout-stat payout-stat-${variant}`}>
          <span>{label}</span>
          <strong>{money(Number(value))}</strong>
          <small>{note}</small>
        </section>
      ))}
    </div>
  )
}
export function PayoutLedger({ payouts }: { payouts: SettlementReport['payouts'] }) {
  const [page, setPage] = useState(1)
  const [detail, setDetail] = useState<SettlementReport['payouts'][number] | null>(null)
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
                  <th className="stl-money-cell">지급 대상 정산금</th>
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
                    <td className="stl-money-cell">{money(p.settlementAmount)}</td>
                    <td className="stl-money-cell payout-deduction">
                      {p.refundDeductionAmount ? '−' : ''}
                      {money(p.refundDeductionAmount)}
                    </td>
                    <td className="stl-money-cell">
                      <strong className="payout-actual">{money(p.payoutAmount)}</strong>
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
        open={detail !== null}
        onCancel={() => setDetail(null)}
        footer={<Button onClick={() => setDetail(null)}>닫기</Button>}
      >
        {detail && (
          <div className="payout-statement">
            <Tag color={detail.origin === 'LEGACY_BACKFILL' ? 'blue' : 'green'}>
              {detail.origin === 'LEGACY_BACKFILL' ? '과거 정산 이관 기록' : '지급 확정'}
            </Tag>
            <h2>
              {detail.payoutDate} 지급 #{detail.payoutId}
            </h2>
            <p>
              {koreanTime(detail.completedAt)}{' '}
              {detail.origin === 'LEGACY_BACKFILL' ? '기록 기준' : '확정'}
            </p>
            <dl>
              <dt>지급 대상 정산금</dt>
              <dd>{money(detail.settlementAmount)}</dd>
              <dt>환불 회수 차감</dt>
              <dd className="payout-deduction">−{money(detail.refundDeductionAmount)}</dd>
              <dt>실제 지급액</dt>
              <dd className="payout-actual">{money(detail.payoutAmount)}</dd>
            </dl>
            <p>
              {detail.payoutAmount === 0
                ? detail.refundDeductionAmount > 0
                  ? '이번 정산금이 환불 회수에 모두 반영되어 입금으로 간주하는 금액은 0원입니다.'
                  : '지급 대상 순정산금이 0원이므로 실제 지급액도 0원입니다.'
                : '환불 회수 후 이 금액이 입금된 것으로 간주합니다.'}{' '}
              이미 확정된 과거 지급 기록은 변경하지 않습니다.
            </p>
            {detail.origin === 'LEGACY_BACKFILL' && (
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
