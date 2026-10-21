import { useState, type ReactNode } from 'react'
import { Alert, Card, Descriptions, Empty, Segmented, Space, Spin, Table, Typography } from 'antd'
import type { AiReport, ReportBody } from '../../../types/case'
import { latestReport } from '../../../utils/caseRules'
import { won } from '../../../utils/format'
import { dateTime, recommendationLabel, reportStatusLabel } from './caseLabels'
import { CodeTag } from './CodeTag'

/** AI 보고서(P4 소유)를 표시만 한다. report_body는 기획 2.7 표준 구성 순서로 보여준다. */
export function ReportCard({
  reports,
  evidenceVersion,
}: {
  reports: AiReport[]
  evidenceVersion: number
}) {
  const latest = latestReport(reports)
  const [selected, setSelected] = useState(latest?.reportVersion ?? 0)
  const report = reports.find((r) => r.reportVersion === selected) ?? latest

  return (
    <Card
      title={<span className="dw-section-title">AI 조사 보고서</span>}
      extra={
        reports.length > 1 && (
          <Segmented
            size="small"
            value={report?.reportVersion}
            onChange={(v) => setSelected(Number(v))}
            options={[...reports]
              .sort((a, b) => a.reportVersion - b.reportVersion)
              .map((r) => ({ value: r.reportVersion, label: `v${r.reportVersion}` }))}
          />
        )
      }
      style={{ borderColor: '#E2E8F0' }}
    >
      {!report ? (
        <Empty description="요청된 보고서가 없습니다." />
      ) : (
        <Space direction="vertical" size={14} style={{ width: '100%' }}>
          <Space size={8} wrap>
            <CodeTag value={reportStatusLabel[report.status]} />
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              보고서 v{report.reportVersion} · 증거 v{report.evidenceVersion} · 요청{' '}
              {dateTime(report.createdAt)}
              {report.completedAt && ` · 완료 ${dateTime(report.completedAt)}`}
            </Typography.Text>
          </Space>
          {report !== latest && (
            <Alert type="info" showIcon message="이전 버전 보고서를 보고 있습니다." />
          )}
          {report.status === 'SUCCEEDED' && report.evidenceVersion !== evidenceVersion && (
            <Alert
              type="warning"
              showIcon
              message="현재 Case 증거 버전과 다른 보고서입니다."
              description={`Case는 증거 v${evidenceVersion}입니다. 이 보고서로 최종 판정할 수 없습니다.`}
            />
          )}
          <ReportContent report={report} />
        </Space>
      )}
    </Card>
  )
}

function ReportContent({ report }: { report: AiReport }) {
  if (report.status === 'PENDING' || report.status === 'RUNNING') {
    return (
      <div style={{ padding: '28px 0', textAlign: 'center' }}>
        <Spin />
        <Typography.Paragraph type="secondary" style={{ marginTop: 12 }}>
          AI가 Case를 조사하고 있습니다. 완료되면 새로고침으로 확인하세요.
        </Typography.Paragraph>
      </div>
    )
  }
  if (report.status === 'FAILED') {
    return (
      <Alert
        type="error"
        showIcon
        message="AI 보고서 생성이 최종 실패했습니다 (총 3회 시도)."
        description="자동 판정은 하지 않습니다. 직접 근거를 조사한 뒤 오른쪽 판정 패널에서 수동 판정하고, 사유에 조사 근거를 기록하세요."
      />
    )
  }
  return report.reportBody ? <ReportBodyView body={report.reportBody} /> : null
}

