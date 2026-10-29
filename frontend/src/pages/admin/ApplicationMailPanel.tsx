import { useEffect, useRef, useState } from 'react'
import { Alert, Button, Card, Modal, Table, Tag } from 'antd'
import {
  getApplication,
  getApplicationMailHistory,
  resendApplicationMail,
  formatApplicationTime,
  mailKindLabels,
  mailStatusLabels,
  resendBlockedMessages,
} from '../../api/adminApplications'
import type { ApplicationDetail, MailPage } from '../../api/adminApplications'

export function ApplicationMailPanel({
  id,
  detail,
  onDetail,
}: {
  id: number
  detail: ApplicationDetail
  onDetail: (detail: ApplicationDetail) => void
}) {
  const [page, setPage] = useState(0)
  const [revision, setRevision] = useState(0)
  const [history, setHistory] = useState<{ key: string; data?: MailPage; error?: boolean } | null>(
    null,
  )
  const [confirm, setConfirm] = useState(false)
  const [sending, setSending] = useState(false)
  const pending = useRef(false)
  const refreshGeneration = useRef(0)
  const [blocked, setBlocked] = useState(false)
  const [message, setMessage] = useState<string | null>(null)
  const feedback = useRef<HTMLDivElement>(null)
  const key = `${id}:${page}:${revision}`
  useEffect(() => {
    let active = true
    getApplicationMailHistory(id, page)
      .then((data) => {
        if (active) setHistory({ key, data })
      })
      .catch(() => {
        if (active) setHistory({ key, error: true })
      })
    return () => {
      active = false
    }
  }, [id, page, revision, key])
  useEffect(() => {
    if (message) feedback.current?.focus()
  }, [message])
  const refresh = async () => {
    const generation = ++refreshGeneration.current
    setBlocked(true)
    try {
      const next = await getApplication(id)
      if (generation !== refreshGeneration.current) return
      onDetail(next)
      setBlocked(false)
    } catch {
      if (generation !== refreshGeneration.current) return
      setMessage(
        '최신 상태를 확인할 수 없습니다. 삭제된 신청일 수 있습니다. 신청 목록 또는 최신 상태를 다시 확인해 주세요.',
      )
    }
    setRevision((v) => v + 1)
  }
  const send = async () => {
    if (pending.current || !detail.latestMail || blocked) return
    pending.current = true
    setSending(true)
    setBlocked(true)
    try {
      const result = await resendApplicationMail(id, detail.latestMail.deliveryId)
      setMessage(
        result.mailStatus === 'SENT'
          ? '새 안내 메일을 SMTP 서버가 수락했습니다. 실제 수신 여부는 수신함에서 확인해 주세요. 신청과 발급 결과는 유지됩니다.'
          : '신청과 발급 결과는 유지됩니다. 새 메일 발송은 완료되지 않았거나 결과가 불확실합니다.',
      )
    } catch {
      setMessage(
        '재발송 결과를 확인해 주세요. 이미 전송되었을 수 있으므로 최신 상태를 확인하며 자동으로 다시 보내지 않습니다.',
      )
    } finally {
      setConfirm(false)
      await refresh()
      pending.current = false
      setSending(false)
    }
  }
  const latest = detail.latestMail
  const current = history?.key === key ? history : null
  return (
    <Card
      title="안내 메일"
      style={{ marginBottom: 20 }}
      extra={
        <Button disabled={sending} onClick={() => void refresh()}>
          최신 상태 확인
        </Button>
      }
    >
      {message && (
        <div ref={feedback} tabIndex={-1} role="status" aria-live="polite">
          <Alert type="info" showIcon message={message} />
        </div>
      )}
      {latest ? (
        <p>
          <strong>{mailKindLabels[latest.kind]}</strong> ·{' '}
          <Tag>{mailStatusLabels[latest.effectiveStatus]}</Tag>{' '}
          {formatApplicationTime(latest.startedAt)}
        </p>
      ) : (
        <p>현재 상태에 해당하는 안내 메일이 없습니다.</p>
      )}
      {latest?.expiresAt && (
        <p>
          링크:{' '}
          {latest.usedAt
            ? '사용 완료'
            : latest.revokedAt
              ? '폐기됨'
              : `유효기간 ${formatApplicationTime(latest.expiresAt)}`}
        </p>
      )}
      <p>
        SMTP 수락은 실제 도착을 보장하지 않습니다. 결과 불확실에는 오래된 처리 중 기록도 포함됩니다.
      </p>
      {detail.resendBlockedReason && (
        <p role="status">
          {resendBlockedMessages[detail.resendBlockedReason] ?? '현재 다시 보낼 수 없습니다.'}
        </p>
      )}
      {detail.nextResendAt && <p>다음 확인 시각: {formatApplicationTime(detail.nextResendAt)}</p>}
      <Button
        disabled={!detail.resendAllowed || blocked || sending}
        loading={sending}
        onClick={() => setConfirm(true)}
      >
        안내 메일 다시 보내기
      </Button>
      {current?.error ? (
        <Alert
          type="error"
          message="발송 이력을 불러오지 못했습니다."
          action={<Button onClick={() => setRevision((v) => v + 1)}>다시 불러오기</Button>}
        />
      ) : (
        <Table
          rowKey="deliveryId"
          loading={!current}
          dataSource={current?.data?.items ?? []}
          size="small"
          scroll={{ x: 720 }}
          style={{ marginTop: 20 }}
          columns={[
            { title: '시도', dataIndex: 'deliveryId' },
            { title: '종류', render: (_, row) => mailKindLabels[row.kind] },
            { title: '결과', render: (_, row) => mailStatusLabels[row.effectiveStatus] },
            { title: '시작', render: (_, row) => formatApplicationTime(row.startedAt) },
            { title: '종료', render: (_, row) => formatApplicationTime(row.completedAt) },
            { title: '실패 코드', render: (_, row) => row.failureCode ?? '—' },
          ]}
          pagination={{
            current: page + 1,
            pageSize: 20,
            total: current?.data?.totalElements ?? 0,
            showSizeChanger: false,
            onChange: (value) => setPage(value - 1),
          }}
        />
      )}
      <Modal
        title="안내 메일을 다시 보낼까요?"
        open={confirm}
        onOk={() => void send()}
        onCancel={() => {
          if (!sending) setConfirm(false)
        }}
        confirmLoading={sending}
        closable={!sending}
        keyboard={!sending}
        maskClosable={!sending}
        cancelButtonProps={{ disabled: sending }}
        okText="새 안내 메일 보내기"
        cancelText="돌아가기"
      >
        <p>
          {latest && mailKindLabels[latest.kind]} 안내를 {detail.email}로 보냅니다.
        </p>
        <p>
          가맹점과 계정은 다시 생성하지 않습니다. 확인·첫 설정 메일은 이전 링크를 폐기하며 새 링크만
          사용할 수 있습니다. 실패해도 이전 링크를 되살리지 않습니다.
        </p>
        <p>이전 메일이 도착했을 수 있어 중복 수신할 수 있습니다.</p>
      </Modal>
    </Card>
  )
}
