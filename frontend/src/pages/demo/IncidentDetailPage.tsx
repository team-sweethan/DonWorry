import { ArrowLeftOutlined, ExclamationCircleOutlined } from '@ant-design/icons'
import { Alert, Button, Card, Descriptions, Space, Typography } from 'antd'
import { useNavigate, useParams } from 'react-router-dom'
import { PageHeader } from '../../components/common/PageHeader'
import { useFeedback } from '../../components/common/Feedback'
import { ScreenResult } from '../../components/common/ScreenResult'
import { StatusBadge } from '../../components/ui/StatusBadge'
import { useDemo } from '../../demo/DemoContext'
import { incidents } from '../../demo/mockData'
import { won } from '../../utils/format'

export function IncidentDetailPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const { merchant, rejectedOrderIds, rejectOrder, resetOrders } = useDemo()
  const { toast, confirmDanger } = useFeedback()
  const incident = incidents.find((x) => x.id === id && x.merchant === merchant)
  if (!incident) return <ScreenResult kind="404" />
  const rejected = rejectedOrderIds.includes(incident.orderId) || incident.status === 'rejected'
  const reject = () =>
    confirmDanger({
      title: '이 결제를 반려할까요?',
      content: `${incident.orderId} / ${won(incident.amount)} — 이 동작은 로컬 시연 데이터만 변경합니다. 실제 결제는 처리되지 않습니다.`,
      onConfirm: () => {
        rejectOrder(incident.orderId)
        toast.success('시연용 사건 상태가 결제 반려로 변경되었습니다.')
      },
    })
  return (
    <>
      <PageHeader
        title="사건 상세"
        description={`${incident.id} · 주문번호 ${incident.orderId}`}
        actions={
          <Space>
            <span className="dw-demo-label">LOCAL DEMO</span>
            <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/security/incidents')}>
              목록으로
            </Button>
          </Space>
        }
      />
      <div className="dw-section-grid">
        <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
          <Card
            title={<span className="dw-section-title">주문 · 결제 정보</span>}
            style={{ borderColor: '#E2E8F0' }}
          >
            <Descriptions
              column={2}
              colon={false}
              size="middle"
              labelStyle={{ color: '#64748b', fontSize: 12 }}
              contentStyle={{ fontWeight: 600 }}
            >
              <Descriptions.Item label="주문번호">{incident.orderId}</Descriptions.Item>
              <Descriptions.Item label="가맹점">{incident.merchant}</Descriptions.Item>
              <Descriptions.Item label="결제 금액">{won(incident.amount)}</Descriptions.Item>
              <Descriptions.Item label="발생 시각">{incident.occurredAt}</Descriptions.Item>
              <Descriptions.Item label="사건 상태">
                <StatusBadge status={rejected ? 'rejected' : incident.status} />
              </Descriptions.Item>
              <Descriptions.Item label="탐지 분류">{incident.category}</Descriptions.Item>
            </Descriptions>
          </Card>
          <Card
            title={<span className="dw-section-title">탐지 근거</span>}
            style={{ borderColor: '#E2E8F0' }}
          >
            <Alert
              type="warning"
              showIcon
              message="확인이 필요한 거래 패턴이 감지되었습니다."
              description="현재 내용은 시연용 예시이며 실제 모델 탐지 결과가 아닙니다."
            />
          </Card>
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
          <Card
            title={<span className="dw-section-title">AI 보고서</span>}
            extra={<span className="dw-demo-label">예시 데이터</span>}
            style={{ borderColor: '#E2E8F0' }}
          >
            <Typography.Paragraph strong>위험 요약</Typography.Paragraph>
            <Typography.Paragraph style={{ color: '#64748b', fontSize: 13 }}>
              거래 패턴을 추가로 검토하는 화면의 레이아웃 예시입니다. 실제 AI 분석 API와 점수는
              연결되어 있지 않습니다.
            </Typography.Paragraph>
            <div className="dw-api-note">
              실제 AI 보고서 제공 범위 및 API 응답 계약은 P3·P4 협의 후 확정
            </div>
          </Card>
          <Card
            title={<span className="dw-section-title">조치</span>}
            style={{ borderColor: '#E2E8F0' }}
          >
            <Typography.Paragraph style={{ fontSize: 13, color: '#64748b' }}>
              반려 실행 전 확인창을 표시하며, 중복 처리된 거래는 다시 반려할 수 없습니다.
            </Typography.Paragraph>
            <Space>
              <Button
                type="primary"
                danger
                icon={<ExclamationCircleOutlined />}
                disabled={rejected}
                onClick={reject}
              >
                결제 반려 (시연)
              </Button>
              {rejected && incident.status !== 'rejected' && (
                <Button
                  onClick={() => {
                    resetOrders()
                    toast.info('시연 데이터가 초기화되었습니다.')
                  }}
                >
                  시연 초기화
                </Button>
              )}
            </Space>
          </Card>
        </div>
      </div>
    </>
  )
}
