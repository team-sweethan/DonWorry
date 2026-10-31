import { useEffect, useRef, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Input,
  Modal,
  Space,
  Spin,
  Table,
  Tag,
  Typography,
} from 'antd'
import { PageHeader } from '../../components/common/PageHeader'
import { ApiError, subscribeUnauthorized } from '../../api/client'
import {
  getIntegration,
  integrationLabels,
  requestIntegrationKey,
  revokeIntegrationKey,
} from '../../api/integration'
import type {
  IntegrationInfo,
  IntegrationStatus,
  KeyAction,
  KeyResult,
} from '../../api/integration'
import { useAuth } from '../../auth/useAuth'
import './integration-preview.css'

const actionLabels: Record<string, string> = {
  ISSUE: '발급',
  REISSUE: '재발급',
  REVOKE: '폐기',
  MIGRATE: '기존 키 이전',
}
const ownerGuides: Record<IntegrationStatus, { message: string; description: string }> = {
  NO_KEY: {
    message: '시크릿 키 발급이 필요합니다',
    description:
      '키를 발급해 쇼핑몰 서버에 설정한 뒤 연결 확인을 진행해 주세요. 키가 없으면 신규 결제를 요청할 수 없습니다.',
  },
  PENDING_CONNECTION: {
    message: '쇼핑몰 연결 확인이 필요합니다',
    description:
      '키를 쇼핑몰 서버에 설정한 뒤 서명된 연결 확인 요청을 보내 주세요. 연결 확인 전에는 신규 결제를 요청할 수 없습니다. 완료 후 새로고침하면 상태가 갱신됩니다.',
  },
  CONNECTED: {
    message: '쇼핑몰 연동이 완료되었습니다',
    description:
      '연동 완료 상태에서는 키 원문이 숨겨집니다. 가맹점이 사용 중이면 신규 결제를 요청할 수 있습니다.',
  },
}
const failure = (error: unknown) =>
  error instanceof ApiError
    ? (error.detail ?? '요청에 실패했습니다.')
    : '처리 결과를 확인할 수 없습니다. 새로고침 후 상태를 확인해 주세요.'
type Confirmation = { action: KeyAction | 'revoke'; version: number }

export function IntegrationPreviewPage() {
  const { user } = useAuth()
  return (
    <>
      <PageHeader
        title="시크릿 키 발급"
        description="쇼핑몰 서버에서 사용할 시크릿 키를 발급하고 관리합니다."
      />
      <IntegrationPanel key={user?.userId} />
    </>
  )
}

