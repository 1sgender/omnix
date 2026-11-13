/**
 * Экран «Наушники»: реестр пар OMNIX Clip по BT-адресу и каталог моделей
 * (сервер: ClipAdminHttpHandler, /v1/admin/earbuds). Адреса приходят
 * маскированными — полный MAC панель не получает.
 */
import { api } from './client'

export type EarbudStatus = 'PROVISIONED' | 'ACTIVE' | 'RELEASED' | 'REVOKED'
export type EarbudModelStatus = 'ACTIVE' | 'HIDDEN'

export interface EarbudRow {
  id: string
  clipSerial: string
  /** Маскированный адрес: AA:••:••:••:EE:FF. */
  btAddress: string
  identityMode: 'MAC' | 'KEY'
  status: EarbudStatus
  ownerAccountId: string | null
  modelId: string | null
  modelName: string | null
  planId: string | null
  btName: string | null
  statusReason: string | null
  boundAt: string | null
  lastSeenAt: string | null
  createdAt: string
  /** Когда пара выдала пробный Pro; null — ещё не выдавала. */
  trialGrantedAt: string | null
}

export interface EarbudModel {
  id: string
  displayName: string
  colorName: string
  colorHex: string
  imageUrl: string | null
  btNamePatterns: string[]
  status: EarbudModelStatus
  createdAt: string
  updatedAt: string
}

export interface RegisterEarbudBody {
  clipSerial: string
  btAddress: string
  modelId?: string
  planId?: string
  btName?: string
}

export interface BulkResult {
  registered: number
  failed: number
  results: { line: number; clipSerial: string; ok: boolean; btAddress?: string; error?: string }[]
}

export interface SaveModelBody {
  id?: string
  displayName: string
  colorName: string
  colorHex: string
  imageUrl?: string
  btNamePatterns: string[]
  status: EarbudModelStatus
}

const BASE = '/v1/admin/earbuds'

export const earbudsApi = {
  list: (params: { status?: EarbudStatus; q?: string }) =>
    api.get<{ earbuds: EarbudRow[] }>(`${BASE}?${new URLSearchParams({
      ...(params.status ? { status: params.status } : {}),
      ...(params.q ? { q: params.q } : {})
    })}`),
  register: (body: RegisterEarbudBody) => api.post<{ earbud: EarbudRow }>(BASE, body),
  registerBulk: (lines: string, modelId?: string, planId?: string) =>
    api.post<BulkResult>(`${BASE}/bulk`, { lines, modelId, planId }),
  unbind: (id: string, reason: string) => api.post<{ earbud: EarbudRow }>(`${BASE}/${id}/unbind`, { reason }),
  revoke: (id: string, reason: string) => api.post<{ earbud: EarbudRow }>(`${BASE}/${id}/revoke`, { reason }),
  resetTrial: (id: string, reason: string) => api.post<{ earbud: EarbudRow }>(`${BASE}/${id}/reset-trial`, { reason }),
  models: () => api.get<{ models: EarbudModel[] }>(`${BASE}/models`),
  createModel: (body: SaveModelBody) => api.post<{ model: EarbudModel }>(`${BASE}/models`, body),
  updateModel: (id: string, body: SaveModelBody) => api.put<{ model: EarbudModel }>(`${BASE}/models/${id}`, body)
}

/** Человекочитаемые причины отказа регистрации (коды сервера). */
export function registerErrorText(code: string | undefined): string {
  switch (code) {
    case 'duplicate_serial': return 'Такой серийник уже есть'
    case 'duplicate_address': return 'Пара с таким адресом уже зарегистрирована'
    case 'invalid_bt_address': return 'Неверный BT-адрес'
    case 'invalid_serial': return 'Неверный серийник'
    case 'invalid_bt_name': return 'Неверное имя BT'
    case 'invalid_line': return 'Строка не в формате «серийник;MAC»'
    case 'unknown_model': return 'Неизвестная модель'
    case 'unknown_plan': return 'Неизвестный тариф'
    default: return code ?? 'Ошибка'
  }
}
