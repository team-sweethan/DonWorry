import { Alert, Tag } from 'antd'
import dayjs from 'dayjs'
import { useEffect, useState } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'
import {
  getLowRiskOperatorAccessDetection,
  getOperatorAccessDetection,
  reviewOperatorAccessDetection,
  type OperatorAccessDetectionDetail,
} from '../../api/fds'
import { refreshOperatorAccessBadge } from '../../hooks/useOperatorAccessBadge'
import { getMerchantDirectory } from '../../api/merchants'
import { ListEmpty } from '../../components/common/ListEmpty'
import { ListLoading } from '../../components/common/ListLoading'
import { PageHeader } from '../../components/common/PageHeader'
import { merchantDisplayName } from '../../utils/merchantDisplayName'
import { displayRuleCode } from '../../utils/ruleDisplayCode'
import {
  actionView,
  featureName,
  spanLabel,
  targetedFeatures,
  windowLabel,
} from './operatorAccessView'
import './caseAssignmentShared.css'
import './OperatorAccessDetectionDetailPage.css'

function formatDate(value: string | null) {
  return value
    ? new Intl.DateTimeFormat('ko-KR', { dateStyle: 'short', timeStyle: 'medium' }).format(
        new Date(value),
      )
    : '—'
}

/** 근거 기록 시각 — 같은 초에 여러 번 막히는 일이 많아 밀리초까지 */
function formatTime(value: string) {
  return dayjs(value).format('MM.DD HH:mm:ss.SSS')
}

function ruleLabel(ruleCode: string) {
  return ruleCode === 'SEC_OPERATOR_OUT_OF_ROLE_REPEAT'
    ? '역할 범위 밖 API 반복 호출'
    : ruleCode === 'SEC_OPERATOR_PERMISSION_MISSING_REPEAT'
      ? '권한 부족 API 반복 호출'
      : ruleCode
}

function deniedTypeLabel(deniedType: string) {
  return deniedType === 'OUT_OF_ROLE'
    ? '역할 범위 밖 접근'
    : deniedType === 'PERMISSION_MISSING'
      ? '필요 권한 없음'
      : deniedType
}

