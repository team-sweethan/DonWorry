import { forwardRef, useLayoutEffect, useRef } from 'react'
import { Input, type InputProps, type InputRef } from 'antd'
import { formatApplicationEdit } from './applicationFormat.ts'

export function InputChecks({
  id,
  checks,
}: {
  id: string
  checks: { label: string; met: boolean }[]
}) {
  return (
    <ul id={id} className="dw-input-checks" aria-live="polite" aria-atomic="true">
      {checks.map(({ label, met }) => (
        <li key={label} className={met ? 'is-met' : undefined}>
          <span aria-hidden="true">{met ? '✓' : '○'}</span>
          <span className="dw-input-sr-only">{met ? '충족: ' : '미충족: '}</span>
          {label}
        </li>
      ))}
    </ul>
  )
}

export const FormattedApplicationInput = forwardRef<
  InputRef,
  Omit<InputProps, 'value' | 'onChange' | 'onInput'> & {
    value?: string
    onChange?: (value: string) => void
    format: (value: string) => string
  }
>(function FormattedApplicationInput({ value = '', onChange, format, ...props }, ref) {
  const selection = useRef<{ input: HTMLInputElement; start: number; end: number } | null>(null)
  useLayoutEffect(() => {
    const pending = selection.current
    if (pending && document.activeElement === pending.input) {
      pending.input.setSelectionRange(pending.start, pending.end)
    }
    selection.current = null
  })
  return (
    <Input
      {...props}
      ref={ref}
      value={value}
      onChange={(event) => {
        const input = event.target
        const result = formatApplicationEdit(
          input.value,
          input.selectionStart ?? input.value.length,
          input.selectionEnd ?? input.value.length,
          value,
          (event.nativeEvent as InputEvent).inputType ?? '',
          format,
        )
        selection.current = { input, start: result.start, end: result.end }
        onChange?.(result.value)
      }}
    />
  )
})
