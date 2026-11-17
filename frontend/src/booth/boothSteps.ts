import type { BoothPreparation } from './boothPreparation.ts'
import type { AuthUser } from '../types/auth.ts'

/**
 * 부스 시연용 안내 단계 (로컬 개발 시연용 · 통합 브랜치에 머지하지 않는다).
 * 한 창에서 소개 → 사장님 → 보안 직원 → 관리자 순서로 펼치고 스크롤하며 설명한다.
 */
export type BoothTrack = 'public' | 'owner' | 'security' | 'admin'

/** 화면에서 박스로 짚어 줄 곳. sel로 찾고, text가 있으면 그 글자가 들어 있는 것만 고른다. */
export type BoothTarget = { sel: string; text?: string }
export type BoothSpot = BoothTarget & {
  note: string
  prepare?: readonly BoothPreparation[]
  /** 아직 펼치지 않은 영역 대신, 실제로 보이는 목록이나 열기 버튼을 짚는다. */
  fallback?: BoothTarget & { note: string }
}

export function resolveBoothSpot<T>(spot: BoothSpot, find: (target: BoothTarget) => T | null) {
  const target = find(spot)
  if (target !== null) return { target, note: spot.note, waiting: false }
  return {
    target: spot.fallback ? find(spot.fallback) : null,
    note: spot.fallback?.note ?? spot.note,
    waiting: true,
  }
}

export type BoothStep = {
  id: string
  /** 있으면 [다음]을 누를 때 이 주소로 이동한다. 없으면 지금 화면에 머문다 (진행자가 직접 누르는 단계). */
  path?: string
  /** path가 없는 단계가 맞는 화면 범위. 이 주소로 시작하는 화면에서만 자막이 유지된다. */
  under?: string
  /** 방문자가 읽는 큰 자막 */
  headline: string
  /** 보충 설명 */
  detail: string
  /** 진행자용 조작 안내 */
  action?: string
  /** 한 창 전체 이어 보기의 기본 코스. 나머지는 질문을 받았을 때 [더 보기]에서 펼친다. */
  core?: boolean
  /** 이 단계 안에서 차례로 짚을 곳. [다음]을 누르면 박스가 하나씩 옮겨 간다. */
  spots?: BoothSpot[]
  /** 이어 보기에서 이 단계를 보여 줄 역할. 지금 로그인한 역할과 다르면 계정을 바꾼 뒤 이동한다. */
  track?: BoothTrack
}

export const boothAudience: Record<BoothTrack, { label: string; customer: boolean }> = {
  public: { label: '돈워리 소개', customer: true },
  owner: { label: '고객 · 가게 사장님', customer: true },
  security: { label: '돈워리 직원 · 보안 담당', customer: false },
  admin: { label: '돈워리 직원 · 관리자', customer: false },
}

export const boothHandoff: Record<BoothTrack, string> = {
  public: '다음은 사장님 창입니다. 쇼핑몰 연결부터 가게의 돈 흐름까지 봅니다.',
  owner: '다음은 보안 직원 창입니다. 사장님 대신 의심 결제를 조사하는 과정을 봅니다.',
  security: '다음을 누르면 관리자 화면으로 이어집니다. 고객 정보를 다루는 직원도 감독합니다.',
  admin: '발표에서는 실제 결제와 공격 상황에서 이 기능들이 동작하는 모습을 보여 드립니다.',
}

export function boothTrackOf(user: AuthUser | null): BoothTrack {
  if (!user) return 'public'
  if (user.role === 'OPERATOR') return 'owner'
  return user.role === 'SECURITY_STAFF' ? 'security' : 'admin'
}

