import { getUsers } from './adminAccounts.ts'
import { getMerchants } from './adminMerchants.ts'
import { getFdsRuleChangeRequests, getFdsRulePolicies } from './fds.ts'

export async function getAdminHome() {
  const [requests, disabledRules, users, merchants] = await Promise.allSettled([
    // 서버가 요청 시각 내림차순으로 반환한다. 전체 건수는 페이지 길이와 구분한다.
    getFdsRuleChangeRequests('PENDING', 0).then((page) => ({
      count: page.totalElements,
      recent: page.content.slice(0, 5),
    })),
    getFdsRulePolicies().then((policies) => policies.filter((policy) => !policy.active).length),
    getUsers(0, '').then((page) => page.totalElements),
    getMerchants(0, '').then((page) => page.totalElements),
  ])
  return { requests, disabledRules, users, merchants }
}

export type AdminHome = Awaited<ReturnType<typeof getAdminHome>>
