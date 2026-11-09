import { ReloadOutlined } from '@ant-design/icons'
import { Alert, Badge, Button, Card, Col, List, Row, Space, Tag, Timeline, Typography } from 'antd'
import { ListEmpty } from '../../components/common/ListEmpty'
import { ListLoading } from '../../components/common/ListLoading'
import dayjs from 'dayjs'
import { useCallback, useEffect, useRef, useState } from 'react'
import {
  getInsiderMonitorStaff,
  getInsiderMonitorTimeline,
  type InsiderMonitorEvent,
  type InsiderMonitorStaff,
  type InsiderMonitorStage,
  type InsiderMonitorTimeline,
} from '../../api/insiderMonitor'
import { PageHeader } from '../../components/common/PageHeader'
import { pollStep } from '../../utils/casePolling'
import {
  INSIDER_MONITOR_POLL_LIMIT_MS,
  INSIDER_MONITOR_POLL_MS,
} from '../../utils/insiderMonitorPolling'
import { displayRuleCode, displayRuleText } from '../../utils/ruleDisplayCode'
import { InsiderCaseDrawer } from './InsiderCasesPage'

// 타임라인 칸이 맨 아래에서 이만큼 안이면 새 줄을 따라 내려간다
const FOLLOW_GAP_PX = 40

const stageView: Record<InsiderMonitorStage, { label: string; color: string; help: string }> = {
  NORMAL: { label: '정상', color: '#52c41a', help: '열린 경보 없음' },
  CAUTION: { label: '주의', color: '#faad14', help: '주의 경보가 열려 있어요' },
  INSIDER_CASE: { label: '내부자 사건', color: '#fa8c16', help: '관리자 판정을 기다려요' },
  BLOCKED: { label: '계정 정지', color: '#f5222d', help: '로그인할 수 없어요' },
}
const severityView: Record<string, { label: string; color: string }> = {
  CAUTION: { label: '주의', color: 'gold' },
  HIGH: { label: '높음', color: 'volcano' },
  CRITICAL: { label: '위험', color: 'red' },
}
const loginLabel: Record<string, string> = {
  LOGIN_SUCCESS: '로그인 성공',
  LOGIN_FAILURE: '로그인 실패',
  LOGOUT: '로그아웃',
}
// 접근 기록 종류 — 서버 enum(StaffAccessType)을 [내부자 경보] 근거와 같은 이름으로. 모르는 값은 코드 그대로 보인다
const accessLabel: Record<string, string> = {
  CASE_DETAIL_VIEW: '사건 상세 열기',
  CASE_DECISION: '판정',
  REPORT_REQUEST: '보고서 요청',
  HANDOFF_RECHECK: '인계 다시 확인',
  REPORT_PROGRESS_VIEW: '보고서 진행 조회',
  REPORT_PRINT: '보고서 인쇄',
  TRANSACTION_LIST_VIEW: '거래 목록 조회',
  TRANSACTION_EXPORT: '거래 내역 내보내기',
  CONTACT_MASKED_VIEW: '주문자 정보(가림) 보기',
  CONTACT_MATCH: '전화번호 일치 확인',
  CONTACT_MATCH_HIT: '전화번호 일치 확인 · 일치',
  CONTACT_MATCH_MISS: '전화번호 일치 확인 · 불일치',
  CONTACT_RAW_VIEW: '전체 전화번호 보기',
}
const denyLabel: Record<string, string> = {
  NOT_ASSIGNEE: '담당 아님',
  CASE_CLOSED: '종료된 사건',
  PAYMENT_NOT_IN_CASE: '사건에 없는 결제',
  INSIDER_ALERT_OPEN: '열린 경보 중 내보내기',
}
const fieldLabel: Record<string, string> = {
  ORDERER_NAME: '주문자 이름',
  ORDERER_PHONE: '주문자 전화',
}
const lockReasonLabel: Record<string, string> = {
  INSIDER_EXPORT_BLOCKED: '경보 중 내보내기 차단 · 세션 종료',
  INSIDER_CASE_DECISION: '내부자 사건 판정 중 정지',
}
const decisionLabel: Record<string, string> = {
  NORMAL_DUTY: '정상 업무',
  CAUTION_NOTICE: '주의 조치',
  DISCIPLINE_REQUEST: '징계 요청',
  DISCIPLINE_LOCK_RELEASE: '징계 뒤 정지 해제',
}
// "N건"은 거래 목록 · 내보내기에만 붙인다
const rowCountTypes = new Set(['TRANSACTION_LIST_VIEW', 'TRANSACTION_EXPORT'])