const publicSteps: BoothStep[] = [
  {
    id: 'landing',
    spots: [
      {
        sel: '.dw-landing-hero-inner',
        note: '온라인 가게의 결제 · 돈 · 정보 보호를 돈워리가 챙깁니다.',
      },
      {
        sel: '.dw-landing-owner',
        note: '사장님은 가게를 운영하고, 돈워리 직원은 의심 결제를 조사합니다.',
      },
      {
        sel: '.dw-landing-features',
        note: '의심 결제만 잠시 멈추고 조사합니다. 정상 결제는 그대로 처리합니다.',
      },
      { sel: '.dw-landing-start', note: '먼저 가게가 돈워리를 쓰기 시작하는 과정입니다.' },
    ],
    path: '/',
    core: true,
    headline: '작은 온라인 가게의 결제 · 자금 · 보안을 함께 챙깁니다',
    detail: '의심 결제 조사와 자금 관리를 돈워리가 대신 챙깁니다.',
  },
  {
    id: 'apply',
    spots: [
      {
        sel: '[aria-label="이용 시작 절차"]',
        note: '이메일 확인 → 돈워리 심사 → 계정 발급 순서입니다.',
      },
      {
        sel: '.dw-application-content',
        note: '가게 정보와 담당자 연락처를 적으면 됩니다.',
      },
    ],
    path: '/apply',
    core: true,
    headline: '사장님은 신청서 한 장으로 시작합니다',
    detail: '이메일 확인 → 돈워리 심사 → 가맹점과 사장님 계정 발급 순서입니다.',
    action: '화면만 보여 주고 제출하지 않습니다',
  },
  {
    id: 'mail',
    path: '/booth-mail',
    core: true,
    headline: '신청하면 확인 메일이, 승인되면 계정 안내 메일이 옵니다',
    detail: '배포 서버가 실제로 보낸 메일의 문구입니다.',
    spots: [
      {
        sel: '.booth-mail-list',
        note: '신청 확인과 이용 승인, 두 통의 안내 메일을 받습니다.',
      },
      {
        sel: '.booth-mail-verify',
        note: '이메일을 확인해야 관리자 심사로 넘어갑니다.',
      },
      {
        sel: '.booth-mail-verify .booth-mail-rule',
        note: '확인 링크는 24시간 · 한 번만 사용. 재발송하면 이전 링크는 무효입니다.',
      },
      {
        sel: '.booth-mail-approve',
        note: '승인 후 비밀번호는 사장님이 직접 정합니다. 메일에 비밀번호를 보내지 않습니다.',
      },
      {
        sel: '.booth-mail-approve .booth-mail-rule',
        note: '첫 비밀번호 설정 링크도 72시간 · 한 번만 사용할 수 있습니다.',
      },
      {
        sel: '.booth-mail-caution',
        note: '쇼핑몰 연결용 시크릿 키도 메일이나 메신저로 공유하지 않도록 안내합니다.',
      },
    ],
  },
  {
    id: 'login',
    path: '/login',
    headline: '사장님 · 보안 직원 · 관리자가 각자 다른 화면으로 들어갑니다',
    detail: '사장님은 자기 가게만, 직원은 맡은 사건만 볼 수 있습니다.',
  },
]

