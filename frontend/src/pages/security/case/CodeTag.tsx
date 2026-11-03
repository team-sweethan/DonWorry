import { Tag } from 'antd'

/** caseLabels의 코드값 라벨을 Tag로 표시한다. 값을 조회하지 못했으면(undefined) '-'로 표시한다. */
export function CodeTag({ value }: { value: { label: string; color: string } | undefined }) {
  if (!value) return <>-</>
  return (
    <Tag color={value.color} style={{ marginInlineEnd: 0, fontWeight: 600 }}>
      {value.label}
    </Tag>
  )
}
