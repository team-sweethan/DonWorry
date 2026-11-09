import { ArrowLeftOutlined } from '@ant-design/icons'
import {
  Alert,
  Badge,
  Button,
  Card,
  Col,
  Descriptions,
  Result,
  Row,
  Space,
  Spin,
  Tag,
  Typography,
} from 'antd'
import dayjs from 'dayjs'
import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { getCaseLinksByPayments } from '../../api/cases'
import { ApiError } from '../../api/client'
import { getMerchantDirectory } from '../../api/merchants'
import { getPaymentTransaction } from '../../api/paymentTransactions'
import { PageHeader } from '../../components/common/PageHeader'
import { ScreenResult } from '../../components/common/ScreenResult'
import type { PaymentTransactionDetail } from '../../types/paymentTransaction'
import { customerPart } from '../../utils/caseRules'
import { customerTransactionsPath } from '../../utils/transactionList'
import { paymentStatusLabel } from './case/caseLabels'
import { HelpTitle } from './transaction/HelpTitle'
import {
  copyable,
  fdsResultText,
  ipText,
  transactionHelp as help,
} from './transaction/transactionDisplay'

const LIST_PATH = '/security/transactions'

const missing = <Typography.Text type="secondary">정보 없음</Typography.Text>

// 사건 칸: 조회 중 · 없음 · 실패를 구분한다 — 실패를 "사건 없음"으로 그리면 잘못 읽힌다
type CaseLink =
  { status: 'loading' } | { status: 'loaded'; caseId: string | null } | { status: 'failed' }