const ownerSteps: BoothStep[] = [
  {
    id: 'owner-home',
    spots: [
      { sel: '.oh-connection', note: '쇼핑몰이 돈워리와 연결돼 있는지 먼저 보여 줍니다.' },
      { sel: '.oh-money', note: '이번 달 들어온 정산금과 보류된 결제 건수를 보여 줍니다.' },
      {
        sel: '.oh-todos[aria-label="지금 확인할 것"]',
        note: '시작 잔액 설정이나 잔액 부족 등 지금 확인할 사항을 안내합니다.',
      },
    ],
    path: '/owner',
    core: true,
    headline: '사장님은 가게의 결제와 돈을 한 화면에서 봅니다',
    detail: '가게 상황을 한눈에 보고, 필요한 화면으로 바로 들어갑니다.',
  },
  {
    id: 'owner-integration',
    spots: [
      {
        sel: '.owner-integration-next',
        note: '키는 쇼핑몰 서버에만 보관합니다. 연결 후 원문은 다시 보이지 않고, 화면을 벗어나면 표시된 키도 지웁니다.',
      },
      { sel: '.owner-integration-history', note: '키를 언제 발급하고 폐기했는지 기록이 남습니다.' },
    ],
    path: '/owner/integration',
    core: true,
    headline: '시크릿 키로 서명한 쇼핑몰의 결제 요청만 받습니다',
    detail: '연동이 끝나면 키 원문은 사장님에게도 다시 보이지 않습니다.',
  },
  {
    id: 'owner-settlements',
    spots: [
      {
        sel: '.stl-settlement-policy',
        note: '매입 완료일부터 주말 · 공휴일을 뺀 3영업일 뒤 정산합니다.',
      },
      {
        sel: '.stl-paid-hero',
        note: '조회 기간의 지급 완료 기록입니다. 은행 이체 내역을 조회하는 화면은 아닙니다.',
      },
      { sel: '.stl-upcoming', note: '다가오는 정산금과 날짜를 따로 보여 줍니다.' },
      {
        sel: '.stl-payment-equation',
        note: '지급할 정산금에서 이미 정산된 환불 주문의 금액을 차감합니다.',
      },
      {
        sel: '.payout-ledger',
        prepare: ['settlement-list', 'payout-kind'],
        note: '실제로 지급 처리된 날짜와 금액을 확인합니다. 위의 정산 예정액과 구분합니다.',
      },
    ],
    path: '/owner/settlements',
    core: true,
    headline: '정산금이 언제, 얼마 들어오는지 날짜별로 봅니다',
    detail: '수수료를 뺀 정산 예정액과 지급 기록을 구분해서 보여 줍니다.',
  },
  {
    id: 'owner-funds',
    spots: [
      {
        sel: '[aria-label="자금 현황 빠른 요약"]',
        note: '자금이 충분한지, 모자란 날이 있는지 한 문장으로 알려 줍니다.',
      },
      {
        sel: '.funds-trend',
        prepare: ['shortage-date'],
        note: '날짜별 예상 잔액과 정산금·지출 흐름을 보여 줍니다. 초록은 정산금, 주황은 지출입니다.',
      },
    ],
    path: '/owner/funds',
    core: true,
    headline: '지출을 등록하면 잔액이 부족할 날을 미리 알려 드립니다',
    detail: '정산으로 들어올 돈과 나갈 돈을 날짜별로 더하고 뺍니다.',
  },
  {
    id: 'owner-loans',
    spots: [
      { sel: '.loan-filters', note: '지역과 지원 대상으로 상품을 조회합니다.' },
      {
        sel: '.loan-product-card',
        note: '상품마다 지역과 지원 대상을 확인합니다. 다음은 상품 한 개의 상세 조건입니다.',
      },
      {
        sel: '.booth-loan-modal .ant-modal-content',
        prepare: ['loan-detail'],
        note: '상품 세부 정보를 확인하고 취급 기관 홈페이지로 이동할 수 있습니다.',
      },
    ],
    path: '/owner/loans',
    core: true,
    headline: '부족한 자금을 준비할 수 있도록 운영자금 상품으로 이어집니다',
    detail: '서민금융진흥원 자료입니다. 지역을 고르면 해당 지역과 전국 상품이 함께 나옵니다.',
  },
  {
    id: 'owner-actions',
    spots: [
      { sel: '.mcl-hero', note: '확인이 필요했던 결제가 몇 건이고 어떻게 끝났는지 요약합니다.' },
      {
        sel: '.mcl-table',
        note: '사건마다 진행 상태와 결과가 한 줄로 보입니다.',
      },
    ],
    path: '/owner/actions',
    core: true,
    headline: '의심 결제는 돈워리 직원이 대신 조사하고, 사장님은 결과만 봅니다',
    detail: '사건이 어디까지 진행됐고 어떻게 끝났는지 확인합니다.',
  },
  {
    id: 'owner-closing',
    core: true,
    headline: '사장님은 가게 운영에 집중하고, 의심 결제는 직원이 조사합니다',
    detail: boothHandoff.owner,
  },
]

