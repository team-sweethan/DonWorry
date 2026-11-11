import dayjs from 'dayjs'

/** 조치 상태 → 무엇을 했는지 · 태그 색. 목록과 상세가 같은 말을 쓴다. 색 이름 태그는 관리 화면 공통 규칙으로 아이콘 + 글자로 그려진다 */
export function actionView(status: string): { label: string; color: string } {
  switch (status) {
    case 'SUCCEEDED':
      return { label: '계정 정지 완료', color: 'green' }
    case 'REQUESTED':
      return { label: '정지 처리 중', color: 'blue' }
    case 'SKIPPED_REACTIVATED':
      return { label: '재활성화 후 조치 제외', color: 'default' }
    case 'NONE':
      return { label: '조치 대상 아님', color: 'default' }
    default:
      return { label: '조치 실패', color: 'red' }
  }
}

/** 최근 발생 시각 — 하루 안은 "N분 전"처럼, 그보다 오래면 날짜 · 시각 */
export function occurredLabel(iso: string, now: Date = new Date()): string {
  const at = dayjs(iso)
  const minutes = dayjs(now).diff(at, 'minute')
  if (minutes < 1) return '방금'
  if (minutes < 60) return `${minutes}분 전`
  if (minutes < 60 * 24) return `${Math.floor(minutes / 60)}시간 전`
  return at.format('YYYY.MM.DD HH:mm')
}

/** 막힌 경로를 사람이 읽는 기능 이름으로. 앞부분이 맞는 첫 줄을 쓴다 (긴 경로를 먼저 둔다) */
const featureByPrefix: Array<[string, string]> = [
  ['/api/fds/operator-access-detections', '계정 접근 이상 탐지'],
  ['/api/fds/insider-detections', '내부자 탐지 룰'],
  ['/api/fds/blocklist', '차단 목록'],
  ['/api/fds', '결제 탐지 룰'],
  ['/api/admin/merchant-applications', '이용 신청 심사'],
  ['/api/admin/merchants', '가맹점 관리'],
  ['/api/admin/case-assignments', '사건 담당 배정'],
  ['/api/admin/insider-', '내부자 보안'],
  ['/api/admin/raw-view-reviews', '원문 열람 검토'],
  ['/api/admin/users', '직원 계정 관리'],
  ['/api/admin', '관리자 기능'],
  ['/api/security-cases', '보안 사건'],
  ['/api/ai-switch', 'AI 조사 스위치'],
  ['/api/ml-shadow-scores', 'ML 점수'],
  ['/api/v1/settlements', '정산'],
  // 사장님이 주소창에 관리 화면 주소를 직접 친 경우 (화면 경로로 기록된다)
  ['/admin', '관리자 화면 직접 접속'],
  ['/security', '보안 직원 화면 직접 접속'],
]

export function featureName(path: string): string | null {
  const hit = featureByPrefix.find(([prefix]) => path === prefix || path.startsWith(prefix))
  return hit ? hit[1] : null
}

export type FeatureCount = { name: string; count: number; paths: string[] }

/** 순위 칸에 보일 기능 수 */
export const TOP_FEATURE_LIMIT = 5

/** 순위를 기능 이름으로 합친다 — 서버는 경로별 수를 자르지 않고 모두 주고, 여기서 합친 뒤 많은 순으로 자른다 — /users/12 · /users/13처럼 번호만 다른 경로가 따로 줄을 차지하지 않게. 모르는 경로는 경로 그대로 */
export function groupByFeature(
  rows: Array<{ httpMethod: string; path: string; count: number }>,
): FeatureCount[] {
  const groups = new Map<string, FeatureCount>()
  for (const row of rows) {
    const name = featureName(row.path) ?? row.path
    const group = groups.get(name) ?? { name, count: 0, paths: [] }
    group.count += row.count
    group.paths.push(`${row.httpMethod} ${row.path}`)
    groups.set(name, group)
  }
  return [...groups.values()]
    .sort((a, b) => b.count - a.count || a.name.localeCompare(b.name))
    .slice(0, TOP_FEATURE_LIMIT)
}

/** 판정 기준 시간 — 60초 단위면 "5분", 아니면 "45초" */
export function windowLabel(seconds: number): string {
  return seconds % 60 === 0 ? `${seconds / 60}분` : `${seconds}초`
}

/** 첫 요청부터 마지막 요청까지 걸린 시간 — "1초 안", "42초", "3분 10초" */
export function spanLabel(firstIso: string, lastIso: string): string {
  const seconds = Math.max(0, Math.round(dayjs(lastIso).diff(dayjs(firstIso)) / 1000))
  if (seconds < 1) return '1초 안'
  if (seconds < 60) return `${seconds}초`
  const rest = seconds % 60
  return rest === 0 ? `${seconds / 60}분` : `${Math.floor(seconds / 60)}분 ${rest}초`
}

export const ruleOptions = [
  { value: '', label: '모든 룰' },
  { value: 'SEC_OPERATOR_OUT_OF_ROLE_REPEAT', label: '역할 범위 밖 API 반복 호출' },
  { value: 'SEC_OPERATOR_PERMISSION_MISSING_REPEAT', label: '권한 부족 API 반복 호출' },
]

export type ListView = 'ACCOUNT' | 'DETECTION'

/** 주소의 보기 · 룰 값 — 모르는 값은 기본(계정별 · 모든 룰) */
export function parseListView(value: string | null): ListView {
  return value === 'DETECTION' ? 'DETECTION' : 'ACCOUNT'
}
export function parseRule(value: string | null): string {
  return ruleOptions.some((option) => option.value === value) ? (value ?? '') : ''
}

/** 근거 요청이 노린 기능 — 같은 기능은 한 번만, 처음 나온 순서대로. 여러 기능을 돌아가며 두드렸으면 탐색, 한 경로만 반복이면 실수일 가능성 */
export function targetedFeatures(rows: Array<{ httpMethod: string; path: string }>): string[] {
  return [...new Set(rows.map((row) => featureName(row.path) ?? `${row.httpMethod} ${row.path}`))]
}
