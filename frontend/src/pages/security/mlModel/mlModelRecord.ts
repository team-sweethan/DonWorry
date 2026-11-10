// ML 모델 화면(#778)의 실험 기록 — 모두 합성 데이터 실험의 실제 값이다.
// 출처는 개인 실험 저장소 fds-ml의 보고서 · 저장된 모델 · ADR-0061. 화면 코드는 이 값을 그리기만 한다.
// 비율(%)은 손으로 적지 않고 건수에서 계산한다 (숫자끼리 어긋나지 않게).

export const mlModelTabs = [
  { key: 'overview', label: '한눈에' },
  { key: 'data', label: '① 데이터 만들기' },
  { key: 'split', label: '② 나누기' },
  { key: 'training', label: '③ 학습' },
  { key: 'compare', label: '④ 모델 비교' },
  { key: 'versions', label: '⑤ 버전 이야기' },
  { key: 'limits', label: '⑥ 한계 · 외부 검증' },
] as const

export type MlModelTab = (typeof mlModelTabs)[number]['key']

/** 주소의 ?tab= 값을 탭으로 — 모르는 값 · 빈 값이면 "한눈에" */
export function mlModelTab(param: string | null): MlModelTab {
  return mlModelTabs.find((tab) => tab.key === param)?.key ?? 'overview'
}

export function percent(part: number, whole: number, digits = 0): string {
  if (whole === 0) return '—'
  return `${((part / whole) * 100).toFixed(digits)}%`
}

export const count = (value: number) => value.toLocaleString('ko-KR')

/** 고민 · 결정 상자 한 줄 — 굵은 머리말 + 설명 */
export type Thought = { head: string; body: string }

// ---- 한눈에 (rf_v1_vs_v2.md Test 줄 · scorecard.md 기준 ① · tabformer_scorecard.md) ----

export const currentModel = {
  version: 'rf-v2',
  since: '11/02',
  algorithm: 'Random Forest',
  trees: 200,
  featureCount: 12,
  thresholdPercent: 30,
}

export const timeline = [
  {
    when: '11/02',
    title: '데이터 · 5개 모델',
    body: '합성 2만 건으로 RF · XGBoost · Transformer · 하이브리드 실험',
  },
  { when: '11/02', title: 'rf-v1 연결', body: '그림자 채점으로 결제 옆에 기록 (ADR-0061)' },
  { when: '11/02', title: 'rf-v2 교체', body: '가맹점 번호를 빼도 성능 유지 → 교체 (#450)' },
  { when: '11/02', title: '외부 검증', body: 'IBM TabFormer에서 무작위 수준 확인' },
  { when: '11/03', title: 'rf-v3 실험', body: '국가 칸 + 20만 건 → 기준 미달로 탈락' },
  { when: '11/09', title: 'v4 준비', body: '표본 부족으로 재학습 보류' },
]

// ---- ① 데이터 만들기 (README 7 · 8절 · profiles.py · data/*.csv 11/10 집계) ----

export const baseDataset = {
  period: '2026-04-01 ~ 08-31 (5개월)',
  merchants: '3곳 (가격 배율 1.0 · 0.7 · 1.4)',
  customers: 1500,
  activeCustomers: 1490,
  normal: 18000,
  fraud: 2000,
  total: 20000,
  attackEpisodes: 936,
  normalAmount: { median: 61100, top10: 181300, top1: 399900 },
  seed: 42,
}

export const customerSegments = [
  { name: '가볍게 (light)', share: 55, amount: '1.5만 ~ 4만 원', monthly: '0.5 ~ 2회' },
  { name: '보통 (regular)', share: 35, amount: '3만 ~ 10만 원', monthly: '2 ~ 5회' },
  { name: '자주 · 많이 (heavy)', share: 10, amount: '7만 ~ 20만 원', monthly: '5 ~ 10회' },
]

export type ScenarioKey =
  | 'CARD_TESTING'
  | 'ACCOUNT_TAKEOVER'
  | 'STOLEN_CARD'
  | 'LOW_AND_SLOW'
  | 'SPLIT_PAYMENT'
  | 'BEHAVIORAL_ANOMALY'
  | 'CARD_DEVICE_CHANGE'

