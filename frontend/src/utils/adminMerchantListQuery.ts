import type { MerchantStatus } from '../api/adminMerchants.ts'
import { parseListKeyword, parseListPage } from './listQuery.ts'

export type AdminMerchantListQuery = { page: number; keyword: string; status?: MerchantStatus }

export function parseAdminMerchantListQuery(params: URLSearchParams): AdminMerchantListQuery {
  const status = params.get('status')
  return {
    page: parseListPage(params.get('page')),
    keyword: parseListKeyword(params.get('keyword')),
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
