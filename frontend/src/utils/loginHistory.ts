export const emptyLoginMessage = '최근 90일 내 로그인 기록 없음'

export function loginTime(value: string, compact = false) {
  return new Intl.DateTimeFormat('ko-KR', {
    timeZone: 'Asia/Seoul',
    ...(compact ? {} : { year: 'numeric' as const }),
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).format(new Date(value))
}

export function loginDevice(agent: string | null) {
  if (!agent) return '알 수 없는 기기'
  const browser = /Edg(?:e|A|iOS)?\//.test(agent)
    ? 'Edge'
    : /OPR\//.test(agent)
      ? 'Opera'
      : /(?:Chrome|CriOS)\//.test(agent)
        ? 'Chrome'
        : /(?:Firefox|FxiOS)\//.test(agent)
          ? 'Firefox'
          : /Safari\//.test(agent)
            ? 'Safari'
            : null
  const os = /Android/.test(agent)
    ? 'Android'
    : /iPhone|iPad|iPod/.test(agent)
      ? 'iOS'
      : /Windows/.test(agent)
        ? 'Windows'
        : /Mac OS X/.test(agent)
          ? 'macOS'
          : /Linux/.test(agent)
            ? 'Linux'
            : null
  return [browser, os].filter(Boolean).join(' · ') || '알 수 없는 기기'
}
