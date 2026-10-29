import type { AdminUser } from '../api/adminAccounts.ts'

export type AdminUserListQuery = {
  page: number
  keyword: string
  role?: AdminUser['role']
  status?: AdminUser['status']
  passwordChangeRequired?: true
}

/** 화면·새로고침·뒤로가기가 같은 조건을 쓰도록 적용된 필터는 URL에서 읽는다. */
export function parseAdminUserListQuery(params: URLSearchParams): AdminUserListQuery {
  const role = params.get('role')
  const status = params.get('status')
  const page = Number(params.get('page'))
  return {
    page: Number.isSafeInteger(page) && page >= 1 && page <= 2_147_483_647 ? page : 1,
    keyword: (params.get('keyword') ?? '').slice(0, 100),
    role: role === 'ADMIN' || role === 'OPERATOR' || role === 'SECURITY_STAFF' ? role : undefined,
    status: status === 'ACTIVE' || status === 'DISABLED' ? status : undefined,
    passwordChangeRequired: params.get('passwordChangeRequired') === 'true' ? true : undefined,
  }
}

/** 전체 조건과 첫 페이지는 생략한다. 해제한 필터가 새로고침 때 다시 적용되지 않게 한다. */
export function adminUserListParams(query: AdminUserListQuery): URLSearchParams {
  const params = new URLSearchParams()
  if (query.keyword) params.set('keyword', query.keyword)
  if (query.role) params.set('role', query.role)
  if (query.status) params.set('status', query.status)
  if (query.passwordChangeRequired) params.set('passwordChangeRequired', 'true')
  if (query.page > 1) params.set('page', String(query.page))
  return params
}
