import type { InsiderMonitorStaff } from '../../api/insiderMonitor'
import { Mascot } from '../../components/brand/BrandMark'
import { stageClass, stageView } from './insiderOfficeView'

// 책상 위 경보 쪽지는 5장까지만 — 그보다 많으면 "+"
const MAX_NOTES = 5

// 직원 한 명 = 책상 한 줄. 왼쪽 그림(모니터 색 · 칸막이 · 빈 의자 = 단계, 노란 쪽지 = 열린 경보),
// 오른쪽 글자는 첫 줄 이름 · 둘째 줄 단계와 경보 수 — 이름이 단계 글자에 밀려 잘리지 않게
function Desk({
  staff,
  open,
  onSelect,
}: {
  staff: InsiderMonitorStaff
  open: boolean
  onSelect: () => void
}) {
  const view = stageView[staff.stage]
  const notes = Math.min(staff.openAlertCount, MAX_NOTES)
  return (
    <li>
      <button
        type="button"
        className={`im-desk ${stageClass[staff.stage]}${open ? ' is-open' : ''}`}
        aria-pressed={open}
        aria-label={`${staff.staffName}, ${view.label}, ${view.help}${staff.openAlertCount > 0 ? `, 열린 경보 ${staff.openAlertCount}건` : ''}`}
        onClick={onSelect}
      >
        <span className="im-desk-scene" aria-hidden="true">
          {staff.stage === 'BLOCKED' ? (
            <span className="im-desk-chair" />
          ) : (
            <Mascot size={64} className="im-desk-mascot" />
          )}
          {staff.stage === 'INSIDER_CASE' && <span className="im-desk-partition" />}
          <span className="im-desk-table">
            <span className="im-desk-notes">
              {Array.from({ length: notes }, (_, i) => (
                <span key={i} className="im-desk-note" />
              ))}
              {staff.openAlertCount > MAX_NOTES && <span className="im-desk-more">+</span>}
            </span>
            <span className="im-desk-monitor" />
          </span>
        </span>
        <span className="im-desk-text">
          <span className="im-desk-name">{staff.staffName}</span>
          <span className="im-desk-line">
            <span className="im-desk-stage">
              <span className="im-stage-icon" aria-hidden="true" />
              {view.label}
            </span>
            <span className="im-desk-help">
              {staff.openAlertCount > 0 ? `경보 ${staff.openAlertCount}건` : view.help}
            </span>
          </span>
        </span>
      </button>
    </li>
  )
}

/** 내부자 관제 사무실 — 왼쪽 세로 한 줄. 책상을 누르면 오른쪽에 그 직원 기록이 뜬다 */
export function InsiderOffice({
  staff,
  openStaffId,
  onSelect,
}: {
  staff: InsiderMonitorStaff[]
  openStaffId: number | null
  onSelect: (staffId: number) => void
}) {
  return (
    <ul className="im-desks">
      {staff.map((s) => (
        <Desk
          key={s.staffId}
          staff={s}
          open={s.staffId === openStaffId}
          onSelect={() => onSelect(s.staffId)}
        />
      ))}
    </ul>
  )
}
