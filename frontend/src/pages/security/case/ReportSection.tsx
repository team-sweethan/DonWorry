import type { CSSProperties, ReactNode } from 'react'

// 보고서 전문의 구역 틀 (#644 PR 3) — 번호 · 제목 · 누가 썼는지 · 짧은 안내
// 누가 썼는지를 늘 붙인다 — 직원이 코드가 센 사실과 AI 문장을 섞어 읽지 않게

const BY = {
  code: { text: '코드가 씀', style: { background: '#F1F5F9', color: '#334155' } },
  record: { text: '직원 판정 기록 그대로', style: { background: '#F1F5F9', color: '#334155' } },
  lookup: { text: '조회 결과 그대로', style: { background: '#F1F5F9', color: '#334155' } },
} satisfies Record<string, { text: string; style: CSSProperties }>

export function ReportSection({
  id,
  no,
  title,
  by,
  note,
  children,
}: {
  id?: string
  no: string
  title: string
  by?: keyof typeof BY
  note?: string
  children: ReactNode
}) {
  return (
    <section id={id} style={sectionStyle}>
      <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'baseline', gap: 8 }}>
        <h2 style={{ margin: 0, fontSize: 17, fontWeight: 750, color: '#0F172A' }}>
          <span style={{ color: '#1E3A8A', marginRight: 6 }}>{no}</span>
          {title}
        </h2>
        {by && <span style={{ ...byStyle, ...BY[by].style }}>{BY[by].text}</span>}
        {note && <span style={{ fontSize: 12.5, color: '#64748B' }}>{note}</span>}
      </div>
      {children}
    </section>
  )
}

const sectionStyle: CSSProperties = {
  background: '#fff',
  border: '1px solid #CBD5E1',
  borderRadius: 14,
  padding: '20px 24px',
  display: 'flex',
  flexDirection: 'column',
  gap: 14,
  scrollMarginTop: 80,
}
const byStyle: CSSProperties = {
  padding: '1px 7px',
  borderRadius: 4,
  fontSize: 12,
  border: '1px solid #CBD5E1',
}
