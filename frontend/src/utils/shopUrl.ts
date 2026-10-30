// 검토용 주소는 HTTP(S) 절대 URL만 허용한다. URL 자동 보정으로 잘못된 입력을 통과시키지 않는다.
export function isShopUrlValid(value: string): boolean {
  const trimmed = value.trim()
  if (trimmed.length > 2048 || !/^https?:\/\/[^/?#]/i.test(trimmed) || /[\s\\]/.test(trimmed))
    return false
  try {
    const authority = /^https?:\/\/([^/?#]+)/i.exec(trimmed)?.[1]
    if (!authority || /[@%]/.test(authority)) return false
    const url = new URL(trimmed)
    // URL은 밑줄 등의 호스트도 허용하므로 서버와 같은 도메인 구간 문법을 추가 검사한다.
    // 국제화 도메인은 URL이 변환한 Punycode로 검사하고 입력 원문은 바꾸지 않는다.
    const hostname = url.hostname.replace(/\.$/, '')
    const validHost =
      hostname.startsWith('[') ||
      hostname.split('.').every((label) => /^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/i.test(label))
    return validHost && !url.username && !url.password
  } catch {
    return false
  }
}
