import { DownOutlined, ReloadOutlined } from '@ant-design/icons'
import { Button, Skeleton } from 'antd'
import { Link } from 'react-router-dom'
import type { MerchantCaseDetail, MerchantCaseRow } from '../../types/merchantCase'
import { won } from '../../utils/format'
import { dateTime } from '../security/case/caseLabels'
import {
  caseConclusion,
  caseStatus,
  conclusionSentence,
  paymentOutcome,
  resultSummary,
} from './merchantCaseProgress'
import { StatusMark } from './StatusMark'
import type { StatusTone } from './statusTone'
import type { CaseDetailState } from './useMerchantCaseDetails'

const resultClass = (tone: StatusTone) =>
  tone === 'progress' || tone === 'pending' ? 'is-live' : tone === 'success' ? '' : 'is-off'

/** 펼친 내용 — 진행 단계 한 줄 · 결론 · 결제별 금액과 결과 · 처리 안내. 응답에 없는 시각은 만들지 않는다 */
function Detail({
  detail,
  settlementLink,
}: {
  detail: MerchantCaseDetail
  settlementLink: boolean
}) {
  const decided = detail.payments.filter((p) => p.decision !== null)
  const accept = decided.filter((p) => p.decision === 'ACCEPT').length
  const reject = decided.length - accept
  const sentence = conclusionSentence(caseConclusion(detail.payments))
  const steps = [
    { key: 'held', when: dateTime(detail.createdAt), text: '결제가 잠시 멈춤', now: false },
    ...(decided.length > 0
      ? [
          {
            key: 'decided',
            when: '담당자 결정',
            text: [accept > 0 && `정상 ${accept}건`, reject > 0 && `취소 ${reject}건`]
              .filter(Boolean)
              .join(' · '),
            now: false,
          },
        ]
      : []),
    detail.displayStatus === 'CLOSED'
      ? {
          key: 'closed',
          when: detail.closedAt ? dateTime(detail.closedAt) : '끝남',
          text: '확인 끝',
          now: false,
        }
      : {
          key: 'now',
          when: '지금',
          text: detail.displayStatus === 'INVESTIGATING' ? '담당자가 확인 중' : '결제에 반영 중',
          now: true,
        },
  ]
  return (
    <div className="mct-detail">
      <ol className="mct-steps" aria-label="진행">
        {steps.map((s) => (
          <li key={s.key} className={s.now ? 'is-now' : undefined}>
            <span className="mct-when">{s.when}</span>
            <span className="mct-what">{s.text}</span>
          </li>
        ))}
      </ol>
      <p className="mct-note">
        {detail.displayStatus === 'INVESTIGATING'
          ? '끝날 때까지 이 돈은 정산되지 않아요. 사장님이 하실 일은 없어요.'
          : sentence}
      </p>
      {detail.payments.length > 0 ? (
        <table className="mct-payments">
          <thead>
            <tr>
              <th scope="col">금액</th>
              <th scope="col">결제</th>
              <th scope="col">결제 시각</th>
              <th scope="col">결과</th>
            </tr>
          </thead>
          <tbody>
            {detail.payments.map((p) => {
              const outcome = paymentOutcome(p)
              const off = outcome.tone === 'danger' || outcome.label === '고객 취소'
              return (
                <tr key={p.paymentId}>
                  <td className={`mct-money${off ? ' is-struck' : ''}`}>
                    {p.amount === null ? '확인 불가' : won(p.amount)}
                  </td>
                  <td>{p.paymentId}</td>
                  <td>{p.requestedAt ? dateTime(p.requestedAt) : '-'}</td>
                  <td>
                    <span className={`mct-outcome ${resultClass(outcome.tone)}`}>
                      {outcome.label}
                    </span>
                    {/* 매입이 끝난 결제는 정산 내역에서 예정일 · 금액을 본다 (merchant-guide). 정산 권한이 있을 때만 */}
                    {settlementLink && outcome.label === '매입 완료' && (
                      <Link className="mct-pay-link" to="/owner/settlements">
                        정산 내역 보기
                      </Link>
                    )}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      ) : (
        <p className="mct-note">연관 결제가 없습니다.</p>
      )}
      {detail.summary?.length ? (
        <ul className="mct-guide">
          {detail.summary.map((line, i) => (
            <li key={i}>{line}</li>
          ))}
        </ul>
      ) : null}
    </div>
  )
}

function Body({
  state,
  onRetry,
  settlementLink,
}: {
  state: CaseDetailState
  onRetry: () => void
  settlementLink: boolean
}) {
  if (state.status === 'loading' && !state.detail)
    return (
      <div className="mct-detail" role="status" aria-label="사건 내용을 불러오는 중">
        <Skeleton active paragraph={{ rows: 2 }} title={false} />
      </div>
    )
  if (state.status === 'forbidden')
    return <p className="mct-detail mct-note">이 사건을 볼 권한이 없어요.</p>
  if (state.status === 'missing')
    return <p className="mct-detail mct-note">이 사건을 찾을 수 없어요.</p>
  if (!state.detail)
    return (
      <div className="mct-detail mct-failed" role="status">
        <span>사건 내용을 불러오지 못했어요.</span>
        <Button size="small" icon={<ReloadOutlined />} onClick={onRetry}>
          다시 시도
        </Button>
      </div>
    )
  return (
    <div className={state.stale ? 'is-stale' : undefined} aria-busy={state.stale && !state.failed}>
      {state.stale && !state.failed && (
        <p className="mct-detail mct-note" role="status">
          최신 내용을 불러오고 있어요. 아래는 이전에 조회한 내용이에요.
        </p>
      )}
      {state.failed && (
        <div className="mct-detail mct-failed" role="status">
          <span>최신 내용을 불러오지 못했어요. 아래는 마지막으로 불러온 내용이에요.</span>
          <Button size="small" icon={<ReloadOutlined />} onClick={onRetry}>
            다시 시도
          </Button>
        </div>
      )}
      <Detail detail={state.detail} settlementLink={settlementLink} />
    </div>
  )
}

/**
 * 사건 한 줄 — 목록 머리와 같은 열(상태 · 발생 · 결제 · 금액 · 결과)에 맞춰 위아래로 비교된다.
 * 사건 번호(내부 순번)는 보이지 않는다. 결제 번호는 쇼핑몰이 결제 API로 이미 받는 번호라 펼친 표에 보인다.
 * 금액 · 결과는 상세를 불러온 사건만 채운다(진행 중 사건 · 한 번 펼친 사건). 나머지는 "—"로 둔다.
 * 펼치면 아래로 부드럽게 열린다. row가 없으면(목록에 없는 번호로 들어온 경우) 상세 응답만으로 그린다.
 */
export function MerchantCaseRowItem({
  caseId,
  row,
  state,
  open,
  onToggle,
  onRetry,
  isNew,
  settlementLink,
}: {
  caseId: string
  row: MerchantCaseRow | null
  state: CaseDetailState
  open: boolean
  onToggle: () => void
  onRetry: () => void
  /** 24시간 안에 생긴 사건 */
  isNew: boolean
  /** 정산 권한이 있으면 매입 완료 결제에 정산 내역 링크를 단다 */
  settlementLink: boolean
}) {
  const detail = state.status === 'ready' && !state.stale && !state.failed ? state.detail : null
  const status = row?.displayStatus ?? detail?.displayStatus
  const createdAt = row?.createdAt ?? detail?.createdAt
  const paymentCount = row?.paymentCount ?? detail?.payments.length
  const conclusion =
    detail &&
    (!row ||
      (detail.displayStatus === row.displayStatus && detail.payments.length === row.paymentCount))
      ? caseConclusion(detail.payments)
      : null
  const bodyId = `case-${caseId}-body`
  return (
    <div className={`mct-row${open ? ' is-open' : ''}`}>
      <button
        type="button"
        className="mct-cols mct-row-head"
        aria-expanded={open}
        aria-controls={bodyId}
        onClick={onToggle}
      >
        <span className="mct-status">
          {status && (
            <>
              <StatusMark tone={caseStatus[status].tone} size={15} />
              {caseStatus[status].word}
            </>
          )}
          {isNew && <span className="mct-new">새로</span>}
        </span>
        <span className="mct-date">{createdAt ? dateTime(createdAt) : ''}</span>
        <span className="mct-num">{paymentCount !== undefined ? `${paymentCount}건` : ''}</span>
        <span className="mct-money">{conclusion ? won(conclusion.totalAmount) : '—'}</span>
        <span className="mct-result">{conclusion ? resultSummary(conclusion) : '—'}</span>
        <DownOutlined className="mct-chevron" aria-hidden="true" />
      </button>
      {open && (
        <div id={bodyId} className="mct-row-body">
          <div className="mct-row-inner">
            <Body state={state} onRetry={onRetry} settlementLink={settlementLink} />
          </div>
        </div>
      )}
    </div>
  )
}