function securitySteps(
  caseId: string | null,
  reportCaseId?: string | null,
  evaluationId?: string | null,
): BoothStep[] {
  const casePath = caseId ? `/security/cases/${caseId}` : undefined
  const reportPath = reportCaseId ? `/security/cases/${reportCaseId}` : casePath
  const separateReport = !!reportCaseId && reportCaseId !== caseId
  return [
    {
      id: 'security-home',
      spots: [
        { sel: '.security-home-hero', note: '내가 맡은 사건 수와 지금 확인이 필요한 항목입니다.' },
        {
          sel: '[aria-label="담당 사건 업무 흐름"]',
          note: '탐지 → 조사 → 판정 → 조치 순서로 사건이 어디에 있는지 봅니다.',
        },
        { sel: '.security-home-queue', note: '먼저 볼 사건 순서대로 줄을 세웁니다.' },
      ],
      path: '/security/home',
      core: true,
      headline: '사장님 대신 의심 결제를 조사하는 돈워리 보안 직원의 화면입니다',
      detail: '내가 맡은 사건의 상태와 지금 확인할 일을 보여 줍니다.',
    },
    {
      id: 'security-transactions',
      spots: [
        { sel: '.dw-table-toolbar', note: '기간 · 가맹점 · 결제 상태로 거릅니다.' },
        { sel: '.dw-tx-table', note: '모든 결제가 판정 결과와 함께 최신순으로 보입니다.' },
      ],
      path: '/security/transactions',
      headline: '사건에 걸리지 않은 결제까지 모든 결제를 봅니다',
      detail: '상세는 내가 맡은 사건 고객의 결제만 열 수 있습니다.',
    },
    {
      id: 'security-rules',
      spots: [
        {
          sel: '.rule-guide-payment-flow',
          note: '켜진 룰의 조건을 확인하고, 적중한 룰의 점수를 더합니다.',
        },
        { sel: '.rule-guide-bands', note: '0~49점 통과 · 50~79점 보류 후 조사 · 80점 이상 거절.' },
        {
          sel: '.rule-guide-payment .rule-guide-footer',
          note: '발표 시연은 고액 30점 + 신규 카드 25점 = 55점, 매입 보류입니다.',
        },
        {
          sel: '.dw-rule-card-payment',
          note: '결제 탐지 룰을 하나씩 카드로 보여 줍니다. 다음은 이 룰을 펼친 상세입니다.',
        },
        {
          sel: '.dw-rule-card-payment .dw-rule-card-detail',
          note: '룰을 펼치면 탐지 기준과 점수, 구체적인 예시와 최근 7일 적중 빈도를 확인할 수 있습니다.',
        },
      ],
      path: '/security/rules',
      core: true,
      headline: '켜진 탐지 룰의 점수를 더해 결제를 세 갈래로 나눕니다',
      detail: '0~49점 통과 · 50~79점 보류 후 조사 · 80점 이상 거절. 합산은 최대 100점입니다.',
      action: '위쪽 흐름 그림과 룰 카드 한 장을 짚어 주세요',
    },
    {
      id: 'security-alerts',
      path: '/security/alerts',
      core: true,
      headline: '보류된 결제는 경보로 남습니다',
      detail: '결제마다 점수와 판정 결과가 기록됩니다.',
      spots: [
        {
          sel: '.dw-alert-card',
          note: '보류되거나 채점에 실패한 결제를 모아 봅니다. 다음은 준비한 55점 결제의 근거입니다.',
        },
      ],
    },
    {
      id: 'security-alert-detail',
      spots: [
        {
          sel: '.dw-alert-verdict-engine',
          note: '이 결제가 몇 점을 받아 어떤 판정이 났는지 보여 줍니다.',
          fallback: {
            sel: '.dw-alert-card',
            note: '준비한 경보 한 건을 열면 점수와 적중 규칙이 나옵니다.',
          },
        },
        {
          sel: '.dw-alert-signals',
          note: '점수만 보여 주지 않고, 어떤 규칙으로 더해졌는지도 남깁니다.',
          fallback: { sel: '.dw-alert-card', note: '경보를 열어 점수를 만든 규칙을 짚어 주세요.' },
        },
      ],
      path: evaluationId ? `/security/alerts/${evaluationId}` : '/security/alerts',
      core: !!evaluationId,
      headline: '룰에 걸린 결제는 어떤 룰로 몇 점이 나왔는지 기록에 남습니다',
      detail: '50점을 넘었거나 채점에 실패한 결제를 먼저 보여 줍니다.',
    },
    {
      id: 'security-blocklist',
      path: '/security/blocklist',
      headline: '차단 목록과 일치하는 결제는 바로 거절합니다',
      detail: '카드 · 기기 · IP · 이메일 · 구매자 식별값을 등록합니다 (R07).',
      action: '등록하지 않습니다',
    },
    {
      id: 'security-ml',
      path: '/security/ml-model',
      headline: 'ML 점수는 참고용입니다. 결제 판정에는 쓰지 않습니다',
      detail: '판정은 탐지 룰만 합니다. 이 화면의 숫자는 합성 데이터 실험 결과입니다.',
    },
    {
      id: 'security-cases',
      spots: [
        { sel: '.case-queue-quick', note: '자주 쓰는 조건으로 사건을 빠르게 거릅니다.' },
        {
          sel: '.case-queue-card',
          note: '보류된 결제를 사건으로 묶고 담당자를 정합니다. 다음은 새로 준비한 사건입니다.',
        },
      ],
      path: '/security/cases',
      core: true,
      headline: '보류된 결제는 사건으로 묶여 담당 직원에게 갑니다',
      detail: '진행 중인 사건은 담당 직원만 열 수 있고, 열람은 기록에 남습니다.',
    },
    {
      id: 'security-masking',
      spots: [
        {
          sel: '.dw-case-summary',
          note: '사건 상태와 조사 기한, 담당 직원과 처리 진행 상황을 확인합니다.',
        },
        {
          sel: '.ant-card',
          text: '주문자 정보',
          prepare: ['contact-read'],
          note: '담당 직원에게도 이름과 전화번호를 가립니다. 지금 가린 정보를 조회한 사실도 기록됩니다.',
          fallback: {
            sel: '.dw-case-facts',
            note: '주문자 정보는 담당 직원의 열린 사건에서만 볼 수 있습니다. 준비한 사건을 확인해 주세요.',
          },
        },
        {
          sel: '.ant-modal-content',
          text: '주문자 전체 전화번호',
          prepare: ['contact-read', 'contact-form'],
          note: '번호 전체를 보려면 이의제기 번호 · 연락 시도 · 열람 사유를 남겨야 합니다.',
          fallback: {
            sel: '.ant-card',
            text: '주문자 정보',
            note: '일치 확인은 같은지만 알려 줍니다. 전체 보기는 사유가 필요하고 관리자 검토로 이어집니다.',
          },
        },
        {
          sel: '.ant-card',
          text: '결제별 판정',
          note: '판정은 결제 한 건씩, 직원이 사유를 적어 내립니다.',
        },
      ],
      path: casePath,
      under: '/security/cases/',
      core: true,
      headline: '고객 전화번호는 직원에게도 가려서 보여 줍니다',
      detail:
        '일치 확인은 맞는지만 알려 주고, 전체 보기는 근거를 적어야 하며 관리자가 나중에 검토합니다.',
    },
    {
      id: 'security-report',
      spots: [
        {
          sel: '.dw-report-scroll > section:first-of-type',
          note: separateReport
            ? '이번에는 별도 사건의 보고서 예시입니다. 생성 방식은 화면 표시를 따릅니다.'
            : '로컬 mock으로 미리 만든 보고서입니다. 같은 사건의 실제 결제·규칙·주문 자료를 묶어 봅니다.',
          fallback: {
            sel: '.ant-card',
            text: 'AI 조사 보고서',
            note: '준비한 성공 보고서의 [전문 보기]를 열어 주세요.',
          },
        },
        { sel: '.dw-report-timeline', note: '무슨 일이 있었는지 시간순으로 정리합니다.' },
        {
          sel: '#report-similar',
          note: '과거 사건은 어떻게 판정했는지 나란히 비교합니다.',
          fallback: {
            sel: '.dw-report-scroll > section:first-of-type',
            note: '유사 사건 비교는 조회 결과가 있는 보고서에서 보여 줍니다.',
          },
        },
        {
          sel: '#report-history',
          note: '요약에서 실제 결제 이력과 근거 자료로 내려가 확인할 수 있습니다.',
          fallback: {
            sel: '.dw-report-scroll > section:first-of-type',
            note: '근거 자료가 포함된 보고서를 준비해 주세요. 없는 기록을 만들어 보여 주지 않습니다.',
          },
        },
        {
          sel: '#report-questions',
          note: '보고서를 읽다가 궁금하면, 확인된 자료를 바탕으로 추가 질문할 수 있습니다.',
          fallback: {
            sel: '.dw-report-scroll > section:first-of-type',
            note: '질문 기능은 지원되는 보고서에서 사용합니다. 이번에는 근거와 확인할 것을 짚습니다.',
          },
        },
      ],
      path: reportPath ? `${reportPath}/report` : undefined,
      under: '/security/cases/',
      core: true,
      headline: 'AI는 근거를 정리할 뿐입니다. 판정은 직원이 합니다',
      detail: '무슨 일인가 → 확인할 것 → 지난 사건과 비교 → 근거 자료 순서로 읽습니다.',
      action: '부스에서는 저장된 mock 보고서를 조회합니다. 추가 AI 호출은 없습니다.',
    },
    {
      id: 'security-compare',
      under: '/security/cases/',
      headline: '과거에 판정한 비슷한 사건과 나란히 놓고 비교합니다',
      detail: '왼쪽은 판정이 끝난 과거 사건, 오른쪽은 지금 판정할 사건입니다.',
      action: '보고서의 지난 사건 비교에서 비슷한 사건을 여세요',
    },
    {
      id: 'security-ai-switch',
      spots: [
        { sel: '.ai-switch-head', note: '지금 AI 조사가 켜져 있는지 꺼져 있는지 보여 줍니다.' },
        {
          sel: '.ai-switch-facts',
          note: '꺼도 보고서는 정해진 문장으로 계속 나오고, 판정은 직원이 합니다.',
        },
        {
          sel: '.ai-switch-facts',
          note: '보안 직원은 정지할 수 있고, 다시 켜기는 관리자만 합니다.',
        },
      ],
      path: '/security/ai-switch',
      core: true,
      headline: 'AI 요약이 이상하면 직원이 바로 정지합니다',
      detail: '정지해도 보고서는 정해진 문장으로 계속 나옵니다. 다시 켜기는 관리자만 합니다.',
      action: '상태와 권한만 짚고 정지는 누르지 않습니다. 다음을 누르면 관리자 화면으로 이어집니다',
    },
    {
      id: 'security-actions',
      path: '/security/actions',
      headline: '판정 뒤 결제마다 매입 · 취소가 어떻게 끝났는지 남습니다',
      detail: '한 사건에 결제가 여러 건이면 결제마다 한 줄씩 기록합니다.',
    },
  ]
}