export function OperatorAccessDetectionDetailPage() {
  const { id, eventId } = useParams()
  const navigate = useNavigate()
  const location = useLocation()
  const [result, setResult] = useState<OperatorAccessDetectionDetail | null>(null)
  const [merchantName, setMerchantName] = useState<string | null>(null)
  const [error, setError] = useState(false)
  const sourceId = Number(eventId ?? id)
  const isLowRisk = eventId !== undefined
  const invalidId = !Number.isSafeInteger(sourceId) || sourceId < 1
  const returnPath =
    (location.state as { operatorAccessDetectionListReturnPath?: string } | null)
      ?.operatorAccessDetectionListReturnPath ?? '/admin/fds/operator-access-detections'

  useEffect(() => {
    if (invalidId) return
    let active = true
    ;(isLowRisk
      ? getLowRiskOperatorAccessDetection(sourceId)
      : getOperatorAccessDetection(sourceId)
    )
      .then((detail) => {
        if (!active) return
        const merchantId = detail.merchantId
        setError(false)
        setMerchantName(null)
        setResult(detail)
        if (!isLowRisk) {
          void reviewOperatorAccessDetection(sourceId)
            .then(refreshOperatorAccessBadge)
            .catch(() => undefined)
        }
        if (merchantId === null) return
        void getMerchantDirectory()
          .then((merchants) => {
            if (active) setMerchantName(merchantDisplayName(merchantId, merchants))
          })
          .catch(() => {
            if (active) setMerchantName(null)
          })
      })
      .catch(() => {
        if (active) setError(true)
      })
    return () => {
      active = false
    }
  }, [sourceId, invalidId, isLowRisk])

  const evidences = result?.evidences ?? []
  const first = evidences[0]
  const last = evidences[evidences.length - 1]
  const reached = result !== null && result.eventCount >= result.thresholdCount
  const action = result ? actionView(result.actionStatus) : null
  const features = targetedFeatures(evidences)
  return (
    <>
      <button type="button" className="oadd-back" onClick={() => navigate(returnPath)}>
        ← 목록으로
      </button>
      <PageHeader
        title={result?.riskLevel === 'LOW' ? '저위험 접근 이상 상세' : '계정 접근 이상 상세'}
        description="기준을 넘은 시도는 계정을 자동으로 정지했어요. 무엇을 몇 번 시도했는지 시간순으로 확인하세요."
      />
      {(error || invalidId) && (
        <Alert type="error" showIcon message="탐지 상세를 불러오지 못했습니다." />
      )}
      {!result && !error && !invalidId && (
        <ListLoading label="탐지 상세를 불러오는 중" rows={4} columns={2} />
      )}
      {result && action && (
        <div className="oadd-stack">
          <section className="ca-panel-box" aria-label="판정 및 조치">
            <header className="oadd-head">
              <div className="oadd-head-main">
                <h2 className="oadd-title">
                  <Tag color={result.riskLevel === 'HIGH' ? 'red' : 'gold'}>
                    {result.riskLevel === 'HIGH' ? '고위험' : '저위험'}
                  </Tag>
                  {`${displayRuleCode(result.ruleCode)} ${ruleLabel(result.ruleCode)}`}
                </h2>
                <p className="oadd-sub">
                  <strong>{result.loginId}</strong>
                  <span>
                    {merchantName ??
                      (result.merchantId !== null ? `고객사 #${result.merchantId}` : '—')}
                  </span>
                  <span>{deniedTypeLabel(result.deniedType)}</span>
                </p>
              </div>
              <span className="oadd-head-action">
                <Tag color={action.color}>{action.label}</Tag>
                <span className="dw-caption">
                  {result.riskLevel === 'LOW'
                    ? '기준 미도달 — 계정 상태 유지'
                    : result.actionCompletedAt
                      ? formatDate(result.actionCompletedAt)
                      : result.failureCode
                        ? `실패 코드 ${result.failureCode}`
                        : ''}
                </span>
                <span className="oadd-links">
                  <Link to={`/admin/users/${result.userId}`}>계정 관리에서 보기 →</Link>
                  {result.merchantId !== null && (
                    <Link to={`/admin/merchants/${result.merchantId}`}>가맹점 보기 →</Link>
                  )}
                </span>
              </span>
            </header>
            <div className="oadd-facts">
              <div className="oadd-fact">
                <span className="oadd-fact-label">발생 / 기준</span>
                <span className="oadd-fact-number">
                  {result.eventCount}
                  <small>{`/ ${result.thresholdCount}회`}</small>
                </span>
                <span
                  className={`oadd-meter${reached ? ' is-reached' : ''}`}
                  role="meter"
                  aria-valuemin={0}
                  aria-valuemax={result.thresholdCount}
                  aria-valuenow={result.eventCount}
                  aria-label={`기준 ${result.thresholdCount}회 중 ${result.eventCount}회`}
                >
                  <span
                    style={{
                      width: `${Math.min(result.eventCount / Math.max(result.thresholdCount, 1), 1) * 100}%`,
                    }}
                  />
                </span>
                <span className="dw-caption">{`${windowLabel(result.windowSeconds)} 안에 ${result.thresholdCount}회면 고위험`}</span>
              </div>
              <div className="oadd-fact">
                <span className="oadd-fact-label">걸린 시간</span>
                <span className="oadd-fact-number">
                  {first && last ? spanLabel(first.occurredAt, last.occurredAt) : '—'}
                </span>
                <span className="dw-caption">
                  {first && last
                    ? `${formatTime(first.occurredAt)} – ${formatTime(last.occurredAt)}`
                    : '근거 요청 없음'}
                </span>
              </div>
              <div className="oadd-fact">
                <span className="oadd-fact-label">노린 기능</span>
                <span className="oadd-fact-number">
                  {features.length}
                  <small>개</small>
                </span>
                <span className="dw-caption">
                  {features.length === 0
                    ? '근거 요청 없음'
                    : features.length === 1
                      ? `${features[0]} 한 기능만 반복했어요`
                      : `여러 기능을 돌아가며 시도했어요 · ${features.slice(0, 3).join(', ')}${features.length > 3 ? ` 외 ${features.length - 3}개` : ''}`}
                </span>
              </div>
            </div>
          </section>

          <section className="ca-panel-box" aria-label="근거 기록">
            <header className="oadd-section-head">
              <h2>{result.riskLevel === 'LOW' ? '확인된 접근 기록' : '판정 근거'}</h2>
              <span className="dw-caption">{`${evidences.length}건 · 시간순`}</span>
            </header>
            {evidences.length === 0 ? (
              <ListEmpty title="근거 기록이 없어요." />
            ) : (
              <ol className="oadd-log">
                {evidences.map((row, index) => (
                  <li key={row.identityEventId} className="oadd-log-row">
                    <time className="oadd-log-time" dateTime={row.occurredAt}>
                      {formatTime(row.occurredAt)}
                    </time>
                    <span className="oadd-log-text">
                      <strong>{`${index + 1}번째 거부`}</strong>
                      {featureName(row.path) && <span>{featureName(row.path)}</span>}
                      <code className="oadd-path">{`${row.httpMethod} ${row.path}`}</code>
                      {result.riskLevel === 'HIGH' && index + 1 === result.thresholdCount && (
                        <Tag color="red">기준 도달</Tag>
                      )}
                    </span>
                    <span className="oadd-log-id">{`원본 #${row.identityEventId}`}</span>
                  </li>
                ))}
                {result.riskLevel === 'HIGH' && (
                  <li className="oadd-log-row is-result">
                    <time
                      className="oadd-log-time"
                      dateTime={result.actionCompletedAt ?? undefined}
                    >
                      {result.actionCompletedAt ? formatTime(result.actionCompletedAt) : '—'}
                    </time>
                    <span className="oadd-log-text">
                      <Tag color={action.color}>{action.label}</Tag>
                      {result.failureCode && (
                        <span className="dw-caption">{result.failureCode}</span>
                      )}
                    </span>
                    <span />
                  </li>
                )}
              </ol>
            )}
          </section>
        </div>
      )}
    </>
  )
}
