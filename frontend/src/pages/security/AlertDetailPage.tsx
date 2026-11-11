import { ArrowLeftOutlined } from '@ant-design/icons'
import {
  Alert,
  Button,
  Card,
  Checkbox,
  Descriptions,
  Form,
  Input,
  Modal,
  Spin,
  Tag,
  Typography,
} from 'antd'
import type { CSSProperties } from 'react'
import { useEffect, useState } from 'react'
import { useLocation, useNavigate, useParams } from 'react-router-dom'
import { useAuth } from '../../auth/useAuth'
import { getCaseLinksByPayments } from '../../api/cases'
import { ApiError } from '../../api/client'
import {
  addFdsBlocklistEntryFromEvaluation,
  getFdsEvaluation,
  getFdsRulePolicies,
  type FdsEvaluationDetail,
  type FdsResult,
  type FdsRulePolicy,
} from '../../api/fds'
import { getMerchantDirectory } from '../../api/merchants'
import {
  getPaymentFdsRecovery,
  requestPaymentFdsReevaluation,
  type PaymentFdsRecovery,
} from '../../api/paymentOperations'
import { PageHeader } from '../../components/common/PageHeader'
import { ScreenResult } from '../../components/common/ScreenResult'
import { customerPart } from '../../utils/caseRules'
import { ResultTag } from './alert/AlertTags'
import { scoreToX } from './alert/scoreScale'
import { failureAnalysisResultLabel, failureCauseLabel } from './alert/failureAnalysis'

