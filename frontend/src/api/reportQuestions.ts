import type { ReportQuestion } from '../types/case.ts'
import { api, ApiError } from './client.ts'

/**
 * 보고서 뒤 사건 질문 (`/api/security-cases/{caseId}/reports/{reportId}/questions`, #752 · ADR-0086).
 * 목록은 그 보고서에 내가 한 질문만 물은 순서대로 온다. 질문은 동기 — 답(최대 30초)을 받아 돌아온다.
 * 답을 만들지 못하면 200 + status FAILED로 온다 (오류가 아니다).
 */

type ServerQuestion = Omit<ReportQuestion, 'questionId'> & { questionId: number }

const path = (caseId: string, reportId: string) =>
  `/security-cases/${caseId}/reports/${reportId}/questions`

export async function getReportQuestions(
  caseId: string,
  reportId: string,
): Promise<ReportQuestion[]> {
  const list = await api<ServerQuestion[]>(path(caseId, reportId), { cache: 'no-store' })
  return list.map(toQuestion)
}

// 질문을 받지 않은 이유 — LIMIT · STOPPED는 입력을 잠근다
// TOO_LONG 400 · FORBIDDEN 403(담당 아님) · NOT_FOUND 404(다른 사건 보고서) · FIXED 409(고정 · 실패 보고서)
// LIMIT 429(최근 24시간 30개) · STOPPED 503(AI 긴급정지 · 꺼짐) · ERROR(그 밖 · 연결 끊김 · 시간 초과)
export type QuestionRejection =
  'TOO_LONG' | 'FORBIDDEN' | 'NOT_FOUND' | 'FIXED' | 'LIMIT' | 'STOPPED' | 'ERROR'

export type AskResult =
  { ok: true; question: ReportQuestion } | { ok: false; reason: QuestionRejection; message: string }

const REJECTIONS: Record<number, QuestionRejection> = {
  400: 'TOO_LONG',
  403: 'FORBIDDEN',
  404: 'NOT_FOUND',
  409: 'FIXED',
  429: 'LIMIT',
  503: 'STOPPED',
}

export const REJECTION_MESSAGES: Record<QuestionRejection, string> = {
  TOO_LONG: '질문을 1~300자로 입력해 주세요.',
  FORBIDDEN: '담당 사건이 아니라 질문할 수 없습니다.',
  NOT_FOUND: '이 사건의 보고서가 아닙니다. 새로고침해 주세요.',
  FIXED: 'AI가 만든 보고서에만 질문할 수 있습니다.',
  LIMIT: '최근 24시간 이 보고서 질문 한도(30개)를 넘었습니다. 보고서를 직접 확인해 주세요.',
  STOPPED: 'AI 조사 긴급정지 중이라 질문을 받지 않습니다. 보고서를 직접 확인해 주세요.',
  ERROR: '질문을 보내지 못했습니다. 잠시 후 다시 시도해 주세요.',
}

export async function askQuestion(
  caseId: string,
  reportId: string,
  question: string,
): Promise<AskResult> {
  try {
    const answer = await api<ServerQuestion>(path(caseId, reportId), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ question: question.trim() }),
    })
    return { ok: true, question: toQuestion(answer) }
  } catch (error) {
    const reason = (error instanceof ApiError && REJECTIONS[error.status]) || 'ERROR'
    return { ok: false, reason, message: REJECTION_MESSAGES[reason] }
  }
}

function toQuestion(q: ServerQuestion): ReportQuestion {
  return {
    ...q,
    questionId: String(q.questionId),
    sentences: q.sentences ?? [],
    checks: q.checks ?? [],
    facts: q.facts ?? [],
  }
}
