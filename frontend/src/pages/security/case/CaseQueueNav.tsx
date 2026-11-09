import { LeftOutlined, RightOutlined } from '@ant-design/icons'
import { Button, Space } from 'antd'
import { useLocation, useNavigate } from 'react-router-dom'
import { caseListNeighbors, caseListStateFor } from '../../../utils/caseListQuery'

/**
 * 상세의 이전 · 다음 사건 (#759). 목록의 현재 조건 · 정렬 순서를 따른다.
 * 목록을 거치지 않고 들어왔거나 순서에 없는 사건이면 보이지 않는다.
 * 누를 때만 그 사건을 연다 — 다음 사건을 미리 조회하지 않는다(상세 조회는 열람 기록이 남는다).
 */
export function CaseQueueNav({ caseId }: { caseId: string }) {
  const navigate = useNavigate()
  const state: unknown = useLocation().state
  const neighbors = caseListNeighbors(state, caseId)
  if (!neighbors || neighbors.total < 2) return null
  const go = (id: string) =>
    navigate(`/security/cases/${id}`, { state: caseListStateFor(state, id) })
  return (
    <Space.Compact aria-label={`목록 순서 ${neighbors.total}건 중 ${neighbors.position}번째 사건`}>
      <Button
        icon={<LeftOutlined />}
        disabled={!neighbors.prev}
        onClick={() => neighbors.prev && go(neighbors.prev)}
        aria-label="이전 사건"
        title="이전 사건"
      />
      <Button
        tabIndex={-1}
        aria-hidden="true"
        style={{ pointerEvents: 'none', color: '#475569', fontVariantNumeric: 'tabular-nums' }}
      >
        {neighbors.position} / {neighbors.total}
      </Button>
      <Button
        icon={<RightOutlined />}
        disabled={!neighbors.next}
        onClick={() => neighbors.next && go(neighbors.next)}
        aria-label="다음 사건"
        title="다음 사건"
      />
    </Space.Compact>
  )
}