const clock = (iso: string) => dayjs(iso).format('HH:mm:ss')

function StageDot({ stage }: { stage: InsiderMonitorStage }) {
  return <Badge color={stageView[stage].color} text={stageView[stage].label} />
}

// 한 줄 설명 — 원문 · 고객 번호는 응답에 없다
function EventText({ event }: { event: InsiderMonitorEvent }) {
  if (event.kind === 'LOGIN') {
    return (
      <Space wrap size={4}>
        <strong>{loginLabel[event.type] ?? event.type}</strong>
        {event.device && <span>{event.device}</span>}
        {event.clientIp && <Tag>{event.clientIp}</Tag>}
      </Space>
    )
  }
  if (event.kind === 'ACCESS') {
    return (
      <Space wrap size={4}>
        <strong>{accessLabel[event.type] ?? event.type}</strong>
        {event.merchantName && <span>{event.merchantName}</span>}
        {event.caseId !== null && <span>{`사건 #${event.caseId}`}</span>}
        {event.fieldType && <span>{fieldLabel[event.fieldType] ?? event.fieldType}</span>}
        {event.itemCount !== null && rowCountTypes.has(event.type) && (
          <span>{`${event.itemCount}건`}</span>
        )}
        {event.customerScope === 'UNRELATED' && <Tag color="gold">무관 고객 검색</Tag>}
        {event.customerScope === 'RELATED' && <Tag>담당 사건 고객</Tag>}
        {event.result === 'DENIED' && (
          <Tag color="red">{`거부 · ${denyLabel[event.denyReason ?? ''] ?? event.denyReason ?? ''}`}</Tag>
        )}
      </Space>
    )
  }
  if (event.kind === 'ALERT') {
    const severity = severityView[event.severity ?? ''] ?? {
      label: event.severity,
      color: 'default',
    }
    return (
      <Space wrap size={4}>
        <strong>{event.type === 'ALERT_CLOSED' ? '경보 닫힘' : '경보'}</strong>
        <span>{`${displayRuleCode(event.ruleCode ?? '')} ${event.ruleLabel}`}</span>
        <Tag color={event.type === 'ALERT_CLOSED' ? 'default' : severity.color}>
          {severity.label}
        </Tag>
        {event.type === 'ALERT_RAISED' && event.summary && (
          <Typography.Text type="secondary">{displayRuleText(event.summary)}</Typography.Text>
        )}
      </Space>
    )
  }
  if (event.type === 'RELEASED') {
    return (
      <Space wrap size={4}>
        <strong>정지 해제</strong>
        <span>
          {`내부자 사건 #${event.insiderCaseId ?? '—'} · ${decisionLabel[event.decision ?? ''] ?? event.decision ?? ''}`}
        </span>
      </Space>
    )
  }
  return (
    <Space wrap size={4}>
      <strong>계정 정지</strong>
      <span>{lockReasonLabel[event.lockReason ?? ''] ?? event.lockReason}</span>
    </Space>
  )
}

