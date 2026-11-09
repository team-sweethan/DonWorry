import { DownOutlined } from '@ant-design/icons'
import { Alert, App, Button, Input, Modal, Tabs } from 'antd'
import { ListEmpty } from '../../components/common/ListEmpty'
import { ListLoading } from '../../components/common/ListLoading'
import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import {
  closeInsiderAlert,
  getInsiderAlerts,
  type InsiderAlert,
  type InsiderAlertStatus,
} from '../../api/insiderAlerts'
import { PageHeader } from '../../components/common/PageHeader'
import { refreshInsiderAlertBadge } from '../../hooks/useInsiderAlertBadge'
import { displayRuleCode, displayRuleText } from '../../utils/ruleDisplayCode'
import { dateTime } from '../security/case/caseLabels'
import { approveMemoPlaceholder, errorText } from './caseAssignmentForm'
import { EvidenceLog, SeverityMark, StaffLink } from './InsiderParts'
import {
  focusGroupFirst,
  groupAlertsByStaff,
  monitorPath,
  type StaffAlertGroup,
} from './insiderView'
import './caseAssignmentShared.css'
import './InsiderAlertsPage.css'

const tabs: Array<{ key: InsiderAlertStatus; label: string }> = [
  { key: 'OPEN', label: '열린 경보' },
  { key: 'CLOSED', label: '닫힌 경보' },
]

/** 경보 한 줄 — 최근 시각 · 규칙 · 등급 · 내용 · 근거(펼치기) · 처리 */
function AlertRow({
  alert,
  status,
  onClose,
}: {
  alert: InsiderAlert
  status: InsiderAlertStatus
  onClose: (alert: InsiderAlert) => void
}) {
  const [open, setOpen] = useState(false)
  const bodyId = `ia-evidence-${alert.alertId}`
  return (
    <li className="ia-row">
      <div className="ia-row-main">
        <span className="ia-row-time">{dateTime(alert.lastEventAt)}</span>
        <span className="ia-row-rule">
          <strong>{`${displayRuleCode(alert.ruleCode)} ${alert.ruleLabel}`}</strong>
          <span className="ia-row-summary">{displayRuleText(alert.summary)}</span>
        </span>
        <SeverityMark value={alert.severity} />
        <button
          type="button"
          className="ia-evidence-toggle"
          aria-expanded={open}
          aria-controls={bodyId}
          onClick={() => setOpen((v) => !v)}
        >
          {`근거 ${alert.evidenceCount}건`}
          <DownOutlined aria-hidden="true" rotate={open ? 180 : 0} />
        </button>
        <span className="ia-row-action">
          {status === 'OPEN' ? (
            <Button size="small" onClick={() => onClose(alert)}>
              정상 업무로 닫기
            </Button>
          ) : (
            <span className="dw-caption">
              {`${alert.closedByName ?? '-'} · ${alert.closedAt ? dateTime(alert.closedAt) : '-'}${alert.closeMemo ? ` · ${alert.closeMemo}` : ''}`}
            </span>
          )}
        </span>
      </div>
      {open && (
        <div id={bodyId} className="ia-row-evidence">
          <EvidenceLog alertId={alert.alertId} />
        </div>
      )}
    </li>
  )
}

/** 직원 한 명의 경보 묶음 — 머리에 가장 높은 등급 · 건수 · 관제로 가는 길 */
function StaffGroup({
  group,
  status,
  focused,
  onClose,
}: {
  group: StaffAlertGroup
  status: InsiderAlertStatus
  focused: boolean
  onClose: (alert: InsiderAlert) => void
}) {
  return (
    <section
      className={`ia-group ca-panel-box${focused ? ' is-focused' : ''}`}
      aria-label={`${group.staffName} 경보`}
      aria-current={focused ? 'true' : undefined}
    >
      <header className="ia-group-head">
        <span className="ia-group-who">
          <StaffLink staffId={group.staffId} name={group.staffName} />
          <SeverityMark value={group.topSeverity} />
          <span className="dw-caption">{`경보 ${group.alerts.length}건 · 최근 ${dateTime(group.lastEventAt)}`}</span>
        </span>
        <Link className="ia-group-monitor" to={monitorPath(group.staffId)}>
          관제에서 기록 보기 →
        </Link>
      </header>
      <ul className="ia-rows">
        {group.alerts.map((alert) => (
          <AlertRow key={alert.alertId} alert={alert} status={status} onClose={onClose} />
        ))}
      </ul>
    </section>
  )
}

/**
 * 관리자 [직원 보안 › 내부자 경보] (#516, ADR-0082).
 * 보안 직원의 접근 기록이 규칙(INS-01 · 03 · 06 · 07 · 08 · 09)에 걸리면 쌓인다. 근거는 기록 종류 · 사건 번호만 보이고 원문은 없다.
 * 경보는 계정 · 사건을 바꾸지 않는다. 관리자는 정상 업무면 닫는다.
 */
