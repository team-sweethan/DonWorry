import { ArrowLeftOutlined } from '@ant-design/icons'
import { Alert, Button, Card, Descriptions, Spin, Table, Tag, Typography } from 'antd'
import type { CSSProperties } from 'react'
import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { ApiError } from '../../api/client'
import {
  getFdsEvaluation,
  getFdsRulePolicies,
  type FdsEvaluationDetail,
  type FdsResult,
  type FdsRulePolicy,
} from '../../api/fds'
import { PageHeader } from '../../components/common/PageHeader'
import { ScreenResult } from '../../components/common/ScreenResult'
import { useMinimumLoadingIndicator } from '../../hooks/useMinimumLoadingIndicator'
import { ResultTag } from './alert/AlertTags'

function formatDate(value: string) {
  return new Intl.DateTimeFormat('ko-KR', { dateStyle: 'short', timeStyle: 'medium' }).format(
    new Date(value),
  )
}

function won(value: unknown) {
  return typeof value === 'number' ? `${value.toLocaleString('ko-KR')}원` : '—'
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
  if (ruleCode === 'R07') return typeof value.buyerRef === 'string' ? value.buyerRef : '—'
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
  const [detail, setDetail] = useState<FdsEvaluationDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [notFound, setNotFound] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [policies, setPolicies] = useState<FdsRulePolicy[]>([])
  const [policyError, setPolicyError] = useState(false)
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
      try {
        const response = await getFdsEvaluation(id)
        if (active) {
          setDetail(response)
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
  const policyByRuleCode = new Map(policies.map((policy) => [policy.ruleCode, policy]))
  const score = evaluation.riskScore
  const resultDescription =
    score === null
      ? `오류 코드 ${evaluation.errorCode ?? '—'}`
      : `${score}점 · ${evaluation.result} 판정`
  return (
    <>
      <PageHeader
        title="경보 상세"
        description={`평가 ${evaluation.evaluationId} · 결제 ${evaluation.paymentId} · 가맹점 ${evaluation.merchantId} · 고객 ${evaluation.customerRef}`}
        actions={
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/security/alerts')}>
            목록으로
          </Button>
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
                  render: (value: string) => <strong>{value}</strong>,
                },
                {
                  title: '결과',
                  dataIndex: 'outcome',
                  render: (outcome: string) => (
                    <Tag color={outcome === 'MATCH' ? 'gold' : 'default'}>{outcome}</Tag>
                  ),
                },
                {
                  title: '현재 정책',
                  key: 'currentPolicy',
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
                  render: (points: number) => <strong>{points ? `+${points}` : '0'}</strong>,
                },
                {
                  title: '평가 당시 값',
                  key: 'observedValue',
                  render: (_, rule) => observedValue(rule.ruleCode, rule.observedValue),
                },
                {
                  title: '기준',
                  key: 'thresholdSnapshot',
                  render: (_, rule) => threshold(rule.ruleCode, rule.thresholdSnapshot),
                },
              ]}
              dataSource={detail.ruleHits}
            />
          </Card>
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
              ]}
              dataSource={detail.history}
              onRow={(record) => ({
                onClick: () => navigate(`/security/alerts/${record.evaluationId}`),
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
            <Descriptions column={1} colon={false}>
              <Descriptions.Item label="결제 ID">{evaluation.paymentId}</Descriptions.Item>
              <Descriptions.Item label="가맹점 ID">{evaluation.merchantId}</Descriptions.Item>
              <Descriptions.Item label="고객 식별자">{evaluation.customerRef}</Descriptions.Item>
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
            주문·카드·현재 결제 상태는 P5가 관리합니다. P2는 평가 요청으로 받은 결제 식별자·고객
            식별자·요청 시각과 평가 근거만 저장합니다.
          </div>
        </div>
      </div>
    </>
  )
}
