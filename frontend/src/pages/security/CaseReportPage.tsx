import { useEffect, useState } from 'react'
import { ArrowLeftOutlined, ReloadOutlined } from '@ant-design/icons'
import { Alert, App, Button, Card, Empty, Segmented, Space, Spin, Typography } from 'antd'
import { useNavigate, useParams } from 'react-router-dom'
import { getCase } from '../../api/cases'
import { PageHeader } from '../../components/common/PageHeader'
import { ScreenResult } from '../../components/common/ScreenResult'
import { useDemo } from '../../demo/DemoContext'
import type { AiReport, CaseDetail } from '../../types/case'
import { pickReport } from '../../utils/reportView'
import { dateTime } from './case/caseLabels'
import { ReportFull } from './case/ReportFull'

// AI 보고서 전문 (#292). Case 상세 조회 API를 그대로 쓰고, 판정은 Case 상세에서 한다
export function CaseReportPage() {
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const { merchant } = useDemo()
  const { message } = App.useApp()
  const [refreshKey, setRefreshKey] = useState(0)
  const [selected, setSelected] = useState<number | null>(null)
  // Case 상세와 같이 조회 범위와 함께 저장해 다른 Case의 늦은 응답을 버린다
  const scope = `${merchant}/${id}`
  const [loaded, setLoaded] = useState<{
    scope: string
    detail: CaseDetail | null | 'error'
  } | null>(null)
  useEffect(() => {
    let alive = true
    getCase(id, merchant)
      .then((result) => {
        if (alive) setLoaded({ scope: `${merchant}/${id}`, detail: result })
      })
      .catch(() => {
        if (!alive) return
        message.error('보고서를 불러오지 못했습니다. 잠시 후 다시 시도하세요.')
        setLoaded({ scope: `${merchant}/${id}`, detail: 'error' })
      })
    return () => {
      alive = false
    }
  }, [id, merchant, refreshKey, message])
  const result = loaded?.scope === scope ? loaded.detail : undefined
  const reload = () => setRefreshKey((k) => k + 1)

  if (result === undefined) return <Spin style={{ display: 'block', margin: '120px auto' }} />
  if (result === 'error')
    return (
      <ScreenResult
        kind="500"
        action={
          <Button type="primary" onClick={reload}>
            다시 시도
          </Button>
        }
      />
    )
  if (result === null) return <ScreenResult kind="404" />

  const { securityCase, reports, decisions } = result
  const casePath = `/security/cases/${securityCase.caseId}`
  const picked = pickReport(reports, selected, securityCase.evidenceVersion)
  // 판정이 끝났거나 종료된 Case는 돌아가기만 보여 준다
  const canDecide = securityCase.status !== 'CLOSED' && decisions.length === 0

  return (
    <>
      <PageHeader
        title="AI 조사 보고서"
        description="결론 → 근거 비교 → 경위 → 확인할 것 순서로 읽습니다. 내부 전용 (사장님 · 구매자 비공개)"
        actions={
          <Space wrap>
            {reports.length > 1 && (
              <Segmented
                value={picked?.report.reportVersion}
                onChange={(v) => setSelected(Number(v))}
                options={[...reports]
                  .sort((a, b) => a.reportVersion - b.reportVersion)
                  .map((r) => ({ value: r.reportVersion, label: `v${r.reportVersion}` }))}
              />
            )}
            <Button icon={<ReloadOutlined />} onClick={reload}>
              새로고침
            </Button>
            <Button icon={<ArrowLeftOutlined />} onClick={() => navigate(casePath)}>
              Case로
            </Button>
            {canDecide && (
              <Button type="primary" onClick={() => navigate(casePath)}>
                판정하러 가기
              </Button>
            )}
          </Space>
        }
      />
      {!picked ? (
        <Card style={{ borderColor: '#E2E8F0' }}>
          <Empty description="요청된 보고서가 없습니다." />
        </Card>
      ) : (
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            보고서 v{picked.report.reportVersion} · 증거 v{picked.report.evidenceVersion} · 요청{' '}
            {dateTime(picked.report.createdAt)}
            {picked.report.completedAt && ` · 완료 ${dateTime(picked.report.completedAt)}`}
          </Typography.Text>
          {!picked.isLatest && (
            <Alert type="info" showIcon message="이전 버전 보고서를 보고 있습니다." />
          )}
          {picked.evidenceMismatch && (
            <Alert
              type="warning"
              showIcon
              message="현재 Case 증거 버전과 다른 보고서입니다."
              description={`Case는 증거 v${securityCase.evidenceVersion}입니다. 이 보고서로 최종 판정할 수 없습니다.`}
            />
          )}
          <ReportState
            report={picked.report}
            label={`Case ${securityCase.caseId} · ${securityCase.customerRef} · 보고서 v${picked.report.reportVersion}`}
          />
        </Space>
      )}
    </>
  )
}

function ReportState({ report, label }: { report: AiReport; label: string }) {
  if (report.status === 'PENDING' || report.status === 'RUNNING') {
    return (
      <Card style={{ borderColor: '#E2E8F0', textAlign: 'center', padding: '20px 0' }}>
        <Spin />
        <Typography.Paragraph type="secondary" style={{ marginTop: 12 }}>
          AI가 Case를 조사하고 있습니다. 완료되면 새로고침으로 확인하세요.
        </Typography.Paragraph>
      </Card>
    )
  }
  if (report.status === 'FAILED') {
    return (
      <Alert
        type="error"
        showIcon
        message="AI 보고서 생성이 최종 실패했습니다 (총 3회 시도)."
        description="자동 판정은 하지 않습니다. Case 상세에서 직접 근거를 조사한 뒤 수동 판정하고, 사유에 조사 근거를 기록하세요."
      />
    )
  }
  if (!report.reportBody) return <Empty description="보고서 내용이 없습니다." />
  return <ReportFull body={report.reportBody} label={label} />
}
