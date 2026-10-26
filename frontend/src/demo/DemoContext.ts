import { createContext, useContext } from 'react'
import type { Role } from '../config/navigation'

export type DemoData = {
  role: Role
  merchant: string
  loggedIn: boolean
  rejectedOrderIds: string[]
}

export type DemoContextValue = DemoData & {
  setRole: (role: Role) => void
  setMerchant: (merchant: string) => void
  login: (role: Role) => void
  logout: () => void
  rejectOrder: (orderId: string) => void
  resetOrders: () => void
}

export const DemoContext = createContext<DemoContextValue | null>(null)

export function useDemo() {
  const context = useContext(DemoContext)
  if (!context) throw new Error('useDemo must be used within DemoProvider')
  return context
}
