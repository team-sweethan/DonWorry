import { Alert, App, Button, Card, Input, Modal, Space, Table, Tag, Typography } from 'antd'
import { useState } from 'react'
import {
  getCaseContact,
  matchContact,
  viewRawContact,
  type CaseContact,
  type RawViewInput,
} from '../../../api/caseContacts'
import { ApiError } from '../../../api/client'
import type { CasePayment } from '../../../types/case'
import { createLatestRequest } from '../../../utils/latestRequest'

const errorText = (error: unknown, fallback: string) =>
  error instanceof ApiError && error.detail ? error.detail : fallback

const emptyRawInput: RawViewInput = { disputeRef: '', merchantContactAttempt: '', reason: '' }

type Row = { paymentId: string; contact: CaseContact | null; loading: boolean }

/**
 * 사건 결제별 주문자 정보 (#619, ADR-0082 1절) — 담당 직원 · 열린 사건에서만 보인다.
 * 결제 1건씩 [주문자 정보 보기]를 눌러야 가린 값을 읽는다 (열람마다 기록되므로 자동으로 모두 읽지 않는다).
 * [전화번호 일치 확인]은 일치 여부만, [전체 전화번호 보기]는 근거를 적으면 전화번호를 이 창에서 한 번 보여 준다.
 */