const adminSteps: BoothStep[] = [
  {
    id: 'admin-home',
    spots: [
      { sel: '.ah-hero', note: '지금 관리자가 처리할 일이 몇 건인지 보여 줍니다.' },
      { sel: '.ah-charts', note: '최근 며칠의 추이와 어디서 일이 들어오는지 봅니다.' },
    ],
    path: '/admin/home',
    core: true,
    headline: '돈워리 관리자는 가맹점과 직원을 관리합니다',
    detail: '사장님이 맡긴 정보를 직원이 제대로 다루는지 살피는 자리입니다.',
  },
  {
    id: 'admin-applications',
    spots: [
      {
        sel: '.ar-process',
        note: '신청 → 이메일 확인 → 심사 → 계정 발급 중 어디인지 보여 줍니다.',
      },
      { sel: '.ar-status-tabs', note: '검토 대기 · 승인 · 반려로 나눠 봅니다.' },
      { sel: '.ar-list', note: '신청 하나를 열어 가게 정보를 확인합니다.' },
    ],
    path: '/admin/applications',
    core: true,
    headline: '사장님의 이용 신청을 여기서 심사합니다',
    detail: '승인하면 가맹점과 사장님 계정이 만들어집니다.',
  },
  {
    id: 'admin-case-assignments',
    path: '/admin/case-assignments',
    core: true,
    headline: '새 사건은 맡은 사건이 적은 보안 직원에게 자동으로 배정합니다',
    detail: '직원별 담당 건수와 다음 배정 차례를 보고, 필요하면 관리자가 담당을 바꿉니다.',
    spots: [
      {
        sel: '.ca-office',
        note: '새 사건은 담당 건수가 적은 직원에게 자동 배정됩니다. 책상 위 숫자는 미종결 사건 수입니다.',
        fallback: {
          sel: '.ca-page .dw-page-description',
          note: '직원별 담당 현황을 불러오면 배정 상태가 나옵니다.',
        },
      },
      {
        sel: '.ca-cases',
        prepare: ['assigned-staff'],
        note: '책상을 누르면 맡은 사건이 나옵니다. 관리자는 고객 정보 대신 배정에 필요한 정보만 봅니다.',
        fallback: {
          sel: '.ca-office',
          note: '사건이 있는 직원 책상을 하나 눌러 담당 목록을 보여 주세요.',
        },
      },
    ],
  },
  {
    id: 'admin-merchants',
    path: '/admin/merchants',
    headline: '가맹점을 등록하고 사용 상태를 관리합니다',
    detail: '가맹점을 만든 뒤 사장님 계정을 연결합니다.',
  },
  {
    id: 'admin-raw-view',
    spots: [
      { sel: '.dw-page-description', note: '전화번호는 이 화면에도 보이지 않습니다.' },
      { sel: '.ant-table', note: '누가, 어느 사건 결제를, 무슨 근거로 봤는지만 남습니다.' },
    ],
    path: '/admin/raw-view-reviews',
    core: true,
    headline: '직원이 고객 전화번호 전체를 보면 기록이 남고, 관리자가 검토합니다',
    detail: '관리자에게도 번호는 보이지 않습니다. 누가, 어느 결제를, 무슨 근거로 봤는지만 봅니다.',
    action: '[적절] · [소명 요청]은 누르지 않습니다',
  },
  {
    id: 'admin-rule-requests',
    spots: [
      {
        sel: '.dw-rule-records',
        note: '직원이 낸 룰 끄기 요청과 사유입니다. 관리자가 승인해야 꺼집니다.',
      },
    ],
    path: '/admin/fds/rule-change-requests',
    core: true,
    headline: '탐지 룰은 직원 혼자 끌 수 없습니다',
    detail: '끄려면 사유를 적어 요청하고, 관리자가 승인해야 꺼집니다.',
  },
  {
    id: 'admin-operator-access',
    path: '/admin/fds/operator-access-detections',
    core: true,
    headline: '사장님 계정이 권한 밖 기능을 반복해서 시도하면 자동으로 정지합니다',
    detail: '무엇을 몇 번 시도했는지 시간순으로 남습니다.',
    spots: [
      {
        sel: '.oad-stats',
        note: '권한 밖 요청은 매번 거절하고, 반복되면 사장님 계정을 정지합니다.',
      },
      {
        sel: '.oad-list',
        note: '누가 어떤 기능을 몇 번 요청했는지, 실제 정지 결과와 함께 남깁니다.',
      },
    ],
  },
  {
    id: 'admin-login-security',
    path: '/admin/login-security',
    core: true,
    headline: '로그인 공격 중 처음 보는 브라우저로 들어온 직원 계정은 바로 정지합니다',
    detail: '로그인 자체는 막지 않고, 시도 수와 실패율로 공격 중인지 판단합니다.',
    spots: [
      {
        sel: '.ls-chart',
        note: '로그인 시도와 실패가 함께 급증하는지 봅니다. 평소에는 정상 상태입니다.',
      },
      {
        sel: '.ls-progress',
        note: '공격 중, 처음 보는 브라우저로 성공한 직원 로그인을 탈취 의심으로 잡아 정지합니다.',
      },
    ],
    action: '현재 상태를 설명합니다. 실제 공격과 계정 정지는 발표에서 보여 줍니다',
  },
  {
    id: 'admin-insider',
    path: '/admin/insider-monitor',
    core: true,
    headline: '직원의 고객 정보 접근을 관리자가 지켜봅니다',
    detail: '규칙에 걸리면 경보가 되고, 높음 이상은 직원별 사건으로 묶입니다.',
    spots: [
      {
        sel: '.im-office',
        note: '직원별 상태를 모니터 색으로 보여 줍니다. 주의 · 높음 · 반출 차단을 구분합니다.',
      },
      {
        sel: '.im-log',
        prepare: ['monitored-staff'],
        note: '맡지 않은 고객 조회가 늘어나면 경보가 올라가고, CSV 반출 시도는 차단합니다.',
        fallback: {
          sel: '.im-office',
          note: '기록이 있는 직원을 고르면 어떤 고객을 언제 조회했는지 나옵니다.',
        },
      },
    ],
    action: '준비한 경보 직원의 기록만 봅니다. 다운로드 · 계정 상태 변경은 하지 않습니다',
  },
  {
    id: 'admin-closing',
    core: true,
    headline: '결제는 조사하고, 돈은 예측하고, 정보를 다루는 직원도 지켜봅니다',
    detail: boothHandoff.admin,
  },
  {
    id: 'admin-users',
    path: '/admin/users',
    headline: '계정을 만들면 임시 비밀번호가 발급됩니다',
    detail: '첫 로그인에서 반드시 바꿔야 하고, 관리자 계정은 화면에서 만들 수 없습니다.',
  },
  {
    id: 'admin-ai-switch',
    spots: [
      { sel: '.ai-switch-head', note: '지금 AI 조사가 켜져 있는지 꺼져 있는지 보여 줍니다.' },
      { sel: '.dw-table-card', note: '누가 언제 왜 껐고 켰는지 기록이 남습니다.' },
    ],
    path: '/admin/ai-switch',
    headline: 'AI 조사를 멈추고 다시 켜는 기록이 모두 남습니다',
    detail: '직원은 정지만 할 수 있고, 다시 켜는 것은 관리자만 합니다.',
    action: '누르지 않습니다',
  },
]

