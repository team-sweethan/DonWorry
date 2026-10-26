import { useEffect, useState } from 'react'
import { ArrowLeftOutlined, ReloadOutlined } from '@ant-design/icons'
import { Alert, App, Button, Card, Descriptions, Space, Spin, Table, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useNavigate, useParams } from 'react-router-dom'
import { getMerchantCase } from '../../api/merchantCases'
import { ApiError } from '../../api/client'
import { PageHeader } from '../../components/common/PageHeader'
import { ScreenResult } from '../../components/common/ScreenResult'
import type { MerchantCaseDetail, MerchantPaymentResult } from '../../types/merchantCase'
import { won } from '../../utils/format'
import { dateTime, paymentStatusLabel, recommendationLabel } from '../security/case/caseLabels'
import { CodeTag } from '../security/case/CodeTag'
import { isApplyingDecision, merchantCaseStatusLabel, merchantDecisionLabel } from './ownerLabels'

/**
 * 고객사 "보안 조치 결과" 상세 (fds 정본 02 §2.12 ②, #205).
 * 직원 판정과 실제 금융 처리 결과(P5 결제 상태)를 구분해 보여준다. 적중 규칙·증거·판정 사유는 서버가 보내지 않는다.
 */
export function MerchantCaseDetailPage() {
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const { message } = App.useApp()
  const [refreshKey, setRefreshKey] = useState(0)
  // detail: null은 404(없는 Case · 다른 가맹점 Case), 'error'는 일시 장애, 'forbidden'은 권한 없음
  const [loaded, setLoaded] = useState<{
    key: string
    detail: MerchantCaseDetail | null | 'error' | 'forbidden'
  } | null>(null)
  const key = `${id}/${refreshKey}`

  useEffect(() => {
    let alive = true
    const current = `${id}/${refreshKey}`
    getMerchantCase(id)
      .then((detail) => {
        if (alive) setLoaded({ key: current, detail })
      })
      .catch((error: unknown) => {
        if (!alive) return
        if (error instanceof ApiError && error.status === 403) {
          setLoaded({ key: current, detail: 'forbidden' })
          return
        }
        message.error('조치 결과를 불러오지 못했습니다. 잠시 후 다시 시도하세요.')
        setLoaded({ key: current, detail: 'error' })
      })
    return () => {
      alive = false
    }
  }, [id, refreshKey, message])

  const result = loaded?.key === key ? loaded.detail : undefined
  if (result === undefined) return <Spin style={{ display: 'block', margin: '120px auto' }} />
  if (result === 'forbidden') return <ScreenResult kind="403" homePath="/owner" />
  if (result === 'error')
    return (
      <ScreenResult
        kind="500"
        action={
          <Button type="primary" onClick={() => setRefreshKey((k) => k + 1)}>
            다시 시도
          </Button>
        }
      />
    )
  if (result === null) return <ScreenResult kind="404" homePath="/owner/actions" />

  const columns: ColumnsType<MerchantPaymentResult> = [
    { title: '결제 ID', dataIndex: 'paymentId', width: 110 },
    {
      title: '금액',
      dataIndex: 'amount',
      align: 'right',
      width: 120,
      render: (v: number | null) => (v === null ? '-' : won(v)),
    },
    {
      title: '결제 시각',
      dataIndex: 'requestedAt',
      width: 150,
      render: (v: string | null) => (v ? dateTime(v) : '-'),
    },
    {
      title: '보안 담당자 판정',
      dataIndex: 'decision',
      width: 140,
      render: (v: MerchantPaymentResult['decision']) =>
        v ? (
          <CodeTag value={merchantDecisionLabel[v]} />
        ) : (
          <span style={{ color: '#94a3b8' }}>판정 전</span>
        ),
    },
    {
      title: '실제 결제 상태',
      key: 'paymentStatus',
      width: 170,
      render: (_, p) => (
        <Space size={4} wrap>
          <CodeTag value={p.paymentStatus ? paymentStatusLabel[p.paymentStatus] : undefined} />
          {isApplyingDecision(p.decision, p.paymentStatus) && (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              판정 반영 처리 중
            </Typography.Text>
          )}
        </Space>
      ),
    },
    {
      title: 'AI 결론',
      dataIndex: 'aiRecommendation',
      width: 130,
      render: (v: MerchantPaymentResult['aiRecommendation']) =>
        v ? <CodeTag value={recommendationLabel[v]} /> : '-',
    },
  ]

  return (
    <>
      <PageHeader
        title={`Case ${result.caseId}`}
        description="결제별 보안 담당자 판정과 실제 결제 처리 결과를 함께 확인합니다."
        actions={
          <>
            <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/owner/actions')}>
              목록
            </Button>
            <Button icon={<ReloadOutlined />} onClick={() => setRefreshKey((k) => k + 1)}>
              새로고침
            </Button>
          </>
        }
      />
      <Card style={{ marginBottom: 16, borderColor: '#E2E8F0' }}>
        <Descriptions column={{ xs: 1, sm: 2, lg: 4 }} size="small">
          <Descriptions.Item label="상태">
            <CodeTag value={merchantCaseStatusLabel[result.displayStatus]} />
          </Descriptions.Item>
          <Descriptions.Item label="연관 결제">{result.payments.length}건</Descriptions.Item>
          <Descriptions.Item label="발생 시각">{dateTime(result.createdAt)}</Descriptions.Item>
          <Descriptions.Item label="조사 종료">
            {result.closedAt ? dateTime(result.closedAt) : '-'}
          </Descriptions.Item>
        </Descriptions>
      </Card>
      <Card title="조사 결과 요약" style={{ marginBottom: 16, borderColor: '#E2E8F0' }}>
        {result.directInvestigation && (
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: result.summary ? 12 : 0 }}
            message="보안 담당자 직접 조사"
            description="AI 보고서 없이 보안 담당자가 직접 조사해 판정한 결제가 있습니다."
          />
        )}
        {result.summary ? (
          <ul style={{ margin: 0, paddingLeft: 20 }}>
            {result.summary.map((line, i) => (
              <li key={i}>{line}</li>
            ))}
          </ul>
        ) : (
          !result.directInvestigation && (
            <Typography.Text type="secondary">
              {result.displayStatus === 'INVESTIGATING'
                ? '조사 중입니다. 결과가 나오면 이곳에 요약이 표시됩니다.'
                : '표시할 조사 요약이 없습니다.'}
            </Typography.Text>
          )
        )}
      </Card>
      <Card className="dw-table-card" style={{ borderColor: '#E2E8F0' }}>
        <div className="dw-table-toolbar">
          <div>
            <h3>결제별 결과</h3>
            <p>
              보안 담당자 판정과 실제 결제 상태는 다를 수 있습니다. 실제 결과는 결제 상태를 기준으로
              봅니다.
            </p>
          </div>
        </div>
        <Table
          rowKey="paymentId"
          columns={columns}
          dataSource={result.payments}
          size="middle"
          scroll={{ x: 820 }}
          pagination={false}
        />
      </Card>
    </>
  )
}
