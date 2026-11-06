import { Input, Radio, Space } from 'antd'
import { assignmentReasonLabel, type AssignmentChangeReason } from '../../api/caseAssignments'
import { assignmentReasons, memoPlaceholder } from './caseAssignmentForm'

/** 사유 · 메모 입력 (담당 변경 · 모두 넘기기 · 변경 요청 공통). 기타는 메모가 필요하다 */
export function ReasonFields({
  reason,
  memo,
  onReason,
  onMemo,
}: {
  reason: AssignmentChangeReason | null
  memo: string
  onReason: (reason: AssignmentChangeReason) => void
  onMemo: (memo: string) => void
}) {
  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <div>
        <div style={{ marginBottom: 6, fontWeight: 600 }}>사유</div>
        <Radio.Group value={reason} onChange={(event) => onReason(event.target.value)}>
          {assignmentReasons.map((key) => (
            <Radio key={key} value={key}>
              {assignmentReasonLabel[key]}
            </Radio>
          ))}
        </Radio.Group>
      </div>
      <Input.TextArea
        value={memo}
        maxLength={200}
        showCount
        rows={3}
        placeholder={memoPlaceholder}
        onChange={(event) => onMemo(event.target.value)}
      />
    </Space>
  )
}
