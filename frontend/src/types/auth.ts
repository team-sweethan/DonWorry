export type AuthUser = {
  userId: number
  loginId: string
  displayName: string
  role: 'ADMIN' | 'OPERATOR' | 'SECURITY_STAFF'
  merchantId: number | null
  tenantScope: 'ALL' | 'MERCHANT'
  permissions: string[]
  passwordChangeRequired: boolean
}