export function IntegrationPanel({ merchantId }: { merchantId?: number }) {
  const { user } = useAuth()
  const userId = user?.userId
  const admin = merchantId !== undefined
  const [revision, setRevision] = useState(0)
  const [page, setPage] = useState(0)
  const [result, setResult] = useState<{
    merchantId?: number
    page: number
    revision: number
    data: IntegrationInfo
  } | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [confirmation, setConfirmation] = useState<Confirmation | null>(null)
  const [password, setPassword] = useState('')
  const [reason, setReason] = useState('')
  const [key, setKey] = useState<KeyResult | null>(null)
  const [busy, setBusy] = useState(false)
  const [needsRefresh, setNeedsRefresh] = useState(false)
  const pending = useRef<AbortController | null>(null)
  const generation = useRef(0)
  const data =
    result !== null &&
    result.merchantId === merchantId &&
    result.page === page &&
    result.revision === revision
      ? result.data
      : null
  useEffect(() => {
    const controller = new AbortController()
    const id = generation.current
    getIntegration(merchantId, page, controller.signal)
      .then((value) => {
        if (!controller.signal.aborted && generation.current === id) {
          setResult({ merchantId, page, revision, data: value })
          setNeedsRefresh(false)
        }
      })
      .catch((err) => {
        if (!controller.signal.aborted) setError(failure(err))
      })
    return () => controller.abort()
  }, [merchantId, page, revision])
  useEffect(() => {
    const clear = () => {
      generation.current++
      pending.current?.abort()
      pending.current = null
      setBusy(false)
      setKey(null)
      setPassword('')
      setReason('')
      setConfirmation(null)
      setNeedsRefresh(true)
    }
    const hide = () => {
      if (document.visibilityState === 'hidden') clear()
    }
    const unsubscribe = subscribeUnauthorized(clear)
    document.addEventListener('visibilitychange', hide)
    window.addEventListener('pagehide', clear)
    return () => {
      clear()
      unsubscribe()
      document.removeEventListener('visibilitychange', hide)
      window.removeEventListener('pagehide', clear)
    }
  }, [merchantId, userId])
  const refresh = () => {
    setKey(null)
    setPassword('')
    setReason('')
    setConfirmation(null)
    setError(null)
    setRevision((v) => v + 1)
  }
  const open = (action: Confirmation['action']) => {
    if (!data) return
    setKey(null)
    setPassword('')
    setReason('')
    setError(null)
    setConfirmation({ action, version: data.keyVersion })
  }
  const submit = async () => {
    if (!confirmation || pending.current) return
    const controller = new AbortController(),
      id = generation.current
    pending.current = controller
    setBusy(true)
    setError(null)
    const supplied = password
    setPassword('')
    try {
      if (confirmation.action === 'revoke' && merchantId !== undefined)
        await revokeIntegrationKey(
          merchantId,
          reason.trim(),
          confirmation.version,
          controller.signal,
        )
      else if (confirmation.action !== 'revoke') {
        const value = await requestIntegrationKey(
          confirmation.action,
          supplied,
          confirmation.version,
          controller.signal,
        )
        if (generation.current === id && !controller.signal.aborted) setKey(value)
      }
      if (generation.current === id && !controller.signal.aborted) {
        setConfirmation(null)
        setReason('')
        setRevision((v) => v + 1)
      }
    } catch (err) {
      if (generation.current === id && !controller.signal.aborted) {
        setConfirmation(null)
        setReason('')
        setKey(null)
        setNeedsRefresh(true)
        setError(failure(err))
      }
    } finally {
      if (generation.current === id) {
        pending.current = null
        setBusy(false)
      }
    }
  }
  return (
    <Card
      title={admin ? '쇼핑몰 연동 관리' : '내 연동 정보'}
      extra={
        <Button disabled={busy} onClick={refresh}>
          새로고침
        </Button>
      }
    >
      {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 16 }} />}
      {needsRefresh && (
        <Alert
          type="warning"
          showIcon
          message="새로고침으로 최신 상태를 확인한 뒤 진행해 주세요."
          style={{ marginBottom: 16 }}
        />
      )}
      {!data ? (
        !error && <Spin />
      ) : (
        <>
          <Descriptions
            column={1}
            items={[
              {
                key: 'code',
                label: '가맹점 코드',
                children: <Typography.Text code>{data.merchantCode}</Typography.Text>,
              },
              {
                key: 'status',
                label: '연동 상태',
                children: (
                  <Tag color={data.status === 'CONNECTED' ? 'green' : 'default'}>
                    {integrationLabels[data.status]}
                  </Tag>
                ),
              },
              {
                key: 'issued',
                label: '키 발급 시각 (UTC)',
                children: data.keyIssuedAt?.replace('T', ' ') ?? '아직 발급하지 않았습니다',
              },
              {
                key: 'connected',
                label: '연결 확인 시각 (UTC)',
                children: data.connectedAt?.replace('T', ' ') ?? '연결 확인 기록이 없습니다',
              },
            ]}
          />
          {admin ? (
            <>
              <Typography.Paragraph>
                관리자는 키 원문을 볼 수 없습니다. 폐기하면 현재 키는 즉시 사용할 수 없으며 실행
                계정과 사유가 기록됩니다.
              </Typography.Paragraph>
              <Button
                danger
                disabled={busy || needsRefresh || data.status === 'NO_KEY'}
                onClick={() => open('revoke')}
              >
                키 폐기
              </Button>
            </>
          ) : (
            <>
              <Alert
                type="info"
                showIcon
                message={ownerGuides[data.status].message}
                description={ownerGuides[data.status].description}
                style={{ margin: '16px 0' }}
              />
              <Space wrap>
                <Button
                  type="primary"
                  disabled={busy || needsRefresh || data.status !== 'NO_KEY'}
                  onClick={() => open('issue')}
                >
                  시크릿 키 발급
                </Button>
                <Button
                  disabled={busy || needsRefresh || data.status !== 'PENDING_CONNECTION'}
                  onClick={() => open('reveal')}
                >
                  다시 보기
                </Button>
                <Button
                  danger
                  disabled={busy || needsRefresh || data.status === 'NO_KEY'}
                  onClick={() => open('reissue')}
                >
                  재발급
                </Button>
              </Space>
              <Typography.Paragraph type="secondary" style={{ marginTop: 12 }}>
                키를 발급·조회·재발급할 때마다 비밀번호를 확인합니다. 키는 쇼핑몰 서버에만 보관하고
                메일·메신저로 보내지 마세요.
              </Typography.Paragraph>
              <Typography.Paragraph>
                <a
                  href="https://github.com/team-sweethan/DonWorry/blob/dev/docs/identity/354-merchant-integration.md"
                  target="_blank"
                  rel="noreferrer"
                >
                  쇼핑몰 개발자 연동 안내
                </a>
              </Typography.Paragraph>
            </>
          )}
          <Table
            rowKey="historyId"
            size="small"
            style={{ marginTop: 24 }}
            dataSource={data.histories}
            scroll={{ x: 600 }}
            pagination={{
              disabled: busy,
              current: page + 1,
              pageSize: 20,
              total: data.totalElements,
              showSizeChanger: false,
              onChange: (p) => {
                setKey(null)
                setPassword('')
                setReason('')
                setConfirmation(null)
                setPage(p - 1)
              },
            }}
            columns={[
              {
                title: '시각 (UTC)',
                dataIndex: 'createdAt',
                render: (v: string) => v.replace('T', ' '),
              },
              { title: '작업', dataIndex: 'action', render: (v: string) => actionLabels[v] ?? v },
              {
                title: '실행 계정 ID',
                dataIndex: 'actorUserId',
                render: (v: number | null) => v ?? '시스템',
              },
              { title: '사유', dataIndex: 'reason' },
            ]}
          />
        </>
      )}
      <Modal
        open={!!confirmation}
        title={
          confirmation?.action === 'revoke'
            ? '키를 폐기할까요?'
            : confirmation?.action === 'reissue'
              ? '키를 재발급할까요?'
              : confirmation?.action === 'reveal'
                ? '비밀번호 확인 후 키 보기'
                : '비밀번호 확인 후 키 발급'
        }
        okText="확인"
        cancelText="취소"
        confirmLoading={busy}
        closable={!busy}
        maskClosable={!busy}
        cancelButtonProps={{ disabled: busy }}
        okButtonProps={{
          danger: confirmation?.action === 'revoke' || confirmation?.action === 'reissue',
          disabled: confirmation?.action === 'revoke' ? !reason.trim() : !password,
        }}
        onOk={submit}
        onCancel={() => {
          if (!pending.current) {
            setConfirmation(null)
            setPassword('')
            setReason('')
          }
        }}
      >
        {confirmation?.action === 'reissue' && (
          <Alert
            type="warning"
            showIcon
            message="결제가 일시 중단됩니다"
            description="이전 키는 즉시 폐기됩니다. 새 키를 쇼핑몰 서버에 설정하고 연결 확인을 마칠 때까지 신규 결제가 중단됩니다."
            style={{ marginBottom: 16 }}
          />
        )}
        {confirmation?.action === 'revoke' ? (
          <>
            <p>기존 키로 새 요청을 인증할 수 없습니다. 폐기 사유를 입력해 주세요.</p>
            <Input.TextArea
              aria-label="폐기 사유"
              maxLength={500}
              value={reason}
              disabled={busy}
              onChange={(e) => setReason(e.target.value)}
            />
          </>
        ) : (
          <Input.Password
            aria-label="현재 비밀번호"
            autoComplete="off"
            maxLength={256}
            value={password}
            disabled={busy}
            onChange={(e) => setPassword(e.target.value)}
            onPressEnter={() => {
              if (password) void submit()
            }}
          />
        )}
      </Modal>
      <Modal
        open={!!key}
        title="시크릿 키"
        footer={<Button onClick={() => setKey(null)}>닫기</Button>}
        onCancel={() => setKey(null)}
      >
        <Alert
          type="warning"
          showIcon
          message="쇼핑몰 서버에만 보관하세요"
          description="이 창을 닫거나 다른 화면으로 이동하면 표시된 키를 지웁니다."
        />
        <Typography.Paragraph code style={{ overflowWrap: 'anywhere', marginTop: 16 }}>
          {key?.secret}
        </Typography.Paragraph>
        <Button
          onClick={() => {
            if (key)
              void navigator.clipboard
                .writeText(key.secret)
                .catch(() =>
                  setError('클립보드에 복사할 수 없습니다. 키를 직접 선택해 복사해 주세요.'),
                )
          }}
        >
          복사
        </Button>
      </Modal>
    </Card>
  )
}
