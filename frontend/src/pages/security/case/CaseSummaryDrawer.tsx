import { useEffect, useState } from 'react'
import { ArrowRightOutlined, LeftOutlined, RightOutlined } from '@ant-design/icons'
import { Alert, Button, Drawer, Skeleton, Space } from 'antd'
import { getCase, isCaseAssigneeOnly } from '../../../api/cases'
import type { CaseDetail } from '../../../types/case'
import { caseDecisionMode, customerPart, latestReport } from '../../../utils/caseRules'
import { won } from '../../../utils/format'
import { handoffErrorLabel } from '../../../utils/handoffErrorText'
import {
  caseStatusLabel,
  dateTime,
  decisionLabel,
  fdsResultLabel,
  paymentStatusLabel,
  reportStatusLabel,
} from './caseLabels'
import { CodeTag } from './CodeTag'

type Loaded = { caseId: string; detail: CaseDetail | null | 'error' | 'forbidden' }

type Props = {
  caseId: string | null
  merchantName: string | undefined
  /** 목록 순서의 앞뒤 사건. 누를 때만 그 사건을 조회한다 */
  prev: string | null
  next: string | null
  onMove: (caseId: string) => void
  onOpen: (caseId: string) => void
  onClose: () => void
}

/**
 * 목록 오른쪽 사건 요약 (#759). 직원이 [요약 보기]를 눌렀을 때만 상세를 한 번 조회한다 —
 * 상세 조회는 열람 기록을 남기므로 줄 선택 · 마우스 올림 · 다음 사건 미리 조회로는 부르지 않는다.
 * 판정은 하지 않는다. 판정 가능 여부는 상세와 같은 규칙(caseDecisionMode)으로 안내만 하고, 서버가 다시 판단한다.
 */
export function CaseSummaryDrawer({
  caseId,
  merchantName,
  prev,
  next,
  onMove,
  onOpen,
  onClose,
}: Props) {
  const [loaded, setLoaded] = useState<Loaded | null>(null)
  useEffect(() => {
    if (!caseId) return
    let alive = true
    getCase(caseId)
      .then((detail) => {
        if (alive) setLoaded({ caseId, detail })
      })
      .catch((error: unknown) => {
        if (alive) setLoaded({ caseId, detail: isCaseAssigneeOnly(error) ? 'forbidden' : 'error' })
      })
    return () => {
      alive = false
    }
  }, [caseId])
  // 다른 사건으로 옮기면 이전 사건 내용을 바로 숨긴다
  const current = loaded?.caseId === caseId ? loaded.detail : undefined

  return (
    <Drawer
      open={caseId !== null}
      onClose={onClose}
      mask={false}
      width={440}
      // 관리 화면 틀 안에 그려 공통 상태 태그 스타일(.admin-viewport[data-role] .ant-tag)을 그대로 쓴다
      getContainer={() => document.querySelector<HTMLElement>('.admin-viewport') ?? document.body}
      className="case-queue-drawer"
      title={caseId ? `Case ${caseId} 요약` : '사건 요약'}
      extra={
        <Space.Compact>
          <Button
            icon={<LeftOutlined />}
            disabled={!prev}
            onClick={() => prev && onMove(prev)}
            aria-label="이전 사건 요약"
            title="이전 사건 요약"
          />
          <Button
            icon={<RightOutlined />}
            disabled={!next}
            onClick={() => next && onMove(next)}
            aria-label="다음 사건 요약"
            title="다음 사건 요약"
          />
        </Space.Compact>
      }
      footer={
        caseId && current !== 'forbidden' ? (
          <div className="case-queue-drawer-foot">
            <span>요약을 열면 상세처럼 열람 기록이 남습니다.</span>
            <Button type="primary" onClick={() => onOpen(caseId)}>
              사건 열기 <ArrowRightOutlined />
            </Button>
          </div>
        ) : null
      }
    >
      {current === undefined ? (
        <Skeleton active paragraph={{ rows: 8 }} />
      ) : current === 'forbidden' ? (
        <Alert
          type="warning"
          showIcon
          message="이 사건의 담당자만 볼 수 있어요"
          description="진행 중인 사건은 담당 보안 직원만 열 수 있어요. 담당이 필요하면 관리자에게 요청하세요."
        />
      ) : current === 'error' ? (
        <Alert
          type="error"
          showIcon
          message="사건 요약을 불러오지 못했습니다. 사건 열기로 상세를 확인하세요."
        />
      ) : current === null ? (
        <Alert type="info" showIcon message="사건을 찾을 수 없습니다." />
      ) : (
        <SummaryBody detail={current} merchantName={merchantName} />
      )}
    </Drawer>
  )
}