function formatTime(value: string) {
  return new Intl.DateTimeFormat('ko-KR', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(new Date(value))
}

function formatShortDate(value: string) {
  return new Intl.DateTimeFormat('ko-KR', {
    year: '2-digit',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(new Date(value))
}

function won(value: unknown) {
  return typeof value === 'number' ? `${value.toLocaleString('ko-KR')}원` : '—'
}

const blocklistCandidateLabels: Record<string, string> = {
  BUYER_REF: '구매자 식별자',
  DEVICE_ID: '기기 ID',
  CARD_TOKEN: '카드',
  IP_ADDRESS: 'IP 주소',
  BUYER_EMAIL: '이메일',
}

const recoveryStatusLabels: Record<PaymentFdsRecovery['status'], string> = {
  WAITING_ANALYSIS: '분석 대기',
  READY: '재평가 가능',
  REQUESTED: '재평가 요청됨',
  COMPLETED: '재평가 완료',
  MANUAL_REVIEW: '수동 검토 필요',
}

const recoveryStatusColors: Record<PaymentFdsRecovery['status'], string> = {
  WAITING_ANALYSIS: 'gold',
  READY: 'blue',
  REQUESTED: 'processing',
  COMPLETED: 'green',
  MANUAL_REVIEW: 'red',
}

function snapshot(value: string | null) {
  if (!value) return null
  try {
    const parsed: unknown = JSON.parse(value)
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed)
      ? (parsed as Record<string, unknown>)
      : null
  } catch {
    return null
  }
}

function observedValue(ruleCode: string, rawValue: string | null) {
  const value = snapshot(rawValue)
  if (!value) return '—'
  if (ruleCode === 'HIGH_AMOUNT' || ruleCode === 'R01') return won(value.amount)
  if (ruleCode === 'RAPID_REPEAT_PAYMENT' || ruleCode === 'R04') {
    return typeof value.attemptCount === 'number' ? `${value.attemptCount}회` : '—'
  }
  // 기기 ID는 서버가 가린 값, 카드는 토큰 대신 표시값만 준다 (#628)
  if (ruleCode === 'R02') {
    return typeof value.maskedDeviceId === 'string' ? value.maskedDeviceId : '—'
  }
  if (ruleCode === 'R03') {
    return typeof value.cardDisplayLabel === 'string' ? value.cardDisplayLabel : '—'
  }
  if (ruleCode === 'R05') return typeof value.ipCountryCode === 'string' ? value.ipCountryCode : '—'
  if (ruleCode === 'R06') {
    return typeof value.smallAttemptCount === 'number'
      ? `소액 ${value.smallAttemptCount}회 · 카드 ${value.cardCount}개 · 거절 ${value.declinedCount}회`
      : '—'
  }
  if (ruleCode === 'R07') {
    const identifierTypes = [
      'BUYER_REF',
      'DEVICE_ID',
      'CARD_TOKEN',
      'IP_ADDRESS',
      'BUYER_EMAIL',
    ] as const
    const matchedTypes = Array.isArray(value.matchedIdentifierTypes)
      ? value.matchedIdentifierTypes.filter((type): type is string => typeof type === 'string')
      : []
    const labelByType: Record<string, string> = {
      BUYER_REF: '구매자',
      DEVICE_ID: '기기',
      CARD_TOKEN: '카드',
      IP_ADDRESS: 'IP',
      BUYER_EMAIL: '이메일',
    }
    const valueByType: Record<string, unknown> = {
      BUYER_REF: value.buyerRef,
      // IP · 기기 ID는 서버가 가린 값만 준다 (#628)
      DEVICE_ID: value.maskedDeviceId,
      // 카드 토큰은 화면에 노출하지 않고, P5가 준 마스킹 표시값만 쓴다.
      CARD_TOKEN: value.cardDisplayLabel,
      IP_ADDRESS: value.maskedIpAddress,
      // 이메일은 서버가 가린 값만 준다 (#603)
      BUYER_EMAIL: value.maskedBuyerEmail,
    }
    const candidates = identifierTypes.flatMap((type) => {
      const matchedValue = valueByType[type]
      return typeof matchedValue === 'string' && matchedValue.trim()
        ? [{ type, label: labelByType[type], value: matchedValue }]
        : []
    })
    if (!candidates.length) return '—'
    return (
      <div className="dw-alert-blocklist-observed">
        {matchedTypes.length > 0 && (
          <span className="dw-alert-blocklist-match-label">일치한 후보</span>
        )}
        <div className="dw-alert-blocklist-candidates">
          {candidates.map((candidate) => (
            <span
              className={
                matchedTypes.includes(candidate.type)
                  ? 'dw-alert-blocklist-candidate dw-alert-blocklist-candidate--matched'
                  : 'dw-alert-blocklist-candidate'
              }
              key={candidate.type}
            >
              <strong>{candidate.label}</strong>
              <span>{candidate.value}</span>
            </span>
          ))}
        </div>
      </div>
    )
  }
  if (ruleCode === 'R08')
    return typeof value.declinedCount === 'number' ? `${value.declinedCount}회` : '—'
  return '—'
}

function threshold(ruleCode: string, rawValue: string | null) {
  const value = snapshot(rawValue)
  if (!value) return '—'
  // 저장값에 기대한 숫자가 없으면 문장을 만들지 않는다 ("undefined회" 같은 표시 방지)
  if (ruleCode === 'HIGH_AMOUNT' || ruleCode === 'R01') {
    return typeof value.amountGte === 'number' ? `${won(value.amountGte)} 이상` : '—'
  }
  if (ruleCode === 'RAPID_REPEAT_PAYMENT' || ruleCode === 'R04') {
    return typeof value.windowMinutes === 'number' && typeof value.attemptCountGte === 'number'
      ? `${value.windowMinutes}분 내 ${value.attemptCountGte}회 이상`
      : '—'
  }
  if (ruleCode === 'R02' || ruleCode === 'R03') return '이전 정상 승인 이력에 없음'
  if (ruleCode === 'R05') return '고위험 국가 목록 일치'
  if (ruleCode === 'R06') return '10분 내 소액 5회 · 카드 3개 · 거절 3회 이상'
  if (ruleCode === 'R07') return '활성 차단 목록과 일치'
  if (ruleCode === 'R08') return '10분 내 이전 거절 2회 이상'
  return '—'
}

const resultTitles: Record<FdsResult, string> = {
  ALLOW: '결제 통과',
  REVIEW: '추가 조사',
  DENY: '결제 차단',
  ERROR: '평가 실패',
}

const resultSentences: Record<FdsResult, string> = {
  ALLOW: '50점 미만이라 결제를 통과시켰습니다.',
  REVIEW:
    '50~79점이라 추가 조사 대상입니다. 카드 승인에 성공하면 결제를 보류(HELD)하고 사건이 됩니다.',
  DENY: '80점 이상이라 카드 승인 요청을 보내지 않았습니다.',
  ERROR: '평가에 실패해 점수가 없습니다. 위험 판정이 아니므로 자동 ALLOW 하지 않습니다.',
}

function prefersReducedMotion() {
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

/** 0에서 target까지 숫자를 올린다. playKey가 바뀌면 다시 올린다. 동작 줄이기면 바로 target */
function useCountUp(target: number, durationMs: number, playKey: string) {
  const [value, setValue] = useState(0)
  useEffect(() => {
    if (prefersReducedMotion()) {
      const frame = requestAnimationFrame(() => setValue(target))
      return () => cancelAnimationFrame(frame)
    }
    let frame = 0
    const start = performance.now()
    const step = (time: number) => {
      const progress = Math.min(1, (time - start) / durationMs)
      setValue(Math.round(target * (1 - Math.pow(1 - progress, 3))))
      if (progress < 1) frame = requestAnimationFrame(step)
    }
    frame = requestAnimationFrame(step)
    return () => cancelAnimationFrame(frame)
  }, [target, durationMs, playKey])
  return value
}

function elapsedLabel(from: string, to: string) {
  const ms = new Date(to).getTime() - new Date(from).getTime()
  // 0ms는 두 시각이 같게 저장된 경우라 걸린 시간으로 보여 주지 않는다
  if (!Number.isFinite(ms) || ms <= 0 || ms >= 60_000) return null
  return ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(1)}초`
}

// 걸린 신호를 판정 색의 짙은 순서로 칠한다. 앞의 두 칸은 흰 글씨, 나머지는 어두운 글씨
// 걸린 신호를 판정 신호색의 밝기 순서로 칠한다 (남색 화면 위, 글자는 어둡게)
const segmentShades: Record<FdsResult, string[]> = {
  ALLOW: ['#b3cbef', '#93b2e3', '#cddcf4', '#e1eaf8'],
  REVIEW: ['#ffcc4d', '#f2b632', '#ffdc85', '#ffe9b3'],
  DENY: ['#ff8da1', '#f76d86', '#ffb3c0', '#ffd0d8'],
  ERROR: ['#c8b8ff', '#ad99f5', '#ddd3ff', '#ebe5ff'],
}
const SEGMENT_STEP_MS = 600

export function AlertDetailPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const location = useLocation()
  const { user } = useAuth()
  const [detail, setDetail] = useState<FdsEvaluationDetail | null>(null)
  const [merchantName, setMerchantName] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [notFound, setNotFound] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [policies, setPolicies] = useState<FdsRulePolicy[]>([])
  const [policyError, setPolicyError] = useState(false)
  const [blocklistOpen, setBlocklistOpen] = useState(false)
  const [blocklistSubmitting, setBlocklistSubmitting] = useState(false)
  const [blocklistError, setBlocklistError] = useState<string | null>(null)
  const [blockedCandidates, setBlockedCandidates] = useState<Set<string>>(new Set())
  const [selectedCandidates, setSelectedCandidates] = useState<string[]>([])
  const [caseIdsByPaymentId, setCaseIdsByPaymentId] = useState<Map<string, string>>(new Map())
  const [recovery, setRecovery] = useState<PaymentFdsRecovery | null>(null)
  const [recoveryError, setRecoveryError] = useState(false)
  const [reevaluationOpen, setReevaluationOpen] = useState(false)
  const [reevaluationSubmitting, setReevaluationSubmitting] = useState(false)
  const [reevaluationError, setReevaluationError] = useState<string | null>(null)
  const [blocklistForm] = Form.useForm<{ reason: string }>()
  const [scorePlay, setScorePlay] = useState(0)
  // 훅은 이른 반환보다 앞에서 부른다
  const matchedHits = (detail?.ruleHits ?? []).filter(
    (rule) => rule.outcome === 'MATCH' && rule.appliedScore > 0,
  )
  const stackDurationMs = 300 + Math.max(1, matchedHits.length) * SEGMENT_STEP_MS
  const shownScore = useCountUp(
    detail?.evaluation.riskScore ?? 0,
    stackDurationMs,
    `${detail?.evaluation.evaluationId ?? ''}-${scorePlay}`,
  )
  const initialDetailLoading = loading && detail === null

  useEffect(() => {
    if (!id) return
    let active = true
    const loadDetail = async () => {
      setLoading(true)
      setNotFound(false)
      setError(null)
      setPolicyError(false)
      setPolicies([])
      setMerchantName(null)
      setCaseIdsByPaymentId(new Map())
      setRecovery(null)
      setRecoveryError(false)
      try {
        const response = await getFdsEvaluation(id)
        let nextRecovery: PaymentFdsRecovery | null = null
        let nextRecoveryError = false
        if (response.evaluation.result === 'ERROR') {
          try {
            nextRecovery = await getPaymentFdsRecovery(response.evaluation.paymentId)
          } catch {
            nextRecoveryError = true
          }
        }
        if (active) {
          // ERROR 경보의 P5 복구 상태는 카드 구조와 실행 버튼을 결정한다. 상세를 먼저
          // 그렸다가 비동기 응답으로 바꾸지 않도록 두 응답을 모두 받은 뒤 표시한다.
          setRecovery(nextRecovery)
          setRecoveryError(nextRecoveryError)
          setDetail(response)
          void getMerchantDirectory()
            .then((merchants) => {
              if (!active) return
              setMerchantName(
                merchants.find((merchant) => merchant.merchantId === response.evaluation.merchantId)
                  ?.displayName ?? null,
              )
            })
            .catch(() => {
              if (active) setMerchantName(null)
            })
          // 차단 여부는 서버가 원문으로 대조해 상세 응답에 담아 준다 (#603)
          setBlockedCandidates(
            new Set(
              response.blocklistCandidates
                .filter((candidate) => candidate.blocked)
                .map((candidate) => candidate.identifierType),
            ),
          )
          void getFdsRulePolicies()
            .then((next) => {
              if (active) {
                setPolicies(next)
                setPolicyError(false)
              }
            })
            .catch(() => {
              if (active) setPolicyError(true)
            })
          const paymentIds = [
            ...new Set(
              [response.evaluation, ...response.history].map((evaluation) =>
                String(evaluation.paymentId),
              ),
            ),
          ]
          void getCaseLinksByPayments(paymentIds)
            .then((links) => {
              if (active)
                setCaseIdsByPaymentId(new Map(links.map((link) => [link.paymentId, link.caseId])))
            })
            .catch(() => {
              if (active) setCaseIdsByPaymentId(new Map())
            })
        }
      } catch (requestError) {
        if (!active) return
        if (requestError instanceof ApiError && requestError.status === 404) setNotFound(true)
        else setError('경보 상세를 불러오지 못했습니다.')
      } finally {
        if (active) setLoading(false)
      }
    }
    void loadDetail()
    return () => {
      active = false
    }
  }, [id])

  if (initialDetailLoading) {
    // 빠른 응답에는 스피너가 보이지 않고 늦을 때만 서서히 나타난다 (loadingTransition.css)
    return (
      <div className="dw-alert-detail-loading">
        <Spin />
      </div>
    )
  }
  if (notFound) return <ScreenResult kind="404" />
  if (!detail || error)
    return <Alert type="error" showIcon message={error ?? '경보 상세가 없습니다.'} />

  const evaluation = detail.evaluation
  const merchantLabel = merchantName ?? `ID ${evaluation.merchantId}`
  const policyByRuleCode = new Map(policies.map((policy) => [policy.ruleCode, policy]))
  const score = evaluation.riskScore
  const isSecurityStaff = user?.role === 'SECURITY_STAFF'
  const caseId = caseIdsByPaymentId.get(String(evaluation.paymentId))
  const alertListReturnPath =
    typeof location.state?.alertListReturnPath === 'string'
      ? location.state.alertListReturnPath
      : null
  const canRunReevaluation =
    isSecurityStaff && (recovery?.status === 'READY' || recovery?.status === 'REQUESTED')
  const resumingReevaluation = recovery?.status === 'REQUESTED'
  const resultDescription =
    score === null
      ? `오류 코드 ${evaluation.errorCode ?? '—'}`
      : `${score}점 · ${evaluation.result} 판정`
  // 한 평가에는 종류마다 후보가 하나뿐이라 종류로 구분한다 (원문은 화면에 없음, #603)
  const candidateKey = (candidate: FdsEvaluationDetail['blocklistCandidates'][number]) =>
    candidate.identifierType
  const closeBlocklistModal = () => {
    if (blocklistSubmitting) return
    setBlocklistOpen(false)
    setBlocklistError(null)
    setSelectedCandidates([])
    blocklistForm.resetFields()
  }
  const openReevaluationModal = () => {
    setReevaluationError(null)
    setReevaluationOpen(true)
  }
  const openAlertDetail = (evaluationId: number) =>
    navigate(`/security/alerts/${evaluationId}`, {
      state: alertListReturnPath ? { alertListReturnPath } : undefined,
    })
  const submitBlocklist = async () => {
    const { reason } = await blocklistForm.validateFields()
    const candidates = detail.blocklistCandidates.filter(
      (candidate) =>
        selectedCandidates.includes(candidateKey(candidate)) &&
        !blockedCandidates.has(candidateKey(candidate)),
    )
    if (candidates.length === 0) return
    setBlocklistSubmitting(true)
    try {
      const results = await Promise.allSettled(
        candidates.map((candidate) =>
          addFdsBlocklistEntryFromEvaluation(evaluation.evaluationId, {
            identifierType: candidate.identifierType,
            reason: reason.trim(),
          }),
        ),
      )
      const succeeded = candidates.filter((_, index) => results[index].status === 'fulfilled')
      const failed = candidates.filter((_, index) => results[index].status === 'rejected')
      if (succeeded.length > 0) {
        setBlockedCandidates(
          (current) =>
            new Set([...current, ...succeeded.map((candidate) => candidateKey(candidate))]),
        )
      }
      if (failed.length > 0) {
        setSelectedCandidates(failed.map((candidate) => candidateKey(candidate)))
        setBlocklistError(
          '일부 항목을 등록하지 못했습니다. 이미 등록된 값인지 확인 후 다시 시도해주세요.',
        )
        return
      }
      setBlocklistOpen(false)
      setBlocklistError(null)
      setSelectedCandidates([])
      blocklistForm.resetFields()
    } finally {
      setBlocklistSubmitting(false)
    }
  }
  const submitReevaluation = async () => {
    setReevaluationSubmitting(true)
    setReevaluationError(null)
    try {
      const result = await requestPaymentFdsReevaluation(evaluation.paymentId)
      setRecovery(result)
      setReevaluationOpen(false)
      void getPaymentFdsRecovery(evaluation.paymentId)
        .then((next) => {
          if (next) setRecovery(next)
        })
        .catch(() => undefined)
    } catch (requestError) {
      if (requestError instanceof ApiError) {
        setReevaluationError(requestError.detail ?? '재평가를 실행할 수 없습니다.')
      } else
        setReevaluationError(
          '재평가 요청 중 네트워크 오류가 발생했습니다. 현재 상태를 다시 확인해주세요.',
        )
    } finally {
      setReevaluationSubmitting(false)
    }
  }
  const unmatchedHits = detail.ruleHits.filter(
    (rule) => !(rule.outcome === 'MATCH' && rule.appliedScore > 0),
  )
  const immediateDeny = matchedHits.some((rule) => rule.ruleCode === 'R07')
  const shades = segmentShades[evaluation.result]
  const blockableCount = detail.blocklistCandidates.filter(
    (candidate) => !blockedCandidates.has(candidateKey(candidate)),
  ).length
  const elapsed = elapsedLabel(evaluation.paymentRequestedAt, evaluation.evaluatedAt)
  const openBlocklist = () => {
    setBlocklistError(null)
    setBlocklistOpen(true)
  }
  const blocklistButtonLabel =
    detail.blocklistCandidates.length === 0
      ? '차단할 식별값 없음'
      : blockableCount === 0
        ? '후보 모두 차단 중'
        : `차단 목록 등록 · 후보 ${blockableCount}개`
  // 걸린 신호를 점수 순서대로 쌓는다. 합이 100을 넘으면 100에서 자른다
  const segments = matchedHits.map((rule, index) => {
    const before = matchedHits
      .slice(0, index)
      .reduce((total, previous) => total + previous.appliedScore, 0)
    // 목록 눈금자와 같은 눈금(ALLOW 28 · REVIEW 42 · DENY 30)에 쌓는다
    const width = scoreToX(before + rule.appliedScore) - scoreToX(before)
    return { rule, width, index, color: shades[Math.min(index, shades.length - 1)] }
  })
  const policyTag = (ruleCode: string) => {
    if (policyError) return <Tag color="warning">정책 조회 실패</Tag>
    const policy = policyByRuleCode.get(ruleCode)
    if (!policy) return null
    return policy.active ? null : <Tag>현재 꺼짐</Tag>
  }

  return (
    <>
      <PageHeader
        title="경보 상세"
        description={`결제 ${evaluation.paymentId}을(를) FDS가 탐지 룰로 채점한 기록입니다. ${
          score === null
            ? '채점에 실패해 점수가 없습니다.'
            : '걸린 신호의 점수를 더해 판정했습니다.'
        }`}
        actions={
          <Button
            icon={<ArrowLeftOutlined />}
            onClick={() => navigate(alertListReturnPath ?? '/security/alerts', { replace: true })}
          >
            목록으로
          </Button>
        }
      />
      {/* 결론(점수 · 판정 · 이유)을 맨 위에, 결제 정보는 오른쪽 사실 칸으로 옮겼다 */}
      <div className="dw-alert-detail dw-alert-detail-layout">
        <div className="dw-alert-detail-main">
          {/* 목록의 채점 엔진 화면과 같은 남색 화면에 점수와 쌓기 막대를 둔다 */}
          <section className="dw-engine-surface dw-alert-verdict-engine" aria-label="채점 결과">
            <div className="dw-alert-verdict-top">
              <div
                className={`dw-alert-big-score dw-alert-big-score--${evaluation.result.toLowerCase()}`}
              >
                {score === null ? '—' : shownScore}
                <small>{score === null ? '점수 없음' : '/ 100'}</small>
              </div>
              <div className="dw-alert-verdict-text">
                <div className="dw-alert-verdict-title">
                  <ResultTag result={evaluation.result} />
                  <strong>{resultTitles[evaluation.result]}</strong>
                </div>
                <p>
                  {immediateDeny
                    ? '차단 목록과 일치해 점수를 더하기 전에 바로 차단했습니다.'
                    : resultSentences[evaluation.result]}
                </p>
                {score === null && evaluation.errorCode && (
                  <code className="dw-alert-error-code">{evaluation.errorCode}</code>
                )}
              </div>
              {score !== null && matchedHits.length > 0 && (
                <Button
                  size="small"
                  className="dw-alert-replay"
                  onClick={() => setScorePlay((play) => play + 1)}
                >
                  점수 다시 쌓기
                </Button>
              )}
            </div>
            {score !== null && (
              <div className="dw-alert-stack">
                <div
                  key={scorePlay}
                  className="dw-alert-stack-track"
                  role="img"
                  aria-label={`위험 점수 ${score}점. ${matchedHits
                    .map((rule) => `${rule.ruleName} ${rule.appliedScore}점`)
                    .join(', ')}`}
                >
                  {segments.map(({ rule, width, index, color }) => (
                    <div
                      key={rule.hitId}
                      className="dw-alert-stack-segment"
                      style={
                        {
                          width: `${width}%`,
                          background: color,
                          '--dw-alert-segment-delay': `${300 + index * SEGMENT_STEP_MS}ms`,
                        } as CSSProperties
                      }
                    >
                      <span>
                        {rule.ruleName} +{rule.appliedScore}
                      </span>
                    </div>
                  ))}
                  <i className="dw-alert-stack-line" style={{ left: `${scoreToX(50)}%` }}>
                    <span>50 REVIEW</span>
                  </i>
                  <i className="dw-alert-stack-line" style={{ left: `${scoreToX(80)}%` }}>
                    <span>80 DENY</span>
                  </i>
                </div>
                <div className="dw-alert-stack-scale" aria-hidden="true">
                  <span>0</span>
                  <span style={{ left: `${scoreToX(50)}%` }}>50</span>
                  <span style={{ left: `${scoreToX(80)}%` }}>80</span>
                  <span style={{ left: '100%' }}>100</span>
                </div>
              </div>
            )}
          </section>
          {evaluation.result !== 'ERROR' && (
            <Card
              title={<span className="dw-section-title">걸린 신호</span>}
              extra={
                <span className="dw-alert-signals-summary">
                  {matchedHits.length}개 · 합계{' '}
                  <strong>
                    +{matchedHits.reduce((total, rule) => total + rule.appliedScore, 0)}
                  </strong>
                </span>
              }
              className="dw-alert-card"
            >
              <div className="dw-alert-signals">
                {policyError && (
                  <Alert
                    type="warning"
                    showIcon
                    message="현재 룰 정책을 불러오지 못했습니다. 평가 당시 저장값은 정상적으로 표시됩니다."
                  />
                )}
                {matchedHits.length === 0 && (
                  <p className="dw-alert-signals-empty">걸린 위험 신호가 없습니다.</p>
                )}
                {segments.map(({ rule, color }) => (
                  <div key={rule.hitId} className="dw-alert-signal">
                    <span className="dw-alert-signal-dot" style={{ background: color }} />
                    <div className="dw-alert-signal-body">
                      <div className="dw-alert-signal-name">
                        <strong>{rule.ruleName}</strong>
                        <span className="dw-alert-signal-code">{rule.ruleCode}</span>
                        {policyTag(rule.ruleCode)}
                      </div>
                      <div className="dw-alert-signal-values">
                        <span>
                          <em>평가 당시</em>
                          {observedValue(rule.ruleCode, rule.observedValue)}
                        </span>
                        <span>
                          <em>기준</em>
                          {threshold(rule.ruleCode, rule.thresholdSnapshot)}
                        </span>
                      </div>
                    </div>
                    <strong className="dw-alert-signal-points">+{rule.appliedScore}</strong>
                  </div>
                ))}
                {unmatchedHits.length > 0 && (
                  <details className="dw-alert-signals-rest">
                    <summary>
                      해당 안 된 신호 {unmatchedHits.length}개 ·{' '}
                      {unmatchedHits.map((rule) => rule.ruleName).join(' · ')}
                    </summary>
                    {unmatchedHits.map((rule) => (
                      <div key={rule.hitId} className="dw-alert-signal dw-alert-signal--off">
                        <span className="dw-alert-signal-dot" />
                        <div className="dw-alert-signal-body">
                          <div className="dw-alert-signal-name">
                            <strong>{rule.ruleName}</strong>
                            <span className="dw-alert-signal-code">{rule.ruleCode}</span>
                            {rule.outcome === 'UNAVAILABLE' && <Tag color="warning">확인 불가</Tag>}
                            {policyTag(rule.ruleCode)}
                          </div>
                          <div className="dw-alert-signal-values">
                            <span>
                              <em>평가 당시</em>
                              {observedValue(rule.ruleCode, rule.observedValue)}
                            </span>
                            <span>
                              <em>기준</em>
                              {threshold(rule.ruleCode, rule.thresholdSnapshot)}
                            </span>
                          </div>
                        </div>
                        <strong className="dw-alert-signal-points">0</strong>
                      </div>
                    ))}
                  </details>
                )}
              </div>
            </Card>
          )}
          <Card
            title={<span className="dw-section-title">결제 처리 흐름</span>}
            extra={
              elapsed ? (
                <span className="dw-alert-elapsed">
                  요청 → 판정 <strong>{elapsed}</strong>
                </span>
              ) : (
                <span className="dw-alert-source-label">FDS 관측</span>
              )
            }
            className="dw-alert-card"
          >
            {/* 점이 P5 → FDS → P5로 계속 지나가며 동기 왕복 구조를 보여 준다 */}
            <div className="dw-alert-pipe">
              <span className="dw-alert-pipe-wire" aria-hidden="true" />
              <span className="dw-alert-pipe-packet" aria-hidden="true" />
              <div className="dw-alert-pipe-node">
                <strong>결제 평가 요청 수신</strong>
                <span>{formatTime(evaluation.paymentRequestedAt)} · P5 → FDS</span>
              </div>
              <div className="dw-alert-pipe-node">
                <strong>FDS {evaluation.result} 판정</strong>
                <span>
                  {formatTime(evaluation.evaluatedAt)} · {resultDescription}
                </span>
              </div>
              <div className="dw-alert-pipe-node">
                <strong>P5에 평가 결과 반환</strong>
                <span>동기 호출 응답 · {evaluation.result}</span>
              </div>
            </div>
          </Card>
          {evaluation.result === 'ERROR' && (
            <Card
              title={<span className="dw-section-title">오류 분석·재평가</span>}
              className="dw-alert-card"
            >
              <Descriptions column={1} colon={false} size="small">
                <Descriptions.Item label="오류 발생 원인">
                  {failureCauseLabel(evaluation.errorCode)}
                </Descriptions.Item>
                <Descriptions.Item label="분석 결과">
                  {failureAnalysisResultLabel(detail.failureAnalysis?.disposition)}
                </Descriptions.Item>
                {recovery && (
                  <>
                    <Descriptions.Item label="복구 상태">
                      <Tag color={recoveryStatusColors[recovery.status]}>
                        {recoveryStatusLabels[recovery.status]}
                      </Tag>
                    </Descriptions.Item>
                    <Descriptions.Item label="재평가 결과">
                      {recovery.evaluationResult ? (
                        <ResultTag result={recovery.evaluationResult} />
                      ) : (
                        '—'
                      )}
                    </Descriptions.Item>
                    {recovery.reevaluationEvaluationId && (
                      <Descriptions.Item label="재평가 경보">
                        <Button
                          type="link"
                          className="dw-alert-payment-link"
                          onClick={() => openAlertDetail(recovery.reevaluationEvaluationId!)}
                        >
                          경보 #{recovery.reevaluationEvaluationId}
                        </Button>
                      </Descriptions.Item>
                    )}
                    <Descriptions.Item label="결제 상태">
                      {recovery.paymentStatus}
                    </Descriptions.Item>
                    <Descriptions.Item label="처리 완료 시각">
                      {recovery.completedAt ? formatShortDate(recovery.completedAt) : '—'}
                    </Descriptions.Item>
                  </>
                )}
              </Descriptions>
              {recoveryError && (
                <Alert
                  type="warning"
                  showIcon
                  message="P5 복구 상태를 불러오지 못했습니다."
                  description="새로고침 후 다시 확인해주세요."
                  style={{ marginTop: 16 }}
                />
              )}
              {!recoveryError && !recovery && (
                <Alert
                  type="info"
                  showIcon
                  message="P5 복구 기록이 아직 없습니다."
                  description="분석 전달 상태를 확인해주세요."
                  style={{ marginTop: 16 }}
                />
              )}
              {recovery?.status === 'MANUAL_REVIEW' && (
                <Alert
                  type="warning"
                  showIcon
                  message="추가 자동 재평가는 허용되지 않습니다."
                  description="재평가 중 다시 오류가 발생했거나 결제 상태가 변경되어 수동 검토가 필요합니다."
                  style={{ marginTop: 16 }}
                />
              )}
            </Card>
          )}
        </div>
        <aside className="dw-alert-detail-side">
          {/* 판정마다 할 일이 다르다. 늘 보이던 빨간 차단 버튼을 여기로 옮겼다 */}
          <Card
            title={<span className="dw-section-title">다음 할 일</span>}
            className="dw-alert-card dw-alert-next"
          >
            {evaluation.result === 'ERROR' ? (
              <>
                {canRunReevaluation ? (
                  <Button type="primary" block onClick={openReevaluationModal}>
                    {resumingReevaluation ? '재평가 재개' : '재평가 실행'}
                  </Button>
                ) : (
                  <p>
                    {recovery
                      ? `복구 상태: ${recoveryStatusLabels[recovery.status]}`
                      : '복구 기록을 확인할 수 없습니다.'}
                  </p>
                )}
                {(recovery?.status === 'READY' || recovery?.status === 'REQUESTED') &&
                  !isSecurityStaff && <p>재평가는 보안 직원만 실행할 수 있습니다.</p>}
              </>
            ) : (
              <>
                {evaluation.result === 'REVIEW' && !caseId && (
                  <p>
                    아직 연결된 사건이 없습니다. 카드 승인에 성공해 결제가 보류되면 사건이 생깁니다.
                  </p>
                )}
                {evaluation.result === 'ALLOW' && <p>통과한 결제입니다. 따로 할 일은 없습니다.</p>}
                {caseId && (
                  <Button
                    type={evaluation.result === 'REVIEW' ? 'primary' : 'default'}
                    block
                    onClick={() => navigate(`/security/cases/${caseId}`)}
                  >
                    사건 보기 · CASE-{caseId}
                  </Button>
                )}
                <Button
                  type={evaluation.result === 'DENY' ? 'primary' : 'default'}
                  danger
                  block
                  disabled={detail.blocklistCandidates.length === 0}
                  onClick={openBlocklist}
                >
                  {blocklistButtonLabel}
                </Button>
              </>
            )}
            <Button
              block
              className="dw-alert-customer-link"
              onClick={() =>
                navigate(
                  `/security/alerts?${new URLSearchParams({
                    filter: 'ALL',
                    query: customerPart(evaluation.customerRef),
                    merchantId: String(evaluation.merchantId),
                  }).toString()}`,
                )
              }
            >
              이 고객의 다른 경보 보기
            </Button>
          </Card>
          <Card className="dw-alert-card dw-alert-facts">
            <Descriptions column={1} colon={false} size="small">
              <Descriptions.Item label="결제 ID">
                <span className="dw-alert-mono">{evaluation.paymentId}</span>
              </Descriptions.Item>
              <Descriptions.Item label="가맹점">{merchantLabel}</Descriptions.Item>
              <Descriptions.Item label="고객 식별자">
                {customerPart(evaluation.customerRef)}
              </Descriptions.Item>
              <Descriptions.Item label="마스킹 카드">
                {detail.cardIdentifier?.cardDisplayLabel ?? '—'}
              </Descriptions.Item>
              <Descriptions.Item label="결제 요청">
                <span className="dw-alert-mono">
                  {formatShortDate(evaluation.paymentRequestedAt)}
                </span>
              </Descriptions.Item>
              <Descriptions.Item label="평가 ID">
                <span className="dw-alert-mono">{evaluation.evaluationId}</span>
              </Descriptions.Item>
              <Descriptions.Item label="평가 시각">
                <span className="dw-alert-mono">{formatShortDate(evaluation.evaluatedAt)}</span>
              </Descriptions.Item>
              <Descriptions.Item label="사건">
                {caseId ? (
                  <Button
                    type="link"
                    className="dw-alert-payment-link"
                    onClick={() => navigate(`/security/cases/${caseId}`)}
                  >
                    CASE-{caseId}
                  </Button>
                ) : (
                  '—'
                )}
              </Descriptions.Item>
            </Descriptions>
            <p className="dw-alert-facts-note">
              평가 요청으로 받은 결제 식별자·고객 식별자·요청 시각 등 평가 근거만 저장합니다.
            </p>
          </Card>
          <Card
            title={<span className="dw-section-title">같은 결제의 평가 이력</span>}
            className="dw-alert-card dw-alert-history"
          >
            {detail.history.length <= 1 && (
              <p className="dw-alert-muted">이 결제의 평가는 지금 보는 평가 하나입니다.</p>
            )}
            <ul hidden={detail.history.length <= 1}>
              {detail.history.map((item) => {
                const current = item.evaluationId === evaluation.evaluationId
                return (
                  <li key={item.evaluationId}>
                    <button
                      type="button"
                      className={`dw-alert-history-item ${current ? 'dw-alert-history-item--current' : ''}`}
                      aria-current={current ? 'page' : undefined}
                      onClick={() => {
                        if (!current) openAlertDetail(item.evaluationId)
                      }}
                    >
                      <span className="dw-alert-history-result">
                        <ResultTag result={item.result} />
                        <strong>{item.riskScore ?? item.errorCode ?? '—'}</strong>
                      </span>
                      <span className="dw-alert-history-meta">
                        #{item.evaluationId} · {formatShortDate(item.evaluatedAt)}
                        {current && ' · 지금 보는 평가'}
                      </span>
                    </button>
                  </li>
                )
              })}
            </ul>
          </Card>
        </aside>
      </div>
      <Modal
        title={resumingReevaluation ? '재평가 재개' : '재평가 실행'}
        open={reevaluationOpen}
        okText={resumingReevaluation ? '재평가 재개' : '재평가 실행'}
        cancelText="취소"
        confirmLoading={reevaluationSubmitting}
        onCancel={() => {
          if (!reevaluationSubmitting) setReevaluationOpen(false)
        }}
        onOk={() => void submitReevaluation()}
      >
        <Typography.Paragraph>
          {resumingReevaluation
            ? '중단된 기존 재평가 작업을 동일 요청으로 재개합니다. 새 평가를 추가로 만들지 않습니다.'
            : '현재 ERROR 평가를 한 번만 재평가합니다. 실행 후 다시 오류가 발생하면 추가 자동 재평가 없이 수동 검토로 전환됩니다.'}
        </Typography.Paragraph>
        {reevaluationError && <Alert type="error" showIcon message={reevaluationError} />}
      </Modal>
      <Modal
        title="차단 목록 등록"
        open={blocklistOpen}
        okText="선택 항목 등록"
        cancelText="취소"
        okButtonProps={{ disabled: selectedCandidates.length === 0 }}
        confirmLoading={blocklistSubmitting}
        onCancel={closeBlocklistModal}
        onOk={() => void submitBlocklist()}
      >
        <Typography.Paragraph type="secondary">
          평가 당시 P5가 전달한 식별값을 선택해 등록합니다. 이미 차단된 값은 다시 등록하지 않습니다.
        </Typography.Paragraph>
        {blocklistError && (
          <Alert type="error" showIcon message={blocklistError} style={{ marginBottom: 16 }} />
        )}
        <Checkbox.Group
          value={selectedCandidates}
          onChange={(values) => setSelectedCandidates(values as string[])}
          style={{ display: 'grid', gap: 12, marginBottom: 16 }}
        >
          {detail.blocklistCandidates.map((candidate) => {
            const key = candidateKey(candidate)
            const blocked = blockedCandidates.has(key)
            return (
              <Checkbox key={key} value={key} disabled={blocked}>
                {blocklistCandidateLabels[candidate.identifierType] ?? candidate.identifierType} ·{' '}
                {candidate.displayValue ?? '표시값 없음'}{' '}
                {blocked && <Tag color="red">차단 중</Tag>}
              </Checkbox>
            )
          })}
        </Checkbox.Group>
        <Form form={blocklistForm} layout="vertical">
          <Form.Item
            label="등록 사유"
            name="reason"
            rules={[
              { required: true, whitespace: true, message: '등록 사유를 입력해주세요.' },
              { max: 500 },
            ]}
          >
            <Input.TextArea
              maxLength={500}
              showCount
              rows={3}
              placeholder="예: 사기 거래로 확인되어 재시도를 차단합니다."
            />
          </Form.Item>
        </Form>
      </Modal>
    </>
  )
}