// 거래 1건 상세 (#732) — 목록 줄 펼치기 대신. 보안 직원은 담당 사건 고객의 결제만 열린다. 열 때마다 서버에 열람 기록이 남는다(화면에 안내는 두지 않음, 11/09 결정)
// 여러 결제 비교 · FDS 점수 · 위험 신호는 여기서 다시 그리지 않고 경보 상세 · AI 보고서로 넘긴다 (ADR-0082 3.3)
export function TransactionDetailPage() {
  const { paymentId = '' } = useParams()
  const navigate = useNavigate()
  const [detail, setDetail] = useState<PaymentTransactionDetail | null>(null)
  const [notFound, setNotFound] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [outOfScope, setOutOfScope] = useState<string | null>(null)
  const [merchantName, setMerchantName] = useState<string | null>(null)
  const [caseLink, setCaseLink] = useState<CaseLink>({ status: 'loading' })

  useEffect(() => {
    let alive = true
    getPaymentTransaction(paymentId)
      .then((found) => {
        if (!alive) return
        setDetail(found)
        // 가맹점 이름 · 사건 연결은 따로 부른다 — 실패해도 결제 정보는 그대로 보인다
        getMerchantDirectory()
          .then((entries) => {
            const merchant = entries.find((entry) => entry.merchantId === found.merchantId)
            if (alive && merchant) setMerchantName(merchant.displayName)
          })
          .catch(() => undefined)
        getCaseLinksByPayments([found.paymentId])
          .then((links) => {
            if (!alive) return
            const link = links.find((item) => item.paymentId === found.paymentId)
            setCaseLink({ status: 'loaded', caseId: link?.caseId ?? null })
          })
          .catch(() => {
            if (alive) setCaseLink({ status: 'failed' })
          })
      })
      .catch((requestError: unknown) => {
        if (!alive) return
        if (requestError instanceof ApiError && requestError.status === 404) setNotFound(true)
        // 담당 사건 고객이 아닌 결제 (#732) — 정지가 아니라 업무 안내라 서버 문구를 그대로 보여 준다
        else if (
          requestError instanceof ApiError &&
          requestError.code === 'PAYMENT_TRANSACTION_OUT_OF_SCOPE'
        )
          setOutOfScope(requestError.detail ?? '담당 사건 고객의 결제만 상세를 볼 수 있습니다.')
        else setError('거래 상세를 불러오지 못했습니다. 잠시 후 다시 시도하세요.')
      })
    return () => {
      alive = false
    }
  }, [paymentId])

  // 목록에서 왔으면 뒤로 가서 같은 조건 · 쪽으로 돌아간다(조건은 주소에 있음). 주소로 바로 열었으면 목록 처음으로
  const backToList = () => {
    const state: unknown = window.history.state
    const index =
      typeof state === 'object' && state !== null && 'idx' in state && typeof state.idx === 'number'
        ? state.idx
        : 0
    if (index > 0) navigate(-1)
    else navigate(LIST_PATH)
  }
  const header = (
    <PageHeader
      title="거래 상세"
      actions={
        <Button icon={<ArrowLeftOutlined />} onClick={backToList}>
          거래 내역으로
        </Button>
      }
    />
  )

  if (notFound) return <ScreenResult kind="404" homePath={LIST_PATH} />
  if (outOfScope)
    return (
      <div>
        {header}
        <Result
          status="warning"
          title="이 결제는 볼 수 없습니다"
          subTitle={outOfScope}
          extra={<Button onClick={backToList}>거래 내역으로</Button>}
        />
      </div>
    )
  if (error)
    return (
      <div>
        {header}
        <Alert type="error" showIcon message={error} />
      </div>
    )
  if (!detail)
    return (
      <div>
        {header}
        <Spin />
      </div>
    )

  const status = paymentStatusLabel[detail.paymentStatus]
  const fds = detail.fdsResult === null ? null : fdsResultText[detail.fdsResult]
  const customer = customerPart(detail.customerRef)
  const customerPath = customerTransactionsPath(detail.merchantId, detail.customerRef)
  const evaluationId = detail.evaluationId
  const connection = [detail.ipCountryCode, ipText(detail.maskedIpAddress)]
    .filter((value) => value !== null)
    .join(' · ')

  return (
    <div>
      {header}
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        <Card
          title={
            <Space wrap>
              <span>결제 {detail.paymentId}</span>
              <Badge
                {...(status.color === 'default'
                  ? { status: 'default' as const }
                  : { color: status.color })}
                text={status.label}
              />
              {fds ? <Tag color={fds.color}>FDS {fds.label}</Tag> : <Tag>FDS 평가 전</Tag>}
            </Space>
          }
          extra={
            <Typography.Text strong style={{ fontSize: 18 }}>
              {detail.amount.toLocaleString('ko-KR')}원
            </Typography.Text>
          }
        >
          <Descriptions column={{ xs: 1, sm: 2, lg: 3 }} colon={false}>
            <Descriptions.Item label="결제 시각">
              {dayjs(detail.createdAt).format('YYYY-MM-DD HH:mm:ss')}
            </Descriptions.Item>
            <Descriptions.Item label="가맹점">
              {merchantName ?? `가맹점 #${detail.merchantId}`}
            </Descriptions.Item>
            <Descriptions.Item label={<HelpTitle label="고객 번호" text={help.customer} />}>
              <Typography.Text copyable={copyable(customer)}>{customer}</Typography.Text>
            </Descriptions.Item>
            <Descriptions.Item label={<HelpTitle label="주문번호" text={help.order} />}>
              <Space size={4} wrap>
                <Typography.Text code copyable={copyable(detail.orderRef)}>
                  {detail.orderRef}
                </Typography.Text>
                {detail.attemptNo !== null && detail.attemptNo > 1 && (
                  <Typography.Text type="secondary">{detail.attemptNo}번째 시도</Typography.Text>
                )}
              </Space>
            </Descriptions.Item>
            <Descriptions.Item label="결제 ID">{detail.paymentId}</Descriptions.Item>
          </Descriptions>
        </Card>

        <Card title="판정 · 연결">
          <Descriptions column={{ xs: 1, sm: 2, lg: 3 }} colon={false}>
            <Descriptions.Item label="FDS 판정">
              {fds ? (
                <Space size={8} wrap>
                  <Tag color={fds.color}>{fds.label}</Tag>
                  {evaluationId !== null && (
                    <Button
                      size="small"
                      onClick={() => navigate(`/security/alerts/${evaluationId}`)}
                    >
                      경보 상세 보기 ↗
                    </Button>
                  )}
                </Space>
              ) : (
                <Typography.Text type="secondary">평가 전</Typography.Text>
              )}
            </Descriptions.Item>
            <Descriptions.Item label="사건">
              {caseLink.status === 'loading' && (
                <Typography.Text type="secondary">…</Typography.Text>
              )}
              {caseLink.status === 'failed' && (
                <Typography.Text type="secondary">확인 불가 — 새로고침해 보세요</Typography.Text>
              )}
              {caseLink.status === 'loaded' &&
                (caseLink.caseId === null ? (
                  '-'
                ) : (
                  <Button
                    size="small"
                    onClick={() => navigate(`/security/cases/${caseLink.caseId}`)}
                  >
                    Case {caseLink.caseId} ↗
                  </Button>
                ))}
            </Descriptions.Item>
            <Descriptions.Item label="이 고객">
              {customerPath ? (
                <Button size="small" onClick={() => navigate(customerPath)}>
                  이 고객 거래 내역 보기
                </Button>
              ) : (
                missing
              )}
            </Descriptions.Item>
          </Descriptions>
        </Card>

        <Row gutter={[12, 12]}>
          <Col xs={24} lg={12}>
            <Card title="주문 정보" style={{ height: '100%' }}>
              <Descriptions column={1} colon={false}>
                <Descriptions.Item label="이메일 (가린 값)">
                  {detail.maskedBuyerEmail ?? missing}
                </Descriptions.Item>
              </Descriptions>
              {/* 주문자 이름 · 전화는 사건에 묶인 결제만 사건 화면에서 본다 (#619 · ADR-0082 원칙 1, 11/09 결정) */}
              {caseLink.status === 'loaded' && caseLink.caseId !== null && (
                <Typography.Text type="secondary">
                  주문자 이름 · 전화는{' '}
                  <Typography.Link onClick={() => navigate(`/security/cases/${caseLink.caseId}`)}>
                    Case {caseLink.caseId} › 주문자 정보
                  </Typography.Link>
                  에서 볼 수 있어요.
                </Typography.Text>
              )}
            </Card>
          </Col>
          <Col xs={24} lg={12}>
            <Card title="결제 환경" style={{ height: '100%' }}>
              <Descriptions column={1} colon={false}>
                <Descriptions.Item label="카드">
                  {detail.cardDisplayLabel ?? missing}
                </Descriptions.Item>
                <Descriptions.Item label={<HelpTitle label="접속" text={help.ip} />}>
                  {connection || missing}
                </Descriptions.Item>
                <Descriptions.Item label={<HelpTitle label="기기" text={help.device} />}>
                  {detail.maskedDeviceId ? <code>{detail.maskedDeviceId}</code> : missing}
                </Descriptions.Item>
              </Descriptions>
            </Card>
          </Col>
        </Row>
      </Space>
    </div>
  )
}