function eventColor(event: InsiderMonitorEvent) {
  if (event.kind === 'LOCK') return event.type === 'RELEASED' ? 'green' : 'red'
  if (event.kind === 'ALERT') {
    if (event.type === 'ALERT_CLOSED') return 'gray'
    return event.severity === 'CAUTION' ? 'gold' : event.severity === 'HIGH' ? 'orange' : 'red'
  }
  if (event.kind === 'LOGIN') return event.type === 'LOGIN_FAILURE' ? 'red' : 'blue'
  if (event.result === 'DENIED') return 'red'
  if (event.customerScope === 'UNRELATED' || event.type === 'CONTACT_RAW_VIEW') return 'gold'
  return 'gray'
}

// 닮은 실제 사고 — "사례 출처 › 공식"(회사 공지 · 판결) 묶음의 사실만, 짧게 (ADR-0082 조각 10 D4)
function IncidentCard({ stage }: { stage: InsiderMonitorStage }) {
  if (stage === 'INSIDER_CASE') {
    return (
      <Alert
        type="warning"
        showIcon
        message="닮은 실제 사고 — 신한카드 가맹점 대표자 정보 (회사 공식 발표)"
        description={
          <ul style={{ margin: 0, paddingLeft: 18 }}>
            <li>
              영업소 직원 12명이 2022년 3월~2025년 5월 가맹점 대표자 정보 19만 2,088건을 신규 카드
              모집에 썼어요.
            </li>
            <li>조회한 화면을 촬영해 넘겼고, 회사는 3년여 뒤 공익 제보로 알게 됐어요.</li>
            <li>돈워리는 하나씩은 허용된 조회라도 겹치면 사건으로 묶어 관리자에게 넘겨요.</li>
          </ul>
        }
      />
    )
  }
  if (stage === 'BLOCKED') {
    return (
      <Alert
        type="error"
        showIcon
        message="닮은 실제 사고 — 2014년 카드 3사 정보 유출 (판결)"
        description={
          <ul style={{ margin: 0, paddingLeft: 18 }}>
            <li>신용평가사 직원(FDS 개발 총괄)이 외부 저장장치로 1억여 건을 빼냈어요.</li>
            <li>징역 3년이 선고됐고, 2심은 USB 반출입 통제가 미흡했다고 봤어요.</li>
            <li>돈워리는 경보 중 내보내기를 그 순간 막고 계정을 정지해요.</li>
          </ul>
        }
      />
    )
  }
  return null
}

