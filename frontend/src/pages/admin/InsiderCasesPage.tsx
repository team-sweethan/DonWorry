import {
  Alert,
  App,
  Button,
  Card,
  Descriptions,
  Drawer,
  Input,
  Popconfirm,
  Radio,
  Space,
  Table,
  Tabs,
  Tag,
  Typography,
} from 'antd'
import { FullscreenExitOutlined, FullscreenOutlined } from '@ant-design/icons'
import { ListEmpty } from '../../components/common/ListEmpty'
import { ListLoading } from '../../components/common/ListLoading'
import { useCallback, useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import type { InsiderAlertSeverity } from '../../api/insiderAlerts'
import { getInsiderMonitorStaff } from '../../api/insiderMonitor'
import {
  decideInsiderCase,
  decisionText,
  getInsiderCaseDetail,
  getInsiderCases,
  outcomeText,
  releaseInsiderLock,
  type InsiderCase,
  type InsiderCaseAlert,
  type InsiderCaseDecision,
  type InsiderCaseDetail,
  type InsiderCaseStatus,
  type LockReleaseOutcome,
} from '../../api/insiderCases'
import { PageHeader } from '../../components/common/PageHeader'
import { refreshInsiderAlertBadge } from '../../hooks/useInsiderAlertBadge'
import { refreshInsiderCaseBadge } from '../../hooks/useInsiderCaseBadge'
import { displayRuleCode, displayRuleText } from '../../utils/ruleDisplayCode'
import { dateTime } from '../security/case/caseLabels'
import { errorText } from './caseAssignmentForm'
import { AgeStamp } from './AgeStamp'
import { EvidenceLog, RuleCell, SeverityMark, StaffLink, StatusMark } from './InsiderParts'
import { InsiderReportsPanel } from './InsiderReportsPanel'
import { waitAge } from './insiderView'
import './InsiderCasesPage.css'

const tabs: Array<{ key: InsiderCaseStatus; label: string }> = [
  { key: 'OPEN', label: '열린 사건' },
  { key: 'CLOSED', label: '닫힌 사건' },
]
const decisions: InsiderCaseDecision[] = ['NORMAL_DUTY', 'CAUTION_NOTICE', 'DISCIPLINE_REQUEST']
const outcomes: LockReleaseOutcome[] = ['RESTORE', 'DEACTIVATE']
const memoPlaceholder = '판정 메모 (선택, 200자까지). 고객 이름 · 번호 같은 개인정보는 적지 마세요.'
const reasonPlaceholder =
  '정지를 푸는 사유 (필수, 200자까지). 고객 이름 · 번호 같은 개인정보는 적지 마세요.'
const oneLine = {
  onHeaderCell: () => ({ style: { whiteSpace: 'nowrap' as const } }),
  onCell: () => ({ style: { whiteSpace: 'nowrap' as const } }),
}

// 담당 현황에서 그 직원 자리를 연 채로 — 남은 FDS 사건을 넘기러 가는 길
const handoverPath = (staffId: number) => `/admin/case-assignments?staff=${staffId}`

/** 판정 결과 한 줄 — 징계 사건은 정지 해제 여부까지 */
function decisionSummary(row: InsiderCase) {
  if (!row.decision) return '-'
  const decided = `${row.decisionLabel ?? row.decision} · ${row.decidedByName ?? '-'} · ${row.decidedAt ? dateTime(row.decidedAt) : '-'}`
  if (row.decision !== 'DISCIPLINE_REQUEST') return decided
  return row.lockReleasedAt
    ? `${decided} → 정지 해제(${row.lockReleaseOutcome ? outcomeText[row.lockReleaseOutcome] : '-'})`
    : `${decided} → 정지 유지`
}

/**
 * 사건 상세 — 묶인 경보 · 정지 여부 · 남은 FDS 사건 · 지난 판정, 그리고 판정 또는 정지 해제.
 * 내부자 관제(#679)도 같은 창을 띄운다.
 */
export function InsiderCaseDrawer({
  insiderCaseId,
  onClose,
  onChanged,
}: {
  insiderCaseId: number
  onClose: () => void
  onChanged: () => void
}) {
  const { message } = App.useApp()
  const [detail, setDetail] = useState<InsiderCaseDetail | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [decision, setDecision] = useState<InsiderCaseDecision | null>(null)
  const [memo, setMemo] = useState('')
  const [outcome, setOutcome] = useState<LockReleaseOutcome | null>(null)
  const [reason, setReason] = useState('')
  const [saving, setSaving] = useState(false)
  // 크게 보기 (#793) — 열 때마다 720px로 시작하고, 원할 때만 화면 전체 너비로 넓힌다 (기억하지 않음)
  const [expanded, setExpanded] = useState(false)

  const load = useCallback(() => {
    getInsiderCaseDetail(insiderCaseId)
      .then((next) => {
        setDetail(next)
        setError(null)
      })
      .catch((e) =>
        setError(errorText(e, '내부자 사건을 불러오지 못했습니다. 잠시 후 다시 시도하세요.')),
      )
  }, [insiderCaseId])
  useEffect(() => {
    load()
  }, [load])

  const insiderCase = detail?.insiderCase
  const disciplineBlocked = (detail?.openFdsCaseCount ?? 0) > 0
  const canRelease =
    insiderCase?.decision === 'DISCIPLINE_REQUEST' &&
    insiderCase.lockReleasedAt === null &&
    detail?.accountLocked === true

  const afterSave = (text: string) => {
    message.success(text)
    onChanged()
    refreshInsiderCaseBadge()
    refreshInsiderAlertBadge()
    load()
  }

  const submitDecision = async () => {
    if (!decision) return
    setSaving(true)
    try {
      await decideInsiderCase(insiderCaseId, decision, memo)
      afterSave(`판정을 저장했습니다: ${decisionText[decision].label}`)
    } catch (e) {
      message.error(errorText(e, '판정하지 못했습니다. 잠시 후 다시 시도하세요.'))
      load()
    } finally {
      setSaving(false)
    }
  }

  const submitRelease = async () => {
    if (!outcome || !reason.trim()) return
    setSaving(true)
    try {
      await releaseInsiderLock(insiderCaseId, outcome, reason)
      afterSave(`정지를 풀었습니다 (${outcomeText[outcome]}).`)
    } catch (e) {
      message.error(errorText(e, '정지를 풀지 못했습니다. 잠시 후 다시 시도하세요.'))
      load()
    } finally {
      setSaving(false)
    }
  }

  return (
    <Drawer
      open
      width={expanded ? '100%' : 720}
      rootClassName={expanded ? 'ic-drawer-expanded' : undefined}
      title={
        insiderCase
          ? `내부자 사건 #${insiderCase.insiderCaseId} · ${insiderCase.staffName ?? `ID ${insiderCase.staffId}`}`
          : '내부자 사건'
      }
      extra={
        <Button
          size="small"
          icon={expanded ? <FullscreenExitOutlined /> : <FullscreenOutlined />}
          onClick={() => setExpanded((value) => !value)}
        >
          {expanded ? '원래 크기' : '크게 보기'}
        </Button>
      }
      onClose={onClose}
      loading={detail === null && error === null}
      // 판정 · 정지 해제는 창 아래에 붙어 있다 — 보고서를 읽으면서 바로 판정한다 (#675)
      footer={
        detail && insiderCase && (insiderCase.status === 'OPEN' || canRelease) ? (
          <div className="ic-footer">
            {insiderCase.status === 'OPEN' && (
              <div className="ic-action" role="group" aria-label="판정">
                <div className="ic-action-title">판정</div>
                <Radio.Group
                  value={decision}
                  onChange={(event) => setDecision(event.target.value as InsiderCaseDecision)}
                >
                  <Space direction="vertical">
                    {decisions.map((value) => (
                      <Radio
                        key={value}
                        value={value}
                        disabled={value === 'DISCIPLINE_REQUEST' && disciplineBlocked}
                      >
                        <strong>{decisionText[value].label}</strong>
                        <span className="ic-radio-help">{decisionText[value].help}</span>
                      </Radio>
                    ))}
                  </Space>
                </Radio.Group>
                {disciplineBlocked && (
                  <Alert
                    type="warning"
                    showIcon
                    style={{ marginTop: 12 }}
                    message={`이 직원이 맡은 FDS 사건 ${detail.openFdsCaseCount}건이 남아 있어 징계 요청을 할 수 없습니다. 정지된 직원의 고객 사건은 아무도 판정할 수 없으니 [담당 현황]에서 먼저 넘겨 주세요.`}
                    action={
                      <Link to={handoverPath(insiderCase.staffId)}>담당 현황에서 넘기기</Link>
                    }
                  />
                )}
                <Input.TextArea
                  style={{ marginTop: 12 }}
                  value={memo}
                  maxLength={200}
                  showCount
                  rows={2}
                  placeholder={memoPlaceholder}
                  onChange={(event) => setMemo(event.target.value)}
                />
                {/* 징계 요청은 계정 정지가 걸리는 가장 무거운 판정 — 한 번 더 묻는다. 나머지는 바로 저장 */}
                <Popconfirm
                  disabled={decision !== 'DISCIPLINE_REQUEST'}
                  title="징계 요청으로 판정할까요?"
                  description={
                    detail.accountLocked
                      ? '인사 처분이 끝날 때까지 계정 정지가 유지돼요.'
                      : '지금 이 직원의 계정이 정지되고, 인사 처분이 끝날 때까지 유지돼요.'
                  }
                  okText="징계 요청"
                  okButtonProps={{ danger: true }}
                  cancelText="다시 고르기"
                  onConfirm={() => void submitDecision()}
                >
                  <Button
                    type="primary"
                    style={{ marginTop: 12, alignSelf: 'flex-start' }}
                    disabled={decision === null}
                    loading={saving}
                    onClick={
                      decision === 'DISCIPLINE_REQUEST' ? undefined : () => void submitDecision()
                    }
                  >
                    판정 확정
                  </Button>
                </Popconfirm>
              </div>
            )}

            {canRelease && (
              <div className="ic-action" role="group" aria-label="징계 사건 정지 해제">
                <div className="ic-action-title">징계 사건 정지 해제</div>
                <Typography.Paragraph type="secondary">
                  징계 요청으로 닫힌 사건입니다. 소명이 받아들여졌거나 처분이 끝났을 때 사유를 적고
                  정지를 풉니다.
                </Typography.Paragraph>
                <Radio.Group
                  value={outcome}
                  onChange={(event) => setOutcome(event.target.value as LockReleaseOutcome)}
                >
                  {outcomes.map((value) => (
                    <Radio key={value} value={value}>
                      {outcomeText[value]}
                    </Radio>
                  ))}
                </Radio.Group>
                <Input.TextArea
                  style={{ marginTop: 12 }}
                  value={reason}
                  maxLength={200}
                  showCount
                  rows={2}
                  placeholder={reasonPlaceholder}
                  onChange={(event) => setReason(event.target.value)}
                />
                <Button
                  danger
                  style={{ marginTop: 12, alignSelf: 'flex-start' }}
                  disabled={outcome === null || reason.trim().length === 0}
                  loading={saving}
                  onClick={() => void submitRelease()}
                >
                  정지 해제
                </Button>
              </div>
            )}
          </div>
        ) : null
      }
    >
      {error && <Alert type="error" showIcon message={error} className="dw-page-alert" />}
      {detail && insiderCase && (
        <Space direction="vertical" size="large" style={{ width: '100%' }}>
          {/* 판정 전에 먼저 볼 것 한 줄 — 등급 · 계정 · 남은 FDS 사건(넘기러 가는 길) */}
          <div className="ic-summary">
            <SeverityMark value={insiderCase.severity} />
            {insiderCase.status === 'OPEN' ? (
              <StatusMark tone="danger">열림</StatusMark>
            ) : (
              <StatusMark tone="quiet">닫힘</StatusMark>
            )}
            {detail.accountLocked ? (
              <StatusMark tone="danger">보안 정지 중</StatusMark>
            ) : (
              <StatusMark tone="quiet">정지 아님</StatusMark>
            )}
            <span className="ic-summary-fds">
              {`맡은 FDS 사건 ${detail.openFdsCaseCount}건`}
              {detail.openFdsCaseCount > 0 && (
                <Link to={handoverPath(insiderCase.staffId)}>담당 현황에서 넘기기 →</Link>
              )}
            </span>
          </div>
          {/* 판정 · 정지 해제 카드는 탭 밖에 둔다 — 보고서를 보다가 바로 판정할 수 있게 (#675) */}
          <Tabs
            items={[
              {
                key: 'case',
                label: '사건 정보',
                children: (
                  <Space direction="vertical" size="large" style={{ width: '100%' }}>
                    <Descriptions
                      size="small"
                      column={2}
                      className="ic-descriptions"
                      styles={{ label: { whiteSpace: 'nowrap' } }}
                    >
                      <Descriptions.Item label="상태">
                        {insiderCase.status === 'OPEN' ? (
                          <Tag color="red">열림</Tag>
                        ) : (
                          <Tag>닫힘</Tag>
                        )}
                      </Descriptions.Item>
                      <Descriptions.Item label="등급">
                        <SeverityMark value={insiderCase.severity} />
                      </Descriptions.Item>
                      <Descriptions.Item label="계정">
                        {detail.accountLocked ? (
                          <Tag color="volcano">보안 정지 중</Tag>
                        ) : (
                          <Tag>정지 아님</Tag>
                        )}
                      </Descriptions.Item>
                      <Descriptions.Item label="맡은 FDS 사건">
                        {detail.openFdsCaseCount}건{' '}
                        {detail.openFdsCaseCount > 0 && (
                          <Link to={handoverPath(insiderCase.staffId)}>담당 현황에서 넘기기</Link>
                        )}
                      </Descriptions.Item>
                      <Descriptions.Item label="열린 시각">
                        {dateTime(insiderCase.openedAt)}
                      </Descriptions.Item>
                      <Descriptions.Item label="판정">
                        {decisionSummary(insiderCase)}
                      </Descriptions.Item>
                      {insiderCase.decisionMemo && (
                        <Descriptions.Item label="판정 메모" span={2}>
                          {insiderCase.decisionMemo}
                        </Descriptions.Item>
                      )}
                      {insiderCase.lockReleasedAt && (
                        <Descriptions.Item label="정지 해제" span={2}>
                          {`${insiderCase.lockReleasedByName ?? '-'} · ${dateTime(insiderCase.lockReleasedAt)} · ${insiderCase.lockReleaseReason ?? ''}`}
                        </Descriptions.Item>
                      )}
                    </Descriptions>

                    <div>
                      <Typography.Title level={5}>
                        묶인 경보 {detail.alerts.length}건
                      </Typography.Title>
                      <Table<InsiderCaseAlert>
                        rowKey="alertId"
                        size="small"
                        pagination={false}
                        dataSource={detail.alerts}
                        expandable={{
                          expandedRowRender: (row) => (
                            <EvidenceLog alertId={row.alertId} ruleCode={row.ruleCode} />
                          ),
                          // 로그인으로 걸린 경보(#770)는 근거 접근 기록이 없어 펼치지 않는다
                          rowExpandable: (row) => displayRuleCode(row.ruleCode) !== 'SEC-INS-10',
                        }}
                        columns={[
                          { ...oneLine, title: '시각', dataIndex: 'raisedAt', render: dateTime },
                          {
                            title: '규칙',
                            onHeaderCell: oneLine.onHeaderCell,
                            render: (_, row) => (
                              <RuleCell
                                code={displayRuleCode(row.ruleCode)}
                                label={row.ruleLabel}
                              />
                            ),
                          },
                          {
                            ...oneLine,
                            title: '등급',
                            dataIndex: 'severity',
                            render: (value: InsiderAlertSeverity) => <SeverityMark value={value} />,
                          },
                          {
                            title: '내용',
                            dataIndex: 'summary',
                            onCell: () => ({ style: { minWidth: 220 } }),
                            render: displayRuleText,
                          },
                        ]}
                      />
                      <Typography.Text type="secondary">
                        줄을 펼치면 근거가 된 접근 기록이 이 자리에서 보여요(원문 없음).
                      </Typography.Text>
                    </div>

                    {detail.pastCases.length > 0 && (
                      <div>
                        <Typography.Title level={5}>이 직원의 지난 내부자 사건</Typography.Title>
                        <Table<InsiderCase>
                          rowKey="insiderCaseId"
                          size="small"
                          pagination={false}
                          dataSource={detail.pastCases}
                          columns={[
                            {
                              ...oneLine,
                              title: '사건',
                              render: (_, row) => `#${row.insiderCaseId}`,
                            },
                            {
                              ...oneLine,
                              title: '열린 시각',
                              dataIndex: 'openedAt',
                              render: dateTime,
                            },
                            {
                              title: '판정',
                              render: (_, row) =>
                                row.status === 'OPEN' ? '진행 중' : decisionSummary(row),
                            },
                          ]}
                        />
                      </div>
                    )}
                  </Space>
                ),
              },
              {
                key: 'reports',
                label: '해제 검토 보고서',
                children: <InsiderReportsPanel insiderCaseId={insiderCase.insiderCaseId} />,
              },
            ]}
          />
        </Space>
      )}
    </Drawer>
  )
}

/**
 * 관리자 [직원 관리 › 보안 › 내부자 사건] (#670, ADR-0082 조각 8).
 * 높음 이상 내부자 경보를 직원 한 명당 하나의 사건으로 묶는다. 관리자가 정상 업무 · 주의 조치 · 징계 요청 중 하나로 판정한다.
 * 사건이 열린 직원은 새 FDS 사건 배정 · 담당 변경을 받지 않는다. 원문 · 고객 번호는 보이지 않는다.
 */
export function InsiderCasesPage() {
  const [status, setStatus] = useState<InsiderCaseStatus>('OPEN')
  const [rows, setRows] = useState<InsiderCase[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [openId, setOpenId] = useState<number | null>(null)
  // 직원별 계정 정지 여부 — 관제 탭과 같은 직원 목록에서 읽는다. 못 읽으면 '-'
  const [locked, setLocked] = useState<ReadonlyMap<number, boolean> | null>(null)
  // 탭을 빠르게 바꾸면 늦게 온 이전 탭 결과를 버린다
  const sequence = useRef(0)

  const reload = useCallback(() => {
    const current = ++sequence.current
    getInsiderCases(status)
      .then((next) => {
        if (current !== sequence.current) return
        setRows(next)
        setError(null)
      })
      .catch(() => {
        if (current !== sequence.current) return
        setRows([])
        setError('내부자 사건을 불러오지 못했습니다. 잠시 후 다시 시도하세요.')
      })
  }, [status])
  const loadLocks = useCallback(() => {
    getInsiderMonitorStaff()
      .then((list) => setLocked(new Map(list.map((s) => [s.staffId, s.locked]))))
      .catch(() => setLocked(null))
  }, [])
  useEffect(() => {
    reload()
    loadLocks()
  }, [reload, loadLocks])

  return (
    <>
      <PageHeader
        title="내부자 사건"
        description="높음 이상 내부자 경보가 직원별 사건 하나로 묶여 올라옵니다. 사건을 열어 경보와 계정 정지 여부를 보고 판정하세요. 사건이 열린 직원에게는 새 FDS 사건이 배정되지 않습니다."
      />
      {error && <Alert type="error" showIcon message={error} className="dw-page-alert" />}
      <Card>
        <Tabs
          activeKey={status}
          onChange={(key) => {
            setError(null)
            sequence.current += 1
            setRows(null)
            setStatus(key as InsiderCaseStatus)
          }}
          items={tabs.map((tab) => ({ key: tab.key, label: tab.label }))}
        />
        <Table<InsiderCase>
          rowKey="insiderCaseId"
          size="small"
          pagination={false}
          scroll={{ x: 'max-content' }}
          dataSource={rows ?? []}
          locale={{
            emptyText:
              rows === null ? (
                <ListLoading label="내부자 사건을 불러오는 중" />
              ) : (
                <ListEmpty
                  title={status === 'OPEN' ? '판정할 사건이 없어요.' : '닫힌 사건이 없어요.'}
                  mascot={status === 'OPEN'}
                />
              ),
          }}
          columns={[
            { ...oneLine, title: '사건', render: (_, row) => `#${row.insiderCaseId}` },
            {
              ...oneLine,
              title: '직원',
              render: (_, row) => <StaffLink staffId={row.staffId} name={row.staffName} />,
            },
            {
              ...oneLine,
              title: '등급',
              dataIndex: 'severity',
              render: (value: InsiderAlertSeverity) => <SeverityMark value={value} />,
            },
            {
              ...oneLine,
              title: '계정',
              render: (_, row) => {
                // 목록을 못 읽었으면 모름. 목록(사용 중 · 정지 직원)에 없으면 사용을 멈춘 계정
                if (locked === null) return <span className="dw-caption">-</span>
                const value = locked.get(row.staffId)
                if (value === undefined) return <span className="dw-caption">사용 중지</span>
                return value ? (
                  <Tag color="volcano">정지 중</Tag>
                ) : (
                  <span className="dw-caption">정지 아님</span>
                )
              },
            },
            { ...oneLine, title: '경보', render: (_, row) => `${row.alertCount}건` },
            status === 'OPEN'
              ? {
                  ...oneLine,
                  title: '기다린 시간',
                  dataIndex: 'openedAt',
                  render: (openedAt: string) => {
                    const age = waitAge(openedAt)
                    return <AgeStamp text={age.text} at={openedAt} late={age.late} />
                  },
                }
              : { ...oneLine, title: '열린 시각', dataIndex: 'openedAt', render: dateTime },
            status === 'OPEN'
              ? { ...oneLine, title: '최근 갱신', dataIndex: 'updatedAt', render: dateTime }
              : { title: '판정', render: (_: unknown, row: InsiderCase) => decisionSummary(row) },
            {
              ...oneLine,
              title: '',
              render: (_: unknown, row: InsiderCase) => (
                <Button
                  size="small"
                  type={status === 'OPEN' ? 'primary' : 'default'}
                  onClick={() => setOpenId(row.insiderCaseId)}
                >
                  {status === 'OPEN' ? '판정하기' : '상세'}
                </Button>
              ),
            },
          ]}
        />
      </Card>
      {openId !== null && (
        <InsiderCaseDrawer
          key={openId}
          insiderCaseId={openId}
          onClose={() => setOpenId(null)}
          onChanged={() => {
            reload()
            loadLocks()
          }}
        />
      )}
    </>
  )
}
