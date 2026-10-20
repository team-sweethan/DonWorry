import { ApiOutlined, InfoCircleOutlined } from '@ant-design/icons'
import { Button, Card, Empty, Typography } from 'antd'
import { useNavigate } from 'react-router-dom'
import { roles } from '../../config/navigation'
import { useDemo } from '../../demo/DemoContext'
import { PageHeader } from '../../components/common/PageHeader'

export function PlaceholderPage({ title, description }: { title: string; description: string }) {
  const { role } = useDemo()
  const navigate = useNavigate()
  return (
    <>
      <PageHeader
        title={title}
        description={description}
        actions={<span className="dw-demo-label">구현 담당 연동 예정</span>}
      />
      <Card style={{ borderColor: '#E2E8F0' }}>
        <Empty
          style={{ padding: 38 }}
          image={<ApiOutlined style={{ fontSize: 42, color: '#94a3b8' }} />}
          description={
            <>
              <Typography.Title level={5} style={{ margin: '8px 0' }}>
                공통 레이아웃 연결 완료
              </Typography.Title>
              <Typography.Text type="secondary">
                이 영역에 담당자의 실제 기능 페이지가 들어갑니다.
              </Typography.Text>
            </>
          }
        >
          <Button icon={<InfoCircleOutlined />} onClick={() => navigate(roles[role].landing)}>
            현재 역할 홈으로
          </Button>
        </Empty>
      </Card>
    </>
  )
}