function trackSteps(
  track: BoothTrack,
  caseId: string | null,
  reportCaseId?: string | null,
  evaluationId?: string | null,
) {
  if (track === 'public') return publicSteps
  if (track === 'owner') return ownerSteps
  return track === 'security' ? securitySteps(caseId, reportCaseId, evaluationId) : adminSteps
}

/** 역할 · 보기 방식에 맞는 단계 목록. canOpen이 false인 주소는 뺀다 (권한 없는 화면에 들어가면 403 기록이 쌓인다). */
export function boothSteps(
  track: BoothTrack,
  options: {
    all: boolean
    caseId: string | null
    reportCaseId?: string | null
    evaluationId?: string | null
    canOpen: (path: string) => boolean
  },
): BoothStep[] {
  return trackSteps(track, options.caseId, options.reportCaseId, options.evaluationId)
    .filter((step) => options.all || step.core)
    .filter((step) => !step.path || options.canOpen(step.path))
}

/**
 * 이어 보기 — 소개 → 사장님 → 보안 직원 → 관리자를 한 줄로 잇는다.
 * tracks는 계정이 준비된 역할만 넘긴다. 사장님 창 전환 안내는 이어 보기에서는 생략한다.
 */
export function boothFlow(
  tracks: BoothTrack[],
  options: {
    all: boolean
    caseId: string | null
    reportCaseId?: string | null
    evaluationId?: string | null
  },
): BoothStep[] {
  return tracks.flatMap((track) =>
    trackSteps(track, options.caseId, options.reportCaseId, options.evaluationId)
      .filter((step) => options.all || step.core)
      .filter((step) => step.id !== 'owner-closing' || track === tracks.at(-1))
      .map((step) => ({ ...step, track })),
  )
}