function ReportBodyView({ body }: { body: ReportBody }) {
  return (
    <div>
      <Section title="1. 사건 개요">
        <Descriptions size="small" column={2} colon={false}>
          <Descriptions.Item label="Case">{body.overview.caseId}</Descriptions.Item>
          <Descriptions.Item label="가맹점">{body.overview.merchantId}</Descriptions.Item>
          <Descriptions.Item label="고객">{body.overview.customerRef}</Descriptions.Item>
          <Descriptions.Item label="대상 거래">{body.overview.paymentCount}건</Descriptions.Item>
          <Descriptions.Item label="총액">{won(body.overview.totalAmount)}</Descriptions.Item>
          <Descriptions.Item label="조사 기준">
            {dateTime(body.overview.investigatedAt)}
          </Descriptions.Item>
        </Descriptions>
      </Section>
      <Section title="2. 핵심 요약">
        <Bullets items={body.summary} />
      </Section>
      <Section title="3. 결제별 분석">
        <Space direction="vertical" size={10} style={{ width: '100%' }}>
          {body.paymentAnalyses.map((p) => (
            <div key={p.paymentId} style={paymentBox}>
              <Space size={8} wrap style={{ marginBottom: 6 }}>
                <strong>{p.paymentId}</strong>
                <span>{won(p.amount)}</span>
                <span style={{ color: '#64748b' }}>FDS {p.riskScore ?? '-'}점</span>
                <CodeTag value={recommendationLabel[p.recommendation]} />
              </Space>
              <LabeledBullets label="위험 근거" items={p.riskEvidence} />
              <LabeledBullets label="정상 근거" items={p.normalEvidence} />
              <LabeledBullets label="미확인" items={p.unknowns} />
            </div>
          ))}
        </Space>
      </Section>
      <Section title="4. 사건 타임라인">
        <Bullets items={body.timeline.map((t) => `${dateTime(t.at)} — ${t.event}`)} />
      </Section>
      <Section title="5. 종합 분석">
        <Bullets items={body.overallAnalysis} />
      </Section>
      <Section title="6. 추가 확인 사항">
        <Bullets items={body.additionalChecks} />
      </Section>
      <Section title="7. AI 권고">
        <Typography.Paragraph strong style={{ marginBottom: 4 }}>
          {body.recommendation.summary}
        </Typography.Paragraph>
        <LabeledBullets label="한계" items={body.recommendation.limitations} />
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          AI 권고는 참고용이며 최종 판정은 직원이 결제별로 합니다.
        </Typography.Text>
      </Section>
      <Section title="8. 증거 추적 정보">
        <Table
          size="small"
          rowKey="evidenceId"
          pagination={false}
          dataSource={body.evidence}
          columns={[
            { title: 'Tool', dataIndex: 'toolName' },
            { title: '출처', dataIndex: 'source', width: 80 },
            { title: '증거 ID', dataIndex: 'evidenceId', width: 110 },
            { title: '조회 시각', dataIndex: 'retrievedAt', width: 140, render: dateTime },
            { title: '데이터 버전', dataIndex: 'dataVersion', width: 100 },
          ]}
        />
      </Section>
    </div>
  )
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section style={{ marginBottom: 16 }}>
      <Typography.Title level={5} style={{ fontSize: 14, marginBottom: 8 }}>
        {title}
      </Typography.Title>
      {children}
    </section>
  )
}

function Bullets({ items }: { items: string[] }) {
  if (items.length === 0) return <Typography.Text type="secondary">없음</Typography.Text>
  return (
    <ul style={{ margin: 0, paddingLeft: 18, fontSize: 13, lineHeight: 1.7 }}>
      {items.map((item, i) => (
        <li key={i}>{item}</li>
      ))}
    </ul>
  )
}

function LabeledBullets({ label, items }: { label: string; items: string[] }) {
  return (
    <div style={{ display: 'flex', gap: 8, fontSize: 13, marginBottom: 4 }}>
      <span style={{ color: '#64748b', flex: '0 0 56px' }}>{label}</span>
      <span>{items.length ? items.join(' · ') : '없음'}</span>
    </div>
  )
}

const paymentBox = {
  padding: '10px 12px',
  border: '1px solid #E2E8F0',
  borderRadius: 6,
  background: '#F8FAFC',
}
