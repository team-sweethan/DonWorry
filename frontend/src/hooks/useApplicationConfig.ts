import { useEffect, useState } from 'react'
import { useLocation } from 'react-router-dom'
import { getApplicationConfig } from '../api/merchantApplications'

export function useApplicationConfig() {
  const location = useLocation()
  const [revision, setRevision] = useState(0)
  const key = `${location.key}:${location.pathname}:${revision}`
  const [result, setResult] = useState<{ key: string; enabled?: boolean; error?: boolean } | null>(
    null,
  )
  useEffect(() => {
    const controller = new AbortController()
    let active = true
    getApplicationConfig(controller.signal)
      .then((value) => {
        if (active) setResult({ key, enabled: value.enabled === true })
      })
      .catch(() => {
        if (active) setResult({ key, error: true })
      })
    return () => {
      active = false
      controller.abort()
    }
  }, [key])
  const current = result?.key === key ? result : null
  return {
    enabled: current?.enabled === true,
    loading: !current,
    error: current?.error === true,
    retry: () => setRevision((v) => v + 1),
  }
}
