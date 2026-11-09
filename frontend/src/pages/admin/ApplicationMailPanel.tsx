import { useEffect, useRef, useState } from 'react'
import { Alert, Button, Modal, Table } from 'antd'
import { RightOutlined } from '@ant-design/icons'
import { mailTag } from './applicationStatusTag'
import {
  getApplication,
  getApplicationMailHistory,
  resendApplicationMail,
  formatApplicationTime,
  mailKindLabels,
  resendBlockedMessages,
} from '../../api/adminApplications'
import type { ApplicationDetail, MailPage } from '../../api/adminApplications'

export function ApplicationMailPanel({
  id,
  detail,
  primary = false,
  onDetail,
}: {
  id: number
  detail: ApplicationDetail
  /** 이메일 미확인처럼 메일이 지금 할 일일 때 화면 맨 위 큰 칸으로 보인다. */
  primary?: boolean
  onDetail: (detail: ApplicationDetail) => void
}) {
  const [page, setPage] = useState(0)
  const [revision, setRevision] = useState(0)
  const [history, setHistory] = useState<{ key: string; data?: MailPage; error?: boolean } | null>(
    null,
  )
  const [confirm, setConfirm] = useState(false)
  const [historyOpen, setHistoryOpen] = useState(false)
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
          ? '메일을 다시 보냈습니다(메일 서버가 받음). 실제 도착은 사장님 메일함에서 확인해 주세요. 신청과 계정은 그대로입니다.'
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
    <section className={primary ? 'ar-primary' : 'ar-section'} aria-labelledby="ar-mail-title">
      <div className="ar-section-head">
        <h2 className={primary ? 'ar-primary-title' : 'ar-section-title'} id="ar-mail-title">
          사장님에게 보낸 메일 <small>{detail.email}</small>
        </h2>
        <Button size="small" disabled={sending} onClick={() => void refresh()}>
          새로고침
        </Button>
      </div>
      {message && (
        <div ref={feedback} tabIndex={-1} role="status" aria-live="polite" className="ar-mail-msg">
          <Alert type="info" showIcon message={message} />
        </div>
      )}
      <div className="ar-box">
        <div className="ar-mail-now">
          <div>
            {latest ? (
              <>
                <strong>
                  {mailKindLabels[latest.kind]} · {mailTag(latest.effectiveStatus)}
                </strong>
                <span>
                  {formatApplicationTime(latest.startedAt)} 보냄
                  {latest.expiresAt &&
                    ` · 메일 속 링크 ${
                      latest.usedAt
                        ? '사용 완료'
                        : latest.revokedAt
                          ? '폐기됨'
                          : `${formatApplicationTime(latest.expiresAt)}까지 사용 가능`
                    }`}
                </span>
              </>
            ) : (
              <strong>지금 보낼 메일이 없습니다.</strong>
            )}
          </div>
          <Button
            disabled={!detail.resendAllowed || blocked || sending}
            loading={sending}
            onClick={() => setConfirm(true)}
          >
            다시 보내기
          </Button>
        </div>
        {(detail.resendBlockedReason || detail.nextResendAt) && (
          <div className="ar-mail-blocked">
            {detail.resendBlockedReason && (
              <p role="status">
                {resendBlockedMessages[detail.resendBlockedReason] ?? '현재 다시 보낼 수 없습니다.'}
              </p>
            )}
            {detail.nextResendAt && (
              <p>{formatApplicationTime(detail.nextResendAt)} 이후 다시 확인해 주세요.</p>
            )}
          </div>
        )}
        <div className="ar-mail-foot">
          <button
            type="button"
            className="ar-disclosure"
            aria-expanded={historyOpen}
            aria-controls="ar-mail-history"
            onClick={() => setHistoryOpen((open) => !open)}
          >
            <RightOutlined aria-hidden="true" />
            보낸 기록{current?.data ? ` ${current.data.totalElements}건` : ''}
          </button>
          <div id="ar-mail-history" hidden={!historyOpen}>
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
                columns={[
                  { title: '번호', dataIndex: 'deliveryId', render: (v: number) => `#${v}` },
                  { title: '메일', render: (_, row) => mailKindLabels[row.kind] },
                  { title: '결과', render: (_, row) => mailTag(row.effectiveStatus) },
                  { title: '보낸 시각', render: (_, row) => formatApplicationTime(row.startedAt) },
                  {
                    title: '끝난 시각',
                    render: (_, row) => formatApplicationTime(row.completedAt),
                  },
                  { title: '실패 코드', render: (_, row) => row.failureCode ?? '—' },
                ]}
                pagination={
                  (current?.data?.totalElements ?? 0) > 20 && {
                    current: page + 1,
                    pageSize: 20,
                    total: current?.data?.totalElements ?? 0,
                    showSizeChanger: false,
                    onChange: (value) => setPage(value - 1),
                  }
                }
              />
            )}
          </div>
          <p className="ar-mail-note">
            ‘발송됨’은 메일 서버가 받았다는 뜻이고, 사장님 메일함에 도착했다는 뜻은 아닙니다. ‘결과
            확인 안 됨’에는 오래 걸린 ‘보내는 중’ 기록도 들어갑니다.
          </p>
        </div>
      </div>
      <Modal
        title="메일을 다시 보낼까요?"
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
        okText="다시 보내기"
        cancelText="돌아가기"
      >
        <p>
          {latest && mailKindLabels[latest.kind]}을 사장님 메일({detail.email})로 다시 보냅니다.
        </p>
        <p>
          가맹점과 사장님 계정은 다시 만들지 않습니다. 확인·첫 비밀번호 메일은 예전 링크를 막고 새
          링크만 쓸 수 있게 합니다. 보내기에 실패해도 예전 링크는 다시 살아나지 않습니다.
        </p>
        <p>예전 메일이 이미 도착했을 수 있어서 사장님이 같은 메일을 두 번 받을 수 있습니다.</p>
      </Modal>
    </section>
  )
}
