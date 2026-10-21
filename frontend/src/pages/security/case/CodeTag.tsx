import { Tag } from 'antd'

/** caseLabels의 코드값 라벨을 Tag로 표시한다. */
export function CodeTag({ value }: { value: { label: string; color: string } }) {
  return (
    <Tag color={value.color} style={{ marginInlineEnd: 0, fontWeight: 600 }}>
      {value.label}
    </Tag>
  )
}
