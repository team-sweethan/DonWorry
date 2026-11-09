import type { ReactNode } from 'react'
import { BellFilled } from '@ant-design/icons'
import { Link } from 'react-router-dom'
import type { StaffLoad } from '../../api/caseAssignments'
import { Mascot } from '../../components/brand/BrandMark'
import { needsHandover, staffState } from './caseAssignmentView'

// 책상 위 서류는 10장까지만 쌓는다 — 그보다 많으면 맨 위에 "+"를 붙인다
const MAX_SHEETS = 10

function deskNote(staff: StaffLoad) {
  const state = staffState(staff)
  if (state === 'inactive')
    return needsHandover(staff) ? '정지 · 남은 사건 넘겨야 함' : '자리 비움 · 정지'
  if (state === 'insider') return '조사 중 · 새 사건 안 받음'
  return staff.assignOrder === 1 ? '다음 새 사건 받을 차례' : '새 사건 받는 중'
}

// 직원 한 명 = 책상 하나. 동전이가 앉아 있고, 맡은 사건은 책상 위 서류 더미로 보인다
function Desk({
  staff,
  open,
  onToggle,
  pendingCount,
}: {
  staff: StaffLoad
  open: boolean
  onToggle: () => void
  pendingCount: number
}) {
  const state = staffState(staff)
  const danger = needsHandover(staff)
  const sheets = Math.min(staff.openCount, MAX_SHEETS)
  const note = deskNote(staff)
  return (
    <li>
      <button
        type="button"
        className={`ca-desk is-${state}${open ? ' is-open' : ''}${danger ? ' is-danger' : ''}`}
        aria-pressed={open}
        aria-label={`${staff.displayName}, 맡은 사건 ${staff.openCount}건, ${note}`}
        onClick={onToggle}
      >
        <span className="ca-desk-scene" aria-hidden="true">
          {state === 'receiving' && staff.assignOrder === 1 && (
            <span className="ca-desk-bubble">다음은 저!</span>
          )}
          {state === 'inactive' ? (
            <span className="ca-desk-chair" />
          ) : (
            <Mascot size={96} className="ca-desk-mascot" />
          )}
          {state === 'insider' && <span className="ca-desk-partition" />}
          <span className="ca-desk-table">
            <span className="ca-desk-papers">
              {Array.from({ length: sheets }, (_, i) => (
                <span key={i} className="ca-desk-sheet" />
              ))}
              {staff.openCount > MAX_SHEETS && <span className="ca-desk-more">+</span>}
              {danger && <span className="ca-desk-warn">!</span>}
            </span>
            <span className="ca-desk-monitor" />
          </span>
        </span>
        <span className="ca-desk-plate">
          <span className="ca-desk-name">{staff.displayName}</span>
          <span className="ca-desk-count">{staff.openCount}건</span>
        </span>
        <span className="ca-desk-note">{note}</span>
      </button>
      {/* 요청이 있는 책상에만 — 책상 버튼 밖 형제로 둔다 (버튼 안에 링크를 넣지 않는다) */}
      {pendingCount ? (
        <Link
          className="ca-desk-request"
          to={`/admin/case-assignment-requests?staff=${staff.staffId}`}
        >
          <BellFilled aria-hidden="true" />
          변경 요청 {pendingCount}건 보기
        </Link>
      ) : null}
    </li>
  )
}

/**
 * 보안팀 사무실 — 직원 수만큼 책상이 놓인다 (이름순, #659).
 * 담당자 없는 사건은 입구 바구니로 보여 주고, 책상을 누르면 아래에 그 직원의 사건이 열린다
 */
export function StaffOffice({
  staff,
  openStaffId,
  onToggle,
  unassigned,
  unassignedAction,
  pendingCounts,
}: {
  staff: StaffLoad[]
  openStaffId: number | null
  onToggle: (staff: StaffLoad) => void
  unassigned: number
  unassignedAction: ReactNode
  pendingCounts: ReadonlyMap<number, number> | null
}) {
  return (
    <div className="ca-office">
      {unassigned > 0 && (
        <div className="ca-inbox ca-waiting-box" role="status">
          <span className="ca-inbox-tray" aria-hidden="true">
            <span />
            <span />
            <span />
          </span>
          <span className="ca-inbox-text">
            <strong>담당자가 없는 사건 {unassigned}건</strong>
            <span className="dw-caption">
              자동 배정을 켜기 전에 생겼거나, 생길 때 활성 보안 직원이 없던 사건입니다.
            </span>
          </span>
          {unassignedAction}
        </div>
      )}
      <ul className="ca-desks">
        {staff.map((s) => (
          <Desk
            key={s.staffId}
            staff={s}
            pendingCount={pendingCounts?.get(s.staffId) ?? 0}
            open={s.staffId === openStaffId}
            onToggle={() => onToggle(s)}
          />
        ))}
      </ul>
    </div>
  )
}
