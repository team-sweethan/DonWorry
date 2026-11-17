import type { BoothTrack } from './boothSteps.ts'

export type BoothAccount = {
  loginId: string
  password: string
  caseId?: string
  reportCaseId?: string
  evaluationId?: string
}
export type BoothAccounts = Partial<Record<Exclude<BoothTrack, 'public'>, BoothAccount>>

// 계정 파일(boothAccounts.local.ts)은 저장소에 없다. 파일이 없으면 이어 보기는 소개 화면만 보여 준다.
const files = import.meta.glob<{ boothAccounts?: BoothAccounts }>('./boothAccounts.local.ts', {
  eager: true,
})
const loaded = Object.values(files)[0]?.boothAccounts ?? {}

export function boothAccount(track: BoothTrack): BoothAccount | null {
  if (track === 'public') return null
  const account = loaded[track]
  return account && account.loginId.trim() !== '' && account.password !== '' ? account : null
}
