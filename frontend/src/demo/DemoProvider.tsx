import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { roles } from '../config/navigation'
import { DemoContext, type DemoContextValue, type DemoData } from './DemoContext'

const KEY = 'donworry-admin-layout-demo-v1'

const defaults: DemoData = {
  role: 'security',
  merchant: 'CakeShop',
  loggedIn: false,
  rejectedOrderIds: [],
}

function loadData(): DemoData {
  try {
    const value = window.localStorage.getItem(KEY)
    if (!value) return defaults
    const parsed: Partial<DemoData> = JSON.parse(value)
    const role =
      parsed.role && Object.keys(roles).includes(parsed.role) ? parsed.role : defaults.role
    return {
      ...defaults,
      ...parsed,
      role,
      merchant: parsed.merchant === 'DemoMart' ? 'DemoMart' : 'CakeShop',
      rejectedOrderIds: Array.isArray(parsed.rejectedOrderIds) ? parsed.rejectedOrderIds : [],
    }
  } catch {
    return defaults
  }
}

/** 주의: 시연용 로컬 상태일 뿐, 실제 인증이나 결제 권한을 제공하지 않음. */
export function DemoProvider({ children }: { children: ReactNode }) {
  const [data, setData] = useState<DemoData>(loadData)

  useEffect(() => {
    try {
      window.localStorage.setItem(KEY, JSON.stringify(data))
    } catch {
      /* storage disabled */
    }
  }, [data])

  const update = (fn: (value: DemoData) => DemoData) => setData(fn)
  const value = useMemo<DemoContextValue>(
    () => ({
      ...data,
      setRole: (role) => update((current) => ({ ...current, role })),
      setMerchant: (merchant) => update((current) => ({ ...current, merchant })),
      login: (role) => update((current) => ({ ...current, role, loggedIn: true })),
      logout: () => update((current) => ({ ...current, loggedIn: false })),
      rejectOrder: (orderId) =>
        update((current) => ({
          ...current,
          rejectedOrderIds: [...new Set([...current.rejectedOrderIds, orderId])],
        })),
      resetOrders: () => update((current) => ({ ...current, rejectedOrderIds: [] })),
    }),
    [data],
  )

  return <DemoContext.Provider value={value}>{children}</DemoContext.Provider>
}