function SummaryBody({
  detail,
  merchantName,
}: {
  detail: CaseDetail
  merchantName: string | undefined
}) {
  const { securityCase, payments, reports, decisions, handoffs } = detail
  const closed = securityCase.status === 'CLOSED'
  const mode = caseDecisionMode(securityCase, reports)
  const report = latestReport(reports)
  const summary = report?.status === 'SUCCEEDED' ? (report.reportBody?.summary ?? []) : []
  const failedHandoffs = handoffs.filter((h) => h.status === 'FAILED')
  const decisionByPayment = new Map(decisions.map((d) => [d.casePaymentId, d]))
  const merchant = merchantName
    ? `${merchantName} (ID ${securityCase.merchantId})`
    : `ID ${securityCase.merchantId}`

  return (
    <div className="case-queue-summary">
      <section className="case-queue-summary-head">
        <CodeTag value={caseStatusLabel[securityCase.status]} />
        <p>
          가맹점 {merchant} · 고객 {customerPart(securityCase.customerRef)}
        </p>
        <p>
          생성 {dateTime(securityCase.createdAt)}
          {securityCase.closedAt ? ` · 종료 ${dateTime(securityCase.closedAt)}` : ''}
        </p>
      </section>

      {!closed && (
        <Alert
          type={mode.kind === 'blocked' ? 'warning' : 'info'}
          showIcon
          message={
            mode.kind === 'normal'
              ? `현재 증거 버전의 보고서(v${mode.report.reportVersion})로 판정할 수 있습니다.`
              : mode.kind === 'manual'
                ? '현재 증거 버전 보고서가 3회 실패해 보고서 없이 판정할 수 있습니다.'
                : mode.message
          }
        />
      )}

      {failedHandoffs.length > 0 && (
        <Alert
          type="error"
          showIcon
          message={`금융 처리 실패 ${failedHandoffs.length}건`}
          description={failedHandoffs
            .map((h) =>
              h.lastErrorCode
                ? (handoffErrorLabel[h.lastErrorCode] ?? h.lastErrorCode)
                : '실패 사유 없음',
            )
            .join(' · ')}
        />
      )}

      <section>
        <h4>결제 {payments.length}건</h4>
        <ul className="case-queue-summary-payments">
          {payments.map((p) => {
            const decision = decisionByPayment.get(p.casePaymentId)
            return (
              <li key={p.casePaymentId}>
                <div className="case-queue-summary-row">
                  <strong>{p.amount === null ? '-' : won(p.amount)}</strong>
                  <CodeTag
                    value={p.paymentStatus ? paymentStatusLabel[p.paymentStatus] : undefined}
                  />
                </div>
                <div className="case-queue-summary-row is-sub">
                  <span>
                    FDS {p.riskScore ?? '-'}점{' '}
                    {p.fdsResult && <CodeTag value={fdsResultLabel[p.fdsResult]} />}
                  </span>
                  {decision ? (
                    <CodeTag value={decisionLabel[decision.decision]} />
                  ) : p.customerCanceledAt ? (
                    <span>고객 승인 취소</span>
                  ) : (
                    <span>판정 전</span>
                  )}
                </div>
                {p.ruleHits.length > 0 && (
                  <p className="case-queue-summary-rules">
                    {p.ruleHits
                      .slice(0, 3)
                      .map((hit) => hit.name)
                      .join(' · ')}
                    {p.ruleHits.length > 3 ? ` 외 ${p.ruleHits.length - 3}개` : ''}
                  </p>
                )}
              </li>
            )
          })}
        </ul>
      </section>

      <section>
        <h4>
          보고서{' '}
          {report ? (
            <CodeTag value={reportStatusLabel[report.status]} />
          ) : (
            <span className="case-queue-muted">요청 전</span>
          )}
        </h4>
        {summary.length > 0 ? (
          <ul className="case-queue-summary-lines">
            {summary.slice(0, 3).map((line) => (
              <li key={line}>{line}</li>
            ))}
          </ul>
        ) : (
          <p className="case-queue-muted">
            {report?.status === 'FAILED'
              ? '보고서 생성에 실패했습니다. 판정 가능 여부는 위 안내와 상세에서 확인하세요.'
              : '요약할 보고서 문장이 아직 없습니다.'}
          </p>
        )}
      </section>
    </div>
  )
}
