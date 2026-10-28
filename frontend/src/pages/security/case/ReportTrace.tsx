import type { CSSProperties, ReactNode } from 'react'
import { Alert, Card, Collapse, Empty, Space, Tag, Typography } from 'antd'
import type { InvestigationTrace, TraceStep } from '../../../types/case'
import { durationText, traceProviderLabel } from '../../../utils/reportView'

// 보고서 전문의 "조사 과정" 탭 (#294). 조사가 끝난 뒤 저장된 기록만 보여 준다 (실시간 표시는 별도 이슈)

const kindIcon: Record<TraceStep['kind'], string> = {
  DECIDE: '🤖',
  TOOL: '🔍',
  NUDGE: '↩',
  WRITE: '✍',
  ASSEMBLE: '📄',
}

export function ReportTrace({ trace }: { trace: InvestigationTrace | undefined }) {
  if (!trace) {
    return (
      <Card style={cardStyle}>
        <Empty description="조사 과정 기록이 생기기 전 보고서입니다." />
      </Card>
    )
  }
  const aiCount = trace.steps.filter((s) => s.actor === 'AI').length
  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card style={cardStyle}>
        <div style={{ display: 'flex', gap: 28, flexWrap: 'wrap' }}>
          <Summary label="사용 모델">
            <Tag
              color={trace.provider === 'OLLAMA' ? 'purple' : 'default'}
              style={{ marginInlineEnd: 0 }}
            >
              {traceProviderLabel(trace)}
            </Tag>
          </Summary>
          <Summary label="총 소요 시간">{durationText(trace.durationMs)}</Summary>
          <Summary label="단계">
            {trace.steps.length}개 (AI {aiCount} · 시스템 {trace.steps.length - aiCount})
          </Summary>
        </div>
      </Card>
      {trace.provider === 'FIXED' && (
        <Alert
          type="info"
          showIcon
          message="AI 서버가 꺼진 상태에서 만든 고정 문장 보고서입니다. AI가 조사하지 않았습니다."
        />
      )}
      <Card
        style={cardStyle}
        title={
          <span className="dw-section-title">
            {trace.provider === 'FIXED' ? '보고서를 만든 과정' : 'AI가 조사한 과정'}
          </span>
        }
        extra={
          <span style={{ color: '#94A3B8', fontSize: 12 }}>
            단계를 누르면 물어본 것과 받은 것이 보입니다
          </span>
        }
      >
        <Collapse
          bordered={false}
          style={{ background: 'transparent' }}
          items={trace.steps.map((s, i) => ({
            key: i,
            label: <StepLabel step={s} index={i + 1} />,
            children: <StepDetail step={s} />,
            style: s.actor === 'SYSTEM' ? systemStepStyle : aiStepStyle,
          }))}
        />
      </Card>
    </Space>
  )
}

function StepLabel({ step, index }: { step: TraceStep; index: number }) {
  return (
    <div style={{ display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap' }}>
      <span style={{ color: '#94A3B8', width: 20 }}>{index}</span>
      <span>{kindIcon[step.kind]}</span>
      <b style={{ fontSize: 13.5 }}>{step.title}</b>
      <Tag color={step.actor === 'AI' ? 'purple' : 'default'} style={{ marginInlineEnd: 0 }}>
        {step.actor === 'AI' ? 'AI' : '시스템'}
      </Tag>
      {step.received && <span style={{ color: '#475569', fontSize: 13 }}>→ {step.received}</span>}
      <span style={{ marginLeft: 'auto', color: '#94A3B8', fontSize: 12 }}>
        {durationText(step.durationMs)}
      </span>
    </div>
  )
}

function StepDetail({ step }: { step: TraceStep }) {
  return (
    <div className="dw-info-list" style={{ paddingLeft: 30 }}>
      <span className="k">물어본 것</span>
      <span>{step.asked ?? '-'}</span>
      <span className="k">받은 것</span>
      <span>{step.received ?? '-'}</span>
      {step.retryReason && (
        <>
          <span className="k">재시도 사유</span>
          <span style={{ color: '#B45309' }}>{step.retryReason}</span>
        </>
      )}
    </div>
  )
}

function Summary({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <Typography.Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 4 }}>
        {label}
      </Typography.Text>
      <span style={{ fontSize: 14, fontWeight: 600 }}>{children}</span>
    </div>
  )
}

const cardStyle: CSSProperties = { borderColor: '#E2E8F0' }
const aiStepStyle: CSSProperties = {
  marginBottom: 8,
  background: '#FFFFFF',
  border: '1px solid #E2E8F0',
  borderRadius: 8,
}
// 시스템이 한 단계(빠진 근거 재요청 · 보충 조회 · 조립)는 회색 바탕으로 AI가 한 일과 구분한다
const systemStepStyle: CSSProperties = {
  ...aiStepStyle,
  background: '#F1F5F9',
  border: '1px dashed #CBD5E1',
}
