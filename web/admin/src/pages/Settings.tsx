import { useState } from 'react'
import type { ReactNode } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { endpoints } from '../api/endpoints'
import type {
  AiRoutingSettings, CostSettings, LimitsSettings, SecuritySettings,
  SettingsSection, SystemSettings
} from '../api/types'
import {
  Button, ErrorState, Field, Input, SkeletonRows, ToastList
} from '../components/ui'
import { useToasts } from '../hooks/useToasts'
import { isApiError } from '../api/client'
import { useAuth } from '../state/auth'

const SECTIONS: { key: SettingsSection; label: string }[] = [
  { key: 'system', label: 'Система' },
  { key: 'security', label: 'Безопасность' },
  { key: 'ai', label: 'AI-роутинг' },
  { key: 'limits', label: 'Лимиты' },
  { key: 'cost', label: 'Стоимость' }
]

export default function Settings() {
  const { can } = useAuth()
  const [section, setSection] = useState<SettingsSection>('system')

  return (
    <div>
      <div className="toolbar">
        <div className="tabs" role="tablist" aria-label="Секция настроек">
          {SECTIONS.map((s) => (
            <button key={s.key} role="tab" aria-selected={section === s.key} className={`tab ${section === s.key ? 'active' : ''}`} onClick={() => setSection(s.key)}>
              {s.label}
            </button>
          ))}
        </div>
      </div>
      {section === 'system' ? <SystemPanel readOnly={!can('SETTINGS_WRITE')} /> : null}
      {section === 'security' ? <SecurityPanel readOnly={!can('SETTINGS_WRITE')} /> : null}
      {section === 'ai' ? <AiPanel readOnly={!can('SETTINGS_WRITE')} /> : null}
      {section === 'limits' ? <LimitsPanel readOnly={!can('SETTINGS_WRITE')} /> : null}
      {section === 'cost' ? <CostPanel readOnly={!can('SETTINGS_WRITE')} /> : null}
    </div>
  )
}

/* ── Общая обёртка секции ────────────────────────────────────────────────── */

function SettingsPanel({ title, desc, queryKey, queryFn, render, readOnly, note }: {
  title: string
  desc: string
  queryKey: string[]
  queryFn: () => Promise<{ value: unknown }>
  render: (value: never, save: (v: unknown) => void, saving: boolean) => ReactNode
  readOnly: boolean
  note?: string
}) {
  const { toasts, ok, err } = useToasts()
  const queryClient = useQueryClient()
  const query = useQuery({ queryKey, queryFn })
  const section = queryKey[1] as SettingsSection

  const save = useMutation({
    mutationFn: (value: unknown) => endpoints.settings.put(section, value),
    onSuccess: () => {
      ok('Настройки применены')
      void queryClient.invalidateQueries({ queryKey })
    },
    onError: (e) => err(isApiError(e) ? e.message : 'Не удалось сохранить')
  })

  if (query.isLoading) return <SkeletonRows rows={4} cols={2} />
  if (query.isError) return <ErrorState error={query.error} retry={() => void query.refetch()} />
  if (!query.data) return null

  return (
    <div className="card" style={{ maxWidth: 620 }}>
      <h2 style={{ fontSize: 15.5 }}>{title}</h2>
      <p style={{ fontSize: 12.5, color: 'var(--tx-3)', margin: '3px 0 16px' }}>{desc}</p>
      {note ? <div className="form-error" style={{ background: 'var(--warn-bg)', borderColor: 'rgba(208,176,112,.25)', color: 'var(--warn)' }}>{note}</div> : null}
      {render(query.data.value as never, (v) => { if (!readOnly) save.mutate(v) }, save.isPending)}
      <ToastList items={toasts} />
    </div>
  )
}

function NumberField({ label, value, onChange, min, max, hint, id, disabled }: {
  label: string; value: number; onChange: (n: number) => void
  min?: number; max?: number; hint?: string; id: string; disabled?: boolean
}) {
  return (
    <Field label={label} htmlFor={id} hint={hint}>
      <Input id={id} type="number" min={min} max={max} value={value} disabled={disabled}
        onChange={(e) => { const n = Number(e.target.value); if (!Number.isNaN(n)) onChange(n) }} />
    </Field>
  )
}