/** 시나리오별 거래 · 공격 사건 수와 구간 배치 (excluded = 구간 경계에 걸쳐 뺀 거래) */
export const scenarios: {
  key: ScenarioKey
  label: string
  how: string
  transactions: number
  episodes: number
  split: { train: number; validation: number; test: number; excluded: number }
}[] = [
  {
    key: 'CARD_TESTING',
    label: '카드 테스팅',
    how: '짧은 시간에 여러 카드로 소액 결제',
    transactions: 286,
    episodes: 57,
    split: { train: 180, validation: 70, test: 36, excluded: 0 },
  },
  {
    key: 'ACCOUNT_TAKEOVER',
    label: '계정 탈취',
    how: '기존 손님의 기기 · 카드 · 금액이 바뀜',
    transactions: 286,
    episodes: 143,
    split: { train: 168, validation: 66, test: 50, excluded: 2 },
  },
  {
    key: 'STOLEN_CARD',
    label: '카드 도용',
    how: '다른 손님이 쓰던 카드를 사용',
    transactions: 286,
    episodes: 143,
    split: { train: 166, validation: 62, test: 58, excluded: 0 },
  },
  {
    key: 'LOW_AND_SLOW',
    label: '저속 반복',
    how: '일정한 간격으로 천천히 반복 결제',
    transactions: 286,
    episodes: 71,
    split: { train: 176, validation: 73, test: 37, excluded: 0 },
  },
  {
    key: 'SPLIT_PAYMENT',
    label: '분할 결제',
    how: '목표 금액을 여러 번으로 쪼갬',
    transactions: 286,
    episodes: 95,
    split: { train: 166, validation: 60, test: 60, excluded: 0 },
  },
  {
    key: 'BEHAVIORAL_ANOMALY',
    label: '행동 이상',
    how: '금액 · 시간대가 평소와 미묘하게 다름',
    transactions: 285,
    episodes: 285,
    split: { train: 178, validation: 54, test: 53, excluded: 0 },
  },
  {
    key: 'CARD_DEVICE_CHANGE',
    label: '카드 · 기기 변경',
    how: '평소와 다른 카드 · 기기 조합',
    transactions: 285,
    episodes: 142,
    split: { train: 164, validation: 58, test: 61, excluded: 2 },
  },
]

export const realisticDataset = {
  total: 200000,
  customers: 10000,
  normal: 199000,
  fraud: 1000,
  fraudPerScenario: '142~143건',
  countries: 'KR 195,727 · VN 876 · US 842 · FR 811 · JP 787 · TH 731 · 고위험 NG 226',
}

export const realisticConditions = [
  { label: '보통 정상', count: 187287 },
  { label: '정상인데 이상해 보임 — 새 손님 첫 결제 고액', count: 2653 },
  { label: '정상인데 이상해 보임 — 몇 분 사이 연달아', count: 2655 },
  { label: '정상인데 이상해 보임 — 카드 · 기기 한꺼번에 바꿈', count: 2653 },
  { label: '해외인데 정상 — 해외 거주', count: 1364 },
  { label: '해외인데 정상 — 해외 여행 중', count: 2388 },
  { label: '사기 — 평소 국가(KR)로 위장', count: 487 },
  { label: '사기 — 해외 접속', count: 287 },
  { label: '사기 — 고위험 국가 접속', count: 226 },
]

export const dataThoughts: Thought[] = [
  {
    head: '사기 10%는 현실보다 훨씬 많습니다.',
    body: '외부 공개 데이터 TabFormer의 사기는 0.12%입니다. 모델이 사기 모양을 충분히 보게 하려는 실험용 가정이었고, 그래서 v3에서는 0.5%로 낮춰 다시 봤습니다.',
  },
  {
    head: '시나리오를 우리 입력 칸이 잡는 신호로 만들었습니다.',
    body: '예: 카드 도용 = "다른 손님이 쓴 카드" 칸이 켜짐. 성적이 좋게 나올 수밖에 없는 구조라는 걸 외부 데이터에서 확인했습니다(⑥).',
  },
  {
    head: '헷갈리는 정상을 넣은 이유',
    body: '"이상해 보이면 사기"로 외우지 않게 하려고요. 상황 정답은 거래 파일과 따로 저장해 학습에 섞이지 않게 했습니다.',
  },
  {
    head: '개인정보',
    body: '실제 카드번호 · IP · 이메일은 쓰지 않았습니다. 카드 · 기기는 SIM-CARD-… 같은 가상 ID, 이메일은 example.invalid를 썼습니다.',
  },
]

