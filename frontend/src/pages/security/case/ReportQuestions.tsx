import { useEffect, useState, type CSSProperties } from 'react'
import { Alert, Button, Input, Spin, Tag } from 'antd'
import { getAiSwitch } from '../../../api/aiSwitch'
import {
  askQuestion,
  getReportQuestions,
  REJECTION_MESSAGES,
  type QuestionRejection,
} from '../../../api/reportQuestions'
import type { ReportBody, ReportQuestion } from '../../../types/case'
import {
  factBadge,
  QUESTION_DAILY_LIMIT,
  QUESTION_EXAMPLES,
  QUESTION_MAX_LENGTH,
  questionsToday,
  sentenceChips,
} from '../../../utils/reportQuestionView'
import { dateTime } from './caseLabels'
import { goToCard } from './goToCard'

const RUNNING_POLL_MS = 3000
const LIMIT_TICK_MS = 60 * 1000

// 보고서 아래 "이 사건에 대해 묻기" (#753, ADR-0086 7절)
// 답은 보고서가 아니다 — 보고서와 모양을 나누고 안내를 늘 보인다. 판정은 보안 직원이 한다
// 고정 보고서(AI 정지 · 꺼짐 중 생성)는 서버가 받지 않아 칸 대신 한 줄만 보인다 (11/10 결정)
// 부모가 보고서마다 key로 새로 그린다 — 다른 보고서의 질문 · 입력이 넘어가지 않게
export function ReportQuestions({
  caseId,
  reportId,
  body,
  evidenceMismatch,
}: {
  caseId: string
  reportId: string
  body: ReportBody
  evidenceMismatch: boolean
}) {
  if (body.investigationTrace?.provider === 'FIXED')
    return (
      <Alert
        className="dw-no-print"
        type="info"
        showIcon
        message="고정 보고서라 질문할 수 없습니다. AI가 만든 보고서에만 질문할 수 있습니다."
      />
    )
  return (
    <QuestionPanel
      caseId={caseId}
      reportId={reportId}
      body={body}
      evidenceMismatch={evidenceMismatch}
    />
  )
}

