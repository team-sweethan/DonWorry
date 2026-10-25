import { useEffect, useState } from 'react'
import { Alert, Button, Card, Table, Tag } from 'antd'
import { getLoginHistories } from '../../api/loginHistories'
import type { LoginHistory, LoginHistoryPage } from '../../api/loginHistories'
import { ApiError } from '../../api/client'
import { PageHeader } from '../../components/common/PageHeader'
import { emptyLoginMessage, loginDevice, loginTime } from '../../utils/loginHistory'

export function LoginHistoryTable({ userId }: { userId?: number }) {
  const [page, setPage] = useState(0)
  const [revision, setRevision] = useState(0)
  const key = `${userId ?? 'me'}:${page}:${revision}`
  const [result, setResult] = useState<{
    key: string
    data?: LoginHistoryPage
    error?: string
  } | null>(null)
  useEffect(() => {
    const controller = new AbortController()
    let alive = true
    getLoginHistories(page, userId, controller.signal)
      .then((data) => {
        if (alive) setResult({ key, data })
      })
      .catch((error: unknown) => {
        if (alive)
          setResult({
            key,
            error:
              error instanceof ApiError
                ? (error.detail ?? '이력을 조회하지 못했습니다.')
                : '서버에 연결할 수 없습니다.',
          })
      })
    return () => {
      alive = false
      controller.abort()
    }
  }, [userId, page, revision, key])
  const current = result?.key === key ? result : null
  return (
    <>
      <p>
        최근 90일의 로그인 시도와 로그아웃을 한국 시간으로 표시합니다. 기기는 브라우저·OS 기준이며
        정확한 기기 식별은 아닙니다.
      </p>
      {current?.error ? (
        <Alert
          type="error"
          showIcon
          message={current.error}
          action={<Button onClick={() => setRevision((r) => r + 1)}>다시 시도</Button>}
        />
      ) : (
        <Table<LoginHistory>
          rowKey="historyId"
          loading={!current}
          dataSource={current?.data?.items ?? []}
          scroll={{ x: 720 }}
          locale={{ emptyText: current ? emptyLoginMessage : '이력을 불러오고 있습니다.' }}
          pagination={{
            current: page + 1,
            pageSize: 20,
            total: current?.data?.totalElements ?? 0,
            showSizeChanger: false,
            onChange: (value) => setPage(value - 1),
          }}
          columns={[
            {
              title: '시각 (한국)',
              dataIndex: 'occurredAt',
              render: (value: string) => loginTime(value),
            },
            {
              title: '활동',
              dataIndex: 'eventType',
              render: (_: string, row) => (
                <Tag color={row.eventType === 'LOGIN_FAILURE' ? 'orange' : undefined}>
                  {row.eventType === 'LOGIN_SUCCESS'
                    ? '로그인 성공'
                    : row.eventType === 'LOGIN_FAILURE'
                      ? '로그인 실패'
                      : row.logoutScope === 'ALL'
                        ? '모든 기기 로그아웃'
                        : '로그아웃'}
                </Tag>
              ),
            },
            {
              title: 'IP',
              dataIndex: 'clientIp',
              render: (value: string | null) => value ?? '확인 불가',
            },
            {
              title: '기기',
              dataIndex: 'userAgent',
              render: (value: string | null) => loginDevice(value),
            },
          ]}
        />
      )}
    </>
  )
}

export function LoginHistoryScreen() {
  return (
    <>
      <PageHeader title="내 로그인 이력" />
      <Card>
        <LoginHistoryTable />
      </Card>
    </>
  )
}
