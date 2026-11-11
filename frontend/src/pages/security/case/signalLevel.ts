import type { BasisSignal } from '../../../types/case'

// 징후 등급 색 — 보고서 전문 ② 확인할 것(ReportChecks)과 Case 상세 보고서 요약(ReportCard)이 함께 쓴다 (#648)
export const SIGNAL_LEVEL = {
  HIGH_RISK: { text: '고위험', line: '#DC2626', bg: '#FEF2F2', fg: '#7F1D1D' },
  CAUTION: { text: '주의', line: '#F59E0B', bg: '#FFFBEB', fg: '#78350F' },
} satisfies Record<BasisSignal['level'], { text: string; line: string; bg: string; fg: string }>
