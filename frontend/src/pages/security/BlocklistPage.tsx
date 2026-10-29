import {
  Alert,
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  Modal,
  Pagination,
  Select,
  Table,
  Tag,
  Typography,
} from 'antd'
import { useEffect, useRef, useState } from 'react'
import {
  addFdsBlocklistEntry,
  deactivateFdsBlocklistEntry,
  getFdsBlocklist,
  type FdsBlocklistEntry,
  type FdsBlocklistPage,
} from '../../api/fds'
import { PageHeader } from '../../components/common/PageHeader'

const typeLabels: Record<FdsBlocklistEntry['identifierType'], string> = {
  CARD_TOKEN: '카드 토큰',
  DEVICE_ID: '기기 ID',
  BUYER_REF: '구매자 식별자',
  IP_ADDRESS: 'IP 주소',
  BUYER_EMAIL: '이메일',
}

const typePlaceholders: Record<FdsBlocklistEntry['identifierType'], string> = {
  CARD_TOKEN: 'cardToken을 입력하세요.',
  DEVICE_ID: 'deviceId를 입력하세요.',
  BUYER_REF: 'buyerRef를 입력하세요.',
  IP_ADDRESS: 'IP 주소를 입력하세요.',
  BUYER_EMAIL: '구매자 이메일을 입력하세요.',
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat('ko-KR', { dateStyle: 'short', timeStyle: 'short' }).format(
    new Date(value),
  )
}

function isActive(entry: FdsBlocklistEntry) {
  return entry.active && (!entry.expiresAt || new Date(entry.expiresAt).getTime() > Date.now())
}

