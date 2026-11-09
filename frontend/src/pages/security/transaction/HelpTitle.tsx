import { InfoCircleOutlined } from '@ant-design/icons'
import { Tooltip } from 'antd'

// 칸 제목 옆 ⓘ 설명 — 거래 내역 목록 · 상세 (#732)
export function HelpTitle({ label, text }: { label: string; text: string }) {
  return (
    <span>
      {label}{' '}
      <Tooltip title={text}>
        <InfoCircleOutlined aria-label={`${label} 설명`} style={{ color: 'rgba(0, 0, 0, 0.45)' }} />
      </Tooltip>
    </span>
  )
}
