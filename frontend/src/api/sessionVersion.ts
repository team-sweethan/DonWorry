const SESSION_VERSION_KEY = 'donworry.auth.version'

// 인증 정보는 저장하지 않는다. 동기 읽기로 storage 이벤트 전달 전에도 다른 탭의 변경을 감지한다.
export function readSessionVersion(): string | undefined {
  try {
    // 읽기만 되고 쓰기가 차단된 저장소도 탭 간 변경을 알릴 수 없다.
    localStorage.setItem(`${SESSION_VERSION_KEY}.probe`, '0'.repeat(36))
    localStorage.removeItem(`${SESSION_VERSION_KEY}.probe`)
    return localStorage.getItem(SESSION_VERSION_KEY) ?? ''
  } catch {
    return undefined
  }
}

export function publishSessionVersion() {
  try {
    const version =
      typeof crypto.randomUUID === 'function'
        ? crypto.randomUUID()
        : Array.from(crypto.getRandomValues(new Uint32Array(4))).join('-')
    localStorage.setItem(SESSION_VERSION_KEY, version)
  } catch {
    // 공유 저장소를 사용할 수 없으면 API 클라이언트가 자동 재전송을 생략한다.
  }
}
