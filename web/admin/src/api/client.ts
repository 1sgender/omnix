import type { ApiError } from './types'

/**
 * Единая точка доступа к API: Bearer-сессия, единая обработка ошибок,
 * редирект на логин при 401. Все запросы same-origin (/v1/...).
 */

const TOKEN_KEY = 'omnix.admin.token'

export function getToken(): string | null {
  return sessionStorage.getItem(TOKEN_KEY)
}

export function setToken(token: string | null): void {
  if (token === null) sessionStorage.removeItem(TOKEN_KEY)
  else sessionStorage.setItem(TOKEN_KEY, token)
}

export class UnauthorizedError extends Error {}

/** Человекочитаемое сообщение по коду/статусу — без сырых стектрейтов. */
function humanize(status: number, body: Record<string, unknown>): string {
  const err = body['error']
  const code = typeof err === 'string' ? err : typeof err === 'object' && err !== null ? String((err as Record<string, unknown>)['code'] ?? '') : ''
  switch (status) {
    case 400: return code === 'invalid_request' ? 'Некорректный запрос — проверьте значения полей' : (typeof err === 'string' && err) || 'Некорректный запрос'
    case 401:
      if (code === 'invalid_credentials') return 'Неверный логин или пароль'
      return code === 'unauthenticated' ? 'Сессия истекла — войдите заново' : 'Нет доступа'
    case 403: return code === 'account_disabled' ? 'Аккаунт отключён' : 'Недостаточно прав для этого действия'
    case 404: return 'Не найдено'
    case 409: return code === 'username_taken' ? 'Такое имя оператора уже занято' : 'Конфликт данных'
    case 429: return code === 'rate_limited' ? 'Слишком много попыток — подождите немного' : 'Слишком много запросов'
    default: return status >= 500 ? 'Ошибка сервера — попробуйте ещё раз' : `Ошибка ${status}`
  }
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = {}
  const token = getToken()
  if (token) headers['Authorization'] = `Bearer ${token}`
  if (body !== undefined) headers['Content-Type'] = 'application/json'

  let response: Response
  try {
    response = await fetch(path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body)
    })
  } catch {
    throw { status: 0, message: 'Нет соединения с сервером' } satisfies ApiError
  }

  if (response.status === 401 && token) {
    // Сессия протухла: чистим, чтобы приложения уошло на логин.
    setToken(null)
    throw new UnauthorizedError('session expired')
  }

  let parsed: Record<string, unknown> = {}
  try {
    parsed = (await response.json()) as Record<string, unknown>
  } catch {
    /* пустое тело — ок для 200/204 */
  }

  if (!response.ok) {
    throw { status: response.status, message: humanize(response.status, parsed) } satisfies ApiError
  }
  return parsed as T
}

export const api = {
  get: <T>(path: string) => request<T>('GET', path),
  post: <T>(path: string, body?: unknown) => request<T>('POST', path, body),
  put: <T>(path: string, body: unknown) => request<T>('PUT', path, body)
}

export function isApiError(e: unknown): e is ApiError {
  return typeof e === 'object' && e !== null && 'status' in e && 'message' in e
}