function CheckRow({ label, checked, onChange, hint, disabled }: {
  label: string; checked: boolean; onChange: (v: boolean) => void; hint?: string; disabled?: boolean
}) {
  return (
    <div className="field" style={{ flexDirection: 'row', alignItems: 'center', gap: 12 }}>
      <span className="switch">
        <input type="checkbox" checked={checked} disabled={disabled} onChange={(e) => onChange(e.target.checked)} />
        <span className="track"><span className="thumb" /></span>
      </span>
      <div>
        <div style={{ fontWeight: 550, fontSize: 13.5 }}>{label}</div>
        {hint ? <div style={{ fontSize: 12, color: 'var(--tx-3)' }}>{hint}</div> : null}
      </div>
    </div>
  )
}

/* ── Секции ──────────────────────────────────────────────────────────────── */

function SystemPanel({ readOnly }: { readOnly: boolean }) {
  const [draft, setDraft] = useState<SystemSettings | null>(null)
  return (
    <SettingsPanel
      title="Система" desc="Режим работы сервера"
      queryKey={['settings', 'system']} queryFn={endpoints.settings.system} readOnly={readOnly}
      render={(value: SystemSettings, save, saving) => {
        const current = draft ?? value
        return (
          <form onSubmit={(e) => { e.preventDefault(); save(current) }}>
            <CheckRow label="Режим обслуживания" hint="Сервер отвечает ошибкой занятости" checked={current.maintenanceMode} disabled={readOnly}
              onChange={(v) => setDraft({ ...current, maintenanceMode: v })} />
            <CheckRow label="Регистрация открыта" checked={current.registrationOpen} disabled={readOnly}
              onChange={(v) => setDraft({ ...current, registrationOpen: v })} />
            <Field label="План по умолчанию" htmlFor="set-default-plan">
              <Input id="set-default-plan" value={current.defaultPlanId} disabled={readOnly}
                onChange={(e) => setDraft({ ...current, defaultPlanId: e.target.value })} />
            </Field>
            <div className="modal-actions" style={{ justifyContent: 'flex-start' }}>
              <Button type="submit" variant="primary" loading={saving} disabled={readOnly || draft === null}>Сохранить</Button>
              {draft !== null ? <Button type="button" onClick={() => setDraft(null)}>Отменить</Button> : null}
            </div>
          </form>
        )
      }}
    />
  )
}

function SecurityPanel({ readOnly }: { readOnly: boolean }) {
  const [draft, setDraft] = useState<SecuritySettings | null>(null)
  const [error, setError] = useState<string | null>(null)
  return (
    <SettingsPanel
      title="Безопасность" desc="Сессии и защита от брутфорса"
      queryKey={['settings', 'security']} queryFn={endpoints.settings.security} readOnly={readOnly}
      render={(value: SecuritySettings, save, saving) => {
        const current = draft ?? value
        return (
          <form
            onSubmit={(e) => {
              e.preventDefault()
              setError(null)
              if (current.sessionTtlMinutes < 5 || current.sessionTtlMinutes > 720) { setError('TTL сессии — 5..720 минут'); return }
              if (current.loginMaxAttempts < 1 || current.loginMaxAttempts > 100) { setError('Попыток логина — 1..100'); return }
              if (current.loginWindowMinutes < 1 || current.loginWindowMinutes > 1440) { setError('Окно логина — 1..1440 минут'); return }
              if (current.minPasswordLength < 12) { setError('Минимальная длина пароля — не ниже 12'); return }
              save(current)
            }}
          >
            {error ? <div className="form-error" role="alert">{error}</div> : null}
            <NumberField id="set-ttl" label="TTL сессии, мин" hint="5–720" value={current.sessionTtlMinutes} min={5} max={720} disabled={readOnly}
              onChange={(n) => setDraft({ ...current, sessionTtlMinutes: n })} />
            <NumberField id="set-attempts" label="Попыток логина" hint="1–100, до блокировки" value={current.loginMaxAttempts} min={1} max={100} disabled={readOnly}
              onChange={(n) => setDraft({ ...current, loginMaxAttempts: n })} />
            <NumberField id="set-window" label="Окно подсчёта, мин" hint="1–1440" value={current.loginWindowMinutes} min={1} max={1440} disabled={readOnly}
              onChange={(n) => setDraft({ ...current, loginWindowMinutes: n })} />
            <NumberField id="set-passlen" label="Мин. длина пароля" hint="не ниже 12" value={current.minPasswordLength} min={12} disabled={readOnly}
              onChange={(n) => setDraft({ ...current, minPasswordLength: n })} />
            <div className="modal-actions" style={{ justifyContent: 'flex-start' }}>
              <Button type="submit" variant="primary" loading={saving} disabled={readOnly || draft === null}>Сохранить</Button>
              {draft !== null ? <Button type="button" onClick={() => { setDraft(null); setError(null) }}>Отменить</Button> : null}
            </div>
          </form>
        )
      }}
    />
  )
}