// ---- ② 나누기 (common/rf_training.py split_and_purge · 11/10 집계, 저장된 모델의 경계 시각과 같음) ----

export type SplitRow = { key: string; label: string; normal: number; fraud: number; use: string }

export const baseSplits: SplitRow[] = [
  { key: 'train', label: '학습 Train', normal: 12800, fraud: 1198, use: '나무를 키움' },
  {
    key: 'validation',
    label: '검증 Validation',
    normal: 2555,
    fraud: 443,
    use: '후보 4개 중 고르기 · 기준값 고르기 · 모델 비교',
  },
  { key: 'test', label: '시험 Test', normal: 2645, fraud: 355, use: '고른 모델로 딱 한 번 채점' },
  { key: 'excluded', label: '제외', normal: 0, fraud: 4, use: '구간 경계에 걸친 공격 사건 2개' },
]

export const baseSplitPeriods = { train: '4/1 ~ 7/19', test: '~ 8/31' }

export const realisticSplits: SplitRow[] = [
  { key: 'train', label: '학습 Train', normal: 139404, fraud: 596, use: '' },
  { key: 'validation', label: '검증 Validation', normal: 29803, fraud: 197, use: '' },
  { key: 'test', label: '시험 Test', normal: 29793, fraud: 207, use: '' },
]

export const splitThoughts: Thought[] = [
  {
    head: '무작위로 섞지 않고 시간순으로 나눴습니다.',
    body: '실제로는 과거로 배우고 미래 결제를 맞혀야 합니다. 섞으면 "미래를 보고 과거를 맞히는" 시험이 되어 성적이 부풀려집니다.',
  },
  {
    head: '경계에 걸친 공격은 통째로 뺐습니다.',
    body: '같은 공격의 앞부분이 학습에, 뒷부분이 시험에 들어가면 답을 미리 본 셈이기 때문입니다.',
  },
  {
    head: 'Test는 한 번만 썼습니다.',
    body: '모델 · 기준값 고르기는 전부 Validation에서 끝내고, Test는 고른 rf 모델만 마지막에 채점했습니다. Test를 보고 다시 고르면 시험이 아니게 됩니다.',
  },
  {
    head: '정답이 새지 않게',
    body: '사기 여부 · 시나리오 이름 · 사건 번호 · 이번 결제의 승인 결과는 입력에 넣지 않고, 모든 입력 칸은 "이번 결제보다 앞선 결제"만 보고 계산합니다.',
  },
]

// ---- ③ 학습 (rf_v2/models/random_forest_v2.joblib 칸 · 중요도 · common/rf_training.py · rf_v1_vs_v2.md Test) ----

/** rf-v2가 판단에 쓴 비중 (나무들의 불순도 감소 비중, 합 100%) */
export const featureImportance = [
  { label: '직전 결제와 간격', share: 22.4 },
  { label: '30분 안 결제 수', share: 10.7 },
  { label: '평소 대비 금액 배수', share: 9.3 },
  { label: '결제 금액', share: 8.2 },
  { label: '24시간 안 결제 수', share: 7.7 },
  { label: '손님 평균 금액', share: 7.6 },
  { label: '본 적 있는 카드', share: 7.5 },
  { label: '손님 지난 결제 수', share: 7.3 },
  { label: '본 적 있는 기기', share: 5.9 },
  { label: '다른 손님이 쓴 카드', share: 5.1 },
  { label: '결제 시각(시)', share: 4.3 },
  { label: '5분 안 결제 수', share: 4.1 },
]

export const trainingSteps = [
  {
    title: '1. 후보 4개를 Train으로 학습',
    body: '칸 구성 2가지(전체 / "다른 손님이 쓴 카드" 뺌) × 사기 가중치 2가지(그대로 / 사기에 무게)',
  },
  {
    title: '2. Validation으로 고르기',
    body: '후보마다 기준값을 5% ~ 95%(5% 간격)로 바꿔 F1이 가장 높은 값을 찾고, F1이 가장 높은 후보를 고름',
  },
  { title: '3. 고른 것', body: '전체 칸 · 가중치 그대로 · 기준값 30%' },
]

