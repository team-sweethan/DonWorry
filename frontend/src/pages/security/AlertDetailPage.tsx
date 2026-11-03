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
  Table,
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
  addFdsBlocklistEntry,
  getFdsEvaluation,
  getFdsBlocklistStatus,
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
import { useMinimumLoadingIndicator } from '../../hooks/useMinimumLoadingIndicator'
import { customerPart } from '../../utils/caseRules'
import { customerTransactionsPath } from '../../utils/transactionList'
import { ResultTag } from './alert/AlertTags'
import { failureAnalysisResultLabel, failureCauseLabel } from './alert/failureAnalysis'

function formatDate(value: string) {
  return new Intl.DateTimeFormat('ko-KR', { dateStyle: 'short', timeStyle: 'medium' }).format(
    new Date(value),
  )
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
  if (ruleCode === 'R02') return typeof value.deviceId === 'string' ? value.deviceId : '—'
  if (ruleCode === 'R03') return typeof value.cardToken === 'string' ? value.cardToken : '—'
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
      DEVICE_ID: value.deviceId,
      // 카드 토큰은 화면에 노출하지 않고, P5가 준 마스킹 표시값만 쓴다.
      CARD_TOKEN: value.cardDisplayLabel,
      IP_ADDRESS: value.ipAddress,
      BUYER_EMAIL: value.buyerEmail,
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
  if (ruleCode === 'HIGH_AMOUNT' || ruleCode === 'R01') return `${won(value.amountGte)} 이상`
  if (ruleCode === 'RAPID_REPEAT_PAYMENT' || ruleCode === 'R04') {
    return `${value.windowMinutes}분 내 ${value.attemptCountGte}회 이상`
  }
  if (ruleCode === 'R02' || ruleCode === 'R03') return '이전 정상 승인 이력에 없음'
  if (ruleCode === 'R05') return '고위험 국가 목록 일치'
  if (ruleCode === 'R06') return '10분 내 소액 5회 · 카드 3개 · 거절 3회 이상'
  if (ruleCode === 'R07') return '활성  일치'
  if (ruleCode === 'R08') return '10분 내 이전 거절 2회 이상'
  return '—'
}

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
  const initialDetailLoading = loading && detail === null
  const showInitialDetailLoading = useMinimumLoadingIndicator(initialDetailLoading)

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
          void Promise.all(
            response.blocklistCandidates.map(async (candidate) => ({
              key: `${candidate.identifierType}:${candidate.identifierValue}`,
              status: await getFdsBlocklistStatus(
                candidate.identifierType,
                candidate.identifierValue,
              ),
            })),
          )
            .then((statuses) => {
              if (active) {
                setBlockedCandidates(
                  new Set(statuses.filter(({ status }) => status.blocked).map(({ key }) => key)),
                )
              }
            })
            .catch(() => {
              if (active) setBlockedCandidates(new Set())
            })
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

  if (initialDetailLoading || showInitialDetailLoading) {
    return <div className="dw-alert-detail-loading">{showInitialDetailLoading && <Spin />}</div>
  }
  if (notFound) return <ScreenResult kind="404" />
  if (!detail || error)
    return <Alert type="error" showIcon message={error ?? '경보 상세가 없습니다.'} />

  const evaluation = detail.evaluation
  const merchantLabel = merchantName ?? `ID ${evaluation.merchantId}`
  // 이 손님의 다른 결제를 거래 내역에서 바로 본다 (#487)
  const customerPath = customerTransactionsPath(evaluation.merchantId, evaluation.customerRef)
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
  const candidateKey = (candidate: FdsEvaluationDetail['blocklistCandidates'][number]) =>
    `${candidate.identifierType}:${candidate.identifierValue}`
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
          addFdsBlocklistEntry({
            identifierType: candidate.identifierType,
            identifierValue: candidate.identifierValue,
            displayLabel: candidate.displayLabel,
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
  return (
    <>
      <PageHeader
        title="경보 상세"
        description={`평가 ${evaluation.evaluationId} · 결제 ${evaluation.paymentId} · 가맹점 ${merchantLabel} · 고객 ${customerPart(evaluation.customerRef)}`}
        actions={
          <>
            {caseId && (
              <Button onClick={() => navigate(`/security/cases/${caseId}`)}>사건 보기</Button>
            )}
            <Button
              danger
              disabled={detail.blocklistCandidates.length === 0}
              onClick={() => {
                setBlocklistError(null)
                setBlocklistOpen(true)
              }}
            >
              차단 목록 등록
            </Button>
            <Button
              icon={<ArrowLeftOutlined />}
              onClick={() => navigate(alertListReturnPath ?? '/security/alerts', { replace: true })}
            >
              목록으로
            </Button>
          </>
        }
      />
      <div className="dw-section-grid dw-alert-detail">
        <div className="dw-alert-detail-column">
          <Card
            title={<span className="dw-section-title">평가 결과</span>}
            extra={<ResultTag result={evaluation.result} />}
            className="dw-alert-card"
          >
            {score === null ? (
              <Alert
                type="warning"
                showIcon
                message="평가 불가 — 점수 없음"
                description={`오류 코드 ${evaluation.errorCode ?? '—'} · 위험 판정이 아니므로 자동 ALLOW 하지 않습니다.`}
              />
            ) : (
              <>
                <div className="dw-alert-score-row">
                  <strong>{score}</strong>
                  <span>점</span>
                  <div className="dw-alert-score-scale">
                    <div className="dw-alert-score-allow" />
                    <div className="dw-alert-score-review" />
                    <div className="dw-alert-score-deny" />
                    <i
                      className="dw-alert-score-marker"
                      style={
                        { '--dw-alert-score-position': `${Math.min(score, 99)}%` } as CSSProperties
                      }
                    />
                  </div>
                </div>
                <div className="dw-alert-score-labels">
                  <span>ALLOW 0~49</span>
                  <span>REVIEW 50~79</span>
                  <span>DENY 80 이상</span>
                </div>
              </>
            )}
          </Card>
          {evaluation.result === 'ERROR' && (
            <Card
              title={<span className="dw-section-title">오류 분석·재평가</span>}
              extra={
                canRunReevaluation ? (
                  <Button type="primary" onClick={openReevaluationModal}>
                    {resumingReevaluation ? '재평가 재개' : '재평가 실행'}
                  </Button>
                ) : undefined
              }
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
                      {recovery.completedAt ? formatDate(recovery.completedAt) : '—'}
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
              {(recovery?.status === 'READY' || recovery?.status === 'REQUESTED') &&
                !isSecurityStaff && (
                  <Typography.Text type="secondary">
                    재평가는 보안 직원만 실행할 수 있습니다.
                  </Typography.Text>
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
          {evaluation.result !== 'ERROR' && (
            <Card
              title={<span className="dw-section-title">위험 신호</span>}
              extra={<Typography.Text type="secondary">평가 당시 저장값</Typography.Text>}
              className="dw-alert-card"
            >
              {policyError && (
                <Alert
                  type="warning"
                  showIcon
                  message="현재 룰 정책을 불러오지 못했습니다. 평가 당시 저장값은 정상적으로 표시됩니다."
                  style={{ marginBottom: 12 }}
                />
              )}
              <Table
                rowKey="hitId"
                size="small"
                pagination={false}
                columns={[
                  {
                    title: '위험 신호',
                    dataIndex: 'ruleName',
                    width: 108,
                    render: (value: string) => <strong>{value}</strong>,
                  },
                  {
                    title: '결과',
                    dataIndex: 'outcome',
                    width: 92,
                    render: (outcome: string) => (
                      <Tag color={outcome === 'MATCH' ? 'gold' : 'default'}>{outcome}</Tag>
                    ),
                  },
                  {
                    title: '현재 정책',
                    key: 'currentPolicy',
                    width: 98,
                    render: (_, rule) => {
                      if (policyError) return <Tag color="warning">조회 실패</Tag>
                      const policy = policyByRuleCode.get(rule.ruleCode)
                      if (!policy) return <Tag>정책 정보 없음</Tag>
                      return policy.active ? (
                        <Tag color="green">현재 켜짐</Tag>
                      ) : (
                        <Tag color="default">현재 꺼짐</Tag>
                      )
                    },
                  },
                  {
                    title: '점수',
                    dataIndex: 'appliedScore',
                    align: 'right',
                    width: 55,
                    render: (points: number) => <strong>{points ? `+${points}` : '0'}</strong>,
                  },
                  {
                    title: '평가 당시 값',
                    key: 'observedValue',
                    width: 330,
                    render: (_, rule) => observedValue(rule.ruleCode, rule.observedValue),
                  },
                  {
                    title: '기준',
                    key: 'thresholdSnapshot',
                    width: 168,
                    render: (_, rule) => threshold(rule.ruleCode, rule.thresholdSnapshot),
                  },
                ]}
                dataSource={detail.ruleHits}
                tableLayout="fixed"
              />
            </Card>
          )}
          <Card
            title={<span className="dw-section-title">같은 결제의 평가 이력</span>}
            className="dw-alert-card"
          >
            <Table
              rowKey="evaluationId"
              size="small"
              pagination={false}
              columns={[
                { title: '평가 ID', dataIndex: 'evaluationId' },
                { title: '평가 시각', dataIndex: 'evaluatedAt', render: formatDate },
                {
                  title: '점수',
                  dataIndex: 'riskScore',
                  align: 'right',
                  render: (value: number | null) => value ?? '—',
                },
                {
                  title: '판정',
                  dataIndex: 'result',
                  render: (value: FdsResult) => <ResultTag result={value} />,
                },
                {
                  title: '오류 코드',
                  dataIndex: 'errorCode',
                  render: (value: string | null) => (value ? <code>{value}</code> : '—'),
                },
                {
                  title: '사건',
                  key: 'case',
                  render: (_, history) => {
                    const historyCaseId = caseIdsByPaymentId.get(String(history.paymentId))
                    if (!historyCaseId) return '—'
                    return (
                      <Button
                        type="link"
                        className="dw-alert-payment-link"
                        onClick={(event) => {
                          event.stopPropagation()
                          navigate(`/security/cases/${historyCaseId}`)
                        }}
                      >
                        CASE-{historyCaseId}
                      </Button>
                    )
                  },
                },
              ]}
              dataSource={detail.history}
              onRow={(record) => ({
                onClick: () => openAlertDetail(record.evaluationId),
                style: { cursor: 'pointer' },
              })}
            />
          </Card>
        </div>
        <div className="dw-alert-detail-column">
          <Card
            title={<span className="dw-section-title">결제 처리 흐름</span>}
            extra={<span className="dw-alert-source-label">FDS 관측</span>}
            className="dw-alert-card"
          >
            <div className="dw-alert-flow">
              <div className="dw-alert-flow-item dw-alert-flow-item--received">
                <strong>결제 평가 요청 수신</strong>
                <span>{formatDate(evaluation.paymentRequestedAt)} · P5 → FDS</span>
              </div>
              <div className="dw-alert-flow-item dw-alert-flow-item--evaluated">
                <strong>FDS {evaluation.result} 판정</strong>
                <span>
                  {formatDate(evaluation.evaluatedAt)} · {resultDescription}
                </span>
              </div>
              <div className="dw-alert-flow-item dw-alert-flow-item--returned">
                <strong>P5에 평가 결과 반환</strong>
                <span>동기 호출 응답 · {evaluation.result}</span>
              </div>
            </div>
          </Card>
          <Card
            title={<span className="dw-section-title">결제 정보</span>}
            className="dw-alert-card"
          >
            <Descriptions column={2} colon={false}>
              <Descriptions.Item label="결제 ID">{evaluation.paymentId}</Descriptions.Item>
              <Descriptions.Item label="가맹점">{merchantLabel}</Descriptions.Item>
              <Descriptions.Item label="고객 식별자">
                {customerPart(evaluation.customerRef)}
                {customerPath && (
                  <Button type="link" size="small" onClick={() => navigate(customerPath)}>
                    이 고객 거래 내역 보기 ↗
                  </Button>
                )}
              </Descriptions.Item>
              <Descriptions.Item label="마스킹 카드">
                {detail.cardIdentifier?.cardDisplayLabel ?? '—'}
              </Descriptions.Item>
              <Descriptions.Item label="카드 식별값">
                {detail.cardIdentifier?.cardToken ?? '—'}
              </Descriptions.Item>
              <Descriptions.Item label="결제 요청 시각">
                {formatDate(evaluation.paymentRequestedAt)}
              </Descriptions.Item>
              <Descriptions.Item label="평가 ID">{evaluation.evaluationId}</Descriptions.Item>
              <Descriptions.Item label="평가 시각">
                {formatDate(evaluation.evaluatedAt)}
              </Descriptions.Item>
            </Descriptions>
          </Card>
          <div className="dw-api-note">
            평가 요청으로 받은 결제 식별자·고객 식별자·요청 시각 등 평가 근거만 저장합니다.
          </div>
        </div>
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
                {candidate.displayLabel ?? candidate.identifierValue}{' '}
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
