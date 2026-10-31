import { api } from './client.ts'
import { readSessionVersion } from './sessionVersion.ts'
export type IntegrationStatus = 'NO_KEY' | 'PENDING_CONNECTION' | 'CONNECTED'
export type KeyAction = 'issue' | 'reveal' | 'reissue'
export type IntegrationInfo = {
  merchantCode: string
  status: IntegrationStatus
  keyVersion: number
  keyIssuedAt: string | null
  connectedAt: string | null
  histories: Array<{
    historyId: number
    actorUserId: number | null
    action: string
    keyVersion: number
    reason: string | null
    createdAt: string
  }>
  totalElements: number
}
export type KeyResult = { secret: string; keyVersion: number }
export const integrationLabels: Record<IntegrationStatus, string> = {
  NO_KEY: '키 없음',
  PENDING_CONNECTION: '연동 전',
  CONNECTED: '연동 완료',
}
export async function getIntegration(merchantId?: number, page = 0, signal?: AbortSignal) {
  const version = readSessionVersion()
  const result = await api<IntegrationInfo>(
    `${merchantId === undefined ? '/owner/integration' : `/admin/merchants/${merchantId}/integration`}?page=${page}`,
    { cache: 'no-store', signal },
  )
  signal?.throwIfAborted()
  if (version !== readSessionVersion())
    throw new DOMException('로그인 상태가 변경되었습니다.', 'AbortError')
  return result
}
export async function requestIntegrationKey(
  action: KeyAction,
  currentPassword: string,
  expectedKeyVersion: number,
  signal?: AbortSignal,
) {
  const version = readSessionVersion()
  const suffix = action === 'issue' ? '' : `/${action}`
  const result = await api<KeyResult>(`/owner/integration/keys${suffix}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    cache: 'no-store',
    body: JSON.stringify({ currentPassword, expectedKeyVersion }),
    signal,
  })
  signal?.throwIfAborted()
  if (version !== readSessionVersion())
    throw new DOMException('로그인 상태가 변경되었습니다.', 'AbortError')
  return result
}
export function revokeIntegrationKey(
  merchantId: number,
  reason: string,
  expectedKeyVersion: number,
  signal?: AbortSignal,
) {
  return api<void>(`/admin/merchants/${merchantId}/integration/revoke`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    cache: 'no-store',
    body: JSON.stringify({ reason, expectedKeyVersion }),
    signal,
  })
}
