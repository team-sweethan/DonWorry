import { Alert, Modal } from 'antd'
import { useState } from 'react'
import type { AssignmentChangeReason } from '../../../api/caseAssignments'
import { errorText, reasonReady } from '../../admin/caseAssignmentForm'
import { ReasonFields } from '../../admin/ReasonFields'

/** 담당자 본인의 [담당 변경 요청] (#608). 새 담당은 관리자가 승인할 때 정한다 — 직원이 지목하지 않는다 */
export function AssignmentRequestModal({
  open,
  caseId,
  onClose,
  onSubmit,
}: {
  open: boolean
  caseId: string
  onClose: () => void
  onSubmit: (reason: AssignmentChangeReason, memo: string) => Promise<void>
}) {
  const [reason, setReason] = useState<AssignmentChangeReason | null>(null)
  const [memo, setMemo] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const reset = () => {
    setReason(null)
    setMemo('')
    setError(null)
  }
  const close = () => {
    reset()
    onClose()
  }
  const submit = async () => {
    if (reason === null) return
    setSaving(true)
    setError(null)
    try {
      await onSubmit(reason, memo)
      reset()
    } catch (e) {
      setError(errorText(e, '담당 변경을 요청하지 못했습니다. 잠시 후 다시 시도하세요.'))
    } finally {
      setSaving(false)
    }
  }
  return (
    <Modal
      title={`Case #${caseId} 담당 변경 요청`}
      open={open}
      okText="요청하기"
      cancelText="닫기"
      confirmLoading={saving}
      okButtonProps={{ disabled: !reasonReady(reason, memo) }}
      onOk={() => void submit()}
      onCancel={close}
    >
      <p>
        관리자가 승인하면 다른 직원이 이 사건을 맡습니다. 처리를 기다리는 동안 이 사건은{' '}
        <strong>판정만</strong> 멈추고, 열람 · 조사는 그대로 할 수 있습니다.
      </p>
      {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 12 }} />}
      <ReasonFields reason={reason} memo={memo} onReason={setReason} onMemo={setMemo} />
    </Modal>
  )
}
