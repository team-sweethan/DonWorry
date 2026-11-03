import { useEffect, useRef, useState } from 'react'

export function useMinimumLoadingIndicator(loading: boolean, delay = 300, minimumDuration = 400) {
  const [visible, setVisible] = useState(false)
  const shownAt = useRef<number | null>(null)

  useEffect(() => {
    if (loading && !visible) {
      const showTimer = window.setTimeout(() => {
        shownAt.current = Date.now()
        setVisible(true)
      }, delay)
      return () => window.clearTimeout(showTimer)
    }

    if (!loading && visible) {
      const elapsed = Date.now() - (shownAt.current ?? Date.now())
      const hideTimer = window.setTimeout(
        () => {
          shownAt.current = null
          setVisible(false)
        },
        Math.max(0, minimumDuration - elapsed),
      )
      return () => window.clearTimeout(hideTimer)
    }
  }, [delay, loading, minimumDuration, visible])

  return visible
}
