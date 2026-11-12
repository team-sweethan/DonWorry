import { useEffect, useState } from 'react'
import type { CSSProperties } from 'react'

const DIGITS = ['0', '1', '2', '3', '4', '5', '6', '7', '8', '9']

/**
 * 자리마다 숫자 띠가 굴러가 멈추는 숫자 (시연용 · 슬롯머신처럼).
 * - 처음 보일 때 0에서 굴러가고, 값이 실제로 바뀌면 바뀐 자리만 다시 굴러간다
 * - 자리는 오른쪽부터 세어 key를 둔다 — 자릿수가 늘어도 같은 자리끼리 이어진다
 * - 화면 읽기 프로그램에는 정확한 값 하나만 읽힌다. 동작 줄이기 설정이면 바로 멈춘 상태로 보인다
 */
export function RollingNumber({
  value,
  suffix = '',
  delay = 0,
}: {
  value: number
  suffix?: string
  delay?: number
}) {
  const [ready, setReady] = useState(false)
  useEffect(() => {
    // 첫 그림은 0에 둔 뒤 다음 프레임에 목표 숫자로 옮겨야 굴러가는 전환이 보인다
    const frame = requestAnimationFrame(() => setReady(true))
    return () => cancelAnimationFrame(frame)
  }, [])
  const text = value.toLocaleString('ko-KR')
  const chars = [...text]
  let digitIndex = 0
  return (
    <span className="rn">
      <span className="rn-sr">{`${text}${suffix}`}</span>
      <span className="rn-visual" aria-hidden="true">
        {chars.map((ch, i) => {
          const fromRight = chars.length - i
          if (!/\d/.test(ch))
            return (
              <span key={`s${fromRight}`} className="rn-sep">
                {ch}
              </span>
            )
          const order = digitIndex++
          const style = {
            '--rn-to': ready ? Number(ch) : 0,
            '--rn-delay': `${delay + order * 70}ms`,
          } as CSSProperties
          return (
            <span key={`d${fromRight}`} className="rn-col" style={style}>
              <span className="rn-strip">
                {DIGITS.map((d) => (
                  <span key={d}>{d}</span>
                ))}
              </span>
            </span>
          )
        })}
        {suffix}
      </span>
    </span>
  )
}