function AiPanel({ readOnly }: { readOnly: boolean }) {
  const [draft, setDraft] = useState<AiRoutingSettings | null>(null)
  return (
    <SettingsPanel
      title="AI-роутинг" desc="Локальный-первый режим и эскалация в облако"
      queryKey={['settings', 'ai']} queryFn={endpoints.settings.ai} readOnly={readOnly}
      note="Приоритеты и вкл/выкл провайдеров применяются немедленно; таймауты и ретраи — только после рестарта сервера."
      render={(value: AiRoutingSettings, save, saving) => {
        const current = draft ?? value
        const providers = Object.keys(current.providers)
        return (
          <form onSubmit={(e) => { e.preventDefault(); save(current) }}>
            <CheckRow label="Локальный режим первым" hint="Простые запросы уходят на устройство" checked={current.localFirstEnabled} disabled={readOnly}
              onChange={(v) => setDraft({ ...current, localFirstEnabled: v })} />
            <CheckRow label="Эскалация в облако" checked={current.cloudEscalationEnabled} disabled={readOnly}
              onChange={(v) => setDraft({ ...current, cloudEscalationEnabled: v })} />
            {providers.length > 0 ? (
              <>
                <h3 className="section-title" style={{ marginTop: 8 }}>Провайдеры</h3>
                {providers.map((id) => {
                  const override = current.providers[id]
                  return (
                    <div key={id} style={{ display: 'flex', gap: 10, alignItems: 'flex-end', marginBottom: 10 }}>
                      <div style={{ flex: 1 }}>
                        <Field label={`${id}: приоритет`} htmlFor={`ai-prio-${id}`} hint="1–1000, пусто = по умолчанию">
                          <Input
                            id={`ai-prio-${id}`} type="number" min={1} max={1000} disabled={readOnly}
                            value={override?.priority ?? ''}
                            placeholder="по умолчанию"
                            onChange={(e) => {
                              const n = e.target.value === '' ? null : Number(e.target.value)
                              setDraft({ ...current, providers: { ...current.providers, [id]: { ...override, priority: n } } })
                            }}
                          />
                        </Field>
                      </div>
                      <div style={{ paddingBottom: 14 }}>
                        <CheckRow label="вкл" checked={override?.enabled ?? true} disabled={readOnly}
                          onChange={(v) => setDraft({ ...current, providers: { ...current.providers, [id]: { ...override, enabled: v } } })} />
                      </div>
                    </div>
                  )
                })}
              </>
            ) : null}
            <div className="modal-actions" style={{ justifyContent: 'flex-start' }}>
              <Button type="submit" variant="primary" loading={saving} disabled={readOnly || draft === null}>Сохранить</Button>
              {draft !== null ? <Button type="button" onClick={() => setDraft(null)}>Отменить</Button> : null}
            </div>
          </form>
        )
      }}
    />
  )
}

