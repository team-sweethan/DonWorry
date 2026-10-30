import tr46 from 'tr46'

// 검토용 주소는 HTTP(S) 절대 URL만 허용한다. URL 자동 보정으로 잘못된 입력을 통과시키지 않는다.
export function isShopUrlValid(value: string): boolean {
  const trimmed = value.trim()
  if (
    trimmed.length > 2048 ||
    !/^https?:\/\/[^/?#]/i.test(trimmed) ||
    /[\s\\<>"{}|^`]/.test(trimmed) ||
    /%(?![0-9a-f]{2})/i.test(trimmed) ||
    [...trimmed].some((char) => {
      const code = char.charCodeAt(0)
      return code < 32 || (code >= 127 && code <= 159)
    })
  )
    return false
  try {
    const match = /^https?:\/\/([^/?#]+)/i.exec(trimmed)
    const authority = match?.[1]
    if (!match || !authority || /[@%]/.test(authority)) return false
    let addressToValidate = trimmed
    if (!authority.startsWith('[')) {
      const separator = authority.lastIndexOf(':')
      const host = separator < 0 ? authority : authority.slice(0, separator)
      const port = separator < 0 ? '' : authority.slice(separator)
      const asciiHost = tr46.toASCII(host, {
        checkBidi: true,
        checkJoiners: true,
        checkHyphens: false,
        useSTD3ASCIIRules: true,
        transitionalProcessing: false,
        verifyDNSLength: false,
        ignoreInvalidPunycode: false,
      })
      if (asciiHost === null) return false
      if (!hasValidIpv4Notation(asciiHost)) return false
      // 런타임 기본 IDNA에 의존하지 않고 검증할 때만 ASCII 호스트로 URL을 해석한다.
      const prefix = trimmed.slice(0, match[0].length - authority.length)
      addressToValidate = prefix + asciiHost + port + trimmed.slice(match[0].length)
    }
    const url = new URL(addressToValidate)
    // URL은 밑줄 등의 호스트도 허용하므로 서버와 같은 도메인 구간 문법을 추가 검사한다.
    // 국제화 도메인은 UTS #46으로 변환한 Punycode로 검사하고 입력 원문은 바꾸지 않는다.
    const hostname = url.hostname.replace(/\.$/, '')
    const validHost =
      hostname.startsWith('[') ||
      hostname.split('.').every((label) => /^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/i.test(label))
    return validHost && !url.username && !url.password
  } catch {
    return false
  }
}

function hasValidIpv4Notation(host: string): boolean {
  const last = host.replace(/\.$/, '').split('.').at(-1) ?? ''
  if (!/^(?:[0-9]+|0x[0-9a-f]*)$/i.test(last)) return true
  const octets = host.split('.')
  return (
    octets.length === 4 &&
    octets.every((part) => /^(?:0|[1-9][0-9]{0,2})$/.test(part) && Number(part) <= 255)
  )
}