export const testResult = {
  f1: 0.7926,
  caught: 258,
  fraud: 355,
  wrongNormal: 38,
  normal: 2645,
  ap: 0.853,
}

export const trainingFacts = [
  { label: '모델', value: 'Random Forest · 나무 200그루 · 잎 최소 2건 · seed 42' },
  {
    label: '서버 연결',
    value: '학습과 같은 칸 계산 코드를 AI 서버로 옮김 · 정답 30건 확률 차이 0',
  },
  { label: '다시 만들기', value: '다른 PC(Linux)에서 데이터 해시 일치 · 확률 차이 최대 2.2e-16' },
]

export const trainingThoughts: Thought[] = [
  {
    head: '가게 번호는 입력에서 뺐습니다(v2).',
    body: '합성 가게 3곳만 보고 배운 모델은 실제 가게 번호가 들어오면 확률이 흔들렸습니다(⑤).',
  },
  {
    head: '카드번호 · 기기 ID 자체는 넣지 않았습니다.',
    body: 'ID를 외우게 되므로, "본 적 있는가 · 다른 손님이 썼는가"처럼 행동으로 바꾼 칸만 넣었습니다.',
  },
  {
    head: '기준값 30%는 사기 10% 데이터에서 고른 값입니다.',
    body: '사기가 훨씬 드문 실제 결제에서는 맞지 않을 수 있습니다. 그래서 HIGH/LOW는 참고 표시로만 씁니다.',
  },
]

// ---- ④ 모델 비교 (scorecard.md 11/02 · Transformer = 저장된 모델로 11/10 같은 방법 계산, rf-v2 검산 일치) ----

/** 비교 문제지: Validation 2,998건 */
export const compareSet = { normal: 2555, fraud: 443 }

export type ModelKey = 'rule' | 'rfV1' | 'rfV2' | 'xgb' | 'transformer' | 'hybrid' | 'control'

export const modelColors: Record<ModelKey, string> = {
  rule: '#94a3b8',
  rfV1: '#60a5fa',
  rfV2: '#1d4ed8',
  xgb: '#0f9d8a',
  transformer: '#9333ea',
  hybrid: '#c2410c',
  control: '#94a3b8',
}

/** body = 무엇인지, detail = 설정값 — 두 줄로 나눠 보여 준다 */
export const modelCards: { key: ModelKey; title: string; body: string; detail: string }[] = [
  {
    key: 'rule',
    title: '탐지 룰',
    body: '지금 판정하는 룰(R01~R08)을 같은 점수로 옮겨 채점',
    detail: '데이터에 IP · 차단 목록이 없어 R05 · R07은 꺼짐',
  },
  {
    key: 'rfV2',
    title: 'rf-v1 · rf-v2',
    body: 'Random Forest — 결정 나무 여러 그루의 다수결',
    detail: 'v1은 가게 번호 포함 13칸 · v2는 뺀 12칸',
  },
  {
    key: 'xgb',
    title: 'XGBoost',
    body: '나무를 차례로 고쳐 가며 쌓는 방식',
    detail: '200그루 · 깊이 4 · 학습률 0.05',
  },
  {
    key: 'transformer',
    title: 'Transformer',
    body: '손님의 직전 결제 5건을 순서대로 읽는 신경망',
    detail: '크기 32 · 4 heads · 1층 · 최대 20회 학습',
  },
  {
    key: 'hybrid',
    title: '하이브리드',
    body: 'Transformer가 결제 순서를 숫자로 요약 → XGBoost가 판단',
    detail: 'Train 앞 70%로 Transformer · 뒤 30%로 XGBoost',
  },
]

