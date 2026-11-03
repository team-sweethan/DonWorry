import type { ThemeConfig } from 'antd'
import { tokens } from './tokens'

/** Ant Design v5 ConfigProvider 통합 테마. */
export const antdTheme: ThemeConfig = {
  token: {
    colorPrimary: tokens.colors.primary,
    colorSuccess: tokens.colors.success,
    colorWarning: tokens.colors.warning,
    colorError: tokens.colors.danger,
    colorText: tokens.colors.text,
    colorTextSecondary: tokens.colors.textSecondary,
    colorBorder: tokens.colors.border,
    colorBgLayout: tokens.colors.canvas,
    colorBgContainer: tokens.colors.surface,
    fontFamily: tokens.typography.family,
    fontSize: tokens.typography.body,
    borderRadius: tokens.radius.control,
    controlHeight: 36,
    controlHeightSM: 32,
    controlHeightLG: 44,
    lineWidth: 1,
  },
  components: {
    Button: { primaryShadow: 'none', defaultShadow: 'none', fontWeight: 600 },
    Card: { borderRadiusLG: tokens.radius.card },
    Table: {
      headerBg: tokens.colors.surfaceMuted,
      headerColor: '#475569',
      rowHoverBg: '#EFF6FF',
      cellPaddingBlock: 13,
      cellPaddingInline: 16,
    },
    Layout: { bodyBg: tokens.colors.canvas },
    Input: { activeBorderColor: tokens.colors.primary },
    Select: { optionSelectedBg: '#EFF6FF' },
    Tag: { borderRadiusSM: 999 },
  },
}
