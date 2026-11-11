/** 표시 규칙: 한국어 원화, 화면에 들어오는 날짜는 API 확정 후 Asia/Seoul 변환 처리. */
export const won = (v: number) => `${new Intl.NumberFormat('ko-KR').format(v)}원`

/** 서버의 UTC 시각(시간대 표시가 없으면 UTC로 본다)을 한국 시간으로 보여 준다. 값이 없으면 '—'. */
export function formatKstDateTime(value: string | null | undefined) {
  if (!value) return '—'
  const utc = value.endsWith('Z') || /[+-]\d\d:\d\d$/.test(value) ? value : `${value}Z`
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short',
    timeZone: 'Asia/Seoul',
  }).format(new Date(utc))
}
