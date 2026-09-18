import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { endpoints } from '../api/endpoints'
import type { ProviderRow } from '../api/types'
import {
  Button, ErrorState, Field, Input, Modal, SkeletonRows, StatusBadge, ToastList
} from '../components/ui'
import { useToasts } from '../hooks/useToasts'
import { isApiError } from '../api/client'
import { useAuth } from '../state/auth'

export default function Providers() {
  const { can } = useAuth()
  const { toasts, ok, err } = useToasts()
  const queryClient = useQueryClient()
  const [editing, setEditing] = useState<ProviderRow | null>(null)

  const query = useQuery({ queryKey: ['providers'], queryFn: endpoints.providers, refetchInterval: 30_000 })

  const save = useMutation({
    mutationFn: (args: { id: string; body: { enabled?: boolean; priority?: number } }) =>
      endpoints.configureProvider(args.id, args.body),
    onSuccess: () => {
      ok('Настройки провайдера применены')
      setEditing(null)
      void queryClient.invalidateQueries({ queryKey: ['providers'] })
    },
    onError: (e) => err(isApiError(e) ? e.message : 'Не удалось применить')
  })

  if (query.isLoading) return <SkeletonRows rows={3} cols={4} />
  if (query.isError) return <ErrorState error={query.error} retry={() => void query.refetch()} />
  if (!query.data) return null

  return (
    <div>
      <div className="grid" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))' }}>
        {query.data.providers.map((p) => (
          <div key={p.id} className="card hoverable">
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
              <strong style={{ fontSize: 15 }}>{p.id}</strong>
              <StatusBadge status={p.status} />
            </div>
            <dl className="kv">
              <dt>Circuit</dt><dd>{p.circuit}</dd>
              <dt>API-ключ</dt><dd>{p.apiKey === '••••CONFIGURED' ? 'настроен' : <span className="dim">—</span>}</dd>
              <dt>Override вкл.</dt><dd>{p.enabledOverride === null ? <span className="dim">по умолчанию</span> : String(p.enabledOverride)}</dd>
              <dt>Приоритет</dt><dd>{p.priorityOverride ?? <span className="dim">по умолчанию</span>}</dd>
              <dt>Запросы</dt><dd className="dim">см. «Использование»</dd>
              <dt>Задержки</dt><dd className="dim">не измеряются</dd>
            </dl>
            {can('PROVIDERS_CONFIGURE') ? (
              <div style={{ marginTop: 14 }}>
                <Button size="sm" onClick={() => setEditing(p)}>Настроить роутинг…</Button>
              </div>
            ) : null}
          </div>
        ))}
      </div>

      <p style={{ marginTop: 18, fontSize: 12.5, color: 'var(--tx-3)' }}>
        Приоритет и вкл/выкл применяются немедленно; таймауты и ретраи требуют рестарта сервера.
      </p>

      {editing ? <ConfigureModal provider={editing} onClose={() => setEditing(null)} onSave={(body) => save.mutate({ id: editing.id, body })} loading={save.isPending} /> : null}
      <ToastList items={toasts} />
    </div>
  )
}

function ConfigureModal({ provider, onClose, onSave, loading }: {
  provider: ProviderRow
  onClose: () => void
  onSave: (body: { enabled?: boolean; priority?: number }) => void
  loading: boolean
}) {
  const [enabled, setEnabled] = useState<boolean>(provider.enabledOverride ?? true)
  const [priority, setPriority] = useState<string>(provider.priorityOverride === null ? '' : String(provider.priorityOverride))
  const [error, setError] = useState<string | null>(null)

  return (
    <Modal title={`Роутинг: ${provider.id}`} sub="Применяется мгновенно (Validate → Persist → Audit → Apply)" onClose={onClose}>
      <div
        className="card"
        style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16, padding: 14 }}
        onClick={() => setEnabled((v) => !v)}
        role="checkbox"
        aria-checked={enabled}
        tabIndex={0}
        onKeyDown={(e) => { if (e.key === ' ' || e.key === 'Enter') { e.preventDefault(); setEnabled((v) => !v) } }}
      >
        <span className="switch">
          <input type="checkbox" checked={enabled} onChange={() => undefined} tabIndex={-1} aria-hidden />
          <span className="track"><span className="thumb" /></span>
        </span>
        <div>
          <div style={{ fontWeight: 550 }}>Провайдер включён</div>
          <div style={{ fontSize: 12, color: 'var(--tx-3)' }}>Отключённый провайдер не получает запросы</div>
        </div>
      </div>

      <Field label="Приоритет" htmlFor="prov-priority" hint="1–1000, меньше — выше в роутинге. Пусто = по умолчанию">
        <Input
          id="prov-priority"
          type="number" min={1} max={1000}
          value={priority}
          onChange={(e) => setPriority(e.target.value)}
          placeholder="по умолчанию"
        />
      </Field>
      {error ? <div className="form-error" role="alert">{error}</div> : null}

      <div className="modal-actions">
        <Button onClick={onClose}>Отмена</Button>
        <Button
          variant="primary" loading={loading}
          onClick={() => {
            setError(null)
            const body: { enabled?: boolean; priority?: number } = { enabled }
            if (priority.trim() !== '') {
              const n = Number(priority)
              if (!Number.isInteger(n) || n < 1 || n > 1000) {
                setError('Приоритет — целое число от 1 до 1000')
                return
              }
              body.priority = n
            }
            onSave(body)
          }}
        >
          Применить
        </Button>
      </div>
    </Modal>
  )
}
