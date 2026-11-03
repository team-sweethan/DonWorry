import { ApiError } from '../../api/client.ts'
export function applicationError(
  error: unknown,
  action: 'submit' | 'verify' | 'resend' = 'submit',
): string {
  if (error instanceof ApiError) {
    if (error.status === 401) return '로그인 세션이 만료되었습니다. 다시 한 번 요청해 주세요.'
    if (error.code === 'CSRF_001') return '요청 보안 확인에 실패했습니다. 다시 시도해 주세요.'
    if (error.code?.startsWith('APPLICATION_') && error.detail) return error.detail
  }
  if (action === 'verify')
    return '확인 결과를 알 수 없습니다. 다시 확인 버튼을 눌러 주세요. 이미 처리되었다면 완료 안내가 표시됩니다.'
  if (action === 'resend')
    return '메일 요청 결과를 알 수 없습니다. 받은편지함과 스팸함을 확인하고 잠시 후 다시 요청해 주세요.'
  return '처리 결과를 확인하지 못했습니다. 접수가 되었을 수 있으니 확인 메일을 먼저 확인해 주세요. 입력 내용은 유지됩니다.'
}
export function takeVerificationToken(
  location: Pick<Location, 'hash' | 'pathname' | 'search'>,
  history: Pick<History, 'replaceState' | 'state'>,
): string | null {
  const token = new URLSearchParams(location.hash.replace(/^#/, '')).get('token')
  if (location.hash)
    history.replaceState(history.state, '', `${location.pathname}${location.search}`)
  return token && /^[A-Za-z0-9_-]{43}$/.test(token) ? token : null
}
