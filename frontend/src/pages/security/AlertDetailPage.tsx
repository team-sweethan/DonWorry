import { ArrowLeftOutlined } from '@ant-design/icons'
import { Alert, Button, Card, Descriptions, Space, Table, Tag, Typography } from 'antd'
import type { CSSProperties } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { PageHeader } from '../../components/common/PageHeader'
import { ScreenResult } from '../../components/common/ScreenResult'
import { fdsAlerts, type FdsResult } from '../../demo/alertMockData'
import { useDemo } from '../../demo/DemoContext'
import { won } from '../../utils/format'
import { PaymentStatusTag, ResultTag } from './alert/AlertTags'

export function AlertDetailPage() {
  const { id } = useParams()
  const { merchant } = useDemo()
  const navigate = useNavigate()
  const alert = fdsAlerts.find((item) => item.paymentId === id && item.merchant === merchant)
  if (!alert) return <ScreenResult kind="404" />

  const score = alert.score
  return (
    <>
      <PageHeader
        title="경보 상세"
        description={`${alert.paymentId} · 주문 ${alert.orderId} · ${alert.merchant} · 고객 ${alert.customer}`}
        actions={
          <Space>
            {alert.caseId && (
              <Button onClick={() => navigate(`/security/cases/${alert.caseId}`)}>
                Case {alert.caseId} 보기
              </Button>
            )}
            <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/security/alerts')}>
              목록으로
            </Button>
          </Space>
        }
      />
      <div className="dw-section-grid dw-alert-detail">
        <div className="dw-alert-detail-column">
          <Card
            title={<span className="dw-section-title">위험 점수</span>}
            extra={<ResultTag result={alert.result} />}
            className="dw-alert-card"
          >
            {score === null ? (
              <Alert
                type="warning"
                showIcon
                message="평가 불가 — 점수 없음"
                description={`오류 코드 ${alert.errorCode ?? '—'} · 위험 판정이 아니므로 자동 ALLOW 하지 않습니다.`}
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
                  <span>ALLOW</span>
                  <span>REVIEW</span>
                  <span>DENY</span>
                </div>
              </>
            )}
          </Card>
          <Card
            title={<span className="dw-section-title">위험 신호</span>}
            extra={
              <Typography.Text type="secondary" className="dw-alert-meta-text">
                시연용 규칙
              </Typography.Text>
            }
            className="dw-alert-card"
          >
            <Table
              rowKey="name"
              size="small"
              pagination={false}
              columns={[
                {
                  title: '위험 신호',
                  dataIndex: 'name',
                  render: (value: string) => <strong>{value}</strong>,
                },
                {
                  title: '결과',
                  dataIndex: 'points',
                  render: (points: number) => (
                    <Tag color={points ? 'gold' : 'default'}>{points ? '적중' : '미적중'}</Tag>
                  ),
                },
                {
                  title: '점수',
                  dataIndex: 'points',
                  align: 'right',
                  render: (points: number) => <strong>{points ? `+${points}` : '0'}</strong>,
                },
                { title: '평가 당시 값', dataIndex: 'observed' },
                { title: '기준', dataIndex: 'threshold' },
              ]}
              dataSource={alert.signals}
            />
          </Card>
          <Card
            title={<span className="dw-section-title">같은 결제의 평가 이력</span>}
            extra={
              <Typography.Text type="secondary" className="dw-alert-meta-text">
                시연 데이터
              </Typography.Text>
            }
            className="dw-alert-card"
          >
            <Table
              rowKey="evaluationId"
              size="small"
              pagination={false}
              columns={[
                { title: '평가 ID', dataIndex: 'evaluationId' },
                { title: '평가 시각', dataIndex: 'evaluatedAt' },
                {
                  title: '점수',
                  dataIndex: 'score',
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
                  render: (value?: string) => value ?? '—',
                },
              ]}
              dataSource={alert.history}
            />
          </Card>
        </div>
        <div className="dw-alert-detail-column">
          <Card
            title={<span className="dw-section-title">결제 처리 흐름</span>}
            extra={<span className="dw-demo-label">P5 조회 영역</span>}
            className="dw-alert-card"
          >
            <div className="dw-alert-flow">
              <div>
                결제 접수 <span>{won(alert.amount)}</span>
              </div>
              <div>
                FDS {alert.result} <span>{score === null ? alert.errorCode : `${score}점`}</span>
              </div>
              <div>
                {alert.paymentStatus === 'HELD'
                  ? 'HELD · 매입 보류'
                  : `결제 상태 · ${alert.paymentStatus}`}
                <span>{alert.caseId ? `Case ${alert.caseId}` : '시연 상태'}</span>
              </div>
            </div>
          </Card>
          <Card
            title={<span className="dw-section-title">결제 정보</span>}
            className="dw-alert-card"
          >
            <Descriptions column={1} colon={false}>
              <Descriptions.Item label="결제 ID">{alert.paymentId}</Descriptions.Item>
              <Descriptions.Item label="주문">{alert.orderId}</Descriptions.Item>
              <Descriptions.Item label="고객">{alert.customer}</Descriptions.Item>
              <Descriptions.Item label="결제 금액">{won(alert.amount)}</Descriptions.Item>
              <Descriptions.Item label="현재 상태">
                <PaymentStatusTag status={alert.paymentStatus} />
              </Descriptions.Item>
            </Descriptions>
          </Card>
          <div className="dw-api-note">
            사장님 화면에는 FDS 점수와 위험 신호를 노출하지 않는 화면 구성 예시입니다.
          </div>
        </div>
      </div>
    </>
  )
}
