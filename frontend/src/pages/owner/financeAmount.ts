export function financeAmount(value: number, compact = true) {
  if (!Number.isSafeInteger(value)) throw new RangeError('원 단위 금액은 안전한 정수여야 합니다.')
  const exact = value.toLocaleString('ko-KR') + '원'
  const amount = BigInt(value)
  const absolute = amount < 0n ? -amount : amount
  if (!compact || absolute < 10000000n) return { exact, text: exact, abbreviated: false }
  const units: [bigint, string][] =
    absolute >= 1000000000000n
      ? [
          [1000000000000n, '조'],
          [100000000n, '억'],
        ]
      : absolute >= 100000000n
        ? [
            [100000000n, '억'],
            [10000n, '만'],
          ]
        : [[10000n, '만']]
  let remainder = absolute
  const parts: string[] = []
  for (const [unit, label] of units) {
    const count = remainder / unit
    if (count) parts.push(count.toLocaleString('ko-KR') + label)
    remainder %= unit
  }
  return {
    exact,
    text: (remainder ? '약 ' : '') + (amount < 0n ? '-' : '') + parts.join(' ') + ' 원',
    abbreviated: true,
  }
}

// 달력은 반올림한 단위 금액을 쓰고, 정확한 원 단위는 날짜 상세에서 보여준다.
export function calendarAmount(value: number) {
  const exact = financeAmount(value, false).exact
  const signed = BigInt(value)
  const absolute = signed < 0n ? -signed : signed
  if (absolute < 10000n) return exact
  const units = [10000n, 100000000n, 1000000000000n]
  const labels = ['만', '억', '조']
  let index = absolute >= units[2] ? 2 : absolute >= units[1] ? 1 : 0
  const round = (unit: bigint, decimals: boolean) => {
    const factor = decimals ? 10n : 1n
    const step = unit / factor
    return { factor, step, count: (absolute + step / 2n) / step }
  }
  let rounded = round(units[index], index > 0 && absolute < units[index] * 100n)
  if (index < units.length - 1 && rounded.count * rounded.step >= units[index + 1]) {
    index++
    rounded = round(units[index], absolute < units[index] * 100n)
  }
  const whole = rounded.count / rounded.factor
  const fraction = rounded.count % rounded.factor
  const approximate = rounded.count * rounded.step !== absolute
  return `${approximate ? '약 ' : ''}${signed < 0n ? '-' : ''}${whole}${fraction ? `.${fraction}` : ''}${labels[index]}`
}
