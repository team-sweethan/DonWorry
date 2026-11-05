import { Alert, Button } from 'antd'
import { WATCH_LIMIT_MS } from '../../../utils/caseListWatch'

/**
 * 홈 · Case 목록 자동 반영(#553)이 제한 시간이 지나 멈췄을 때 보이는 안내.
 * 새로고침하면 목록을 다시 불러오고 자동 반영도 다시 시작한다.
 */
export function CaseWatchStoppedNotice({ onRefresh }: { onRefresh: () => void }) {
  return (
    <Alert
      type="info"
      showIcon
      style={{ marginBottom: 16 }}
      message={`${WATCH_LIMIT_MS / 60_000}분 동안 조작이 없어 새 Case 자동 반영을 멈췄습니다. 새로고침하면 다시 시작합니다.`}
      action={
        <Button size="small" onClick={onRefresh}>
          새로고침
        </Button>
      }
    />
  )
}
