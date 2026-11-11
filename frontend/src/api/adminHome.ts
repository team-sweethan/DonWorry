import { getUsers } from './adminAccounts.ts'
import { getMerchants } from './adminMerchants.ts'
import { getAiSwitch } from './aiSwitch.ts'
import { getFdsRuleChangeRequests, getFdsRulePolicies, getOperatorAccessSummary } from './fds.ts'

export async function getAdminHome() {
  const [
    requests,
    disabledRules,
    users,
    merchants,
    passwordPendingUsers,
    aiSwitch,
    operatorAccess,
  ] = await Promise.allSettled([
    // 서버가 요청 시각 내림차순으로 반환한다. 전체 건수는 페이지 길이와 구분한다.
    getFdsRuleChangeRequests('PENDING', 0).then((page) => ({
      count: page.totalElements,
      recent: page.content.slice(0, 5),
    })),
    getFdsRulePolicies().then((policies) => policies.filter((policy) => !policy.active).length),
    getUsers(0, '').then((page) => page.totalElements),
    getMerchants(0, '').then((page) => page.totalElements),
    // 임시 비밀번호를 아직 바꾸지 않은 사용 중 계정. 관리자가 전달 여부를 챙길 대상이다.
    getUsers(0, '', undefined, 'ACTIVE', true).then((page) => page.totalElements),
    // AI 조사 긴급정지 · 서버 연결 상태 (ADR-0083)
    getAiSwitch(),
    // 사장님 계정 접근 이상 — 최근 7일(한국 날짜) 거부 요청 · 계정 수 (#515, #746)
    getOperatorAccessSummary(),
  ])
  return {
    requests,
    disabledRules,
    users,
    merchants,
    passwordPendingUsers,
    aiSwitch,
    operatorAccess,
  }
}

export type AdminHome = Awaited<ReturnType<typeof getAdminHome>>
