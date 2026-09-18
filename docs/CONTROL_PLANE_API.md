# OMNIX Control Plane — карта API (аудит 2026-09-18)

Источник истины: `server/src/main/kotlin/com/omnix/server/` (Kotlin, без фреймворков,
kotlinx.serialization, Postgres, миграции V001–V009).

## Auth
- `POST /v1/admin/auth/login` {username, password} → 200 {token, role, actor, expiresAt} |
  401 invalid_credentials | 403 account_disabled | 429 rate_limited (5/мин И 5/сутки на username+ip)
- `POST /v1/admin/auth/logout` → {loggedOut}
- `GET  /v1/admin/auth/me` → {actor, role, permissions[]} — RBAC для UI
- Сессия: Bearer token, TTL 30 мин, **sliding** (renewIfDue при каждом запросе). Хранить в sessionStorage.
- Роли: SUPER_ADMIN > ADMIN > SUPPORT > VIEWER. Права: DASHBOARD_READ, USERS_READ,
  DEVICES_READ, DEVICES_REVOKE, LICENSES_READ/WRITE, SUBSCRIPTIONS_READ, PROVIDERS_READ/CONFIGURE,
  USAGE_READ, LOGS_READ, AUDIT_READ, SETTINGS_READ/WRITE, ADMINS_MANAGE, FEATURES_READ/WRITE.

## Данные (все ответы {…}, Cache-Control: no-store)
- `GET dashboard` → {users.total, licenses.{active,issued}, devices.tokensActive,
  requests.{today,errorsToday,tokensToday}, billing.ordersPending, providers.{id:{status,circuit}}, localExecutionRate:"NOT COLLECTED"}
- `GET health` → {api, database, aiGateway, authentication, licenseService, providers{…{status,circuit,permanentReason?}}}
- `GET users?q&page&size` → {users:[{id, externalRef, status, createdAt, licenses, activeLicenses, lastActiveAt}]}
  - q — поиск по externalRef; page с 0, size ≤? (default 50); totals НЕ возвращаются → UI: «Load more»
- `GET users/{id}` → {account{…}, licenses[], devices[], usage{requests,errors,inputTokens,outputTokens,promptChars}}
- `GET devices?page&size` → {devices:[{tokenId, accountId, status, issuedAt, lastUsedAt, expiresAt, deviceBinding, model/firmware/battery:"NOT COLLECTED"}]}
- `POST devices/{id}/revoke` → {revoked, status:"REVOKED"} (destructive, подтверждение)
- `GET licenses?status&page&size` → {statusFilter, licenses:[{id, status, billingStatus, codeHint, planId,
  accountId, issuedAt, startsAt, expiresAt, redeemedAt}]}
  - LicenseStatus: ISSUED|ACTIVE|EXPIRED|REVOKED|DISABLED; неверный status → 400
  - BillingStatus: GRANTED|PENDING|PAID|PAST_DUE|CANCELED|REFUNDED
- `GET licenses/{id}` → license row
- `POST licenses/{id}/disable|enable` → {changed}; `POST licenses/{id}/extend` {days 1..3650} → {changed};
  `POST licenses/{id}/change-plan` {planId} → {changed} | 400 plan not found
- `GET subscriptions?page&size` → {orders:[{id, accountId, planId, provider, status, amountMinor, currency, createdAt, paidAt}]}
- `GET providers` → {providers:[{id, status, circuit, enabledOverride, priorityOverride, apiKey:"••••CONFIGURED",
  requests:"SEE /v1/admin/usage", latency*:"NOT MEASURED"}], runtimeNote{…}} — секреты НИКОГДА не возвращаются
  - ProviderId: GROQ|GEMINI|OPENROUTER
- `POST providers/{id}/configure` {enabled?, priority?} → {applied, version} (мгновенно)
- `GET usage?days(1..90)` → {periodDays, requests, cloudRequests, localRequests:"NOT COLLECTED", errors,
  inputTokens, outputTokens, byProvider[{provider,requests,errors,inputTokens,outputTokens}]}
- `GET usage/cost?days` → {periodDays, totalUsd, knownUsd, unknownProviders[], lines[{provider, inputTokens,
  outputTokens, usdPerMillionInput, usdPerMillionOutput, costUsd, formula}]}
- `GET logs?component&page&size` → {logs:[{time, component, type, actor, result, latencyMs, requestId}]}
- `GET audit?action&actor&page&size` → {events:[{time, actor, action, entityType, entityId, oldValue, newValue, remoteAddress}]}
- `GET settings/{system|security|ai|limits|cost}` → {section, version?, value{…}}
  - system{maintenanceMode, registrationOpen, defaultPlanId}; security{sessionTtlMinutes 5..720,
    loginMaxAttempts, loginWindowMinutes, minPasswordLength}; ai{localFirstEnabled, cloudEscalationEnabled,
    providers{GROQ|GEMINI|OPENROUTER:{enabled?, priority? 1..1000}}}; limits{perDayRequests, perDayTokens,
    perDayCostUsd}; cost{providers{…{input?, output? USD за 1M}}}
- `PUT settings/{section}` (полное тело секции) → {applied, version} | 400 invalid
- `GET admins` → {admins:[{id, username, role, status, createdAt}]} (ADMINS_MANAGE)
- `POST admins` {username, password≥12, role} → {id, role} | 400 invalid | 409 username_taken
- `POST admins/{id}/set-status` {status: ACTIVE|DISABLED} | `POST admins/{id}/set-password` {password}
- `GET features` → {flags:[{key, enabled, rolloutPercent, description}]}
- `PUT features/{key}` {enabled, rolloutPercent?, description?} → {applied, key}
- `POST /v1/admin/licenses/issue` {plan_id, one_time, expires_at?, account_ref?, metadata?, request_id?} →
  201 {success, license_id, code, status, plan_id, issued_at, expires_at, request_id} — код показывается ОДИН раз
- `POST /v1/admin/licenses/revoke` {license_id} (по static-токену)

## ДОБАВЛЕНО этим PR (backend, минимально)
1. `GET /v1/admin/plans` → {plans:[{id, displayName, durationDays, amountMinor, currency, active}]} —
   из таблицы billing_plans (для формы выдачи лицензии; раньше планов в API не было вообще).
2. `licenses/issue|revoke` теперь принимают и админ-сессию (RBAC LICENSES_WRITE) — раньше только static-токен,
   из панели выдать лицензию было невозможно.
3. `/admin/*` — статика SPA (было: server-rendered AdminUiHandler под /v1/admin/ui/…, удалён).
   Старые пути /v1/admin/ui/** → 301 на /admin.

## Честные «NOT COLLECTED/MEASURED» (показывать как «не собирается», НЕ выдумывать)
localExecutionRate, localRequests, model/firmware/battery у устройств, latency p50/p95 у провайдеров,
promptChars. Тotals для пагинации нет → паттерн «Load more».

## Схема БД (миграции V001–V009)
accounts(id, external_ref, status, created_at, last_active_at) · licenses(id, code_hash, code_hint, plan_id,
account_id?, status, billing_status, issued/starts/expires/redeemed_at, metadata) · api_tokens(токены
устройств, status, last_used_at, device_binding) · admin_accounts / admin_sessions / admin_audit_log /
admin_settings / feature_flags · billing_orders / billing_events · billing_plans(id, product_id, display_name,
duration_days, amount_minor, currency, active) · ai_usage_records · clip_devices · license_rate_limit_events.
