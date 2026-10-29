import type { CSSProperties } from 'react'
import { Card, Empty, Tag, Typography } from 'antd'
import { useLocation, useNavigate } from 'react-router-dom'
import type { SimilarCases } from '../../../types/case'
import { similarOutcomeTags } from '../../../utils/reportView'
import { dateTime } from './caseLabels'

// 비슷한 과거 사건 (#296) — 참고용이며 자동 판정에 쓰지 않는다. 항목을 누르면 그 Case 상세로 간다
// similarCases가 없으면(예전 보고서) 카드를 그리지 않고, null이면 "조회하지 못함"을 보여 준다

const toneColor = { accept: 'green', reject: 'red', none: 'default' } as const

export function SimilarCasesCard({ similar }: { similar: SimilarCases | null }) {
  const navigate = useNavigate()
  // 목록 복귀 조건(#243)을 다른 Case로 갈 때도 그대로 넘긴다
  const listReturn = useLocation().state
  const shown = similar?.cases.length ?? 0
  const hidden = similar ? similar.totalMatched - shown : 0

  return (
    <Card
      style={{ borderColor: '#E2E8F0' }}
      title={
        <span className="dw-section-title">
          비슷한 과거 사건{similar && ` (${shown}건${hidden > 0 ? ` · 외 ${hidden}건` : ''})`}
        </span>
      }
      extra={<span style={{ color: '#94A3B8', fontSize: 12 }}>참고용 · 자동 판정에 쓰지 않음</span>}
      styles={{ body: { padding: shown ? 0 : undefined } }}
    >
      {similar === null ? (
        <Typography.Text type="secondary">
          비슷한 과거 사건을 조회하지 못했습니다. 판정에는 영향이 없습니다.
        </Typography.Text>
      ) : shown === 0 ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="비슷한 지난 사건이 없습니다." />
      ) : (
        similar.cases.map((c) => (
          <div
            key={c.caseId}
            role="link"
            tabIndex={0}
            style={rowStyle}
            onClick={() => navigate(`/security/cases/${c.caseId}`, { state: listReturn })}
            onKeyDown={(e) => {
              if (e.key === 'Enter') navigate(`/security/cases/${c.caseId}`, { state: listReturn })
            }}
          >
            <span
              style={{
                ...badgeStyle,
                ...(c.similarity === 'SAME_CUSTOMER' ? strongStyle : weakStyle),
              }}
            >
              {c.similarity === 'SAME_CUSTOMER' ? '강하게 비슷' : '비슷'}
            </span>
            <div>
              <div style={{ fontWeight: 700, fontSize: 13.5 }}>
                Case {c.caseId} ·{' '}
                {c.similarity === 'SAME_CUSTOMER'
                  ? '같은 고객'
                  : `같은 규칙 ${c.sharedRules.length}개`}
              </div>
              <div style={{ color: '#64748b', fontSize: 12, marginTop: 2 }}>
                {c.closedAt ? `${dateTime(c.closedAt)} 종료` : '종료 시각 없음'}
                {c.sharedRules.length > 0 && ` · ${c.sharedRules.map((r) => r.name).join(' · ')}`}
              </div>
            </div>
            <span style={{ display: 'flex', gap: 4, flexWrap: 'wrap', justifyContent: 'flex-end' }}>
              {similarOutcomeTags(c).map((t) => (
                <Tag
                  key={t.label}
                  color={toneColor[t.tone]}
                  style={{ marginInlineEnd: 0, fontWeight: 600 }}
                >
                  {t.label}
                </Tag>
              ))}
            </span>
          </div>
        ))
      )}
    </Card>
  )
}

const rowStyle: CSSProperties = {
  display: 'grid',
  gridTemplateColumns: '96px 1fr auto',
  gap: 10,
  alignItems: 'center',
  padding: '12px 18px',
  borderBottom: '1px solid #F1F5F9',
  cursor: 'pointer',
}
const badgeStyle: CSSProperties = {
  fontSize: 11.5,
  fontWeight: 700,
  padding: '3px 8px',
  borderRadius: 6,
  textAlign: 'center',
}
const strongStyle: CSSProperties = { background: '#FEE2E2', color: '#991B1B' }
const weakStyle: CSSProperties = { background: '#FEF3C7', color: '#92400E' }
