/**
 * Типизированные функции по каждому endpoint'у (карта: docs/CONTROL_PLANE_API.md).
 * Пагинация: сервер НЕ отдаёт totals — размер страницы + страница (с 0),
 * UI использует паттерн «Load more».
 */
import { api } from './client'
import type {
  AdminRow, AiRoutingSettings, AuditRow, CostSettings, DashboardResponse,
  DeviceRow, FlagRow, HealthResponse, IssueLicenseResponse, LicenseRow,
  LicensesResponse, LimitsSettings, LogRow, LoginResponse, MeResponse,
  OrderRow, PlanRow, ProvidersResponse, SecuritySettings, SettingsResponse,
  SystemSettings, UsageCostResponse, UsageResponse, UserDetailResponse, UserRow
} from './types'

const PAGE_SIZE = 50

export const endpoints = {
  // ── auth ──
  login: (username: string, password: string) =>
    api.post<LoginResponse>('/v1/admin/auth/login', { username, password }),
  logout: () => api.post<{ loggedOut: boolean }>('/v1/admin/auth/logout'),
  me: () => api.get<MeResponse>('/v1/admin/auth/me'),

  // ── обзор ──
  dashboard: () => api.get<DashboardResponse>('/v1/admin/dashboard'),
  health: () => api.get<HealthResponse>('/v1/admin/health'),

  // ── пользователи ──
  users: (params: { q?: string; page?: number }) =>
    api.get<{ users: UserRow[] }>(`/v1/admin/users?${new URLSearchParams({
      ...(params.q ? { q: params.q } : {}),
      page: String(params.page ?? 0),
      size: String(PAGE_SIZE)
    })}`),
  user: (id: string) => api.get<UserDetailResponse>(`/v1/admin/users/${id}`),

  // ── устройства ──
  devices: (page: number) =>
    api.get<{ devices: DeviceRow[] }>(`/v1/admin/devices?page=${page}&size=${PAGE_SIZE}`),
  revokeDevice: (tokenId: string) =>
    api.post<{ revoked: boolean; status: string }>(`/v1/admin/devices/${tokenId}/revoke`),

  // ── лицензии ──
  licenses: (params: { status?: string; page?: number }) =>
    api.get<LicensesResponse>(`/v1/admin/licenses?${new URLSearchParams({
      ...(params.status && params.status !== 'ALL' ? { status: params.status } : {}),
      page: String(params.page ?? 0),
      size: String(PAGE_SIZE)
    })}`),
  license: (id: string) => api.get<LicenseRow>(`/v1/admin/licenses/${id}`),
  licenseAction: (id: string, action: 'disable' | 'enable' | 'extend' | 'change-plan', body?: unknown) =>
    api.post<{ changed: boolean }>(`/v1/admin/licenses/${id}/${action}`, body),
  issueLicense: (body: { plan_id: string; one_time: boolean; expires_at?: string; account_ref?: string; metadata?: Record<string, string> }) =>
    api.post<IssueLicenseResponse>('/v1/admin/licenses/issue', body),
  revokeLicense: (licenseId: string, reason: string) =>
    api.post<{ success: boolean }>('/v1/admin/licenses/revoke', { license_id: licenseId, reason }),
  plans: () => api.get<{ plans: PlanRow[] }>('/v1/admin/plans'),

  // ── подписки / провайдеры ──
  subscriptions: (page: number) =>
    api.get<{ orders: OrderRow[] }>(`/v1/admin/subscriptions?page=${page}&size=${PAGE_SIZE}`),
  providers: () => api.get<ProvidersResponse>('/v1/admin/providers'),
  configureProvider: (id: string, body: { enabled?: boolean; priority?: number }) =>
    api.post<{ applied: boolean; version: number }>(`/v1/admin/providers/${id}/configure`, body),

  // ── usage / logs / audit ──
  usage: (days: number) => api.get<UsageResponse>(`/v1/admin/usage?days=${days}`),
  usageCost: (days: number) => api.get<UsageCostResponse>(`/v1/admin/usage/cost?days=${days}`),
  logs: (params: { component?: string; page?: number }) =>
    api.get<{ logs: LogRow[] }>(`/v1/admin/logs?${new URLSearchParams({
      ...(params.component ? { component: params.component } : {}),
      page: String(params.page ?? 0),
      size: String(PAGE_SIZE)
    })}`),
  audit: (params: { action?: string; actor?: string; page?: number }) =>
    api.get<{ events: AuditRow[] }>(`/v1/admin/audit?${new URLSearchParams({
      ...(params.action ? { action: params.action } : {}),
      ...(params.actor ? { actor: params.actor } : {}),
      page: String(params.page ?? 0),
      size: String(PAGE_SIZE)
    })}`),

  // ── операторы / фичи / настройки ──
  admins: () => api.get<{ admins: AdminRow[] }>('/v1/admin/admins'),
  createAdmin: (body: { username: string; password: string; role: string }) =>
    api.post<{ id: string; role: string }>('/v1/admin/admins', body),
  setAdminStatus: (id: string, status: 'ACTIVE' | 'DISABLED') =>
    api.post<{ status: string }>(`/v1/admin/admins/${id}/set-status`, { status }),
  setAdminPassword: (id: string, password: string) =>
    api.post<{ rotated: boolean }>(`/v1/admin/admins/${id}/set-password`, { password }),

  flags: () => api.get<{ flags: FlagRow[] }>('/v1/admin/features'),
  putFlag: (key: string, body: { enabled: boolean; rolloutPercent?: number; description?: string }) =>
    api.put<{ applied: boolean; key: string }>(`/v1/admin/features/${key}`, body),

  settings: {
    system: () => api.get<SettingsResponse<SystemSettings>>('/v1/admin/settings/system'),
    security: () => api.get<SettingsResponse<SecuritySettings>>('/v1/admin/settings/security'),
    ai: () => api.get<SettingsResponse<AiRoutingSettings>>('/v1/admin/settings/ai'),
    limits: () => api.get<SettingsResponse<LimitsSettings>>('/v1/admin/settings/limits'),
    cost: () => api.get<SettingsResponse<CostSettings>>('/v1/admin/settings/cost'),
    put: (section: 'system' | 'security' | 'ai' | 'limits' | 'cost', value: unknown) =>
      api.put<{ applied: boolean; version: number }>(`/v1/admin/settings/${section}`, value)
  }
}