/** 정상 결제를 0 · 5 · 50건까지 잘못 의심할 때 잡은 사기 (Validation 443건 중) */
export const modelScores: {
  key: ModelKey
  label: string
  current?: boolean
  muted?: boolean
  caught: { fp0: number; fp5: number; fp50: number }
  ap: number
}[] = [
  { key: 'rule', label: '탐지 룰', caught: { fp0: 42, fp5: 64, fp50: 67 }, ap: 0.4347 },
  { key: 'rfV1', label: 'rf-v1', caught: { fp0: 40, fp5: 245, fp50: 350 }, ap: 0.8984 },
  {
    key: 'rfV2',
    label: 'rf-v2',
    current: true,
    caught: { fp0: 47, fp5: 229, fp50: 347 },
    ap: 0.9022,
  },
  { key: 'xgb', label: 'XGBoost', caught: { fp0: 81, fp5: 208, fp50: 347 }, ap: 0.907 },
  {
    key: 'transformer',
    label: 'Transformer',
    caught: { fp0: 112, fp5: 198, fp50: 333 },
    ap: 0.894,
  },
  { key: 'hybrid', label: '하이브리드', caught: { fp0: 50, fp5: 118, fp50: 328 }, ap: 0.8894 },
  {
    key: 'control',
    label: '대조군: XGBoost를 하이브리드와 같은 30% 데이터로',
    muted: true,
    caught: { fp0: 129, fp5: 205, fp50: 330 },
    ap: 0.897,
  },
]

/** 룰 점수는 띄엄띄엄이라 50건에 맞출 수 없어, 넘지 않는 가장 가까운 기준을 썼다 */
export const ruleActualWrongAt50 = 12

/** 정상 50건 잘못 의심할 때 시나리오별로 잡은 사기 (건수, 분모는 scenarios[].split.validation) */
export const scenarioCaughtAt50: {
  key: ModelKey
  label: string
  caught: Record<ScenarioKey, number>
}[] = [
  {
    key: 'rule',
    label: '탐지 룰',
    caught: {
      CARD_TESTING: 42,
      ACCOUNT_TAKEOVER: 10,
      STOLEN_CARD: 11,
      LOW_AND_SLOW: 0,
      SPLIT_PAYMENT: 0,
      BEHAVIORAL_ANOMALY: 2,
      CARD_DEVICE_CHANGE: 2,
    },
  },
  {
    key: 'rfV1',
    label: 'rf-v1',
    caught: {
      CARD_TESTING: 65,
      ACCOUNT_TAKEOVER: 53,
      STOLEN_CARD: 62,
      LOW_AND_SLOW: 52,
      SPLIT_PAYMENT: 52,
      BEHAVIORAL_ANOMALY: 19,
      CARD_DEVICE_CHANGE: 47,
    },
  },
  {
    key: 'rfV2',
    label: 'rf-v2',
    caught: {
      CARD_TESTING: 65,
      ACCOUNT_TAKEOVER: 53,
      STOLEN_CARD: 62,
      LOW_AND_SLOW: 49,
      SPLIT_PAYMENT: 53,
      BEHAVIORAL_ANOMALY: 17,
      CARD_DEVICE_CHANGE: 48,
    },
  },
  {
    key: 'xgb',
    label: 'XGBoost',
    caught: {
      CARD_TESTING: 65,
      ACCOUNT_TAKEOVER: 53,
      STOLEN_CARD: 62,
      LOW_AND_SLOW: 47,
      SPLIT_PAYMENT: 52,
      BEHAVIORAL_ANOMALY: 18,
      CARD_DEVICE_CHANGE: 50,
    },
  },
  {
    key: 'transformer',
    label: 'Transformer',
    caught: {
      CARD_TESTING: 62,
      ACCOUNT_TAKEOVER: 56,
      STOLEN_CARD: 61,
      LOW_AND_SLOW: 44,
      SPLIT_PAYMENT: 45,
      BEHAVIORAL_ANOMALY: 17,
      CARD_DEVICE_CHANGE: 48,
    },
  },
  {
    key: 'hybrid',
    label: '하이브리드',
    caught: {
      CARD_TESTING: 65,
      ACCOUNT_TAKEOVER: 57,
      STOLEN_CARD: 60,
      LOW_AND_SLOW: 40,
      SPLIT_PAYMENT: 43,
      BEHAVIORAL_ANOMALY: 17,
      CARD_DEVICE_CHANGE: 46,
    },
  },
]

