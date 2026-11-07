import { api } from './client.ts'

/**
 * AI 조사 긴급정지 (`/api/ai-switch`, #626 · ADR-0083).
 * 조회 · 끄기는 보안 직원 · 관리자, 다시 켜기는 관리자만. 끄기 · 켜기 응답은 바뀐 뒤의 상태다.
 */
export type AiSwitchAction = 'STOPPED' | 'RESUMED'

export type AiSwitchHistory = {
  historyId: string
  action: AiSwitchAction
  reason: string
  changedByName: string
  changedAt: string
}

export type AiSwitchStatus = {
  /** 설정 ai.server.enabled — 꺼져 있으면 스위치를 켜도 고정 보고서 */
  configEnabled: boolean
  switchOn: boolean
  /** configEnabled && switchOn */
  aiActive: boolean
  /** 최신순 20건 */
  histories: AiSwitchHistory[]
}

const base = '/ai-switch'

export function getAiSwitch() {
  return api<AiSwitchStatus>(base, { cache: 'no-store' })
}

export function stopAi(reason: string) {
  return change('stop', reason)
}

export function resumeAi(reason: string) {
  return change('resume', reason)
}

function change(action: 'stop' | 'resume', reason: string) {
  return api<AiSwitchStatus>(`${base}/${action}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason: reason.trim() }),
  })
}
