import { useEffect, useRef } from 'react'
import { Tooltip } from 'antd'
import { financeAmount } from './financeAmount'
import { useCountUp } from '../../hooks/useCountUp'
import './finance-money.css'

/**
 * countUp: 요약 카드의 큰 금액만 처음 보일 때 0부터 세어 올라간다 (시연용).
 * 세는 동안에도 읽기 프로그램 · 말풍선에는 정확한 금액을 준다. 표 안의 금액에는 쓰지 않는다.
 */
export function FinanceMoney({
  value,
  compact = true,
  countUp = false,
}: {
  value: number
  compact?: boolean
  countUp?: boolean
}) {
  const amount = financeAmount(value, compact)
  const counted = useCountUp(countUp ? value : null)
  const counting = counted !== null && counted !== value
  const text = counting ? financeAmount(counted, compact).text : amount.text
  const wrapperRef = useRef<HTMLSpanElement>(null)
  const textRef = useRef<HTMLSpanElement>(null)
  useEffect(() => {
    const wrapper = wrapperRef.current
    const text = textRef.current
    const parent = wrapper?.parentElement
    // 세는 중에는 글자 크기를 맞추지 않는다. 다 센 뒤 최종 금액 기준으로 한 번 맞춘다
    if (!wrapper || !text || !parent || counting) return
    let active = true
    const fit = () => {
      if (!active) return
      text.style.fontSize = ''
      const parentStyle = getComputedStyle(parent)
      const available =
        parent.clientWidth -
        parseFloat(parentStyle.paddingLeft) -
        parseFloat(parentStyle.paddingRight)
      const natural = text.getBoundingClientRect().width
      const base = parseFloat(getComputedStyle(wrapper).fontSize)
      if (available > 0 && natural > available)
        text.style.fontSize =
          Math.max(10, Math.floor((base * available) / natural)).toString() + 'px'
    }
    fit()
    const observer = new ResizeObserver(fit)
    observer.observe(parent)
    document.fonts.ready.then(fit)
    return () => {
      active = false
      observer.disconnect()
    }
  }, [amount.text, counting])
  return (
    <Tooltip title={amount.exact} trigger={['hover', 'focus', 'click']}>
      <span
        ref={wrapperRef}
        className="finance-money"
        aria-label={amount.exact}
        title={amount.exact}
        tabIndex={amount.abbreviated ? 0 : undefined}
      >
        <span ref={textRef} className="finance-money__text">
          {text}
        </span>
      </span>
    </Tooltip>
  )
}