export type BoothPosition = { stepId: string; at: string; spot: number } | null

function fits(step: BoothStep, pathname: string) {
  if (step.path) return step.path === pathname
  if (step.under) return pathname.startsWith(step.under)
  // 경로가 없는 마무리는 선택한 화면에서만 유지한다(position.at으로 확인).
  return false
}

/**
 * 지금 보여 줄 단계. 진행자가 고른 단계는 그 화면에 있는 동안 유지하고,
 * 메뉴를 눌러 다른 화면으로 가면 그 화면에 맞는 단계로 자막이 따라간다.
 */
export function resolveBoothStep(
  steps: BoothStep[],
  position: BoothPosition,
  pathname: string,
): BoothStep | null {
  if (steps.length === 0) return null
  const chosen = position ? steps.find((step) => step.id === position.stepId) : undefined
  if (chosen && (position?.at === pathname || fits(chosen, pathname))) return chosen
  const exact = steps.find((step) => step.path === pathname)
  if (exact) return exact
  const within = steps.find((step) => !step.path && step.under && pathname.startsWith(step.under))
  if (within) return within
  const parent = steps
    .filter((step) => step.path && pathname.startsWith(step.path + '/'))
    .sort((a, b) => (b.path?.length ?? 0) - (a.path?.length ?? 0))[0]
  return parent ?? steps[0]
}
