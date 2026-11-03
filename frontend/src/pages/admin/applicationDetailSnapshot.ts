import type { ApplicationDetail } from '../../api/adminApplications.ts'

export type ApplicationDetailSnapshot = {
  key: string
  data?: ApplicationDetail
  error?: string
  needsRefresh?: boolean
}

// 카드 조회와 전체 조회가 경쟁해도 현재 신청의 같은 요청에만 새 상세를 반영한다.
export function applyApplicationDetailSnapshot(
  current: ApplicationDetailSnapshot | null,
  expectedKey: string,
  detail: ApplicationDetail,
): ApplicationDetailSnapshot | null {
  if (
    current?.key !== expectedKey ||
    current.data?.application.applicationId !== detail.application.applicationId
  )
    return current
  return { key: expectedKey, data: detail }
}

// 불명확한 검토 응답도 그 요청이 속한 상세에만 잠금을 남긴다.
export function requireApplicationDetailRefresh(
  current: ApplicationDetailSnapshot | null,
  expectedKey: string,
): ApplicationDetailSnapshot | null {
  return current?.key === expectedKey ? { ...current, needsRefresh: true } : current
}
