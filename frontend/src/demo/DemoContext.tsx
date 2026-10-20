import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { roles, type Role } from '../config/navigation'

const KEY = 'donworry-admin-layout-demo-v1'
type DemoData = { role: Role; merchant: string; loggedIn: boolean; rejectedOrderIds: string[] }
const defaults: DemoData = {
  role: 'security',
  merchant: 'CakeShop',
  loggedIn: false,
  rejectedOrderIds: [],
}

function loadData(): DemoData {
  try {
    const v = window.localStorage.getItem(KEY)
    if (!v) return defaults
    const parsed: Partial<DemoData> = JSON.parse(v)
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

type DemoContextValue = DemoData & {
  setRole: (r: Role) => void
  setMerchant: (m: string) => void
  login: (r: Role) => void
  logout: () => void
  rejectOrder: (orderId: string) => void
  resetOrders: () => void
}
const DemoContext = createContext<DemoContextValue | null>(null)

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
  const update = (fn: (v: DemoData) => DemoData) => setData(fn)
  const value = useMemo<DemoContextValue>(
    () => ({
      ...data,
      setRole: (role) => update((v) => ({ ...v, role })),
      setMerchant: (merchant) => update((v) => ({ ...v, merchant })),
      login: (role) => update((v) => ({ ...v, role, loggedIn: true })),
      logout: () => update((v) => ({ ...v, loggedIn: false })),
      rejectOrder: (orderId) =>
        update((v) => ({ ...v, rejectedOrderIds: [...new Set([...v.rejectedOrderIds, orderId])] })),
      resetOrders: () => update((v) => ({ ...v, rejectedOrderIds: [] })),
    }),
    [data],
  )
  return <DemoContext.Provider value={value}>{children}</DemoContext.Provider>
}

export function useDemo() {
  const context = useContext(DemoContext)
  if (!context) throw new Error('useDemo must be used within DemoProvider')
  return context
}