export function BlocklistPage() {
  const [page, setPage] = useState(0)
  const [result, setResult] = useState<FdsBlocklistPage | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [modalOpen, setModalOpen] = useState(false)
  const [selectedEntry, setSelectedEntry] = useState<FdsBlocklistEntry | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [identifierType, setIdentifierType] =
    useState<FdsBlocklistEntry['identifierType']>('CARD_TOKEN')
  const [form] = Form.useForm<{ identifierValue: string; displayLabel?: string; reason: string }>()
  const requestSequence = useRef(0)

  const reload = () => {
    const sequence = ++requestSequence.current
    getFdsBlocklist(page)
      .then((next) => {
        if (sequence === requestSequence.current) setResult(next)
      })
      .catch(() => {
        if (sequence === requestSequence.current) setError('차단 목록을 불러오지 못했습니다.')
      })
  }

  useEffect(reload, [page])

  const closeModal = () => {
    if (submitting) return
    setModalOpen(false)
    form.resetFields()
    setIdentifierType('CARD_TOKEN')
  }

  const submit = async () => {
    const values = await form.validateFields()
    setSubmitting(true)
    try {
      await addFdsBlocklistEntry({
        identifierType,
        identifierValue: values.identifierValue.trim(),
        displayLabel: values.displayLabel?.trim() || null,
        reason: values.reason.trim(),
      })
      setModalOpen(false)
      form.resetFields()
      setIdentifierType('CARD_TOKEN')
      setPage(0)
      reload()
    } catch {
      setError('차단 식별값을 등록하지 못했습니다. 이미 등록된 값인지 확인해주세요.')
    } finally {
      setSubmitting(false)
    }
  }

  const deactivate = async (entryId: number) => {
    try {
      await deactivateFdsBlocklistEntry(entryId)
      reload()
    } catch {
      setError('차단 해제를 완료하지 못했습니다.')
    }
  }

  return (
    <>
      <PageHeader
        title="차단 목록"
        description="등록된 카드 토큰·기기 ID·IP 주소·이메일·구매자 식별자와 일치하면 R07이 점수 계산 없이 즉시 DENY를 반환합니다."
        actions={
          <Button type="primary" onClick={() => setModalOpen(true)}>
            차단 값 등록
          </Button>
        }
      />
      {error && (
        <Alert type="error" showIcon message={error} closable onClose={() => setError(null)} />
      )}
      <Card style={{ marginTop: error ? 16 : 0 }}>
        <Table
          rowKey="entryId"
          size="small"
          pagination={false}
          dataSource={result?.content ?? []}
          columns={[
            {
              title: '종류',
              dataIndex: 'identifierType',
              render: (value: FdsBlocklistEntry['identifierType']) => (
                <Tag>{typeLabels[value]}</Tag>
              ),
            },
            {
              title: '관리',
              render: (_, entry: FdsBlocklistEntry) =>
                isActive(entry) ? (
                  <Button
                    size="small"
                    onClick={(event) => {
                      event.stopPropagation()
                      void deactivate(entry.entryId)
                    }}
                  >
                    차단 해제
                  </Button>
                ) : (
                  '—'
                ),
            },
            {
              title: '식별값',
              render: (_, entry: FdsBlocklistEntry) =>
                entry.identifierType === 'CARD_TOKEN'
                  ? (entry.displayLabel ?? '카드 토큰')
                  : entry.identifierValue,
              ellipsis: true,
            },
            { title: '등록 사유', dataIndex: 'reason', ellipsis: true },
            { title: '등록 시각', dataIndex: 'createdAt', render: formatDate },
            {
              title: '상태',
              render: (_, entry: FdsBlocklistEntry) =>
                isActive(entry) ? <Tag color="red">차단 중</Tag> : <Tag>비활성</Tag>,
            },
          ]}
          onRow={(entry) => ({
            onClick: () => setSelectedEntry(entry),
            style: { cursor: 'pointer' },
          })}
        />
        <Pagination
          current={page + 1}
          pageSize={10}
          total={result?.totalElements ?? 0}
          showSizeChanger={false}
          style={{ marginTop: 16, textAlign: 'right' }}
          onChange={(next) => setPage(next - 1)}
        />
      </Card>
      <Modal
        title="차단 값 등록"
        open={modalOpen}
        okText="등록"
        cancelText="취소"
        confirmLoading={submitting}
        onCancel={closeModal}
        onOk={() => void submit()}
      >
        <Form form={form} layout="vertical">
          <Form.Item label="종류">
            <Select
              value={identifierType}
              onChange={(value: FdsBlocklistEntry['identifierType']) => setIdentifierType(value)}
              options={Object.entries(typeLabels).map(([value, label]) => ({ value, label }))}
            />
          </Form.Item>
          <Form.Item
            label="식별값"
            name="identifierValue"
            rules={[
              { required: true, whitespace: true, message: '차단할 식별값을 입력해주세요.' },
              { max: 255 },
            ]}
          >
            <Input maxLength={255} placeholder={typePlaceholders[identifierType]} />
          </Form.Item>
          {identifierType === 'CARD_TOKEN' && (
            <Form.Item label="표시 카드" name="displayLabel" rules={[{ max: 50 }]}>
              <Input maxLength={50} placeholder="예: VISA •••• 1234 (P5가 전달한 마스킹 값)" />
            </Form.Item>
          )}
          <Form.Item
            label="등록 사유"
            name="reason"
            rules={[
              { required: true, whitespace: true, message: '등록 사유를 입력해주세요.' },
              { max: 500 },
            ]}
          >
            <Input.TextArea
              maxLength={500}
              showCount
              rows={4}
              placeholder="예: 사기 결제로 확인되어 재시도를 차단합니다."
            />
          </Form.Item>
        </Form>
      </Modal>
      <Modal
        title="차단 등록 사유"
        open={selectedEntry !== null}
        footer={<Button onClick={() => setSelectedEntry(null)}>닫기</Button>}
        onCancel={() => setSelectedEntry(null)}
      >
        {selectedEntry && (
          <Descriptions column={1} size="small" colon={false}>
            <Descriptions.Item label="종류">
              {typeLabels[selectedEntry.identifierType]}
            </Descriptions.Item>
            <Descriptions.Item label="식별값">
              {selectedEntry.identifierType === 'CARD_TOKEN'
                ? (selectedEntry.displayLabel ?? '카드 토큰')
                : selectedEntry.identifierValue}
            </Descriptions.Item>
            <Descriptions.Item label="등록 시각">
              {formatDate(selectedEntry.createdAt)}
            </Descriptions.Item>
            <Descriptions.Item label="등록 사유">
              <Typography.Paragraph style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}>
                {selectedEntry.reason}
              </Typography.Paragraph>
            </Descriptions.Item>
          </Descriptions>
        )}
      </Modal>
    </>
  )
}
