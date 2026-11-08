import { Alert, App, Button, Card, Input, Modal, Table, Tabs, Tag } from 'antd'
import { useCallback, useEffect, useRef, useState } from 'react'
import {
  closeInsiderAlert,
  getInsiderAlertDetail,
  getInsiderAlerts,
  type InsiderAlert,
  type InsiderAlertEvidence,
  type InsiderAlertSeverity,
  type InsiderAlertStatus,
} from '../../api/insiderAlerts'
import { PageHeader } from '../../components/common/PageHeader'
import { refreshInsiderAlertBadge } from '../../hooks/useInsiderAlertBadge'
import { displayRuleCode, displayRuleText } from '../../utils/ruleDisplayCode'
import { dateTime } from '../security/case/caseLabels'
import { approveMemoPlaceholder, errorText } from './caseAssignmentForm'

const tabs: Array<{ key: InsiderAlertStatus; label: string }> = [
  { key: 'OPEN', label: '열린 경보' },
  { key: 'CLOSED', label: '닫힌 경보' },
]
const severityTag: Record<InsiderAlertSeverity, { label: string; color: string }> = {
  CAUTION: { label: '주의', color: 'gold' },
  HIGH: { label: '높음', color: 'red' },
  CRITICAL: { label: '위험', color: 'magenta' },
}
// 접근 기록 종류 — 서버 enum(StaffAccessType)을 화면 이름으로. 모르는 값은 코드 그대로 보인다
const accessLabel: Record<string, string> = {
  CASE_DETAIL_VIEW: '사건 상세 열기',
  CASE_DECISION: '판정',
  REPORT_REQUEST: '보고서 요청',
  HANDOFF_RECHECK: '인계 다시 확인',
  REPORT_PROGRESS_VIEW: '보고서 진행 조회',
  TRANSACTION_LIST_VIEW: '거래 목록 조회',
  TRANSACTION_EXPORT: '거래 내역 내보내기',
  CONTACT_MASKED_VIEW: '주문자 정보(가림) 보기',
  CONTACT_MATCH: '번호 대조',
  CONTACT_MATCH_HIT: '번호 대조 일치',
  CONTACT_MATCH_MISS: '번호 대조 불일치',
  CONTACT_RAW_VIEW: '주문자 전화 원문 보기',
}
const denyLabel: Record<string, string> = {
  NOT_ASSIGNEE: '담당 아님',
  CASE_CLOSED: '종료된 사건',
  PAYMENT_NOT_IN_CASE: '사건에 없는 결제',
  INSIDER_ALERT_OPEN: '열린 경보 중 내보내기',
}
const oneLine = {
  onHeaderCell: () => ({ style: { whiteSpace: 'nowrap' as const } }),
  onCell: () => ({ style: { whiteSpace: 'nowrap' as const } }),
}

/** 근거 기록 — 펼칠 때 불러온다. 원문 값은 없고 무엇을 · 어느 사건에서 · 결과만 */
function EvidenceRows({ alertId }: { alertId: number }) {
  const [rows, setRows] = useState<InsiderAlertEvidence[] | null>(null)
  const [error, setError] = useState(false)
  useEffect(() => {
    let alive = true
    getInsiderAlertDetail(alertId)
      .then((detail) => {
        if (alive) setRows(detail.evidence)
      })
      .catch(() => {
        if (alive) setError(true)
      })
    return () => {
      alive = false
    }
  }, [alertId])
  if (error) return <Alert type="error" showIcon message="근거 기록을 불러오지 못했습니다." />
  return (
    <Table<InsiderAlertEvidence>
      rowKey="eventId"
      size="small"
      loading={rows === null}
      pagination={false}
      dataSource={rows ?? []}
      columns={[
        { ...oneLine, title: '시각', dataIndex: 'occurredAt', render: dateTime },
        {
          ...oneLine,
          title: '한 일',
          dataIndex: 'accessType',
          render: (value: string) => accessLabel[value] ?? value,
        },
        {
          ...oneLine,
          title: '결과',
          render: (_, row) =>
            row.result === 'ALLOWED' ? (
              <Tag color="blue">허용</Tag>
            ) : (
              <Tag color="red">
                거부{row.denyReason ? ` · ${denyLabel[row.denyReason] ?? row.denyReason}` : ''}
              </Tag>
            ),
        },
        { ...oneLine, title: '사건', render: (_, row) => (row.caseId ? `#${row.caseId}` : '-') },
        { ...oneLine, title: '결제', render: (_, row) => row.paymentId ?? '-' },
        {
          ...oneLine,
          title: '가맹점',
          render: (_, row) => row.merchantName ?? (row.merchantId ? `ID ${row.merchantId}` : '-'),
        },
      ]}
    />
  )
}

/**
 * 관리자 [직원 보안 › 내부자 경보] (#516, ADR-0082).
 * 보안 직원의 접근 기록이 규칙(INS-01 · 03 · 06 · 07 · 08 · 09)에 걸리면 쌓인다. 근거는 기록 종류 · 사건 번호만 보이고 원문은 없다.
 * 경보는 계정 · 사건을 바꾸지 않는다. 관리자는 정상 업무면 닫는다.
 */
export function InsiderAlertsPage() {
  const { message } = App.useApp()
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

  return (
    <>
      <PageHeader
        title="내부자 경보"
        description="보안 직원의 고객 정보 접근이 규칙에 걸리면 여기에 쌓입니다. 줄을 펼치면 근거가 된 접근 기록이 보입니다(원문 없음). 업무상 정상이면 [정상 업무로 닫기]를 누르세요."
      />
      {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 16 }} />}
      <Card>
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
        <Table<InsiderAlert>
          rowKey="alertId"
          size="small"
          loading={rows === null}
          pagination={false}
          scroll={{ x: 'max-content' }}
          dataSource={rows ?? []}
          expandable={{ expandedRowRender: (row) => <EvidenceRows alertId={row.alertId} /> }}
          columns={[
            { ...oneLine, title: '최근 시각', dataIndex: 'lastEventAt', render: dateTime },
            { ...oneLine, title: '직원', render: (_, row) => row.staffName ?? `ID ${row.staffId}` },
            {
              ...oneLine,
              title: '규칙',
              render: (_, row) => `${displayRuleCode(row.ruleCode)} ${row.ruleLabel}`,
            },
            {
              ...oneLine,
              title: '등급',
              dataIndex: 'severity',
              render: (value: InsiderAlertSeverity) => (
                <Tag color={severityTag[value].color}>{severityTag[value].label}</Tag>
              ),
            },
            { title: '내용', dataIndex: 'summary', render: displayRuleText },
            { ...oneLine, title: '근거', render: (_, row) => `${row.evidenceCount}건` },
            status === 'OPEN'
              ? {
                  ...oneLine,
                  title: '처리',
                  render: (_: unknown, row: InsiderAlert) => (
                    <Button
                      size="small"
                      onClick={() => {
                        setMemo('')
                        setTarget(row)
                      }}
                    >
                      정상 업무로 닫기
                    </Button>
                  ),
                }
              : {
                  title: '처리',
                  render: (_: unknown, row: InsiderAlert) =>
                    `${row.closedByName ?? '-'} · ${row.closedAt ? dateTime(row.closedAt) : '-'}${row.closeMemo ? ` · ${row.closeMemo}` : ''}`,
                },
          ]}
        />
      </Card>
      <Modal
        title="정상 업무로 닫기"
        open={target !== null}
        okText="닫기 확정"
        cancelText="취소"
        confirmLoading={saving}
        onOk={() => void submit()}
        onCancel={() => setTarget(null)}
      >
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
    </>
  )
}
