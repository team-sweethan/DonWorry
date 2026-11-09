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
  // 화면 코드에서 읽지 않는 참고값이다. 실제 틀 크기는 global.css의 .admin-viewport[data-role] 변수가 정한다 (#713)
  layout: {
    headerHeight: 64,
    sidebarWidth: 236,
    sidebarCollapsedWidth: 72,
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
    // 글자 크기 단계는 global.css의 --dw-text-* 변수와 같게 둔다 (#713)
    pageTitle: 26,
    sectionTitle: 18,
    secondary: 14,
    caption: 13,
    // 관리 화면(ManagementLayout) 안의 antd 기본 글자
    managementBody: 15,
  },
  spacing: [4, 8, 12, 16, 24, 32, 48],
} as const