function QuestionPanel({
  caseId,
  reportId,
  body,
  evidenceMismatch,
}: {
  caseId: string
  reportId: string
  body: ReportBody
  evidenceMismatch: boolean
}) {
  const [questions, setQuestions] = useState<ReportQuestion[] | 'error' | null>(null)
  const [reloadKey, setReloadKey] = useState(0)
  // 긴급정지 조회 실패면 잠그지 않는다 — 서버가 503으로 다시 막는다
  const [aiActive, setAiActive] = useState<boolean | null>(null)
  const [text, setText] = useState('')
  const [pending, setPending] = useState<string | null>(null)
  const [rejected, setRejected] = useState<QuestionRejection | null>(null)
  // 24시간 셈의 기준 시각 — 1분마다 갱신해 창을 벗어난 질문이 빠지면 잠금이 저절로 풀린다 (#765 Codex 리뷰)
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), LIMIT_TICK_MS)
    return () => window.clearInterval(timer)
  }, [])

  useEffect(() => {
    let alive = true
    getReportQuestions(caseId, reportId)
      .then((list) => {
        if (alive) setQuestions(list)
      })
      .catch(() => {
        if (alive) setQuestions('error')
      })
    return () => {
      alive = false
    }
  }, [caseId, reportId, reloadKey])

  useEffect(() => {
    let alive = true
    getAiSwitch()
      .then((status) => {
        if (alive) setAiActive(status.aiActive)
      })
      .catch(() => {
        if (alive) setAiActive(null)
      })
    return () => {
      alive = false
    }
  }, [reloadKey])

  // 다른 탭에서 묻는 중이거나 연결이 끊긴 뒤 다시 받은 목록의 RUNNING — 끝날 때까지 다시 조회한다 (#765 Codex 리뷰)
  // 2분 넘은 RUNNING은 서버가 FAILED로 보내므로 조회는 끝난다. 내가 묻는 중에는 POST 응답을 기다린다
  const running = Array.isArray(questions) && questions.some((q) => q.status === 'RUNNING')
  useEffect(() => {
    if (!running || pending !== null) return
    let alive = true
    const timer = window.setInterval(() => {
      getReportQuestions(caseId, reportId)
        .then((list) => {
          if (alive) setQuestions(list)
        })
        .catch(() => null)
    }, RUNNING_POLL_MS)
    return () => {
      alive = false
      window.clearInterval(timer)
    }
  }, [caseId, reportId, running, pending])

  const list = Array.isArray(questions) ? questions : []
  const today = questionsToday(list, now)
  // 한도 잠금은 목록 개수로만 정한다 — 429를 따로 기억하면 창을 벗어나도 풀리지 않는다 (429 뒤에는 목록을 다시 받는다)
  const lock: QuestionRejection | null =
    aiActive === false || rejected === 'STOPPED'
      ? 'STOPPED'
      : today >= QUESTION_DAILY_LIMIT
        ? 'LIMIT'
        : null
  const trimmed = text.trim()
  const canAsk = !lock && pending === null && trimmed.length > 0 && Array.isArray(questions)

  const ask = async () => {
    if (!canAsk) return
    setPending(trimmed)
    setRejected(null)
    const result = await askQuestion(caseId, reportId, trimmed)
    setPending(null)
    if (result.ok) {
      setText('')
      const answered = result.question
      setQuestions((current) =>
        Array.isArray(current)
          ? current.some((q) => q.questionId === answered.questionId)
            ? current.map((q) => (q.questionId === answered.questionId ? answered : q))
            : [...current, answered]
          : current,
      )
      return
    }
    setRejected(result.reason)
    // 시간 초과 · 연결 끊김이면 서버에 질문 행이 남았을 수 있고, 429면 다른 탭 질문이 목록에 없을 수 있다
    // — 목록을 다시 받아 맞춘다
    if (result.reason === 'ERROR' || result.reason === 'LIMIT') setReloadKey((k) => k + 1)
  }

  return (
    <section className="dw-no-print" style={panelStyle} aria-label="이 사건에 대해 묻기">
      <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'baseline', gap: 8 }}>
        <h2 style={{ margin: 0, fontSize: 17, fontWeight: 750, color: '#0F172A' }}>
          이 사건에 대해 묻기
        </h2>
        <span style={{ fontSize: 12.5, color: '#64748B' }}>
          질문과 답은 기록됩니다 · 오늘 이 보고서 질문 {today} / {QUESTION_DAILY_LIMIT}
        </span>
      </div>
      <div style={noticeStyle}>
        답은 보고서가 아니며 판정 근거가 아닙니다. 판정은 보고서와 확인할 것을 보고 직접 내려
        주세요.
      </div>
      {evidenceMismatch && (
        <Alert
          type="warning"
          showIcon
          message="이 보고서를 만든 뒤 사건 자료가 바뀌었습니다. 새로 조회하지 않고 보고서 내용으로만 답합니다."
        />
      )}
      {lock && <Alert type="warning" showIcon message={REJECTION_MESSAGES[lock]} />}

      {questions === null ? (
        <Spin style={{ display: 'block', margin: '12px auto' }} />
      ) : questions === 'error' ? (
        <Alert
          type="error"
          showIcon
          message="질문 목록을 불러오지 못했습니다."
          action={
            <Button size="small" onClick={() => setReloadKey((k) => k + 1)}>
              다시 시도
            </Button>
          }
        />
      ) : list.length === 0 && pending === null ? (
        <p style={{ margin: 0, fontSize: 13, color: '#64748B' }}>
          아직 질문이 없습니다. 아래 예시를 누르거나 직접 물어보세요.
        </p>
      ) : (
        <ol style={listStyle}>
          {list.map((q) => (
            <li key={q.questionId}>
              <QuestionItem question={q} body={body} />
            </li>
          ))}
          {pending !== null && (
            <li>
              <QuestionLine text={pending} />
              <div style={{ ...answerStyle, color: '#64748B' }}>
                <Spin size="small" /> 답을 만드는 중입니다 (최대 30초)
              </div>
            </li>
          )}
        </ol>
      )}

      {rejected && rejected !== lock && rejected !== 'STOPPED' && (
        <Alert type="error" showIcon message={REJECTION_MESSAGES[rejected]} />
      )}
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
        {QUESTION_EXAMPLES.map((example) => (
          <button
            key={example}
            type="button"
            style={
              lock || pending !== null
                ? { ...exampleStyle, opacity: 0.45, cursor: 'not-allowed' }
                : exampleStyle
            }
            disabled={!!lock || pending !== null}
            onClick={() => setText(example)}
          >
            {example}
          </button>
        ))}
      </div>
      {/* showCount 글자 수는 입력창 아래로 나온다 — 칸 테두리를 넘지 않게 아래 여백을 둔다 */}
      <div style={{ display: 'flex', gap: 8, alignItems: 'flex-start', marginBottom: 14 }}>
        <Input.TextArea
          value={text}
          onChange={(e) => setText(e.target.value)}
          onPressEnter={(e) => {
            // Enter는 보내기, Shift+Enter는 줄바꿈 · 한글 조합 중 Enter는 무시
            if (e.shiftKey || e.nativeEvent.isComposing) return
            e.preventDefault()
            void ask()
          }}
          maxLength={QUESTION_MAX_LENGTH}
          showCount
          autoSize={{ minRows: 1, maxRows: 4 }}
          disabled={!!lock || pending !== null}
          placeholder="이 사건에 대해 궁금한 것을 물어보세요"
          aria-label="질문"
          style={{ flex: 1 }}
        />
        <Button type="primary" onClick={() => void ask()} disabled={!canAsk} loading={!!pending}>
          묻기
        </Button>
      </div>
    </section>
  )
}

function QuestionLine({ text }: { text: string }) {
  return (
    <div style={questionStyle}>
      <span style={{ fontWeight: 700, color: '#1E3A8A', marginRight: 6 }}>질문</span>
      {text}
    </div>
  )
}

