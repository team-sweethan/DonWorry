import { useState } from 'react'
import { BellOutlined, DeleteOutlined, PlusOutlined, SearchOutlined } from '@ant-design/icons'
import {
  Alert,
  Button,
  Card,
  Checkbox,
  DatePicker,
  Divider,
  Empty,
  Input,
  Modal,
  Radio,
  Select,
  Skeleton,
  Space,
  Spin,
  Switch,
  Table,
  Tabs,
  Tag,
  Tooltip,
  Typography,
} from 'antd'
import { PageHeader } from '../../components/common/PageHeader'
import { useFeedback } from '../../components/common/Feedback'
import { StatusBadge } from '../../components/ui/StatusBadge'
import { tokens } from '../../styles/tokens'
import { won } from '../../utils/format'

export function ComponentShowcasePage() {
  const { toast, confirmDanger } = useFeedback()
  const [modal, setModal] = useState(false)
  const swatches = [
    ['Sidebar', tokens.colors.sidebar],
    ['Primary', tokens.colors.primary],
    ['Accent', tokens.colors.accent],
    ['Canvas', tokens.colors.canvas],
    ['Surface', tokens.colors.surface],
    ['Success', tokens.colors.success],
    ['Warning', tokens.colors.warning],
    ['Danger', tokens.colors.danger],
  ]
  return (
    <>
      <PageHeader
        title="공통 UI 가이드"
        description="팀원이 재사용할 Ant Design 컴포넌트와 돈워리 테마를 확인합니다."
        actions={<span className="dw-demo-label">DESIGN SYSTEM v1.0</span>}
      />
      <div className="dw-api-note" style={{ marginBottom: 20 }}>
        디자인 규격은 첨부 시안 기반 제안값입니다. 실제 운영 화면 구현 전 팀 승인 후 사용하세요.
      </div>
      <Card
        title={<span className="dw-section-title">01 · Color tokens</span>}
        style={{ marginBottom: 18 }}
      >
        <div className="dw-spec-swatches">
          {swatches.map(([label, value]) => (
            <div key={label}>
              <div className="dw-swatch" style={{ background: value }} />
              <div className="dw-spec-label">{label}</div>
              <div className="dw-spec-code">{value}</div>
            </div>
          ))}
        </div>
      </Card>
      <div className="dw-section-grid" style={{ marginBottom: 18 }}>
        <Card title={<span className="dw-section-title">02 · Button</span>}>
          <Space wrap>
            <Button type="primary" icon={<PlusOutlined />}>
              Primary
            </Button>
            <Button>Secondary</Button>
            <Button danger icon={<DeleteOutlined />}>
              Danger
            </Button>
            <Button type="link">Text link</Button>
            <Button type="primary" loading>
              Loading
            </Button>
            <Button disabled>Disabled</Button>
          </Space>
          <Divider style={{ margin: '18px 0' }} />
          <Space>
            <Button size="small">Small 32</Button>
            <Button>Medium 36</Button>
            <Button size="large">Large 44</Button>
          </Space>
        </Card>
        <Card title={<span className="dw-section-title">03 · StatusBadge</span>}>
          <Space wrap size={[8, 12]}>
            <StatusBadge status="review" />
            <StatusBadge status="inProgress" />
            <StatusBadge status="done" />
            <StatusBadge status="rejected" />
            <StatusBadge status="approved" />
            <StatusBadge status="alert" />
          </Space>
          <Divider style={{ margin: '18px 0' }} />
          <Space>
            <Tag color="blue">기본 태그</Tag>
            <Tag>일반 태그</Tag>
          </Space>
        </Card>
        <Card title={<span className="dw-section-title">04 · Form controls</span>}>
          <Space direction="vertical" style={{ width: '100%' }} size={12}>
            <Input placeholder="사건번호를 입력하세요" prefix={<SearchOutlined />} />
            <Input status="error" placeholder="오류 상태" />
            <Select
              placeholder="가맹점 선택"
              style={{ width: '100%' }}
              options={[
                { label: 'CakeShop', value: 'cake' },
                { label: 'DemoMart', value: 'mart' },
              ]}
            />
            <DatePicker placeholder="날짜 선택" style={{ width: '100%' }} />
            <Space>
              <Checkbox defaultChecked>Checkbox</Checkbox>
              <Radio defaultChecked>Radio</Radio>
              <Switch size="small" defaultChecked />
            </Space>
          </Space>
        </Card>
        <Card title={<span className="dw-section-title">05 · Feedback</span>}>
          <Space wrap>
            <Button onClick={() => toast.success('저장되었습니다.')}>Success toast</Button>
            <Button onClick={() => toast.error('잠시 후 다시 시도하세요.')}>Error toast</Button>
            <Button type="primary" onClick={() => setModal(true)}>
              Modal 열기
            </Button>
            <Button
              danger
              onClick={() =>
                confirmDanger({
                  title: '삭제하시겠습니까?',
                  content: '로컬 시연용 확인창입니다.',
                  onConfirm: () => {
                    toast.success('시연 동작 완료')
                  },
                })
              }
            >
              Confirm
            </Button>
          </Space>
          <Divider style={{ margin: '18px 0' }} />
          <Alert type="info" showIcon message="정보 알림: 중요한 안내는 화면에 유지합니다." />
        </Card>
      </div>
      <Card
        title={<span className="dw-section-title">06 · Table & Pagination</span>}
        style={{ marginBottom: 18 }}
      >
        <Table
          rowKey="id"
          columns={[
            { title: '사건번호', dataIndex: 'id' },
            { title: '금액', dataIndex: 'amount', render: won },
            { title: '상태', dataIndex: 'status', render: () => <StatusBadge status="review" /> },
          ]}
          dataSource={[
            { id: 'INC-001', amount: 32000, status: 'review' },
            { id: 'INC-002', amount: 58000, status: 'review' },
          ]}
          pagination={{
            pageSize: 20,
            showSizeChanger: false,
            showTotal: (total) => `총 ${total}건`,
          }}
        />
      </Card>
      <div className="dw-section-grid">
        <Card title={<span className="dw-section-title">07 · Loading / Empty</span>}>
          <Space direction="vertical" style={{ width: '100%' }} size={16}>
            <Spin />
            <Skeleton active paragraph={{ rows: 2 }} />
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="조회 결과가 없습니다." />
          </Space>
        </Card>
        <Card title={<span className="dw-section-title">08 · Etc.</span>}>
          <Space direction="vertical" style={{ width: '100%' }}>
            <Tabs
              items={[
                { key: 'a', label: '전체', children: '전체 탭' },
                { key: 'b', label: '검토 대기', children: '대기 탭' },
              ]}
            />
            <Tooltip title="키보드 포커스도 지원">
              <Button icon={<BellOutlined />}>Tooltip</Button>
            </Tooltip>
            <Typography.Text type="secondary">
              기본 본문 14px · 제목 24px · Radius 6px
            </Typography.Text>
          </Space>
        </Card>
      </div>
      <Modal
        title="공통 Modal 예시"
        open={modal}
        width={480}
        onCancel={() => setModal(false)}
        onOk={() => {
          setModal(false)
          toast.success('확인했습니다.')
        }}
        okText="확인"
        cancelText="취소"
      >
        <p>모달은 확인과 취소를 제공하며, ESC를 눌러 닫을 수 있습니다.</p>
      </Modal>
    </>
  )
}
