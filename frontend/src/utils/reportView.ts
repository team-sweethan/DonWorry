import type {
  AiRecommendation,
  AiReport,
  BasisRule,
  CaseDetail,
  BasisSignal,
  DecisionBasis,
  InvestigationTrace,
  NormalBasis,
  PaymentSignals,
  ReportBody,
  ReportRuleHitDetail,
  SimilarCaseItem,
  TraceStep,
} from '../types/case.ts'
import { latestReport } from './caseRules.ts'

// AI 보고서 화면(요약 카드 · 전문 페이지) 표시 규칙. React 없이 테스트한다.

type PaymentAnalysis = ReportBody['paymentAnalyses'][number]

// 결론 카드의 한 줄. #288 전에 저장된 보고서는 결론이 없어 null
export function reportHeadline(body: ReportBody): string | null {
  const headline = body.verdict?.headline.trim()
  return headline ? headline : null
}

// 결론 카드의 큰 숫자 = FDS 점수 최댓값. 조회 못 한 점수(null)는 0으로 세지 않는다
export function topRiskScore(scores: Array<number | null>): number | null {
  const known = scores.filter((s): s is number => s !== null)
  return known.length ? Math.max(...known) : null
}

// 전문 보기 버튼 조건. 최신 보고서가 생성 중 · 실패여도 이전에 성공한 보고서가 있으면 볼 수 있어야 한다
export function hasReadableReport(reports: AiReport[]): boolean {
  return reports.some((r) => r.status === 'SUCCEEDED' && r.reportBody !== null)
}

export type PickedReport = {
  report: AiReport
  isLatest: boolean
  // 성공한 보고서인데 Case 증거 버전과 다르면 이 보고서로 판정할 수 없다
  evidenceMismatch: boolean
}

// 선택한 버전이 없으면(처음 열었거나 사라진 버전) 최신 보고서를 보여 준다
export function pickReport(
  reports: AiReport[],
  selectedVersion: number | null,
  caseEvidenceVersion: number,
): PickedReport | null {
  const latest = latestReport(reports)
  if (!latest) return null
  const report = reports.find((r) => r.reportVersion === selectedVersion) ?? latest
  return {
    report,
    isLatest: report === latest,
    evidenceMismatch:
      report.status === 'SUCCEEDED' && report.evidenceVersion !== caseEvidenceVersion,
  }
}

export type RuleEvidenceRow = {
  name: string
  appliedScore: number | null
  evidenceId: string | null
  criterion: string | null
  observed: string | null
}

// 위험 근거의 규칙 줄. ruleHitDetails(FDS 조회 기록)가 없는 예전 보고서는 규칙 이름만 보여 준다
export function ruleEvidenceRows(analysis: PaymentAnalysis): RuleEvidenceRow[] {
  if (analysis.ruleHitDetails) {
    return analysis.ruleHitDetails.map((d) => ({
      name: d.name,
      appliedScore: d.appliedScore,
      evidenceId: d.evidenceId,
      criterion: d.criterion ?? null,
      observed: d.observed ?? null,
    }))
  }
  return analysis.ruleHits.map((name) => ({
    name,
    appliedScore: null,
    evidenceId: null,
    criterion: null,
    observed: null,
  }))
}

// 결제별 "미확인"을 한 목록으로 모은다 (같은 문장은 한 번만)
export function unknownItems(body: ReportBody): string[] {
  return [...new Set(body.paymentAnalyses.flatMap((p) => p.unknowns))]
}

// 조사 과정 탭의 "사용 모델" 문구 — 고정 문장 · mock을 AI 모델 이름처럼 보이지 않게 한다 (#294)
export function traceProviderLabel(trace: Pick<InvestigationTrace, 'provider' | 'model'>): string {
  if (trace.provider === 'FIXED') return '고정 문장 (AI 서버 꺼짐)'
  if (trace.provider === 'MOCK') return 'mock (Qwen 미사용 · 정해진 순서)'
  return trace.model
}

