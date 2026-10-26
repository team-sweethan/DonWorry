import type {
  MerchantCaseDetail,
  MerchantCaseList,
  MerchantCaseRow,
  MerchantPaymentResult,
} from '../types/merchantCase.ts'
import { api, ApiError } from './client.ts'

/**
 * 고객사 "보안 조치 결과" API (`/api/merchant/security-cases`, #205).
 * 가맹점은 서버가 로그인 세션으로 정한다 — 화면은 가맹점 ID를 보내지 않는다.
 * 백엔드 ID는 숫자이고 화면 타입은 문자열이다. 변환은 이 파일 안에서만 한다.
 */

type Id = number
type ServerRow = Omit<MerchantCaseRow, 'caseId'> & { caseId: Id }
type ServerList = Omit<MerchantCaseList, 'cases'> & { cases: ServerRow[] }
type ServerPayment = Omit<MerchantPaymentResult, 'paymentId'> & { paymentId: Id }
type ServerDetail = Omit<MerchantCaseDetail, 'caseId' | 'payments'> & {
  caseId: Id
  payments: ServerPayment[]
}

/** 권한 없음(403, INV_014)은 그대로 던진다. 화면이 권한 안내를 보여준다. */
export async function getMerchantCases(): Promise<MerchantCaseList> {
  const list = await api<ServerList>('/merchant/security-cases', { cache: 'no-store' })
  return { ...list, cases: list.cases.map((c) => ({ ...c, caseId: String(c.caseId) })) }
}

/** 없는 Case와 다른 가맹점 Case는 둘 다 404로 오고 null을 돌려준다. */
export async function getMerchantCase(caseId: string): Promise<MerchantCaseDetail | null> {
  try {
    const detail = await api<ServerDetail>(
      `/merchant/security-cases/${encodeURIComponent(caseId)}`,
      { cache: 'no-store' },
    )
    return {
      ...detail,
      caseId: String(detail.caseId),
      payments: detail.payments.map((p) => ({ ...p, paymentId: String(p.paymentId) })),
    }
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) return null
    throw error
  }
}
