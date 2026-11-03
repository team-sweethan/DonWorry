/** 돈워리 디자인 토큰 — 시안 기반 제안값. 팀 승인 후 이 파일만 조정. */
export const tokens = {
  colors: {
    primary: '#2563EB',
    primaryHover: '#1D4ED8',
    accent: '#3B82F6',
    sidebar: '#172554',
    sidebarHover: '#20366B',
    canvas: '#F5F7FB',
    surface: '#FFFFFF',
    surfaceMuted: '#F8FAFC',
    text: '#0F172A',
    textSecondary: '#64748B',
    border: '#E2E8F0',
    success: '#15803D',
    warning: '#B45309',
    danger: '#DC2626',
  },
  layout: {
    headerHeight: 56,
    sidebarWidth: 220,
    mainPadding: 32,
    minAdminWidth: 1280,
  },
  radius: {
    control: 6,
    card: 8,
  },
  typography: {
    family:
      '"Pretendard Variable", Pretendard, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif',
    body: 14,
    pageTitle: 24,
    sectionTitle: 18,
  },
  spacing: [4, 8, 12, 16, 24, 32, 48],
} as const
