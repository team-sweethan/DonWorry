const CSRF_COOKIE = 'XSRF-TOKEN'
export const CSRF_HEADER = 'X-XSRF-TOKEN'

export function isUnsafeMethod(method: string | undefined) {
  return !['GET', 'HEAD', 'OPTIONS', 'TRACE'].includes((method ?? 'GET').toUpperCase())
}

// 로그인·로그아웃이 쿠키를 바꾸므로 메모리에 보관하지 않고 전송할 때마다 읽는다.
export function readCsrfToken(): string | undefined {
  const cookie = document.cookie
    .split(';')
    .find((value) => value.trim().startsWith(`${CSRF_COOKIE}=`))
  return cookie
    ? decodeURIComponent(cookie.trim().slice(CSRF_COOKIE.length + 1)) || undefined
    : undefined
}

// 서버의 /auth/csrf는 쿠키가 남아 있으면 같은 토큰을 유지하므로, 거절된 쿠키를 지워야 새로 발급된다.
export function clearCsrfToken() {
  document.cookie = `${CSRF_COOKIE}=; Max-Age=0; Path=/`
}

// 필터가 업무 처리 전에 거절한 CSRF 오류만 true다. 비 JSON 403은 기존 오류 처리로 넘긴다.
export async function isCsrfRejection(res: Response) {
  if (res.status !== 403) return false
  try {
    const problem: unknown = await res.clone().json()
    return (
      typeof problem === 'object' &&
      problem !== null &&
      'code' in problem &&
      problem.code === 'CSRF_001'
    )
  } catch {
    return false
  }
}
