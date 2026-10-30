import { useState } from 'react'
import { Alert, Button, Card, Descriptions, Space, Steps, Table, Tag, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { PageHeader } from '../../components/common/PageHeader'
import './integration-preview.css'

/**
 * 사장님 [시크릿 키 발급] 미리보기 (#354).
 * 키 발급 API(PR B) 전이라 서버를 호출하지 않는다. 실제 가맹점 값으로 오해하지 않도록 모든 값에 "예시"를 붙인다.
 */
type IssueRecord = { at: string; action: string; actor: string }

const exampleRecords: IssueRecord[] = [
  { at: '2026-10-30 13:55', action: '발급', actor: '사장님 (myshop_owner)' },
]

const recordColumns: ColumnsType<IssueRecord> = [
  { title: '시각', dataIndex: 'at', width: 200 },
  { title: '작업', dataIndex: 'action', width: 120 },
  { title: '실행', dataIndex: 'actor' },
]

// 연결 확인 API는 PR C에서 정한다. 경로는 형식만 보여 주는 예시다.
const requestExample = `# 연결 확인 요청 예시 (주소·값 모두 예시)
curl -X POST https://api.donworry.example/api/v1/merchant-connection/check \\
  -H "X-Merchant-Code: {가맹점 코드}" \\
  -H "X-Timestamp: {Unix 초}" \\
  -H "X-Nonce: {요청마다 새 UUID}" \\
  -H "X-Signature: {시크릿 키로 만든 서명}"`

function ExampleTag() {
  return <Tag className="dw-integration-example">예시</Tag>
}

export function IntegrationPreviewPage() {
  const [showCautions, setShowCautions] = useState(false)

  return (
    <>
      <PageHeader
        title="시크릿 키 발급"
        description="쇼핑몰 서버가 돈워리 결제 API를 쓸 수 있도록 시크릿 키를 발급하고 연결 상태를 확인합니다."
      />
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="직접 발급 기능은 준비 중입니다"
        description="지금은 담당자가 설정한 임시 시크릿 키(서버 환경변수)로 연결합니다. 발급 기능이 열리면 이 화면에서 직접 키를 받을 수 있습니다."
      />

      <Card
        className="dw-integration-preview"
        style={{ marginBottom: 16, borderColor: '#E2E8F0' }}
        title={
          <Space size={8}>
            내 연동 정보
            <ExampleTag />
          </Space>
        }
        extra={<Tag color="default">예시 화면 · 준비 중</Tag>}
      >
        <Descriptions column={1} size="middle" colon={false} labelStyle={{ width: 140 }}>
          <Descriptions.Item label="연동 상태">
            <Tag color="blue">연동 전</Tag>
          </Descriptions.Item>
          <Descriptions.Item label="가맹점 코드">
            <Typography.Text code>MYSHOP</Typography.Text>
          </Descriptions.Item>
          <Descriptions.Item label="시크릿 키">
            <Typography.Text code>dw_sk_••••••••••••••••a3f9</Typography.Text>
          </Descriptions.Item>
          <Descriptions.Item label="마지막 연결 확인">
            아직 연결 확인 요청이 없습니다
          </Descriptions.Item>
        </Descriptions>
        <Space wrap style={{ marginTop: 16 }}>
          <Button type="primary" disabled>
            시크릿 키 발급
          </Button>
          <Button disabled>다시 보기</Button>
          <Button disabled>복사</Button>
          <Button disabled>재발급</Button>
          <Typography.Text type="secondary">
            키를 보거나 재발급할 때 비밀번호를 한 번 더 확인합니다
          </Typography.Text>
        </Space>
        <div className="dw-section-title" style={{ margin: '24px 0 12px' }}>
          <Space size={8}>
            발급 기록
            <ExampleTag />
          </Space>
        </div>
        <Table
          rowKey="at"
          size="small"
          columns={recordColumns}
          dataSource={exampleRecords}
          pagination={false}
        />
      </Card>

      <Card title="연동은 3단계로 끝나요" style={{ marginBottom: 16, borderColor: '#E2E8F0' }}>
        <Steps
          className="dw-integration-steps"
          responsive
          items={[
            {
              status: 'process',
              title: '시크릿 키 발급',
              description: (
                <>
                  이 화면에서 키를 받습니다.
                  <br />
                  <Tag color="blue">→ 연동 전</Tag>
                  <br />
                  연동 전까지는 키를 다시 볼 수 있어요.
                </>
              ),
            },
            {
              status: 'process',
              title: '쇼핑몰에 설정',
              description: (
                <>
                  쇼핑몰 서버에 가맹점 코드와 키를 넣습니다.
                  <br />
                  개발자에게 연동 문서를 전달하세요.
                </>
              ),
            },
            {
              status: 'process',
              title: '연결 확인',
              description: (
                <>
                  쇼핑몰이 확인 요청을 보내면 끝납니다.
                  <br />
                  <Tag color="green">→ 연동 완료 · 결제 가능</Tag>
                  <br />
                  이후 키는 숨겨지고 재발급만 가능해요.
                </>
              ),
            },
          ]}
        />
      </Card>

      <Card title="요청 예시" style={{ borderColor: '#E2E8F0' }}>
        <Typography.Paragraph type="secondary">
          쇼핑몰 서버는 요청마다 아래 헤더를 붙입니다. 서명 만드는 방법은 연동 문서를 참고하세요.
        </Typography.Paragraph>
        <pre className="dw-integration-code">{requestExample}</pre>
        <Typography.Paragraph type="secondary" style={{ marginTop: 8 }}>
          시크릿 키 자체는 절대 요청에 넣지 않습니다. 키로 만든 <strong>서명</strong>만 보냅니다.
        </Typography.Paragraph>
        <div className="dw-integration-foot">
          <Typography.Text type="secondary">
            쇼핑몰 개발자용 연동 문서는 준비 중입니다.
          </Typography.Text>
          <Button type="link" onClick={() => setShowCautions((open) => !open)}>
            키를 다룰 때 주의할 점 {showCautions ? '접기' : '보기'}
          </Button>
        </div>
        {showCautions && (
          <ul className="dw-integration-cautions">
            <li>재발급하면 이전 키는 바로 막히고, 새 키로 연결 확인할 때까지 결제가 멈춥니다.</li>
            <li>키를 다른 사람에게 보여 주거나 메일·메신저로 보내지 마세요.</li>
          </ul>
        )}
      </Card>
    </>
  )
}
