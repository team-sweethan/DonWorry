import { useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react'
import { Alert, Button, Card, Descriptions, Empty, Form, Input, Spin, Tag } from 'antd'
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons'
import { useLocation, useSearchParams } from 'react-router-dom'
import { PageHeader } from '../../components/common/PageHeader'
import { normalizePaymentId } from '../../api/paymentOperations'
import type { PaymentOperationDetail } from '../../api/paymentOperations'
import {
  PaymentLookupStore,
  formatPaymentTime,
  lookupFailureMessages,
  paymentLookupQuery,
  recordNotice,
} from './paymentLookupState'
import './PaymentLookupPage.css'

const paymentLabels: Record<string, string> = {
  PENDING: '처리 중',
  DENIED: 'FDS 차단',
  DECLINED: '승인 거절',
  HELD: '매입 보류',
  CAPTURED: '매입 완료',
  VOIDED: '승인 취소',
  EXPIRED: '만료',
}
const evaluationLabels: Record<string, string> = {
  ALLOW: '허용',
  REVIEW: '검토 필요',
  DENY: '차단',
  ERROR: '평가 오류',
}
const actionLabels: Record<string, string> = { CAPTURE: '매입 요청', VOID: '승인 취소 요청' }
const actionStatusLabels: Record<string, string> = {
  PENDING: '처리 대기',
  SUCCEEDED: '처리 성공',
  FAILED: '처리 실패',
}
const categoryLabels: Record<string, string> = {
  EVALUATION_PENDING: '평가·승인 처리 대기',
  EVALUATION_ERROR: '평가 오류 확인 필요',
  AUTO_CAPTURE_PENDING: '자동 매입 대기',
  DECISION_PENDING: '직원 판정 대기',
  STAFF_CAPTURE_PENDING: '직원 요청 매입 대기',
  VOID_FAILED: '승인 취소 실패 확인 필요',
  FINANCIAL_COMPLETED: '금융 처리 완료',
  PAYMENT_TERMINATED: '결제 종료',
  UNKNOWN: '상태 확인 필요',
}
const label = (labels: Record<string, string>, value: string | null) =>
  value === null ? '기록 없음' : `${labels[value] ?? '알 수 없는 상태'} (${value})`
const value = (text: string | null) => text ?? '기록 없음'

function PaymentFacts({ detail }: { detail: PaymentOperationDetail }) {
  const { payment, request, evaluation, action, observation } = detail
  const evaluationNotice = recordNotice(evaluation.recordStatus, 'evaluation')
  const actionNotice = recordNotice(action.recordStatus, 'action')
  return (
    <>
      <Card
        title={`결제 #${payment.paymentId}`}
        extra={
          <Tag
            color={
              payment.paymentStatus === 'CAPTURED'
                ? 'green'
                : payment.paymentStatus === 'HELD'
                  ? 'gold'
                  : 'default'
            }
          >
            {label(paymentLabels, payment.paymentStatus)}
          </Tag>
        }
      >
        <p className="pl-amount">
          {payment.amount.toLocaleString('ko-KR')}
          <small>원</small>
        </p>
        <Descriptions
          column={{ xs: 1, sm: 2, lg: 3 }}
          items={[
            { key: 'order', label: '주문 참조', children: value(payment.orderRef) },
            { key: 'merchant', label: '가맹점 ID', children: String(payment.merchantId) },
            { key: 'created', label: '결제 접수', children: formatPaymentTime(payment.createdAt) },
            {
              key: 'changed',
              label: '현재 상태 반영',
              children: formatPaymentTime(payment.statusChangedAt),
            },
            { key: 'decline', label: '승인 거절 코드', children: value(payment.declineCode) },
          ]}
        />
      </Card>
      <div className="pl-grid">
        <Card title="FDS 평가">
          {evaluationNotice && <Alert type="info" showIcon message={evaluationNotice} />}
          <Descriptions
            column={1}
            items={[
              { key: 'record', label: '평가 기록', children: evaluation.recordStatus },
              {
                key: 'score',
                label: '위험 점수',
                children: evaluation.riskScore === null ? '기록 없음' : `${evaluation.riskScore}점`,
              },
              {
                key: 'result',
                label: '평가 결과',
                children: label(evaluationLabels, evaluation.evaluationResult),
              },
              {
                key: 'at',
                label: '평가 시각',
                children: formatPaymentTime(evaluation.evaluatedAt),
              },
              { key: 'error', label: '평가 오류 코드', children: value(evaluation.errorCode) },
            ]}
          />
          <p className="pl-note">
            저장된 평가 결과입니다. 개별 탐지 규칙의 근거는 경보·Case에서 확인해 주세요.
          </p>
        </Card>
        <Card title="직원 금융 조치">
          {actionNotice && <Alert type="info" showIcon message={actionNotice} />}
          <Descriptions
            column={1}
            items={[
              { key: 'record', label: '조치 기록', children: action.recordStatus },
              { key: 'type', label: '조치 종류', children: label(actionLabels, action.actionType) },
              {
                key: 'status',
                label: '조치 결과',
                children: label(actionStatusLabels, action.actionStatus),
              },
              {
                key: 'requested',
                label: '요청 시각',
                children: formatPaymentTime(action.requestedAt),
              },
              {
                key: 'completed',
                label: '결과 확정 시각',
                children: formatPaymentTime(action.completedAt),
              },
              { key: 'error', label: '조치 오류 코드', children: value(action.errorCode) },
            ]}
          />
          <p className="pl-note">금융 조치 결과와 Case 종료·정산 완료는 별도입니다.</p>
        </Card>
      </div>
      <Card title="처리 현황">
        <p>
          <Tag>{categoryLabels[observation.category] ?? observation.category}</Tag> 확인 시각{' '}
          {formatPaymentTime(observation.observedAt)}
        </p>
        <p>
          {recordNotice(request.recordStatus, 'request') ??
            `요청 처리 단계: ${value(request.processingStage)}`}
        </p>
        {observation.reasons.length > 0 && (
          <ul>
            {observation.reasons.map((reason, index) => (
              <li key={`${index}:${reason}`}>{reason}</li>
            ))}
          </ul>
        )}
        {observation.limitations.length > 0 && (
          <Alert
            type="info"
            showIcon
            message="조회 범위"
            description={
              <ul>
                {observation.limitations.map((limitation, index) => (
                  <li key={`${index}:${limitation}`}>{limitation}</li>
                ))}
              </ul>
            }
          />
        )}
      </Card>
    </>
  )
}

export function PaymentLookupPage() {
  const [params, setParams] = useSearchParams()
  const location = useLocation()
  const search = params.toString()
  const query = useMemo(() => paymentLookupQuery(new URLSearchParams(search)), [search])
  const store = useMemo(() => new PaymentLookupStore(), [])
  const snapshot = useSyncExternalStore(store.subscribe, store.getSnapshot)
  useEffect(() => {
    void store.load(query)
    return () => store.dispose()
  }, [store, query, location.key])
  return (
    <PaymentSearch
      key={`${location.key}:${search}`}
      defaultInput={params.get('paymentId') ?? ''}
      query={query}
      snapshot={snapshot}
      onSearch={(id) => setParams({ paymentId: id })}
      onRefresh={() => void store.load(query)}
    />
  )
}

type SearchProps = {
  defaultInput: string
  query: ReturnType<typeof paymentLookupQuery>
  snapshot: ReturnType<PaymentLookupStore['getSnapshot']>
  onSearch: (id: string) => void
  onRefresh: () => void
}
function PaymentSearch({ defaultInput, query, snapshot, onSearch, onRefresh }: SearchProps) {
  const [draft, setDraft] = useState(defaultInput)
  const [invalid, setInvalid] = useState(false)
  const feedback = useRef<HTMLDivElement>(null)
  const canonical = normalizePaymentId(draft)
  const sameQuery = query.kind === 'valid' && canonical === query.paymentId
  const current =
    sameQuery && 'paymentId' in snapshot && snapshot.paymentId === query.paymentId ? snapshot : null
  const invalidMessage = '결제 ID는 1~9,007,199,254,740,991 범위의 정수로 입력해 주세요.'
  const invalidInput = invalid || (query.kind === 'invalid' && draft === defaultInput)
  useEffect(() => {
    if (current?.kind === 'ready' || current?.kind === 'error' || invalid) feedback.current?.focus()
  }, [current, invalid])
  return (
    <div className="payment-lookup">
      <PageHeader
        title="거래 내역 · 결제 단건 조회"
        description="결제 ID로 현재 결제·FDS 평가·금융 조치 결과를 확인합니다."
      />
      <Card>
        <Form
          layout="vertical"
          onFinish={() => {
            if (!canonical) {
              setInvalid(true)
              return
            }
            setInvalid(false)
            onSearch(canonical)
          }}
        >
          <Form.Item
            label="결제 ID"
            htmlFor="payment-lookup-id"
            validateStatus={invalidInput ? 'error' : undefined}
            help={invalidInput ? invalidMessage : undefined}
          >
            <div className="pl-search">
              <Input
                id="payment-lookup-id"
                inputMode="numeric"
                autoComplete="off"
                maxLength={30}
                value={draft}
                placeholder="예: 123"
                aria-invalid={invalidInput}
                onChange={(event) => {
                  setDraft(event.target.value)
                  setInvalid(false)
                }}
              />
              <Button type="primary" icon={<SearchOutlined />} htmlType="submit">
                조회
              </Button>
              <Button
                icon={<ReloadOutlined />}
                disabled={!sameQuery || current?.kind === 'loading'}
                onClick={onRefresh}
              >
                다시 조회
              </Button>
            </div>
          </Form.Item>
        </Form>
        <p className="pl-note">
          전체 거래 목록이 아닌 단건 조회입니다. 조회 시 결제 상태를 변경하지 않습니다.
        </p>
      </Card>
      <div
        className="pl-results"
        ref={feedback}
        tabIndex={-1}
        role="region"
        aria-label="결제 조회 결과"
        aria-live="polite"
      >
        {invalidInput ? (
          <Alert type="error" showIcon message={invalidMessage} />
        ) : query.kind === 'idle' ? (
          <Empty description="결제 ID를 입력하고 조회해 주세요." />
        ) : !sameQuery ? (
          <Alert
            type="info"
            showIcon
            message="입력값이 변경되었습니다. 조회 버튼을 눌러 새 결제를 확인해 주세요."
          />
        ) : !current || current.kind === 'loading' ? (
          <div className="pl-loading" role="status">
            <Spin />
            <p>결제 #{query.kind === 'valid' ? query.paymentId : ''}을 조회하고 있습니다.</p>
          </div>
        ) : current.kind === 'error' ? (
          <Alert
            type={current.failure === 'not-found' ? 'info' : 'error'}
            showIcon
            message={`결제 #${current.paymentId} 조회 결과`}
            description={lookupFailureMessages[current.failure]}
            action={
              current.failure === 'unavailable' ? (
                <Button onClick={onRefresh}>다시 시도</Button>
              ) : undefined
            }
          />
        ) : current.kind === 'ready' ? (
          <PaymentFacts detail={current.detail} />
        ) : null}
      </div>
    </div>
  )
}