function LimitsPanel({ readOnly }: { readOnly: boolean }) {
  const [draft, setDraft] = useState<LimitsSettings | null>(null)
  return (
    <SettingsPanel
      title="Лимиты" desc="Дневные квоты клиента (UTC-сутки)"
      queryKey={['settings', 'limits']} queryFn={endpoints.settings.limits} readOnly={readOnly}
      render={(value: LimitsSettings, save, saving) => {
        const current = draft ?? value
        return (
          <form onSubmit={(e) => { e.preventDefault(); save(current) }}>
            <NumberField id="set-dayreq" label="Запросов в день" value={current.perDayRequests} min={0} disabled={readOnly}
              onChange={(n) => setDraft({ ...current, perDayRequests: n })} />
            <NumberField id="set-daytok" label="Токенов в день" value={current.perDayTokens} min={0} disabled={readOnly}
              onChange={(n) => setDraft({ ...current, perDayTokens: n })} />
            <NumberField id="set-daycost" label="Стоимость в день, $" value={current.perDayCostUsd} min={0} disabled={readOnly}
              onChange={(n) => setDraft({ ...current, perDayCostUsd: n })} />
            <div className="modal-actions" style={{ justifyContent: 'flex-start' }}>
              <Button type="submit" variant="primary" loading={saving} disabled={readOnly || draft === null}>Сохранить</Button>
              {draft !== null ? <Button type="button" onClick={() => setDraft(null)}>Отменить</Button> : null}
            </div>
          </form>
        )
      }}
    />
  )
}

function CostPanel({ readOnly }: { readOnly: boolean }) {
  const [draft, setDraft] = useState<CostSettings | null>(null)
  return (
    <SettingsPanel
      title="Стоимость" desc="Тарифы провайдеров для расчёта затрат (USD за 1M токенов)"
      queryKey={['settings', 'cost']} queryFn={endpoints.settings.cost} readOnly={readOnly}
      render={(value: CostSettings, save, saving) => {
        const current = draft ?? value
        const providers = ['GROQ', 'GEMINI', 'OPENROUTER']
        const get = (id: string) => current.providers[id] ?? { usdPerMillionInput: null, usdPerMillionOutput: null }
        return (
          <form onSubmit={(e) => { e.preventDefault(); save(current) }}>
            {providers.map((id) => (
              <div key={id} style={{ display: 'flex', gap: 10 }}>
                <div style={{ flex: 1 }}>
                  <Field label={`${id}: $/1M in`} htmlFor={`cost-in-${id}`}>
                    <Input id={`cost-in-${id}`} type="number" min={0} step="0.01" disabled={readOnly}
                      value={get(id).usdPerMillionInput ?? ''} placeholder="не задан"
                      onChange={(e) => {
                        const n = e.target.value === '' ? null : Number(e.target.value)
                        setDraft({ ...current, providers: { ...current.providers, [id]: { ...get(id), usdPerMillionInput: n } } })
                      }} />
                  </Field>
                </div>
                <div style={{ flex: 1 }}>
                  <Field label={`$/1M out`} htmlFor={`cost-out-${id}`}>
                    <Input id={`cost-out-${id}`} type="number" min={0} step="0.01" disabled={readOnly}
                      value={get(id).usdPerMillionOutput ?? ''} placeholder="не задан"
                      onChange={(e) => {
                        const n = e.target.value === '' ? null : Number(e.target.value)
                        setDraft({ ...current, providers: { ...current.providers, [id]: { ...get(id), usdPerMillionOutput: n } } })
                      }} />
                  </Field>
                </div>
              </div>
            ))}
            <div className="modal-actions" style={{ justifyContent: 'flex-start' }}>
              <Button type="submit" variant="primary" loading={saving} disabled={readOnly || draft === null}>Сохранить</Button>
              {draft !== null ? <Button type="button" onClick={() => setDraft(null)}>Отменить</Button> : null}
            </div>
          </form>
        )
      }}
    />
  )
}