export function ContactCard({ caseId, payments }: { caseId: string; payments: CasePayment[] }) {
  const { message } = App.useApp()
  const [contacts, setContacts] = useState<Record<string, CaseContact>>({})
  const [loadingId, setLoadingId] = useState<string | null>(null)
  const [matching, setMatching] = useState<CaseContact | null>(null)
  const [matchPhone, setMatchPhone] = useState('')
  const [matchResult, setMatchResult] = useState<boolean | null>(null)
  const [rawTarget, setRawTarget] = useState<CaseContact | null>(null)
  const [rawInput, setRawInput] = useState<RawViewInput>(emptyRawInput)
  // 원문은 이 상태에만 잠깐 둔다 — 창을 닫거나 새로고침하면 사라진다
  const [rawPhone, setRawPhone] = useState<string | null>(null)
  const [matchSaving, setMatchSaving] = useState(false)
  const [rawSaving, setRawSaving] = useState(false)
  const [matchRequests] = useState(createLatestRequest)
  const [rawRequests] = useState(createLatestRequest)

  const rows: Row[] = payments.map((p) => ({
    paymentId: p.paymentId,
    contact: contacts[p.paymentId] ?? null,
    loading: loadingId === p.paymentId,
  }))

  const load = async (paymentId: string) => {
    setLoadingId(paymentId)
    try {
      const contact = await getCaseContact(caseId, paymentId)
      setContacts((current) => ({ ...current, [paymentId]: contact }))
    } catch (e) {
      message.error(errorText(e, '주문자 정보를 불러오지 못했습니다.'))
    } finally {
      setLoadingId(null)
    }
  }

  // 입력 변경 · 창 닫기 · 대상 변경 뒤에 도착한 응답은 버린다 — 다른 번호 · 다른 결제의 결과가 지금 창에 보이지 않게 (#621 리뷰)
  const resetMatch = () => {
    matchRequests.invalidate()
    setMatchSaving(false)
    setMatchResult(null)
  }
  const openMatch = (contact: CaseContact) => {
    resetMatch()
    setMatchPhone('')
    setMatching(contact)
  }
  const closeMatch = () => {
    resetMatch()
    setMatching(null)
  }
  const submitMatch = async () => {
    if (!matching) return
    const request = matchRequests.begin()
    setMatchSaving(true)
    try {
      const matched = await matchContact(caseId, matching.paymentId, matchPhone)
      if (matchRequests.isCurrent(request)) setMatchResult(matched)
    } catch (e) {
      if (matchRequests.isCurrent(request))
        message.error(errorText(e, '전화번호 일치 여부를 확인하지 못했습니다.'))
    } finally {
      if (matchRequests.isCurrent(request)) setMatchSaving(false)
    }
  }

  const resetRaw = () => {
    rawRequests.invalidate()
    setRawSaving(false)
    setRawPhone(null)
  }
  const openRaw = (contact: CaseContact) => {
    resetRaw()
    setRawInput(emptyRawInput)
    setRawTarget(contact)
  }
  const closeRaw = () => {
    resetRaw()
    setRawTarget(null)
  }
  const submitRaw = async () => {
    if (!rawTarget) return
    const request = rawRequests.begin()
    setRawSaving(true)
    try {
      const phone = await viewRawContact(caseId, rawTarget.paymentId, rawInput)
      if (rawRequests.isCurrent(request)) setRawPhone(phone)
    } catch (e) {
      if (rawRequests.isCurrent(request))
        message.error(errorText(e, '전체 전화번호를 볼 수 없습니다.'))
    } finally {
      if (rawRequests.isCurrent(request)) setRawSaving(false)
    }
  }
  const rawReady =
    rawInput.disputeRef.trim() !== '' &&
    rawInput.merchantContactAttempt.trim() !== '' &&
    rawInput.reason.trim() !== ''

  return (
    <Card
      title={<span className="dw-section-title">주문자 정보</span>}
      style={{ borderColor: '#E2E8F0' }}
      extra={
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          담당자에게만 보입니다 · 열람 · 일치 확인 · 전체 보기는 모두 기록됩니다
        </Typography.Text>
      }
    >
      <Table<Row>
        size="small"
        rowKey="paymentId"
        pagination={false}
        dataSource={rows}
        columns={[
          { title: '결제 ID', dataIndex: 'paymentId', render: (v) => <strong>{v}</strong> },
          {
            title: '주문자',
            render: (_, row) => {
              if (!row.contact)
                return (
                  <Button
                    size="small"
                    loading={row.loading}
                    onClick={() => void load(row.paymentId)}
                  >
                    주문자 정보 보기
                  </Button>
                )
              if (!row.contact.available)
                return (
                  <Typography.Text type="secondary">
                    가맹점이 보내지 않은 결제입니다
                  </Typography.Text>
                )
              return (
                <Space size={12}>
                  <span>{row.contact.ordererName ?? '-'}</span>
                  <span>{row.contact.ordererPhone ?? '-'}</span>
                </Space>
              )
            },
          },
          {
            title: '픽업자',
            render: (_, row) => {
              const differs = row.contact?.available ? row.contact.pickupDiffers : null
              if (differs === null) return '-'
              return differs ? <Tag color="gold">주문자와 다름</Tag> : '주문자와 같음'
            },
          },
          {
            title: '',
            render: (_, row) => {
              const contact = row.contact
              if (!contact?.available || !contact.ordererPhone) return null
              return (
                <Space size={6}>
                  <Button size="small" onClick={() => openMatch(contact)}>
                    전화번호 일치 확인
                  </Button>
                  <Button size="small" danger onClick={() => openRaw(contact)}>
                    전체 전화번호 보기
                  </Button>
                </Space>
              )
            },
          },
        ]}
      />
      <Modal
        title={matching ? `결제 ${matching.paymentId} 주문자 전화번호 일치 확인` : ''}
        open={matching !== null}
        okText="확인"
        cancelText="닫기"
        confirmLoading={matchSaving}
        okButtonProps={{ disabled: matchPhone.trim() === '' }}
        onOk={() => void submitMatch()}
        onCancel={closeMatch}
      >
        <p>
          고객이 말한 번호를 입력하면 이 결제의 주문자 번호와 같은지만 알려 드립니다. 저장된 번호는
          보여 주지 않습니다.
        </p>
        <Input
          value={matchPhone}
          maxLength={20}
          placeholder="010-1234-5678"
          onChange={(event) => {
            // 보낸 번호와 다른 번호 아래에 이전 결과가 보이지 않게 진행 중인 대조를 무효로 한다
            resetMatch()
            setMatchPhone(event.target.value)
          }}
        />
        {matchResult !== null && (
          <Alert
            style={{ marginTop: 12 }}
            type={matchResult ? 'success' : 'warning'}
            showIcon
            message={matchResult ? '주문자 번호와 일치합니다.' : '주문자 번호와 일치하지 않습니다.'}
          />
        )}
      </Modal>
      <Modal
        title={rawTarget ? `결제 ${rawTarget.paymentId} 주문자 전체 전화번호` : ''}
        open={rawTarget !== null}
        okText={rawPhone ? '닫기' : '전체 전화번호 보기'}
        cancelButtonProps={{ style: rawPhone ? { display: 'none' } : undefined }}
        cancelText="취소"
        confirmLoading={rawSaving}
        okButtonProps={{ disabled: !rawPhone && !rawReady }}
        onOk={() => (rawPhone ? closeRaw() : void submitRaw())}
        onCancel={closeRaw}
        destroyOnHidden
      >
        {rawPhone ? (
          <Space direction="vertical" style={{ width: '100%' }}>
            <Typography.Title level={3} style={{ margin: 0 }}>
              {rawPhone}
            </Typography.Title>
            <Typography.Text type="secondary">
              이 번호는 지금 한 번만 보입니다. 창을 닫거나 새로고침하면 다시 가려집니다. 이 열람은
              관리자 검토 목록에 올라갑니다.
            </Typography.Text>
          </Space>
        ) : (
          <Space direction="vertical" style={{ width: '100%' }} size={12}>
            <Alert
              type="warning"
              showIcon
              message="공식 이의제기 처리 중 가맹점과 연락이 닿지 않을 때만 쓰는 기능입니다."
              description="입력한 내용과 열람 사실이 기록되고 관리자가 나중에 검토합니다. 고객 이름 · 전화번호 같은 개인정보는 적지 마세요."
            />
            <Input
              value={rawInput.disputeRef}
              maxLength={100}
              placeholder="이의제기 참조 번호 (예: 카드사 접수 번호)"
              onChange={(event) => setRawInput({ ...rawInput, disputeRef: event.target.value })}
            />
            <Input.TextArea
              value={rawInput.merchantContactAttempt}
              maxLength={200}
              showCount
              rows={2}
              placeholder="가맹점 연락 시도 내역 (예: 대표번호 2회 통화 실패, 메일 무응답)"
              onChange={(event) =>
                setRawInput({ ...rawInput, merchantContactAttempt: event.target.value })
              }
            />
            <Input.TextArea
              value={rawInput.reason}
              maxLength={500}
              showCount
              rows={3}
              placeholder="사유 (500자까지, 예: 카드사 이의제기 고객 본인 확인 연락). 고객 이름 · 번호는 적지 마세요."
              onChange={(event) => setRawInput({ ...rawInput, reason: event.target.value })}
            />
          </Space>
        )}
      </Modal>
    </Card>
  )
}