export const compareThoughts: Thought[] = [
  {
    head: '왜 정확도(accuracy)로 비교하지 않았나',
    body: '정상이 85%라 "전부 정상"이라고만 해도 85점입니다. 또 모델마다 기준값이 달라 그대로 비교할 수 없습니다. 그래서 정상 결제를 잘못 의심하는 수를 똑같이 맞추고 사기를 몇 건 잡는지 셌습니다.',
  },
  {
    head: '순서를 읽는 모델(Transformer)은 넓게 보면 이득이 작았습니다.',
    body: '정상 50건 잘못 의심 기준에서 RF · XGBoost가 둘 다 놓친 사기 81건 중 Transformer가 11건을 더 잡았지만, 대신 정상 25건을 더 의심했습니다. 같은 30% 데이터로 배운 대조군보다 하이브리드가 오히려 낮았습니다(50건 기준 330 → 328, 5건 기준 205 → 118).',
  },
  {
    head: '다만 잘못 의심을 0건으로 묶으면 Transformer가 가장 많이 잡았습니다.',
    body: '112건으로 RF 47 · XGBoost 81보다 많습니다. 이 구간은 몇 건 차이로 순위가 바뀌는 불안정한 구간이라 v1 · v2 판정 때도 참고로만 봤고, 후속 비교 거리로 남겨 뒀습니다.',
  },
  {
    head: 'RF를 고른 이유 (ADR-0061)',
    body: '정상 50건 잘못 의심 기준에서 XGBoost와 같은 수준입니다. 학습 데이터 1.4만 건으로는 순서 모델의 이점을 확인하기 어렵고, torch 의존성이 크며, 조사 보고서에 근거를 설명하기 어렵습니다. RF는 CPU에서 바로 계산됩니다. 다른 모델은 같은 연결 방식의 두 번째 그림자 모델로 나중에 비교할 수 있게 남겨 뒀습니다.',
  },
  {
    head: '룰이 못 잡는 곳을 ML이 메웁니다.',
    body: '저속 반복 · 분할 결제는 룰 0%입니다. 반대로 행동 이상은 모든 모델이 약합니다(31~35%).',
  },
]

// ---- ⑤ 버전 이야기 (rf_v1_vs_v2.md · rf_v2_vs_v3.md · ADR-0061 개정) ----

export type Criterion = { passed: boolean; text: string; result: string }

export const versions: {
  key: string
  name: string
  when: string
  status: 'base' | 'current' | 'dropped'
  statusLabel: string
  summary: string
  criteria: Criterion[]
  notes: string[]
}[] = [
  {
    key: 'v1',
    name: 'rf-v1',
    when: '11/02',
    status: 'base',
    statusLabel: '기준 모델 · 되돌리기용',
    summary: '입력 13칸 (가게 번호 포함) · 기준값 30%',
    criteria: [],
    notes: [
      '합성 가게 3곳만 보고 배움',
      '문제: 같은 손님 · 같은 결제(첫 결제 60만 원)인데 가게 번호만 바꾸면 확률 22% · 36% · 44% · 39%',
      'Validation 2,998건 중 49건이 기준값 위아래로 뒤집힘 (평균 2.7%p · 최대 26.3%p 차이)',
    ],
  },
  {
    key: 'v2',
    name: 'rf-v2',
    when: '11/02',
    status: 'current',
    statusLabel: '사용 중',
    summary: '가게 번호만 뺀 12칸 · 같은 절차 · 기준값 30%',
    criteria: [
      {
        passed: true,
        text: '정상 50건 잘못 의심할 때 잡은 사기가 5% 넘게 줄지 않음',
        result: '350 → 347 (−0.9%)',
      },
      { passed: true, text: 'AP가 0.01 넘게 떨어지지 않음', result: '0.8984 → 0.9022' },
    ],
    notes: [
      '가게 번호를 바꿔도 뒤집힘 0건 (확률 차이 0)',
      'Test: F1 0.7829 → 0.7926 · 잡은 사기 256 → 258 · 잘못 의심한 정상 43 → 38',
    ],
  },
  {
    key: 'v3',
    name: 'rf-v3',
    when: '11/03',
    status: 'dropped',
    statusLabel: '탈락',
    summary: 'v2 12칸 + "평소 국가와 다른가" · 현실형 20만 건 · 기준값 45%',
    criteria: [
      {
        passed: true,
        text: '같은 수만큼 잘못 의심할 때 더 잡음 · AP 유지',
        result: '138 → 170 · 0.52 → 0.66',
      },
      { passed: true, text: '해외 사기 잡은 비율 +20%p 이상', result: '65% → 99%' },
      { passed: false, text: '해외 정상 결제를 잘못 의심한 비율 10% 이하', result: '34.1%' },
      { passed: true, text: '고액 정상 결제를 잘못 의심한 비율 줄어듦', result: '5.6% → 3.1%' },
      { passed: true, text: '외부 데이터 AP 안 떨어짐', result: '0.0021 → 0.2291' },
    ],
    notes: ['해외 여행 중인 정상 손님의 50.5%를 의심 → v2 유지'],
  },
]