const KIND_TAGS = {
  ANSWER: null,
  NOT_IN_REPORT: { color: 'default', text: '보고서에 없음' },
  DECLINED_DECISION: { color: 'orange', text: '판정 질문 — 답하지 않음' },
  DECLINED_SCOPE: { color: 'orange', text: '사건 밖 질문 — 답하지 않음' },
} as const

function QuestionItem({ question, body }: { question: ReportQuestion; body: ReportBody }) {
  const asked = (
    <span style={{ fontSize: 12, color: '#94A3B8', marginLeft: 8 }}>
      {dateTime(question.askedAt)}
    </span>
  )
  if (question.status === 'RUNNING')
    return (
      <>
        <QuestionLine text={question.question} />
        <div style={{ ...answerStyle, color: '#64748B' }}>
          <Spin size="small" /> 답을 만드는 중입니다 {asked}
        </div>
      </>
    )
  if (question.status === 'FAILED' || !question.kind)
    return (
      <>
        <QuestionLine text={question.question} />
        <div style={{ ...answerStyle, color: '#B91C1C' }}>
          답을 만들지 못했습니다 — 보고서를 직접 확인하세요 {asked}
        </div>
      </>
    )
  const tag = KIND_TAGS[question.kind]
  return (
    <>
      <QuestionLine text={question.question} />
      <div style={answerStyle}>
        {tag && (
          <Tag color={tag.color} style={{ marginBottom: 6 }}>
            {tag.text}
          </Tag>
        )}
        {question.sentences.map((s, i) => (
          <p key={i} style={{ margin: '0 0 6px' }}>
            {s.text}
            {sentenceChips(s.facts, question.facts, body).map((chip) =>
              chip.kind === 'link' ? (
                <button
                  key={chip.link.target}
                  type="button"
                  style={linkChipStyle}
                  onClick={() => goToCard(chip.link.target)}
                >
                  {chip.link.label} ↗
                </button>
              ) : (
                <span
                  key={`${chip.badge.label}:${chip.badge.retrievedAt ?? ''}`}
                  style={badgeStyle}
                >
                  {chip.badge.label}
                  {chip.badge.retrievedAt && ` · ${dateTime(chip.badge.retrievedAt)}`}
                </span>
              ),
            )}
          </p>
        ))}
        {question.kind === 'DECLINED_DECISION' && question.checks.length > 0 && (
          <div style={{ marginTop: 6 }}>
            <b style={{ fontSize: 13 }}>직접 확인할 것 (보고서의 확인할 것 전체)</b>
            <ul style={{ margin: '4px 0 0', paddingLeft: 18 }}>
              {question.checks.map((c) => (
                <li key={c}>{c}</li>
              ))}
            </ul>
          </div>
        )}
        {question.facts.length > 0 && (
          <details style={{ marginTop: 4 }}>
            <summary style={{ cursor: 'pointer', fontSize: 12.5, color: '#475569' }}>
              근거 사실 {question.facts.length}개 보기
            </summary>
            <ul style={{ margin: '4px 0 0', paddingLeft: 18, fontSize: 13 }}>
              {question.facts.map((f) => {
                const badge = factBadge(f)
                return (
                  <li key={f.id}>
                    {f.text}
                    {badge && (
                      <span style={badgeStyle}>
                        {badge.label}
                        {badge.retrievedAt && ` · ${dateTime(badge.retrievedAt)}`}
                      </span>
                    )}
                  </li>
                )
              })}
            </ul>
          </details>
        )}
        <div style={{ fontSize: 12, color: '#94A3B8', marginTop: 4 }}>
          물은 시각 {dateTime(question.askedAt)}
        </div>
      </div>
    </>
  )
}

const panelStyle: CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 12,
  padding: 16,
  border: '1px dashed #A78BFA',
  borderRadius: 12,
  background: '#FAF5FF',
}

const noticeStyle: CSSProperties = {
  fontSize: 13,
  color: '#5B21B6',
  background: '#EDE9FE',
  borderRadius: 8,
  padding: '8px 12px',
}

const listStyle: CSSProperties = {
  listStyle: 'none',
  margin: 0,
  padding: 0,
  display: 'flex',
  flexDirection: 'column',
  gap: 12,
}

const questionStyle: CSSProperties = { fontSize: 14, color: '#0F172A', marginBottom: 4 }

const answerStyle: CSSProperties = {
  fontSize: 14,
  lineHeight: 1.6,
  background: '#fff',
  border: '1px solid #E9D5FF',
  borderRadius: 8,
  padding: '10px 12px',
}

const exampleStyle: CSSProperties = {
  border: '1px solid #C4B5FD',
  background: '#fff',
  color: '#5B21B6',
  borderRadius: 999,
  padding: '2px 10px',
  fontSize: 12.5,
  cursor: 'pointer',
}

const linkChipStyle: CSSProperties = {
  marginLeft: 6,
  border: 'none',
  background: '#EEF2FF',
  color: '#3730A3',
  borderRadius: 6,
  padding: '0 6px',
  fontSize: 12,
  cursor: 'pointer',
}

const badgeStyle: CSSProperties = {
  marginLeft: 6,
  background: '#FEF3C7',
  color: '#92400E',
  borderRadius: 6,
  padding: '0 6px',
  fontSize: 12,
}