export function InsiderAlertsPage() {
  const { message } = App.useApp()
  const [searchParams] = useSearchParams()
  // 관제에서 오면(?staff=) 그 직원 묶음을 맨 위에 강조한다. 주소는 들어올 때 한 번만 읽는다
  const [focusStaff] = useState<number | null>(() => {
    const id = Number(searchParams.get('staff'))
    return Number.isInteger(id) && id > 0 ? id : null
  })
  const [status, setStatus] = useState<InsiderAlertStatus>('OPEN')
  const [rows, setRows] = useState<InsiderAlert[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [target, setTarget] = useState<InsiderAlert | null>(null)
  const [memo, setMemo] = useState('')
  const [saving, setSaving] = useState(false)
  // 탭을 빠르게 바꾸면 늦게 온 이전 탭 결과를 버린다
  const sequence = useRef(0)

  const reload = useCallback(() => {
    const current = ++sequence.current
    getInsiderAlerts(status)
      .then((next) => {
        if (current !== sequence.current) return
        setRows(next)
        setError(null)
      })
      .catch(() => {
        if (current !== sequence.current) return
        setRows([])
        setError('내부자 경보를 불러오지 못했습니다. 잠시 후 다시 시도하세요.')
      })
  }, [status])
  useEffect(() => {
    reload()
  }, [reload])

  const submit = async () => {
    if (!target) return
    setSaving(true)
    try {
      await closeInsiderAlert(target.alertId, memo)
      message.success('정상 업무로 닫았습니다.')
      setTarget(null)
      reload()
      refreshInsiderAlertBadge()
    } catch (e) {
      message.error(errorText(e, '경보를 닫지 못했습니다. 잠시 후 다시 시도하세요.'))
    } finally {
      setSaving(false)
    }
  }

  const groups = rows ? focusGroupFirst(groupAlertsByStaff(rows), focusStaff) : []
  const focusMissing =
    focusStaff !== null && rows !== null && !groups.some((g) => g.staffId === focusStaff)
  return (
    <div className="ia-page">
      <PageHeader
        title="내부자 경보"
        description="보안 직원의 고객 정보 접근이 규칙에 걸리면 직원별로 쌓입니다. [근거]를 펼치면 근거가 된 접근 기록이 보입니다(원문 없음). 업무상 정상이면 [정상 업무로 닫기]를 누르세요."
      />
      {error && <Alert type="error" showIcon message={error} className="dw-page-alert" />}
      <div className="ia-tabs">
        <Tabs
          activeKey={status}
          onChange={(key) => {
            setError(null)
            // 이전 탭의 행을 새 탭 결과처럼 잠깐 보여 주지 않는다
            sequence.current += 1
            setRows(null)
            setStatus(key as InsiderAlertStatus)
          }}
          items={tabs.map((tab) => ({ key: tab.key, label: tab.label }))}
        />
      </div>
      {rows === null ? (
        <ListLoading label="내부자 경보를 불러오는 중" />
      ) : rows.length === 0 ? (
        <div className="ia-empty ca-panel-box">
          {/* 못 불러온 것을 "경보 없음"으로 읽지 않게 */}
          {error ? (
            <ListEmpty title="경보를 불러오지 못했어요." />
          ) : (
            <ListEmpty
              title={status === 'OPEN' ? '열린 경보가 없어요.' : '닫힌 경보가 없어요.'}
              mascot={status === 'OPEN'}
            />
          )}
        </div>
      ) : (
        <div className="ia-groups">
          {focusMissing && (
            <Alert
              type="info"
              showIcon
              message={
                status === 'OPEN'
                  ? '관제에서 고른 직원의 열린 경보가 없어요.'
                  : '관제에서 고른 직원의 닫힌 경보가 없어요.'
              }
            />
          )}
          <p className="dw-caption ia-groups-caption">
            {`직원 ${groups.length}명 · 경보 ${rows.length}건 · 높은 등급 직원이 위`}
          </p>
          {groups.map((group) => (
            <StaffGroup
              key={group.staffId}
              group={group}
              status={status}
              focused={group.staffId === focusStaff}
              onClose={(alert) => {
                setMemo('')
                setTarget(alert)
              }}
            />
          ))}
        </div>
      )}
      <Modal
        title="정상 업무로 닫기"
        open={target !== null}
        okText="닫기 확정"
        cancelText="취소"
        confirmLoading={saving}
        onOk={() => void submit()}
        onCancel={() => setTarget(null)}
      >
        {target && (
          <div className="ia-modal-target">
            <span>
              <b>{target.staffName ?? `ID ${target.staffId}`}</b>
              {` · ${displayRuleCode(target.ruleCode)} ${target.ruleLabel}`}
            </span>
            <span className="dw-caption">{displayRuleText(target.summary)}</span>
          </div>
        )}
        <p>
          닫은 뒤에는 그 전 기록으로 같은 규칙 경보가 다시 뜨지 않습니다. 새로 쌓이는 기록은 다시
          셉니다.
        </p>
        <Input.TextArea
          value={memo}
          maxLength={200}
          showCount
          rows={3}
          placeholder={approveMemoPlaceholder}
          onChange={(event) => setMemo(event.target.value)}
        />
      </Modal>
    </div>
  )
}
