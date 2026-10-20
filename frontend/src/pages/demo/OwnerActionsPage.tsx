import { ReloadOutlined } from '@ant-design/icons'
import { Button, Card, Table, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { PageHeader } from '../../components/common/PageHeader'
import { StatusBadge, type PaymentStatus } from '../../components/ui/StatusBadge'
import { useDemo } from '../../demo/DemoContext'
import { incidents, type Incident } from '../../demo/mockData'
import { won } from '../../utils/format'

export function OwnerActionsPage() {
  const { rejectedOrderIds, resetOrders } = useDemo()
  const rows = incidents
    .filter((v) => v.merchant === 'CakeShop')
    .map((v) => ({
      ...v,
      status: rejectedOrderIds.includes(v.orderId) ? ('rejected' as PaymentStatus) : v.status,
    }))
  const columns: ColumnsType<Incident> = [
    {
      title: '주문번호',
      dataIndex: 'orderId',
      render: (v) => <Typography.Text strong>{v}</Typography.Text>,
    },
    { title: '가맹점', dataIndex: 'merchant' },
    { title: '거래 금액', dataIndex: 'amount', render: won, align: 'right' },
    {
      title: '처리 결과',
      dataIndex: 'status',
      render: (v: PaymentStatus) => <StatusBadge status={v} />,
    },
    { title: '기록 시각 (예시)', dataIndex: 'occurredAt' },
  ]
  return (
    <>
      <PageHeader
        title="조치 결과"
        description="내 가맹점의 결제 검토 및 조치 상태를 확인합니다."
        actions={
          <>
            <span className="dw-demo-label">LOCAL DEMO</span>
            <Button icon={<ReloadOutlined />} onClick={resetOrders}>
              시연 데이터 초기화
            </Button>
          </>
        }
      />
      <Card className="dw-table-card" style={{ borderColor: '#E2E8F0' }}>
        <div className="dw-table-toolbar">
          <div>
            <h3>CakeShop 조치 내역</h3>
            <p>사장님 화면에는 직원용 내부 근거·AI 보고서를 표시하지 않습니다.</p>
          </div>
        </div>
        <Table<Incident>
          rowKey="id"
          columns={columns}
          dataSource={rows}
          pagination={{ pageSize: 20, showSizeChanger: false }}
          scroll={{ x: 700 }}
        />
      </Card>
      <div style={{ height: 18 }} />
      <div className="dw-api-note">
        보안 직원 화면에서 DEMO-001을 시연용 반려 처리한 뒤 사장님 역할로 전환하면 상태를 확인할 수
        있습니다. 실제 결제/정산 연동은 없습니다.
      </div>
    </>
  )
}