/** rf-v3 자세히 — v3 Test 30,000건, 룰 "보류"와 같은 수(정상 536건)를 잘못 의심할 때. 정상은 잘못 의심한 비율, 사기는 잡은 비율 (%) */
export const v3Conditions: {
  label: string
  count: number
  fraud: boolean
  rule: number
  rfV2: number
  noCountry: number
  rfV3: number
}[] = [
  {
    label: '정상 · 카드 · 기기 한꺼번에 바꿈',
    count: 519,
    fraud: false,
    rule: 100.0,
    rfV2: 27.2,
    noCountry: 15.6,
    rfV3: 6.4,
  },
  {
    label: '정상 · 몇 분 사이 연달아',
    count: 201,
    fraud: false,
    rule: 0.0,
    rfV2: 13.9,
    noCountry: 2.5,
    rfV3: 2.5,
  },
  {
    label: '정상 · 해외 거주',
    count: 191,
    fraud: false,
    rule: 0.0,
    rfV2: 1.0,
    noCountry: 2.1,
    rfV3: 1.6,
  },
  {
    label: '정상 · 해외 여행 중',
    count: 378,
    fraud: false,
    rule: 0.0,
    rfV2: 2.4,
    noCountry: 3.2,
    rfV3: 50.5,
  },
  {
    label: '사기 · 평소 국가로 위장',
    count: 115,
    fraud: true,
    rule: 13.9,
    rfV2: 67.8,
    noCountry: 68.7,
    rfV3: 68.7,
  },
  {
    label: '사기 · 해외 접속',
    count: 52,
    fraud: true,
    rule: 13.5,
    rfV2: 73.1,
    noCountry: 67.3,
    rfV3: 100.0,
  },
  {
    label: '사기 · 고위험 국가 접속',
    count: 40,
    fraud: true,
    rule: 52.5,
    rfV2: 55.0,
    noCountry: 77.5,
    rfV3: 97.5,
  },
]

export const versionThoughts: Thought[] = [
  {
    head: '국가 칸을 어떻게 정의할까',
    body: '"처음 본 국가인가"(A) vs "평소 국가와 다른가"(B)를 외부 데이터로 먼저 비교했습니다. 사기범의 첫 결제가 그 나라를 "본 적 있음"으로 만들어 A는 그다음을 놓쳤습니다(AP A 0.036 · B 0.508) → B를 골랐습니다.',
  },
  {
    head: '왜 탈락시켰나',
    body: '사기는 더 잡지만 남는 의심을 해외 여행 중인 정상 손님에게 썼습니다. "평소 국가와 다른가" 하나로는 여행과 해외 공격을 가르지 못합니다.',
  },
  {
    head: '기준을 고치지 않았습니다.',
    body: '잘못 의심 34건 지점에서 보면 해외 정상 4.7%로 통과처럼 보이지만, 결과를 보고 지점을 바꾸는 건 사후 조정이라 판정은 그대로 뒀습니다. 다시 하려면 여행과 공격을 가를 칸을 더한 실험부터 해야 합니다.',
  },
]

// ---- ⑥ 한계 · 외부 검증 (tabformer_scorecard.md · shadow_whatif.md · v4 준비도 보고서 11/09) ----

