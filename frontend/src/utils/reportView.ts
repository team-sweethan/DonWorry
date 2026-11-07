import type {
  AiReport,
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
import { won } from './format.ts'
import type { MlCell } from './transactionList.ts'

// AI 보고서 화면(요약 카드 · 전문 페이지) 표시 규칙. React 없이 테스트한다.

type PaymentAnalysis = ReportBody['paymentAnalyses'][number]

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

// 조사 과정 탭의 "사용 모델" 문구 — 고정 문장 · mock을 AI 모델 이름처럼 보이지 않게 한다 (#294)
export function traceProviderLabel(
  trace: Pick<InvestigationTrace, 'provider' | 'model' | 'fixedReason'>,
): string {
  if (trace.provider === 'FIXED' && trace.fixedReason === 'ADMIN_STOPPED')
    return '고정 문장 (AI 조사 긴급정지)'
  if (trace.provider === 'FIXED') return '고정 문장 (AI 서버 꺼짐)'
  if (trace.provider === 'MOCK') return 'mock (Qwen 미사용 · 정해진 순서)'
  return trace.model
}

// 고정 보고서면 보고서 맨 위에 보여 줄 안내 (#626, ADR-0083 결정 6) — 직원이 요약이 짧은 이유를 첫 화면에서 알게
// 이유가 없는 예전 고정 보고서는 그때 기준표가 없었을 수 있어 "기준표는 같다"고 쓰지 않는다
export function fixedReportNotice(trace: InvestigationTrace | undefined): string | null {
  if (trace?.provider !== 'FIXED') return null
  const same = '조회 결과와 징후 · 확인할 것은 AI 보고서와 같고, AI 요약과 추가 조사는 없습니다.'
  if (trace.fixedReason === 'ADMIN_STOPPED')
    return `AI 조사 긴급정지 중에 만든 고정 보고서입니다. ${same}`
  if (trace.fixedReason === 'CONFIG_OFF')
    return `AI 서버가 꺼진 상태에서 만든 고정 보고서입니다. ${same}`
  return 'AI 서버가 꺼진 상태에서 만든 고정 보고서입니다. AI 요약과 추가 조사는 없습니다.'
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
  // 기본 조회는 코드가 한 단계로 한다 (#578) — 끝나기 전에는 단계가 없다
  if (!last) return { title: '기본 조회 실행 중', note: null }
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
      // 시스템 조회는 기본 조회다 — 다음은 AI가 추가로 볼 조회를 고르는 단계다 (#578, 첫 추가 도구 #581)
      return last.actor === 'SYSTEM'
        ? { title: '추가로 볼 조회를 고르는 중', note: null }
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

// ── 징후 · 정상 근거 (#501, 판정 권고는 #644에서 뺐다) ──

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

// basis: 결제 하나의 판정 근거, 또는 사건 전체 징후 · 정상 근거 (#591 — 결제가 여러 건인 요약)
export function markSignals(
  text: string,
  basis: Pick<DecisionBasis, 'signals' | 'normals'> | null | undefined,
): TextPart[] {
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
export const caseSignalId = signalId

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

// ② 확인할 것 오른쪽 정상 근거 설명 — 같은 이름은 한 장으로 묶되, 결제마다 설명하는 징후가 다르면 결제 번호로 나눠 말한다
// caseNormals(첫 결제 값만)에 normalNote를 쓰면 결제 B가 설명하는 징후를 "정상 사유가 되지 않습니다"로 보였다 (#652 Codex 리뷰)
// 결제가 한 건이면 normalNote 문장 그대로
export function caseNormalNotes(body: ReportBody): Array<{ label: string; note: string }> {
  const withBasis = body.paymentAnalyses.filter((p) => p.basis)
  const groups = new Map<string, Array<{ paymentId: string; n: NormalBasis }>>()
  for (const p of withBasis)
    for (const n of p.basis?.normals ?? []) {
      const group = groups.get(n.label) ?? []
      group.push({ paymentId: String(p.paymentId), n })
      groups.set(n.label, group)
    }
  return [...groups.entries()].map(([label, group]) => {
    if (withBasis.length <= 1) return { label, note: normalNote(group[0].n) }
    const explaining = group.filter((g) => g.n.explains.length > 0)
    const idle = group.filter((g) => g.n.explains.length === 0)
    const ids = (gs: typeof group) => gs.map((g) => g.paymentId).join(' · ')
    const explains = [...new Set(explaining.flatMap((g) => g.n.explains))].join(' · ')
    if (idle.length === 0)
      return { label, note: `${explains} 징후의 정상 사유입니다. (결제 ${ids(explaining)})` }
    if (explaining.length === 0) {
      const can = group[0].n.canExplain.join(' · ')
      return {
        label,
        note: `${can} 징후의 정상 사유가 될 수 있습니다. 결제 ${ids(idle)}에는 그 징후가 없어 정상 사유가 되지 않습니다.`,
      }
    }
    return {
      label,
      note: `결제 ${ids(explaining)}: ${explains} 징후의 정상 사유입니다. 결제 ${ids(idle)}: 그 징후가 없어 정상 사유가 되지 않습니다.`,
    }
  })
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

// ── 요약 문장 끝 표 링크 (#559) ──
// AI 요약 문장의 근거 사실 번호를 보고, 그 사실이 있는 표 카드로 가는 링크를 붙인다
// 번호(이력-1 등)는 화면에 보이지 않는다 — 표 카드 제목으로 보여 준다

export type SummaryLink = { target: string; label: string }
export type SummaryLine = { text: string; links: SummaryLink[] }

// 주제 → 이동할 카드 (ReportFull의 id와 같다). 접속 정보는 ① 결제 표의 그 결제 줄로 간다
const TOPIC_CARDS: Record<string, SummaryLink> = {
  history: { target: 'report-history', label: '고객 최근 결제' },
  linked: { target: 'report-linked', label: '다른 손님과 겹치는 것' },
  similar: { target: 'report-similar', label: '지난 사건과 비교' },
  order: { target: 'report-order', label: '주문 정보' },
}

// ── 주문 정보 카드 (#634) ──

// 주문 → 결제 걸린 시간 — ai-server facts._elapsed와 같은 단위. 가맹점 시계 기준이라 음수면 그렇게 말한다
export function orderElapsedText(seconds: number | null): string | null {
  if (seconds === null) return null
  if (seconds < 0) return '주문 시각이 결제보다 늦게 기록됨'
  if (seconds < 120) return `주문 ${seconds}초 뒤 결제`
  if (seconds < 7200) return `주문 ${Math.floor(seconds / 60)}분 뒤 결제`
  return `주문 ${Math.floor(seconds / 3600)}시간 뒤 결제`
}

// 주문자 전화 묶음 번호 → A · B · C (번호 자체가 전화번호처럼 보이지 않게)
export function phoneGroupLabel(group: number): string {
  return group >= 1 && group <= 26 ? String.fromCharCode(64 + group) : String(group)
}

export function paymentCardId(paymentId: string): string {
  return `report-payment-${paymentId}`
}

// 링크 정보가 없는 보고서(#559 전)는 문장만, 같은 카드는 문장마다 한 번만
// 고정 보고서는 summaryFacts가 빈 목록이다 — 요약 문장 수와 다르면 요약을 그대로 보여 준다 (#559 Codex 리뷰)
export function summaryLines(body: ReportBody): SummaryLine[] {
  const linked = body.summaryFacts
  if (!linked || !body.facts || linked.length !== body.summary.length) {
    return body.summary.map((text) => ({ text, links: [] }))
  }
  const facts = new Map(body.facts.map((f) => [f.id, f]))
  return linked.map((sentence) => {
    const links: SummaryLink[] = []
    for (const id of sentence.factIds) {
      const fact = facts.get(id)
      const link =
        fact?.topic === 'signals'
          ? fact.paymentId
            ? { target: paymentCardId(fact.paymentId), label: `결제 ${fact.paymentId} 접속 정보` }
            : null
          : fact
            ? TOPIC_CARDS[fact.topic]
            : null
      if (link && !links.some((l) => l.target === link.target)) links.push(link)
    }
    return { text: sentence.text, links }
  })
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

// ── ML 참고 위험도 (#544) ──
// 판정에 쓰지 않는 참고 점수 (ADR-0061) — 거래 내역 "ML 참고" 칸(mlCell)과 같은 상태 · 숫자를 문장으로
export function mlReportLine(cell: MlCell): string {
  switch (cell.kind) {
    case 'scored':
      return `ML 참고 위험도 ${cell.percent}% · 기준 ${cell.thresholdPercent}% ${
        cell.aboveThreshold ? '이상' : '미만'
      } (${cell.modelVersion} · 판정에 쓰지 않음)`
    case 'none':
      return 'ML 참고 위험도 · 아직 채점 전이거나(결제 후 약 30초 뒤) ML 채점이 꺼져 있어요'
    case 'failed':
      return 'ML 참고 위험도 · 채점 중 오류가 났어요 (결제 결과와는 상관없어요)'
    case 'skipped':
      return 'ML 참고 위험도 · 채점하지 않았어요'
    case 'unknown':
      return 'ML 참고 위험도를 불러오지 못했어요'
    case 'loading':
      return 'ML 참고 위험도 불러오는 중…'
  }
}

// ── 사건 요약 · 확인할 것 (#591) ──

// 사건 개요 한 줄 — 코드가 쓴 개요, 없으면(예전 · 고정 보고서) 결제 건수 · 합계로 만든다
// 예전 보고서의 결론 한 줄(verdict.headline)은 권고 문장이라 쓰지 않는다 (#644, ADR-0009 결정 13)
export function overviewText(body: ReportBody): string {
  const overview = body.caseOverview?.trim()
  if (overview) return overview
  return `결제 ${body.overview.paymentCount}건 · 합계 ${won(body.overview.totalAmount)}`
}

// 확인할 것 카드 한 장 — paymentIds: 이 징후가 있는 결제 전부, openPaymentIds: 그중 풀리지 않은 결제
// mixed: 결제마다 정상 사유가 달라 일부만 풀림
// shownPaymentId: 카드에 보여 주는 값(detail)을 가져온 결제 — 규칙 점수 · 증거도 이 결제 것을 붙인다 (#652 Codex 리뷰)
export type CheckSignal = BasisSignal & {
  paymentIds: string[]
  openPaymentIds: string[]
  mixed: boolean
  shownPaymentId: string
}

// 판정 전에 확인해야 하는 징후 — 정상 사유가 없고 다른 징후에 포함되지도 않은 것
export const isOpenSignal = (s: BasisSignal) => !s.normalReason && !s.countedIn
const isOpen = isOpenSignal

const LEVEL_ORDER: Record<BasisSignal['level'], number> = { HIGH_RISK: 0, CAUTION: 1 }

// 확인할 것 징후 (#644 ② 확인할 것) — 같은 징후는 카드 한 장으로 묶고 결제 번호를 함께 적는다. 고위험 먼저
// 한 결제라도 풀리지 않았으면 풀리지 않은 쪽을 남긴다
// caseSignals(첫 결제 값만 남김)를 쓰면 결제 A만 정상 사유가 있을 때 결제 B의 확인할 것이 사라졌다 (#591 Codex 리뷰)
// 정상 사유는 결제 금액마다 따로 계산된다 (Spring DecisionBasisCalculator). 징후 · 정상 근거가 없으면 null
export function checkSignals(body: ReportBody): CheckSignal[] | null {
  const withBasis = body.paymentAnalyses.filter((p) => p.basis)
  if (withBasis.length === 0) return null
  const groups = new Map<string, Array<{ paymentId: string; signal: BasisSignal }>>()
  for (const p of withBasis)
    for (const signal of p.basis?.signals ?? []) {
      const group = groups.get(signalId(signal)) ?? []
      group.push({ paymentId: String(p.paymentId), signal })
      groups.set(signalId(signal), group)
    }
  return [...groups.values()]
    .map((group) => {
      const open = group.filter((g) => isOpen(g.signal))
      const shown = open[0] ?? group[0]
      return {
        ...shown.signal,
        paymentIds: [...new Set(group.map((g) => g.paymentId))],
        openPaymentIds: open.map((g) => g.paymentId),
        mixed: open.length > 0 && open.length < group.length,
        shownPaymentId: shown.paymentId,
      }
    })
    .sort((a, b) => LEVEL_ORDER[a.level] - LEVEL_ORDER[b.level])
}

// 체크할 징후와 순번 — 아래 고정 줄 "확인 1 / 3 완료"
// 확인할 것 카드와 같은 순서 · 같은 조건(정상 사유 없음 + 확인할 것 있음)
// 징후 카드에 붙일 FDS 규칙 기록 — 카드 값을 가져온 결제의 기록만 본다
// 사건 전체에서 첫 기록을 고르면 결제 B의 값에 결제 A의 점수 · 증거가 붙었다 (#652 Codex 리뷰)
export function checkSignalRecord(body: ReportBody, signal: CheckSignal): SignalRuleRecord | null {
  const payment = body.paymentAnalyses.find((p) => String(p.paymentId) === signal.shownPaymentId)
  return signalRuleRecord(signal, payment?.ruleHitDetails)
}

// 비교 표 "지금" 열의 판정 상태 — 일부만 판정한 사건은 판정 끝이 아니다 (#652 Codex 리뷰)
// progress는 decisionProgress 결과 (고객이 직접 취소한 결제는 남은 건수에서 뺀다)
export function nowDecisionText(progress: {
  decided: number
  remaining: number
  done: boolean
}): string {
  if (progress.done) return '판정 끝'
  if (progress.decided > 0) return `일부 판정 · 남은 결제 ${progress.remaining}건`
  return '판정 전'
}

export function checkRows(body: ReportBody): Array<{ id: string; row: number }> {
  const signals = checkSignals(body) ?? []
  return signals.flatMap((s, i) =>
    isOpen(s) && s.checkBefore ? [{ id: signalId(s), row: i + 1 }] : [],
  )
}

// ── 지난 사건과 비교 (#644 ③) ──
// 지금 사건과 지난 사건 여러 건을 징후 줄로 나란히 놓는다. 해석 문장 · 권고 줄은 없다

// 사건 한 열 — null이면 징후 정리가 없는 보고서(예전 보고서 · 보고서 없음 · 불러오지 못함)
export type CompareColumn = { signals: CheckSignal[]; normals: NormalBasis[] } | null

export function compareColumn(body: ReportBody | null): CompareColumn {
  const signals = body ? checkSignals(body) : null
  if (!body || !signals) return null
  return { signals, normals: caseNormals(body) }
}

// UNKNOWN: 그 사건은 징후 정리가 없어 있는지 모른다 — "없음"으로 보이면 틀린 비교가 된다
export type PastCell =
  | { kind: 'NONE' }
  | { kind: 'UNKNOWN' }
  | { kind: 'OPEN' | 'EXPLAINED' | 'COUNTED'; signal: CheckSignal }

// diff[i]: i번째 열이 지금 사건(0번 열)과 다른 칸인지. 모르는 칸은 다르다고 하지 않는다
export type PastCompareRow = {
  id: string
  label: string
  level: BasisSignal['level']
  cells: PastCell[]
  diff: boolean[]
}

const cellOf = (column: CompareColumn, id: string): PastCell => {
  if (!column) return { kind: 'UNKNOWN' }
  const signal = column.signals.find((s) => signalId(s) === id)
  if (!signal) return { kind: 'NONE' }
  if (signal.countedIn) return { kind: 'COUNTED', signal }
  if (signal.openPaymentIds.length === 0) return { kind: 'EXPLAINED', signal }
  return { kind: 'OPEN', signal }
}

// columns[0]은 지금 사건. 줄은 지금 사건 징후 먼저, 지난 사건에만 있는 징후는 뒤에 (고위험 먼저)
export function pastCompareRows(columns: CompareColumn[]): PastCompareRow[] {
  const seen = new Map<string, CheckSignal>()
  for (const column of columns)
    for (const s of column?.signals ?? []) if (!seen.has(signalId(s))) seen.set(signalId(s), s)
  const nowIds = new Set((columns[0]?.signals ?? []).map(signalId))
  const ordered = [...seen.entries()].sort(
    ([a, sa], [b, sb]) =>
      Number(!nowIds.has(a)) - Number(!nowIds.has(b)) ||
      LEVEL_ORDER[sa.level] - LEVEL_ORDER[sb.level],
  )
  return ordered.map(([id, s]) => {
    const cells = columns.map((column) => cellOf(column, id))
    const now = cells[0]
    return {
      id,
      label: s.label,
      level: s.level,
      cells,
      diff: cells.map(
        (cell, i) =>
          i > 0 && cell.kind !== 'UNKNOWN' && now.kind !== 'UNKNOWN' && cell.kind !== now.kind,
      ),
    }
  })
}

// ── PR 2(#650) 전에 저장된 보고서의 글자 ──
// 판정 기준표 · 권고를 뺀 뒤에도 옛 본문에는 그때 이름이 남아 있다. 저장 본문은 고치지 않고 화면에서만 바꾼다
// 조사 과정 탭의 단계 이름은 조사 기록이라 바꾸지 않는다 (11/08 사용자 결정)
const LEGACY_TEXT: Record<string, string> = {
  '판정 기준표 (Spring 계산)': '징후 · 정상 근거 (Spring 계산)',
  '판정 기준표를 받지 못한 결제는 권고를 추가 확인으로 두었습니다.':
    '징후 · 정상 근거를 받지 못한 결제가 있습니다. FDS 규칙 · 점수로 확인해 주세요.',
}

export function legacyReportText(text: string): string {
  return Object.hasOwn(LEGACY_TEXT, text) ? LEGACY_TEXT[text] : text
}

// 처음에 보여 줄 요약 문장 수 — 나머지는 [더 보기]
export const SUMMARY_SHOWN = 3

export type SummaryFold = { hidden: number; hiddenNotable: number }

// 요약 접기 — 숨은 문장 수와, 숨은 문장에만 나오는 눈여겨볼 사실 수
// 보이는 문장에 이미 나온 사실은 세지 않는다 — 버튼을 안 눌러도 놓치지 않으니까
// 근거 번호가 문장 수와 맞지 않는 보고서(고정 · 예전)는 눈여겨볼 사실을 알 수 없어 0
export function summaryFold(body: ReportBody, shown: number = SUMMARY_SHOWN): SummaryFold {
  const hidden = Math.max(body.summary.length - shown, 0)
  const sentences = body.summaryFacts
  if (hidden === 0 || !sentences || sentences.length !== body.summary.length || !body.facts)
    return { hidden, hiddenNotable: 0 }
  const notable = new Set(body.facts.filter((f) => f.notable).map((f) => f.id))
  const seen = new Set(sentences.slice(0, shown).flatMap((s) => s.factIds))
  const later = new Set(sentences.slice(shown).flatMap((s) => s.factIds))
  const hiddenNotable = [...later].filter((id) => notable.has(id) && !seen.has(id)).length
  return { hidden, hiddenNotable }
}

// 안전망 줄 (#591) — AI가 빠뜨려 시스템이 덧붙인 눈여겨볼 사실 문장 (사실 문장은 코드가 썼다)
export function appendedFactTexts(body: ReportBody): string[] {
  const facts = new Map((body.facts ?? []).map((f) => [f.id, f.text]))
  return (body.summaryAppended ?? []).flatMap((id) => {
    const text = facts.get(id)
    return text ? [text] : []
  })
}
