// 유효하지 않은 문자를 지워 정상 번호처럼 만들지 않고, 서버 검증에 그대로 전달한다.
// 숫자와 구분자만 있는데 지원 범위를 벗어나면, 입력 도중 넣은 구분자가 남지 않도록 숫자만 돌려준다.
export function formatBusinessNumber(value: string): string {
  if (!/^[0-9 -]*$/.test(value)) return value
  const digits = value.replace(/[- ]/g, '')
  if (digits.length > 10) return digits
  return [digits.slice(0, 3), digits.slice(3, 5), digits.slice(5)].filter(Boolean).join('-')
}

export function formatPhone(value: string): string {
  if (!/^[0-9 -]*$/.test(value)) return value
  const digits = value.replace(/[- ]/g, '')
  let prefix: number
  let maximum: number
  if (/^02/.test(digits)) {
    prefix = 2
    maximum = 10
  } else if (/^(?:01[016789]|0(?:3[123]|4[1234]|5[12345]|6[1234]|70|80))/.test(digits)) {
    prefix = 3
    maximum = 11
  } else return digits
  if (digits.length > maximum) return digits
  const middle = digits.length >= prefix + 8 ? 4 : 3
  return [
    digits.slice(0, prefix),
    digits.slice(prefix, prefix + middle),
    digits.slice(prefix + middle),
  ]
    .filter(Boolean)
    .join('-')
}

export function isBusinessNumberValid(value: string): boolean {
  const digits = value.replace(/[- ]/g, '')
  if (!/^[0-9]{10}$/.test(digits) || /^0{10}$/.test(digits)) return false
  const weights = [1, 3, 7, 1, 3, 7, 1, 3, 5]
  const sum = weights.reduce((total, weight, index) => total + Number(digits[index]) * weight, 0)
  return (10 - ((sum + Math.floor((Number(digits[8]) * 5) / 10)) % 10)) % 10 === Number(digits[9])
}

function contentLength(value: string): number {
  return value.replace(/[- ]/g, '').length
}

function caretAt(value: string, count: number): number {
  if (count === 0) return 0
  let seen = 0
  for (let index = 0; index < value.length; index++) {
    if (!/[- ]/.test(value[index]) && ++seen === count) return index + 1
  }
  return value.length
}

export function formatApplicationEdit(
  raw: string,
  start: number,
  end: number,
  previous: string,
  inputType: string,
  format: (value: string) => string,
  hadSelection = false,
): { value: string; start: number; end: number } {
  // 자동 구분자에 붙은 Backspace/Delete는 옆 숫자를 지운다. 구분자만 선택해 지운 경우는 숫자를 지키고 구분자를 되살린다.
  if (
    !hadSelection &&
    raw.length === previous.length - 1 &&
    format(raw) === previous &&
    start === end
  ) {
    if (inputType === 'deleteContentBackward' && previous[start] === '-') {
      raw = raw.slice(0, start - 1) + raw.slice(start)
      start = end = Math.max(0, start - 1)
    } else if (inputType === 'deleteContentForward' && previous[start] === '-') {
      raw = raw.slice(0, start) + raw.slice(start + 1)
    }
  }
  const value = format(raw)
  if (value === raw) return { value, start, end }
  return {
    value,
    start: caretAt(value, contentLength(raw.slice(0, start))),
    end: caretAt(value, contentLength(raw.slice(0, end))),
  }
}

export function loginIdChecks(value: string) {
  const normalized = value.trim()
  return [
    { label: '소문자·숫자·밑줄(_)만 사용', met: /^[a-z0-9_]+$/.test(normalized) },
    { label: '3~30자', met: normalized.length >= 3 && normalized.length <= 30 },
  ]
}

export function passwordChecks(value: string) {
  const length = Array.from(value).length
  return [
    { label: '영문 포함', met: /[a-zA-Z]/.test(value) },
    { label: '숫자 포함', met: /[0-9]/.test(value) },
    { label: '8~64자', met: length >= 8 && length <= 64 },
    {
      label: '72바이트 이하',
      met: value.length > 0 && new TextEncoder().encode(value).length <= 72,
    },
  ]
}
