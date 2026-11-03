import { Tag } from 'antd'
import type { FdsResult } from '../../../api/fds'

export function ResultTag({ result }: { result: FdsResult }) {
  return (
    <Tag className={`dw-alert-result-tag dw-alert-result-tag--${result.toLowerCase()}`}>
      {result}
    </Tag>
  )
}
