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
