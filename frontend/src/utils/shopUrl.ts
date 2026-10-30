// 검토용 주소는 HTTP(S) 절대 URL만 허용한다. URL 자동 보정으로 잘못된 입력을 통과시키지 않는다.
export function isShopUrlValid(value: string): boolean {
  const trimmed = value.trim()
  if (trimmed.length > 2048 || !/^https?:\/\/[^/?#]/i.test(trimmed) || /[\s\\]/.test(trimmed))
    return false
  try {
    const url = new URL(trimmed)
    return (
      Boolean(url.hostname) &&
      !trimmed.split(/[/]/)[2].includes('@') &&
      !url.username &&
      !url.password
    )
  } catch {
    return false
  }
}