// 걸린 시간 표시 — 1초 미만은 ms, 그 이상은 소수 첫째 자리 초
export function durationText(ms: number | null): string {
  if (ms === null) return '-'
  return ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(1)}초`
}

export type OutcomeTag = { label: string; tone: 'accept' | 'reject' | 'none' }

// 비슷한 사건 결과 태그 (#296 B안) — 매입 진행 · 승인 취소를 결제별 건수로 따로 보여 준다
export function similarOutcomeTags(
  item: Pick<SimilarCaseItem, 'acceptCount' | 'rejectCount'>,
): OutcomeTag[] {
  const tags: OutcomeTag[] = []
  if (item.acceptCount > 0) tags.push({ label: `매입 진행 ${item.acceptCount}건`, tone: 'accept' })
  if (item.rejectCount > 0) tags.push({ label: `승인 취소 ${item.rejectCount}건`, tone: 'reject' })
  return tags.length ? tags : [{ label: '판정 기록 없음', tone: 'none' }]
}

// 카드 · 기기 · 접속 국가 한 줄의 칸 (#346) — 가맹점이 안 보낸 값은 "정보 없음"으로 남긴다
export type SignalText = { label: string; value: string; missing: boolean }
const NOT_SENT = '정보 없음 (가맹점이 보내지 않음)'

export function signalTexts(s: PaymentSignals): SignalText[] {
  const card = s.cardDisplayLabel ?? (s.cardProvided ? '있음 (표시값 없음)' : NOT_SENT)
  const country = s.ipCountryCode
    ? s.ipCountryCode === 'KR'
      ? '한국 (KR)'
      : `해외 (${s.ipCountryCode})`
    : s.ipProvided
      ? '국가 모름'
      : NOT_SENT
  return [
    { label: '카드', value: card, missing: !s.cardProvided },
    { label: '기기', value: s.deviceProvided ? '정보 있음' : NOT_SENT, missing: !s.deviceProvided },
    { label: '접속 국가', value: country, missing: !s.ipProvided },
  ]
}

export type LiveTask = { title: string; note: string | null }

// 생성 중 "지금 하는 일" (#355 A안) — 서버는 끝난 단계만 주므로 마지막 단계를 보고 다음 단계를 추측한다
export function liveCurrentTask(status: AiReport['status'], steps: TraceStep[]): LiveTask {
  if (status === 'PENDING') {
    return { title: '조사 시작을 기다리는 중', note: '다른 조사가 끝나면 시작합니다' }
  }
  const last = steps.at(-1)
  if (!last) return { title: '사건 근거를 보고 첫 조회를 고르는 중', note: null }
  const writes = steps.filter((s) => s.kind === 'WRITE').length
  const writing = (attempt: number): LiveTask => ({
    title: attempt === 1 ? '판단 문장 쓰는 중 (1차)' : `판단 문장 다시 쓰는 중 (${attempt}차)`,
    note: '가장 오래 걸리는 단계입니다',
  })
  switch (last.kind) {
    case 'DECIDE':
      return last.received?.startsWith('조사 완료')
        ? writing(1)
        : { title: '고른 조회를 실행하는 중', note: null }
    case 'TOOL':
      // 시스템 보충 조회는 AI가 조사 완료라고 답한 뒤에 하므로 다음은 판단 문장 쓰기다
      return last.actor === 'SYSTEM'
        ? writing(1)
        : { title: '다음에 볼 조회를 고르는 중', note: null }
    case 'NUDGE':
      return { title: '다음에 볼 조회를 고르는 중', note: null }
    case 'WRITE':
      return last.received?.startsWith('형식 검사 통과')
        ? { title: '보고서 조립 중', note: null }
        : writing(writes + 1)
    case 'ASSEMBLE':
      return { title: '보고서 저장 중', note: null }
  }
}

// 조사 시작 후 지난 시간 — 보고서 요청 시각 기준. 기기 시계가 조금 달라도 음수는 0초로 보인다
export function elapsedText(createdAt: string, now: number): string {
  const seconds = Math.max(0, Math.floor((now - Date.parse(createdAt)) / 1000))
  if (seconds < 60) return `${seconds}초`
  return `${Math.floor(seconds / 60)}분 ${seconds % 60}초`
}

// ── 판정 근거 화면 (#501) ──

// 권고 앞말 — 판정 근거가 있으면 기준표(코드)가 정한 판정, 없으면 예전 보고서의 AI 권고
export type RecommendationSource = '기준표 판정' | 'AI 권고'

export function recommendationSource(
  analysis: Pick<PaymentAnalysis, 'basis'> | null | undefined,
): RecommendationSource {
  return analysis?.basis ? '기준표 판정' : 'AI 권고'
}

// 종합 권고 앞말 — 모든 결제에 판정 근거가 있을 때만 기준표 판정
export function reportRecommendationSource(body: ReportBody): RecommendationSource {
  const all = body.paymentAnalyses.length > 0 && body.paymentAnalyses.every((p) => p.basis)
  return all ? '기준표 판정' : 'AI 권고'
}

// "기준표 판정과 다름" · "AI 권고와 다름"처럼 조사를 붙인다
export const sourceWith: Record<RecommendationSource, string> = {
  '기준표 판정': '기준표 판정과',
  'AI 권고': 'AI 권고와',
}

// 판정 기준표 4칸 — ai-server evidence.RULE_LABELS와 같은 순서 · 문장
export const BASIS_RULE_ROWS: Array<{ rule: BasisRule; text: string }> = [
  {
    rule: 'HIGH_RISK_WITH_OTHERS',
    text: '고위험 징후 + 정상 사유 없는 다른 징후 → 승인 취소 검토',
  },
  { rule: 'HIGH_RISK_ONLY', text: '고위험 징후 하나만 정상 사유 없음 → 추가 확인' },
  { rule: 'CAUTION_ONLY', text: '주의 징후만 정상 사유 없음 → 추가 확인' },
  { rule: 'ALL_EXPLAINED', text: '모든 징후에 정상 사유 있음 → 매입 진행 검토' },
]

// 징후를 고위험 · 주의로 나눈다
export function basisGroups(basis: DecisionBasis): {
  highRisk: BasisSignal[]
  caution: BasisSignal[]
} {
  return {
    highRisk: basis.signals.filter((s) => s.level === 'HIGH_RISK'),
    caution: basis.signals.filter((s) => s.level === 'CAUTION'),
  }
}

// 징후 오른쪽 꼬리표 — 같은 행동을 이미 셌으면 그 징후 이름, 아니면 정상 사유 유무
export function signalTag(s: BasisSignal): { text: string; open: boolean } {
  if (s.countedIn) return { text: `${s.countedIn}에 포함 · 따로 세지 않음`, open: false }
  if (s.normalReason) return { text: `정상 사유 있음 · ${s.normalReason}`, open: false }
  return { text: '정상 사유 없음', open: true }
}

// 정상 거래 근거 설명 — 설명할 수 있는 징후와 이번에 실제로 설명한 징후
export function normalNote(n: NormalBasis): string {
  const can = n.canExplain.join(' · ')
  if (n.explains.length > 0) return `${n.explains.join(' · ')} 징후의 정상 사유입니다.`
  return `${can} 징후의 정상 사유가 될 수 있습니다. 이번 결제에는 그 징후가 없어 위 징후의 정상 사유가 되지 않습니다.`
}

// 조사 요약 밑줄 — 문장에 징후 이름이 그대로 있을 때만 긋는다 (추측해서 긋지 않음)
export type MarkKind = 'HIGH_RISK' | 'CAUTION' | 'NORMAL'
export type TextPart = { text: string; mark: MarkKind | null }

// 정상 거래 근거는 화면 이름에 건수가 있어 AI 문장에 그대로 나오지 않는다 — 대표 말로 찾는다
const NORMAL_TERMS: Record<string, string> = { SIMILAR_AMOUNT: '비슷한 금액' }

// "해외 접속 (TH)"처럼 괄호 붙은 이름은 괄호 앞까지로 찾는다
const baseName = (label: string) => label.replace(/\s*\([^)]*\)\s*$/, '')

export function markSignals(text: string, basis: DecisionBasis | null | undefined): TextPart[] {
  if (!basis) return [{ text, mark: null }]
  const terms: Array<{ term: string; mark: MarkKind }> = [
    ...basis.signals.map((s) => ({ term: baseName(s.label), mark: s.level })),
    ...basis.normals.flatMap((n) =>
      NORMAL_TERMS[n.key] ? [{ term: NORMAL_TERMS[n.key], mark: 'NORMAL' as const }] : [],
    ),
  ]
    .filter((t) => t.term.length > 0)
    .sort((a, b) => b.term.length - a.term.length)
  const parts: TextPart[] = []
  let plain = ''
  let i = 0
  while (i < text.length) {
    const hit = terms.find((t) => text.startsWith(t.term, i))
    if (hit) {
      if (plain) parts.push({ text: plain, mark: null })
      plain = ''
      parts.push({ text: hit.term, mark: hit.mark })
      i += hit.term.length
    } else {
      plain += text[i]
      i += 1
    }
  }
  if (plain) parts.push({ text: plain, mark: null })
  return parts
}

// ── 비슷한 사건 나란히 비교 (#501) ──

// 해외 접속은 나라가 이름에 들어 있어 나라별로 따로 센다 (#501 리뷰)
const OVERSEAS = 'OVERSEAS'
const signalId = (s: BasisSignal) => (s.key === OVERSEAS ? `${s.key}:${s.label}` : s.key)

// 사건 전체 징후 — 결제마다 같은 징후는 한 번만, 판정 근거가 하나도 없으면 null (예전 보고서)
export function caseSignals(body: ReportBody): BasisSignal[] | null {
  const bases = body.paymentAnalyses.flatMap((p) => (p.basis ? [p.basis] : []))
  if (bases.length === 0) return null
  const seen = new Map<string, BasisSignal>()
  for (const s of bases.flatMap((b) => b.signals))
    if (!seen.has(signalId(s))) seen.set(signalId(s), s)
  return [...seen.values()]
}

// 사건 전체 정상 거래 근거 — 같은 이름은 한 번만
export function caseNormals(body: ReportBody): NormalBasis[] {
  const seen = new Map<string, NormalBasis>()
  for (const p of body.paymentAnalyses)
    for (const n of p.basis?.normals ?? []) if (!seen.has(n.label)) seen.set(n.label, n)
  return [...seen.values()]
}

// 비교 한 줄 — 같은 징후는 한 줄에 나란히, 한쪽에만 있으면 반대쪽은 빈칸
// tag: 두 사건 공통 · 나라만 다름(해외 접속 나라가 다름) · 한쪽만
export type CompareTag = 'SAME' | 'COUNTRY_DIFFERS' | 'ONLY'
export type CompareRow = { past: BasisSignal | null; now: BasisSignal | null; tag: CompareTag }

export function compareSignals(past: BasisSignal[], now: BasisSignal[]): CompareRow[] {
  const leftPast = [...past]
  const take = (match: (s: BasisSignal) => boolean) => {
    const i = leftPast.findIndex(match)
    return i < 0 ? null : leftPast.splice(i, 1)[0]
  }
  // 1) 이름까지 같은 징후 → 두 사건 공통
  const paired = now.map((n) => ({ n, p: take((s) => signalId(s) === signalId(n)) }))
  // 2) 남은 해외 접속끼리 한 번씩 → 나라만 다름, 3) 그래도 남으면 이 사건만
  const rows: CompareRow[] = []
  const only: CompareRow[] = []
  for (const { n, p } of paired) {
    if (p) {
      rows.push({ past: p, now: n, tag: 'SAME' })
      continue
    }
    const other = take((s) => s.key === n.key)
    if (other) rows.push({ past: other, now: n, tag: 'COUNTRY_DIFFERS' })
    else only.push({ past: null, now: n, tag: 'ONLY' })
  }
  return [...rows, ...only, ...leftPast.map((p) => ({ past: p, now: null, tag: 'ONLY' as const }))]
}

// 비교 한 줄의 React 키 — 해외 접속은 나라가 달라도 키가 같아 이름까지 쓴다
export function compareRowKey(row: CompareRow): string {
  return [row.past, row.now].map((s) => (s ? signalId(s) : '-')).join('|')
}

// 비교 화면에 쓸 보고서 — 가장 최근에 성공한 보고서 본문, 없으면 null
export function latestReadableBody(reports: AiReport[]): ReportBody | null {
  return latestReadable(reports)?.reportBody ?? null
}

function latestReadable(reports: AiReport[]): AiReport | null {
  const done = reports.filter((r) => r.status === 'SUCCEEDED' && r.reportBody !== null)
  if (done.length === 0) return null
  return done.reduce((a, b) => (b.reportVersion > a.reportVersion ? b : a))
}

export type CompareReport = {
  body: ReportBody
  reportVersion: number
  evidenceVersion: number
  // 사건 증거 버전과 다르면 나중에 합쳐진 결제는 이 보고서가 조사하지 않았다 (#501 리뷰)
  evidenceMismatch: boolean
}

// 비교 화면 한쪽의 보고서 — 최근 성공 보고서와 버전 · 증거 버전 불일치 여부
export function compareReport(
  detail: Pick<CaseDetail, 'reports' | 'securityCase'>,
): CompareReport | null {
  const report = latestReadable(detail.reports)
  if (!report?.reportBody) return null
  return {
    body: report.reportBody,
    reportVersion: report.reportVersion,
    evidenceVersion: report.evidenceVersion,
    evidenceMismatch: report.evidenceVersion !== detail.securityCase.evidenceVersion,
  }
}

export type VerdictLines = {
  overall: AiRecommendation | null
  payments: Array<{ paymentId: string; recommendation: AiRecommendation }>
}

// 종합 권고 · 결제별 권고 줄 — 결제가 2건 이상이거나 결론이 없는 예전 보고서면 결제별로 보여 준다
// 보고서 전문 · 비교 화면이 같은 규칙을 쓴다 (#501 리뷰)
export function verdictLines(body: ReportBody): VerdictLines {
  const overall = body.verdict?.overallRecommendation ?? null
  const perPayment = body.paymentAnalyses.length > 1 || overall === null
  return {
    overall,
    payments: perPayment
      ? body.paymentAnalyses.map((p) => ({
          paymentId: String(p.paymentId),
          recommendation: p.recommendation,
        }))
      : [],
  }
}

// 판정 진행 — 고객이 직접 취소한 결제는 판정 대상이 아니라 남은 건수에서 뺀다 (#501 리뷰)
// 일부만 판정한 사건은 판정 끝이 아니다 (CaseFlowTests.decidePartially_keepsWaiting…)
export function decisionProgress(
  detail: Pick<CaseDetail, 'payments' | 'decisions' | 'securityCase'>,
): { decided: number; remaining: number; done: boolean } {
  const decidedIds = new Set(detail.decisions.map((d) => d.casePaymentId))
  const remaining = detail.payments.filter(
    (p) => !decidedIds.has(p.casePaymentId) && !p.customerCanceledAt,
  ).length
  const done = detail.securityCase.status === 'CLOSED' || (remaining === 0 && decidedIds.size > 0)
  return { decided: detail.decisions.length, remaining, done }
}

// ── 코드 문장과 AI 문장 구분 (#543) ──
// #532부터 결론 한 줄 · 권고 요약 · 결제별 이유는 기준표(코드)가 쓰고 AI는 사실 설명만 쓴다

// 결론 · 권고 요약을 코드가 쓴 보고서인지 (#546 리뷰)
// #501 ~ #532 사이 보고서는 판정 근거가 있어도 AI가 썼다 — #532에서 결제별 이유와 함께 코드로 바뀌어 이유로 알아본다
function isCodeWrittenReport(body: ReportBody): boolean {
  return (
    reportRecommendationSource(body) === '기준표 판정' &&
    body.paymentAnalyses.every((p) => isCodeReason(p.reason))
  )
}

// 결론 카드 작은 제목 — 기준표 보고서면 "기준표 판정", 예전 보고서는 AI가 쓴 결론이라 그대로
export function verdictCardTitle(body: ReportBody): '기준표 판정' | 'AI 조사 요약' {
  return isCodeWrittenReport(body) ? '기준표 판정' : 'AI 조사 요약'
}

// 상세 정보의 권고 요약 제목
export function recommendationSummaryTitle(
  body: ReportBody,
): '권고 요약 (기준표)' | 'AI 권고 원문' {
  return isCodeWrittenReport(body) ? '권고 요약 (기준표)' : 'AI 권고 원문'
}

// 코드가 쓴 결제별 이유의 시작 말 — ai-server app/agent/verdict_text.py payment_reason과 같은 문장
const CODE_REASON_STARTS = [
  '고위험 징후를 포함해 정상 사유가 없는 징후가 겹칩니다',
  '정상 사유가 없는 고위험 징후가 있습니다',
  '정상 사유가 없는 주의 징후가 있습니다',
  '모든 징후에 정상 사유가 있습니다',
  '적중한 징후가 없습니다',
  'FDS 평가 결과를 확인하지 못했습니다',
]

// #501 ~ #532 사이 보고서는 판정 근거가 있어도 이유를 AI가 썼다 — 시작 말로 코드 이유만 골라낸다
export function isCodeReason(reason: string | undefined): boolean {
  return !!reason && CODE_REASON_STARTS.some((start) => reason.startsWith(start))
}

// 조사 요약 박스(AI 해석)에 넣을 문장 — 코드 이유는 "왜 이 판정인가요?"로 옮기고 AI 이유만 남긴다
export function summaryBoxLines(
  summary: string[],
  analysis: Pick<PaymentAnalysis, 'reason'>,
): string[] {
  const reason = analysis.reason
  return reason && !isCodeReason(reason) ? [...summary, reason] : summary
}

// 예전 영문 규칙 코드 → R 코드 — ai-server evidence._ALIASES와 같은 표
const RULE_ALIASES: Record<string, string> = {
  HIGH_AMOUNT: 'R01',
  NEW_DEVICE: 'R02',
  NEW_CARD: 'R03',
  RAPID_REPEAT_PAYMENT: 'R04',
}

export type SignalRuleRecord = Pick<ReportRuleHitDetail, 'appliedScore' | 'evidenceId'>

// 규칙 징후에 붙일 FDS 기록(점수 · 증거 번호) — 규칙이 아닌 징후(같은 IP · 해외 접속 등)는 null
export function signalRuleRecord(
  signal: Pick<BasisSignal, 'key'>,
  details: Array<Pick<ReportRuleHitDetail, 'code' | 'appliedScore' | 'evidenceId'>> | undefined,
): SignalRuleRecord | null {
  const hit = details?.find((d) => (RULE_ALIASES[d.code] ?? d.code) === signal.key)
  return hit ? { appliedScore: hit.appliedScore, evidenceId: hit.evidenceId } : null
}

// "+30점 · 증거 E-2-v1-02" — 점수가 없는 예전 기록은 증거 번호만
export function signalRecordText(record: SignalRuleRecord): string {
  const score = record.appliedScore === null ? null : `+${record.appliedScore}점`
  return [score, `증거 ${record.evidenceId}`].filter(Boolean).join(' · ')
}
