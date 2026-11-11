import { useCallback, useEffect, useRef, useState } from 'react'
import { ArrowLeftOutlined, PrinterOutlined, ReloadOutlined } from '@ant-design/icons'
import { Alert, App, Button, Card, Empty, Segmented, Space, Spin, Tabs, Typography } from 'antd'
import { useLocation, useNavigate, useParams } from 'react-router-dom'
import { getCase, isCaseAssigneeOnly } from '../../api/cases'
import { PageHeader } from '../../components/common/PageHeader'
import { ScreenResult } from '../../components/common/ScreenResult'
import type { AiReport, CaseDetail } from '../../types/case'
import { caseListReturnPath } from '../../utils/caseListQuery'
import { customerPart } from '../../utils/caseRules'
import { decisionProgress, pickReport } from '../../utils/reportView'
import { CaseForbidden } from './case/CaseForbidden'
import { dateTime } from './case/caseLabels'
import { ReportFull } from './case/ReportFull'
import type { NowProgress } from './case/ReportPastCompare'
import { ReportPrintMarks } from './case/ReportPrint'
import { ReportQuestions } from './case/ReportQuestions'
import { ReportTrace } from './case/ReportTrace'

// AI 보고서 전문 (#292). Case 상세 조회 API를 그대로 쓰고, 판정은 Case 상세에서 한다
export function CaseReportPage() {
  const { id = '' } = useParams()
  const navigate = useNavigate()
  // Case 상세에서 받은 목록 복귀 조건을 돌아갈 때 다시 넘긴다 (#243)
  const listReturn = useLocation().state
  const { message } = App.useApp()
  const [refreshKey, setRefreshKey] = useState(0)
  const [selected, setSelected] = useState<number | null>(null)
  // 버전을 바꿔도 보던 탭을 유지해 v1 · v2의 조사 과정을 바로 비교할 수 있게 한다 (#294)
  const [tab, setTab] = useState<'report' | 'trace'>('report')
  // 인쇄는 보고서 탭만 찍는다 (#623) — 조사 과정 탭에서 Ctrl+P를 눌러도 보고서로 바꿔 찍고 끝나면 되돌린다
  const tabBeforePrint = useRef<'report' | 'trace' | null>(null)
  const showReportForPrint = useCallback(() => {
    setTab((now) => {
      // 인쇄 직전 신호가 두 번 와도 처음 보던 탭을 잃지 않는다
      if (tabBeforePrint.current === null) tabBeforePrint.current = now
      return 'report'
    })
  }, [])
  const restoreTabAfterPrint = useCallback(() => {
    const before = tabBeforePrint.current
    tabBeforePrint.current = null
    if (before) setTab(before)
  }, [])
  // Case 상세와 같이 조회 범위와 함께 저장해 다른 Case의 늦은 응답을 버린다
  const scope = id
  const [loaded, setLoaded] = useState<{
    scope: string
    detail: CaseDetail | null | 'error' | 'forbidden'
  } | null>(null)
  useEffect(() => {
    let alive = true
    getCase(id)
      .then((result) => {
        if (alive) setLoaded({ scope: id, detail: result })
      })
      .catch((error: unknown) => {
        if (!alive) return
        // 담당이 아닌 진행 중 사건 (#612)
        if (isCaseAssigneeOnly(error)) {
          setLoaded({ scope: id, detail: 'forbidden' })
          return
        }
        message.error('보고서를 불러오지 못했습니다. 잠시 후 다시 시도하세요.')
        setLoaded({ scope: id, detail: 'error' })
      })
    return () => {
      alive = false
    }
  }, [id, refreshKey, message])
  const result = loaded?.scope === scope ? loaded.detail : undefined
  const reload = () => setRefreshKey((k) => k + 1)

  if (result === undefined) return <Spin style={{ display: 'block', margin: '120px auto' }} />
  if (result === 'forbidden') return <CaseForbidden listPath={caseListReturnPath(listReturn)} />
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
  const progress = decisionProgress(result)
  const printable = picked?.report.status === 'SUCCEEDED' && !!picked.report.reportBody

  return (
    <div className="dw-report-print">
      <PageHeader
        title="AI 조사 보고서"
        description="무슨 일인가 → 확인할 것 → 지난 사건과 비교 → 근거 자료 → 조사 범위 순서로 읽습니다. 내부 전용 (사장님 · 구매자 비공개)"
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
            {printable && (
              // 브라우저 인쇄 창 — 대상에서 "PDF로 저장"을 고른다. 출력 기록은 ReportPrintMarks가 남긴다
              <Button icon={<PrinterOutlined />} onClick={() => window.print()}>
                PDF로 저장
              </Button>
            )}
            <Button icon={<ReloadOutlined />} onClick={reload}>
              새로고침
            </Button>
            <Button
              icon={<ArrowLeftOutlined />}
              onClick={() => navigate(casePath, { state: listReturn })}
            >
              Case로
            </Button>
            {canDecide && (
              <Button type="primary" onClick={() => navigate(casePath, { state: listReturn })}>
                판정하러 가기
              </Button>
            )}
          </Space>
        }
      />
      {/* Space 밖에 둔다 — 화면에서 숨긴 칸도 Space 간격을 차지한다 */}
      {picked && printable && (
        <ReportPrintMarks
          caseId={securityCase.caseId}
          reportId={picked.report.reportId}
          heading={`Case ${securityCase.caseId} · 보고서 v${picked.report.reportVersion} · 증거 v${picked.report.evidenceVersion}`}
          onBeforePrint={showReportForPrint}
          onAfterPrint={restoreTabAfterPrint}
        />
      )}
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
          {picked.report.status === 'SUCCEEDED' && picked.report.reportBody && (
            <Card
              className="dw-no-print"
              style={{ borderColor: '#E2E8F0' }}
              styles={{ body: { padding: '0 16px' } }}
            >
              <Tabs
                activeKey={tab}
                onChange={(key) => setTab(key === 'trace' ? 'trace' : 'report')}
                items={[
                  { key: 'report', label: '보고서' },
                  { key: 'trace', label: '조사 과정' },
                ]}
                tabBarStyle={{ marginBottom: 0 }}
              />
            </Card>
          )}
          {/* 조사 과정 탭에서도 보고서를 지우지 않고 숨긴다 (#623 Codex 리뷰) — 다시 그리면 ML 참고 · 지난 사건을
              재조회해 인쇄 직전 전환 때 "불러오는 중"으로 찍히고, ② 확인할 것 체크도 지워진다 */}
          <div>
            {tab === 'trace' &&
              picked.report.status === 'SUCCEEDED' &&
              picked.report.reportBody && (
                <ReportTrace trace={picked.report.reportBody.investigationTrace} />
              )}
            <div style={tab === 'trace' && printable ? { display: 'none' } : undefined}>
              <ReportState
                report={picked.report}
                label={`Case ${securityCase.caseId} · 고객 ${customerPart(securityCase.customerRef)} · 보고서 v${picked.report.reportVersion}`}
                refreshKey={refreshKey}
                progress={progress}
                evidenceMismatch={picked.evidenceMismatch}
                onDecide={canDecide ? () => navigate(casePath, { state: listReturn }) : undefined}
              />
            </div>
          </div>
        </Space>
      )}
    </div>
  )
}

function ReportState({
  report,
  label,
  refreshKey,
  progress,
  evidenceMismatch,
  onDecide,
}: {
  report: AiReport
  label: string
  refreshKey: number
  progress: NowProgress
  evidenceMismatch: boolean
  onDecide?: () => void
}) {
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
  // 버전마다 새로 그린다 — 판정 전에 확인할 것 체크가 다른 버전으로 넘어가지 않게 (#591)
  // 질문 칸도 보고서마다 새로 그린다 (#753) — 질문은 보고서 하나에 묻는다. [새로고침]이면 목록도 다시 받는다
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      <ReportFull
        key={report.reportId}
        body={report.reportBody}
        label={label}
        refreshKey={refreshKey}
        progress={progress}
        onDecide={onDecide}
      />
      <ReportQuestions
        key={`${report.reportId}:${refreshKey}`}
        caseId={report.caseId}
        reportId={report.reportId}
        body={report.reportBody}
        evidenceMismatch={evidenceMismatch}
      />
    </div>
  )
}
