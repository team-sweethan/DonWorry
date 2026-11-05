import { useEffect, useRef } from 'react'
import { Tooltip } from 'antd'
import { financeAmount } from './financeAmount'
import './finance-money.css'

export function FinanceMoney({ value, compact = true }: { value: number; compact?: boolean }) {
  const amount = financeAmount(value, compact)
  const wrapperRef = useRef<HTMLSpanElement>(null)
  const textRef = useRef<HTMLSpanElement>(null)
  useEffect(() => {
    const wrapper = wrapperRef.current
    const text = textRef.current
    const parent = wrapper?.parentElement
    if (!wrapper || !text || !parent) return
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
  }, [amount.text])
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
          {amount.text}
        </span>
      </span>
    </Tooltip>
  )
}
