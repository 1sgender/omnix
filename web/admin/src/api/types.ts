/**
 * Типы — точное отражение ответов сервера (см. AdminHttpHandler.kt /
 * LicenseBillingHttpHandler.kt). Никаких выдуманных полей: то, чего бэкенд
 * не отдаёт, здесь нет или явно null.
 */

export type AdminRole = 'SUPER_ADMIN' | 'ADMIN' | 'SUPPORT' | 'VIEWER'

export type AdminPermission =
  | 'DASHBOARD_READ' | 'USERS_READ' | 'DEVICES_READ' | 'DEVICES_REVOKE'
  | 'LICENSES_READ' | 'LICENSES_WRITE' | 'SUBSCRIPTIONS_READ'
  | 'PROVIDERS_READ' | 'PROVIDERS_CONFIGURE' | 'USAGE_READ' | 'LOGS_READ'
  | 'AUDIT_READ' | 'SETTINGS_READ' | 'SETTINGS_WRITE' | 'ADMINS_MANAGE'
  | 'FEATURES_READ' | 'FEATURES_WRITE'

export interface LoginResponse { token: string; role: AdminRole; actor: string; expiresAt: string }
export interface MeResponse { actor: string; role: AdminRole; permissions: AdminPermission[] }

export interface DashboardResponse {
  users: { total: number }
  licenses: { active: number; issued: number }
  devices: { tokensActive: number }
  requests: { today: number; errorsToday: number; tokensToday: number }
  billing: { ordersPending: number }
  providers: Record<string, { status: string; circuit: string }>
  localExecutionRate: string
}

export interface HealthResponse {
  api: { status: string }
  database: { status: string }
  aiGateway: { status: string }
  authentication: { status: string }
  licenseService: { status: string }
  providers: Record<string, { status: string; circuit: string; permanentReason?: string }>
}

export type LicenseStatus = 'ISSUED' | 'ACTIVE' | 'EXPIRED' | 'REVOKED' | 'DISABLED'
export type LicenseBillingStatus = 'GRANTED' | 'PENDING' | 'PAID' | 'PAST_DUE' | 'CANCELED' | 'REFUNDED'

export interface LicenseRow {
  id: string
  status: LicenseStatus
  billingStatus: LicenseBillingStatus
  codeHint: string
  planId: string
  accountId: string | null
  issuedAt: string
  startsAt: string | null
  expiresAt: string | null
  redeemedAt: string | null
}

export interface LicensesResponse { statusFilter: string; licenses: LicenseRow[] }

export interface UserRow {
  id: string
  externalRef: string | null
  status: string
  createdAt: string
  licenses: number
  activeLicenses: number
  lastActiveAt: string | null
}

export interface DeviceRow {
  tokenId: string
  accountId: string
  status: string
  issuedAt: string
  lastUsedAt: string | null
  expiresAt: string | null
  deviceBinding: string | null
  model: string
  firmware: string
  battery: string
}

export interface UserDetailResponse {
  account: { id: string; externalRef: string | null; status: string; createdAt: string; lastActiveAt: string | null }
  licenses: LicenseRow[]
  devices: DeviceRow[]
  usage: { requests: number; errors: number; inputTokens: number; outputTokens: number; promptChars: string }
}

export interface OrderRow {
  id: string; accountId: string; planId: string; provider: string; status: string
  amountMinor: number; currency: string; createdAt: string; paidAt: string | null
}

export interface PlanRow {
  id: string; displayName: string | null; durationDays: number
  amountMinor: number; currency: string | null; active: boolean
}

export interface ProviderRow {
  id: string; status: string; circuit: string
  enabledOverride: boolean | null; priorityOverride: number | null
  apiKey: string; requests: string; latencyP50: string; latencyP95: string
}

export interface ProvidersResponse {
  providers: ProviderRow[]
  runtimeNote: { priority: string; enabled: string; timeout: string; retry: string }
}

export interface UsageResponse {
  periodDays: number; requests: number; cloudRequests: number; localRequests: string
  errors: number; inputTokens: number; outputTokens: number
  byProvider: { provider: string; requests: number; errors: number; inputTokens: number; outputTokens: number }[]
}

export interface UsageCostResponse {
  periodDays: number; totalUsd: number; knownUsd: number; unknownProviders: string[]
  lines: {
    provider: string; inputTokens: number; outputTokens: number
    usdPerMillionInput: number | null; usdPerMillionOutput: number | null
    costUsd: number; formula: string
  }[]
}

export interface LogRow {
  time: string; component: string; type: string; actor: string
  result: string; latencyMs: number | null; requestId: string | null
}

export interface AuditRow {
  time: string; actor: string; action: string; entityType: string
  entityId: string | null; oldValue: string; newValue: string; remoteAddress: string | null
}

export interface AdminRow { id: string; username: string; role: AdminRole; status: string; createdAt: string }

export interface FlagRow { key: string; enabled: boolean; rolloutPercent: number; description: string }

export interface IssueLicenseResponse {
  success: boolean; license_id: string; code: string; status: string
  plan_id: string; issued_at: string; expires_at: string | null; request_id: string
}

export interface SystemSettings { maintenanceMode: boolean; registrationOpen: boolean; defaultPlanId: string }
export interface SecuritySettings {
  sessionTtlMinutes: number; loginMaxAttempts: number; loginWindowMinutes: number; minPasswordLength: number
}
export interface AiProviderOverride { enabled: boolean | null; priority: number | null }
export interface AiRoutingSettings {
  localFirstEnabled: boolean; cloudEscalationEnabled: boolean
  providers: Record<string, AiProviderOverride>
}
export interface LimitsSettings { perDayRequests: number; perDayTokens: number; perDayCostUsd: number }
export interface CostSettings {
  providers: Record<string, { usdPerMillionInput: number | null; usdPerMillionOutput: number | null }>
}

export type SettingsSection = 'system' | 'security' | 'ai' | 'limits' | 'cost'

export interface SettingsResponse<T> { section: string; version?: number; value: T; note?: string }

export interface ApiError {
  status: number
  /** Человекочитаемое сообщение — уже готово к показу. */
  message: string
  code?: string
}