/** 내부자 관제 한 화면 (#679). 왼쪽 직원 단계 · 가운데 로그인부터 타임라인 · 아래 사고 카드와 조치 */
export function InsiderMonitorPage() {
  const [staff, setStaff] = useState<InsiderMonitorStaff[] | null>(null)
  const [selected, setSelected] = useState<number | null>(null)
  const [timeline, setTimeline] = useState<InsiderMonitorTimeline | null>(null)
  const [error, setError] = useState(false)
  const [caseId, setCaseId] = useState<number | null>(null)
  const selectedRef = useRef<number | null>(null)
  const busy = useRef(false)
  // 자동 갱신 시계: 마지막 사용자 조작 시각(null이면 다음 갱신부터 센다). 멈춤 시간이 지나면 stopped (#691)
  const pollFrom = useRef<number | null>(null)
  const [stopped, setStopped] = useState(false)
  const scrollBox = useRef<HTMLDivElement>(null)
  // 관리자가 위로 올려 지난 줄을 보는 중이면 false — 자동으로 끌어내리지 않는다
  const followBottom = useRef(true)

  const refresh = useCallback(async () => {
    if (busy.current) return
    busy.current = true
    const target = selectedRef.current
    try {
      const [list, next] = await Promise.all([
        getInsiderMonitorStaff(),
        target === null ? Promise.resolve(null) : getInsiderMonitorTimeline(target),
      ])
      setStaff(list)
      // 그사이 다른 직원을 골랐으면 버린다
      if (selectedRef.current === target) setTimeline(next)
      setError(false)
    } catch {
      setError(true)
    } finally {
      busy.current = false
    }
  }, [])

  // 사용자가 직접 조작했다 — 멈춤 시계를 다시 0부터 센다
  const resetPoll = useCallback(() => {
    pollFrom.current = Date.now()
    setStopped(false)
  }, [])

  const restart = useCallback(() => {
    resetPoll()
    void refresh()
  }, [resetPoll, refresh])

  useEffect(() => {
    const tick = () => {
      if (document.visibilityState !== 'visible') return
      const step = pollStep(pollFrom.current, Date.now(), INSIDER_MONITOR_POLL_LIMIT_MS)
      pollFrom.current = step.from
      if (step.poll) void refresh()
      else setStopped(true)
    }
    // 탭으로 돌아오면 자리를 비운 사이 바뀐 상태를 반영하고 시계를 다시 센다
    const onVisibility = () => {
      if (document.visibilityState === 'visible') restart()
    }
    tick()
    const timer = window.setInterval(tick, INSIDER_MONITOR_POLL_MS)
    document.addEventListener('visibilitychange', onVisibility)
    return () => {
      window.clearInterval(timer)
      document.removeEventListener('visibilitychange', onVisibility)
    }
  }, [refresh, restart])

  // 새 줄이 오면 타임라인 칸을 맨 아래로 (화면 전체가 아니라 칸 안에서만)
  useEffect(() => {
    const box = scrollBox.current
    if (box && followBottom.current) box.scrollTop = box.scrollHeight
  }, [timeline])

  const select = (staffId: number) => {
    selectedRef.current = staffId
    followBottom.current = true
    resetPoll()
    setSelected(staffId)
    setTimeline(null)
    getInsiderMonitorTimeline(staffId)
      .then((next) => {
        if (selectedRef.current === staffId) setTimeline(next)
      })
      .catch(() => setError(true))
  }

  const current = timeline?.staff ?? null
  return (
    <>
      <PageHeader
        title="내부자 관제"
        description={`보안 직원의 지금 단계와, 고른 직원의 마지막 로그인부터 지금까지 로그인 · 고객정보 조회 · 경보 · 정지를 시간순으로 보여 줘요. 원문 값과 고객 번호는 없고, 화면을 보고 있는 동안 ${INSIDER_MONITOR_POLL_MS / 1000}초마다 새로 고쳐요. ${INSIDER_MONITOR_POLL_LIMIT_MS / 60_000}분 동안 조작이 없으면 자동 갱신을 멈춰요.`}
      />
      {stopped && (
        <Alert
          type="info"
          showIcon
          className="dw-page-alert"
          message={`${INSIDER_MONITOR_POLL_LIMIT_MS / 60_000}분 동안 조작이 없어 자동 갱신을 멈췄습니다. 자리를 비운 동안 로그인 유지 시간이 늘어나지 않게 하기 위해서예요.`}
          action={
            <Button size="small" icon={<ReloadOutlined />} onClick={restart}>
              다시 시작
            </Button>
          }
        />
      )}
      {error && (
        <Alert
          type="error"
          showIcon
          message="관제 정보를 불러오지 못했습니다. 잠시 뒤 다시 시도해요."
          className="dw-page-alert"
        />
      )}
      <Row gutter={[16, 16]}>
        <Col xs={24} md={7}>
          <Card title="보안 직원" size="small">
            <List<InsiderMonitorStaff>
              dataSource={staff ?? []}
              locale={{
                emptyText:
                  staff === null ? (
                    <ListLoading label="보안 직원을 불러오는 중" columns={1} />
                  ) : (
                    <ListEmpty title="사용 중이거나 정지된 보안 직원이 없어요." />
                  ),
              }}
              renderItem={(item) => (
                <List.Item
                  onClick={() => select(item.staffId)}
                  style={{
                    cursor: 'pointer',
                    paddingInline: 8,
                    background: item.staffId === selected ? 'rgba(22, 119, 255, 0.08)' : undefined,
                  }}
                >
                  <List.Item.Meta
                    title={item.staffName}
                    description={
                      item.openAlertCount > 0
                        ? `${stageView[item.stage].help} · 열린 경보 ${item.openAlertCount}건`
                        : stageView[item.stage].help
                    }
                  />
                  {/* 상태 글자는 한 줄로 두고 설명이 왼쪽에서 줄바꿈되게 */}
                  <span style={{ whiteSpace: 'nowrap', flexShrink: 0, marginLeft: 8 }}>
                    <StageDot stage={item.stage} />
                  </span>
                </List.Item>
              )}
            />
          </Card>
        </Col>
        <Col xs={24} md={17}>
          <Space direction="vertical" size="middle" style={{ width: '100%' }}>
            <Card
              size="small"
              title={current ? `${current.staffName} 타임라인` : '타임라인'}
              extra={current && <StageDot stage={current.stage} />}
            >
              {selected === null ? (
                <ListEmpty title="왼쪽에서 직원을 골라 주세요." />
              ) : timeline === null ? (
                <ListLoading label="불러오는 중이에요." rows={5} columns={2} />
              ) : (
                <Space direction="vertical" style={{ width: '100%' }}>
                  <Typography.Text type="secondary">
                    {`${dayjs(timeline.from).format('MM.DD HH:mm:ss')}부터(마지막 로그인 30분 전) · 마지막 갱신 ${clock(timeline.to)}`}
                  </Typography.Text>
                  {timeline.truncated && (
                    <Alert type="info" showIcon message="줄이 많아 최근 200줄만 보여 줘요." />
                  )}
                  {timeline.events.length === 0 ? (
                    <ListEmpty title="이 구간에 기록이 없어요." />
                  ) : (
                    <div
                      ref={scrollBox}
                      onScroll={(event) => {
                        const box = event.currentTarget
                        followBottom.current =
                          box.scrollHeight - box.scrollTop - box.clientHeight < FOLLOW_GAP_PX
                      }}
                      style={{
                        maxHeight: '55vh',
                        overflowY: 'auto',
                        paddingTop: 8,
                        paddingRight: 8,
                      }}
                    >
                      <Timeline
                        items={timeline.events.map((event, index) => ({
                          key: `${event.kind}-${event.occurredAt}-${index}`,
                          color: eventColor(event),
                          children: (
                            // 시각 칸은 줄바꿈 · 줄어듦 없이, 긴 요약은 오른쪽 칸에서만 줄바꿈
                            <div style={{ display: 'flex', gap: 8, alignItems: 'flex-start' }}>
                              <Typography.Text
                                type="secondary"
                                style={{
                                  fontVariantNumeric: 'tabular-nums',
                                  whiteSpace: 'nowrap',
                                  flexShrink: 0,
                                }}
                              >
                                {clock(event.occurredAt)}
                              </Typography.Text>
                              <div style={{ flex: 1, minWidth: 0 }}>
                                <EventText event={event} />
                              </div>
                            </div>
                          ),
                        }))}
                      />
                    </div>
                  )}
                </Space>
              )}
            </Card>
            {current && <IncidentCard stage={current.stage} />}
            {current?.openInsiderCaseId != null && (
              <Card size="small" title="조치">
                <Space wrap>
                  <Button type="primary" onClick={() => setCaseId(current.openInsiderCaseId)}>
                    {`내부자 사건 #${current.openInsiderCaseId} 열기`}
                  </Button>
                  <Typography.Text type="secondary">
                    해제 검토 보고서 확인과 판정을 이 창에서 해요.
                  </Typography.Text>
                </Space>
              </Card>
            )}
          </Space>
        </Col>
      </Row>
      {caseId !== null && (
        <InsiderCaseDrawer
          key={caseId}
          insiderCaseId={caseId}
          onClose={() => setCaseId(null)}
          onChanged={restart}
        />
      )}
    </>
  )
}
