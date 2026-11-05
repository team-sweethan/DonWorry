import type { MerchantStatus } from '../api/adminMerchants.ts'

export type AdminMerchantListQuery = { page: number; keyword: string; status?: MerchantStatus }

export function parseAdminMerchantListQuery(params: URLSearchParams): AdminMerchantListQuery {
  const page = Number(params.get('page'))
  const status = params.get('status')
  return {
    page: Number.isSafeInteger(page) && page >= 1 && page <= 2_147_483_647 ? page : 1,
    keyword: (params.get('keyword') ?? '').slice(0, 100),
    status: status === 'ACTIVE' || status === 'INACTIVE' ? status : undefined,
  }
}

export function adminMerchantListParams(query: AdminMerchantListQuery): URLSearchParams {
  const params = new URLSearchParams()
  if (query.keyword) params.set('keyword', query.keyword)
  if (query.status) params.set('status', query.status)
  if (query.page > 1) params.set('page', String(query.page))
  return params
}