export const tabformer = {
  description:
    'IBM이 공개한 합성 카드 거래(2,000명 · 약 2,439만 건 · 사기 0.12%) 중 손님 250명 · 2015~2019년만 썼습니다. 검증 구간 16만 건 · 사기 252건.',
  fraud: 252,
  wrongNormalPercent: 1.96,
  caught: [
    { key: 'rule' as ModelKey, label: '탐지 룰', count: 14 },
    { key: 'rfV2' as ModelKey, label: 'rf-v2 (우리 데이터로 학습)', count: 8 },
    { key: 'rfV1' as ModelKey, label: 'RF (TabFormer로 다시 학습)', count: 47 },
  ],
  ap: [
    { label: '무작위로 찍었을 때', value: 0.0016 },
    { label: 'rf-v2', value: 0.0021, bad: true },
    { label: 'RF (TabFormer로 다시 학습)', value: 0.0146 },
  ],
}

export const shadowCheck = {
  description:
    '돈워리 로컬 서버에 정상 · 사기 시나리오 결제 50건을 보내 실제로 쌓인 기록을 정답과 대조했습니다 (11/03).',
  rows: [
    { label: '정상', count: 27, rule: 0, ml: 0 },
    { label: '사기', count: 23, rule: 5, ml: 13 },
  ],
  notes: [
    '둘 다 놓친 사기 10건 — 고위험 국가 3 · 소액 반복 4 · 고액 연속 2 · 같은 카드 1',
    'ML HIGH는 금액에 몰림 — 50만 원 이상 결제의 74%, 미만은 20%',
    '같은 결제 46건을 rf-v1 · rf-v2로 채점 — HIGH/LOW 뒤집힘 0건',
  ],
}

export const limitThoughts: Thought[] = [
  {
    head: '합성 성적이 좋은 이유는 우리가 그렇게 만들었기 때문입니다.',
    body: 'TabFormer의 사기는 "어디서 · 무슨 업종에서"로 드러나는데, 우리 12칸은 손님의 행동 이력만 봅니다. 합성 성적표의 "룰 대비 약 3.8배"는 우리가 설계한 사기 안에서만 맞는 결론입니다.',
  },
  {
    head: '그래서 판정에 쓰지 않습니다.',
    body: '점수를 룰 점수에 더하지 않고, 승인 · 보류 · 거절을 바꾸지 않습니다. 실제 결제에서 직원 판정 · 분쟁 결과로 근거가 쌓인 뒤에 다시 판단합니다 (ADR-0061).',
  },
  {
    head: '다음 단계(v4)는 보류했습니다.',
    body: '더 큰 데이터를 만들기 전에 표본이 충분한지 먼저 검사했더니, 완전히 확인된 공격 사건이 Validation 58 · Test 75개로 목표 200개에 못 미쳐(준비도 HOLD) 추가 생성 · 재학습을 멈췄습니다.',
  },
]

export const sources: Record<MlModelTab, string> = {
  overview:
    'fds-ml git 기록 · ADR-0061 · comparisons/reports/rf_v1_vs_v2.md · reports/scorecard.md · reports/tabformer_scorecard.md',
  data: 'fds-ml README.md 7 · 8 · 13절 · profiles.py · data/transactions.csv · data/v3/*.csv (11/10 집계)',
  split:
    'fds-ml common/rf_training.py split_and_purge · README 4 · 14절 · 11/10 집계 (저장된 모델의 경계 시각과 같음)',
  training:
    'rf_v2/models/random_forest_v2.joblib (칸 · 기준값 · 중요도) · common/rf_training.py · comparisons/reports/rf_v1_vs_v2.md Test 줄 · README 5절 · ADR-0061',
  compare:
    'fds-ml reports/scorecard.md (11/02) · Transformer 줄 = 저장된 모델로 11/10 같은 방법으로 계산 (재학습 없음, 같은 계산으로 rf-v2를 검산해 성적표와 일치) · ADR-0061 "버린 대안"',
  versions:
    'fds-ml comparisons/reports/rf_v1_vs_v2.md · comparisons/reports/rf_v2_vs_v3.md · ADR-0061 개정(#450)',
  limits:
    'fds-ml reports/tabformer_scorecard.md · reports/shadow_whatif.md · README 외부 데이터 절 · v4 준비도 보고서 (11/09)',
}
