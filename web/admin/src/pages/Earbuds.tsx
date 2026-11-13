import { useState } from 'react'
import type { FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Plus, ListPlus } from 'lucide-react'
import {
  Button, DataTable, EmptyState, ErrorState, Field, Input, Modal, Select,
  SkeletonRows, StatusBadge, ToastList, formatDateTime
} from '../components/ui'
import { useToasts } from '../hooks/useToasts'
import { isApiError } from '../api/client'
import { useAuth } from '../state/auth'
import { earbudsApi, registerErrorText } from '../api/earbuds'
import type { BulkResult, EarbudModel, EarbudModelStatus, EarbudRow, EarbudStatus, SaveModelBody } from '../api/earbuds'

/**
 * «Наушники»: реестр готовых пар OMNIX Clip (временная схема по BT-адресу
 * до прошивки с ключом) и каталог моделей/цветов. Все изменения сервер
 * пишет в аудит; права проверяет сервер (DEVICES_READ / DEVICES_REVOKE).
 */

const STATUS_TABS: (EarbudStatus | 'ALL')[] = ['ALL', 'PROVISIONED', 'ACTIVE', 'RELEASED', 'REVOKED']
const STATUS_LABELS: Record<EarbudStatus | 'ALL', string> = {
  ALL: 'Все', PROVISIONED: 'На складе', ACTIVE: 'Привязаны', RELEASED: 'Отвязаны владельцем', REVOKED: 'Заблокированы'
}

function errorText(e: unknown, fallback: string): string {
  if (!isApiError(e)) return fallback
  if (e.status === 409) return 'Пара с таким серийником или адресом уже есть (или действие уже выполнено)'
  return e.message
}

export default function Earbuds() {
  const [section, setSection] = useState<'pairs' | 'models'>('pairs')
  return (
    <div>
      <div className="toolbar">
        <div className="tabs" role="tablist" aria-label="Раздел">
          <button role="tab" aria-selected={section === 'pairs'} className={`tab ${section === 'pairs' ? 'active' : ''}`} onClick={() => setSection('pairs')}>
            Пары
          </button>
          <button role="tab" aria-selected={section === 'models'} className={`tab ${section === 'models' ? 'active' : ''}`} onClick={() => setSection('models')}>
            Модели
          </button>
        </div>
      </div>
      {section === 'pairs' ? <Pairs /> : <Models />}
    </div>
  )
}

/* ── Пары ─────────────────────────────────────────────────────────────────── */

function Pairs() {
  const { can } = useAuth()
  const { toasts, ok, err } = useToasts()
  const queryClient = useQueryClient()
  const [status, setStatus] = useState<EarbudStatus | 'ALL'>('ALL')
  const [q, setQ] = useState('')
  const [registerOpen, setRegisterOpen] = useState(false)
  const [bulkOpen, setBulkOpen] = useState(false)
  const [action, setAction] = useState<{ row: EarbudRow; kind: 'unbind' | 'revoke' } | null>(null)

  const list = useQuery({
    queryKey: ['earbuds', status, q],
    queryFn: () => earbudsApi.list({ status: status === 'ALL' ? undefined : status, q: q.trim() || undefined })
  })
  const models = useQuery({ queryKey: ['earbud-models'], queryFn: earbudsApi.models })
  const refresh = () => void queryClient.invalidateQueries({ queryKey: ['earbuds'] })
  const canWrite = can('DEVICES_REVOKE')

  return (
    <div>
      <p style={{ color: 'var(--tx-3)', fontSize: 12.5, marginBottom: 14 }}>
        Пара опознаётся по Bluetooth-адресу — временная мера до прошивки с ключом (адрес подделывается на root-телефоне).
        Адреса показаны маскированными.
      </p>
      <div className="toolbar">
        <div className="tabs" role="tablist" aria-label="Фильтр по статусу">
          {STATUS_TABS.map((tab) => (
            <button key={tab} role="tab" aria-selected={status === tab} className={`tab ${status === tab ? 'active' : ''}`} onClick={() => setStatus(tab)}>
              {STATUS_LABELS[tab]}
            </button>
          ))}
        </div>
        <div className="grow">
          <Input placeholder="Серийник, MAC или ID аккаунта" value={q} onChange={(e) => setQ(e.target.value)} aria-label="Поиск" />
        </div>
        <div style={{ flex: 1 }} />
        {canWrite ? (
          <>
            <Button onClick={() => setBulkOpen(true)}><ListPlus size={15} /> Списком</Button>
            <Button variant="primary" onClick={() => setRegisterOpen(true)}><Plus size={15} /> Зарегистрировать пару</Button>
          </>
        ) : null}
      </div>

      {list.isLoading ? <SkeletonRows rows={7} cols={7} /> : null}
      {list.isError ? <ErrorState error={list.error} retry={() => void list.refetch()} /> : null}
      {list.data ? (
        list.data.earbuds.length === 0 ? (
          <div className="table-wrap"><EmptyState title="Пар нет" desc="Зарегистрируйте пару при упаковке" /></div>
        ) : (
          <DataTable headers={['Серийник', 'Адрес', 'Модель', 'Статус', 'Владелец', 'Привязан', 'Последний раз', '']}>
            {list.data.earbuds.map((row) => (
              <tr key={row.id}>
                <td className="mono">{row.clipSerial}</td>
                <td className="mono dim">{row.btAddress}</td>
                <td className="dim">{row.modelName ?? '—'}</td>
                <td title={row.statusReason ?? undefined}><StatusBadge status={row.status} /></td>
                <td className="mono dim">{row.ownerAccountId ? `${row.ownerAccountId.slice(0, 8)}…` : '—'}</td>
                <td className="dim">{formatDateTime(row.boundAt)}</td>
                <td className="dim">{formatDateTime(row.lastSeenAt)}</td>
                <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                  {canWrite && row.status !== 'PROVISIONED' ? (
                    <Button size="sm" onClick={() => setAction({ row, kind: 'unbind' })}>
                      {row.status === 'REVOKED' ? 'Разблокировать' : 'Отвязать'}
                    </Button>
                  ) : null}
                  {canWrite && row.status !== 'REVOKED' ? (
                    <Button size="sm" variant="danger" style={{ marginLeft: 6 }} onClick={() => setAction({ row, kind: 'revoke' })}>
                      Заблокировать
                    </Button>
                  ) : null}
                </td>
              </tr>
            ))}
          </DataTable>
        )
      ) : null}

      {registerOpen ? (
        <RegisterModal
          models={models.data?.models ?? []}
          onClose={() => setRegisterOpen(false)}
          onDone={(serial) => { ok(`Пара ${serial} зарегистрирована`); setRegisterOpen(false); refresh() }}
        />
      ) : null}
      {bulkOpen ? (
        <BulkModal models={models.data?.models ?? []} onClose={() => { setBulkOpen(false); refresh() }} />
      ) : null}
      {action ? (
        <ActionModal
          row={action.row}
          kind={action.kind}
          onClose={() => setAction(null)}
          onDone={(message) => { ok(message); setAction(null); refresh() }}
          onError={(message) => err(message)}
        />
      ) : null}
      <ToastList items={toasts} />
    </div>
  )
}

function ModelSelect({ models, value, onChange }: { models: EarbudModel[]; value: string; onChange: (v: string) => void }) {
  return (
    <Select value={value} onChange={(e) => onChange(e.target.value)}>
      <option value="">— без модели —</option>
      {models.map((m) => (
        <option key={m.id} value={m.id}>
          {m.displayName} · {m.colorName}{m.status === 'HIDDEN' ? ' (скрыта)' : ''}
        </option>
      ))}
    </Select>
  )
}

function RegisterModal({ models, onClose, onDone }: { models: EarbudModel[]; onClose: () => void; onDone: (serial: string) => void }) {
  const [serial, setSerial] = useState('')
  const [address, setAddress] = useState('')
  const [modelId, setModelId] = useState('')
  const [planId, setPlanId] = useState('')
  const [btName, setBtName] = useState('')
  const [error, setError] = useState<string | null>(null)
  const mutation = useMutation({
    mutationFn: () => earbudsApi.register({
      clipSerial: serial.trim(),
      btAddress: address.trim(),
      modelId: modelId || undefined,
      planId: planId.trim() || undefined,
      btName: btName.trim() || undefined
    }),
    onSuccess: (res) => onDone(res.earbud.clipSerial),
    onError: (e) => setError(errorText(e, 'Не удалось зарегистрировать'))
  })
  const submit = (e: FormEvent) => { e.preventDefault(); setError(null); mutation.mutate() }
  return (
    <Modal title="Зарегистрировать пару" sub="Подключите пару к телефону и перепишите адрес из настроек Bluetooth" onClose={onClose}>
      <form onSubmit={submit}>
        <Field label="Серийный номер" htmlFor="eb-serial"><Input id="eb-serial" value={serial} onChange={(e) => setSerial(e.target.value)} required /></Field>
        <Field label="Bluetooth-адрес (MAC)" hint="AA:BB:CC:DD:EE:FF — регистр и разделители не важны" htmlFor="eb-mac">
          <Input id="eb-mac" value={address} onChange={(e) => setAddress(e.target.value)} required />
        </Field>
        <Field label="Модель и цвет"><ModelSelect models={models} value={modelId} onChange={setModelId} /></Field>
        <Field label="Тариф" hint="Пусто — базовый тариф покупателя (free)" htmlFor="eb-plan">
          <Input id="eb-plan" value={planId} onChange={(e) => setPlanId(e.target.value)} />
        </Field>
        <Field label="Имя Bluetooth (необязательно)" htmlFor="eb-name"><Input id="eb-name" value={btName} onChange={(e) => setBtName(e.target.value)} /></Field>
        {error ? <div className="form-error" role="alert">{error}</div> : null}
        <div className="modal-actions">
          <Button type="button" onClick={onClose}>Отмена</Button>
          <Button type="submit" variant="primary" loading={mutation.isPending}>Зарегистрировать</Button>
        </div>
      </form>
    </Modal>
  )
}

function BulkModal({ models, onClose }: { models: EarbudModel[]; onClose: () => void }) {
  const [lines, setLines] = useState('')
  const [modelId, setModelId] = useState('')
  const [result, setResult] = useState<BulkResult | null>(null)
  const [error, setError] = useState<string | null>(null)
  const mutation = useMutation({
    mutationFn: () => earbudsApi.registerBulk(lines, modelId || undefined),
    onSuccess: (res) => setResult(res),
    onError: (e) => setError(errorText(e, 'Не удалось зарегистрировать список'))
  })
  return (
    <Modal title="Регистрация списком" sub="Каждая строка: серийник;MAC (можно добавить ;имя BT). До 500 строк." onClose={onClose}>
      <Field label="Список">
        <textarea
          className="textarea"
          rows={8}
          value={lines}
          onChange={(e) => setLines(e.target.value)}
          placeholder={'OMX-0001;AA:BB:CC:DD:EE:01\nOMX-0002;AA:BB:CC:DD:EE:02'}
          style={{ fontFamily: 'var(--mono, monospace)', width: '100%' }}
        />
      </Field>
      <Field label="Модель и цвет для всех строк"><ModelSelect models={models} value={modelId} onChange={setModelId} /></Field>
      {error ? <div className="form-error" role="alert">{error}</div> : null}
      {result ? (
        <div style={{ margin: '10px 0', fontSize: 13 }}>
          <p>Зарегистрировано: {result.registered}, с ошибкой: {result.failed}</p>
          {result.results.filter((r) => !r.ok).map((r) => (
            <div key={r.line} className="dim">Строка {r.line} ({r.clipSerial || '—'}): {registerErrorText(r.error)}</div>
          ))}
        </div>
      ) : null}
      <div className="modal-actions">
        <Button onClick={onClose}>Закрыть</Button>
        <Button variant="primary" loading={mutation.isPending} disabled={!lines.trim()} onClick={() => { setError(null); mutation.mutate() }}>
          Зарегистрировать
        </Button>
      </div>
    </Modal>
  )
}

function ActionModal({ row, kind, onClose, onDone, onError }: {
  row: EarbudRow; kind: 'unbind' | 'revoke'; onClose: () => void
  onDone: (message: string) => void; onError: (message: string) => void
}) {
  const [reason, setReason] = useState('')
  const mutation = useMutation({
    mutationFn: () => (kind === 'unbind' ? earbudsApi.unbind(row.id, reason.trim()) : earbudsApi.revoke(row.id, reason.trim())),
    onSuccess: () => onDone(kind === 'unbind' ? `Пара ${row.clipSerial} снова на складе` : `Пара ${row.clipSerial} заблокирована`),
    onError: (e) => onError(errorText(e, 'Действие не выполнено'))
  })
  const title = kind === 'unbind'
    ? (row.status === 'REVOKED' ? 'Разблокировать пару?' : 'Отвязать пару?')
    : 'Заблокировать пару?'
  const sub = kind === 'unbind'
    ? `${row.clipSerial} вернётся в статус «на складе» и сможет быть привязана к любому аккаунту`
    : `${row.clipSerial} перестанет давать доступ к ИИ и не привяжется ни к одному аккаунту`
  return (
    <Modal title={title} sub={sub} onClose={onClose}>
      <Field label="Причина" htmlFor="eb-reason">
        <Input id="eb-reason" value={reason} onChange={(e) => setReason(e.target.value)} placeholder="возврат, брак, кража…" />
      </Field>
      <div className="modal-actions">
        <Button onClick={onClose}>Отмена</Button>
        <Button
          variant={kind === 'revoke' ? 'danger' : 'primary'}
          loading={mutation.isPending}
          disabled={!reason.trim()}
          onClick={() => mutation.mutate()}
        >
          {kind === 'unbind' ? (row.status === 'REVOKED' ? 'Разблокировать' : 'Отвязать') : 'Заблокировать'}
        </Button>
      </div>
    </Modal>
  )
}

/* ── Модели ───────────────────────────────────────────────────────────────── */

function Models() {
  const { can } = useAuth()
  const { toasts, ok, err } = useToasts()
  const queryClient = useQueryClient()
  const [editing, setEditing] = useState<EarbudModel | 'new' | null>(null)
  const models = useQuery({ queryKey: ['earbud-models'], queryFn: earbudsApi.models })
  const canWrite = can('DEVICES_REVOKE')
  const refresh = () => void queryClient.invalidateQueries({ queryKey: ['earbud-models'] })

  const toggle = useMutation({
    mutationFn: (m: EarbudModel) => earbudsApi.updateModel(m.id, {
      displayName: m.displayName, colorName: m.colorName, colorHex: m.colorHex,
      imageUrl: m.imageUrl ?? undefined, btNamePatterns: m.btNamePatterns,
      status: m.status === 'ACTIVE' ? 'HIDDEN' : 'ACTIVE'
    }),
    onSuccess: (res) => { ok(res.model.status === 'HIDDEN' ? 'Модель скрыта' : 'Модель показана'); refresh() },
    onError: (e) => err(errorText(e, 'Не удалось изменить модель'))
  })

  return (
    <div>
      <p style={{ color: 'var(--tx-3)', fontSize: 12.5, marginBottom: 14 }}>
        Каталог отдаётся приложению с сервера: новая модель или цвет появляются без обновления приложения. Скрытые модели клиенту не отдаются.
      </p>
      {canWrite ? (
        <div className="toolbar">
          <div style={{ flex: 1 }} />
          <Button variant="primary" onClick={() => setEditing('new')}><Plus size={15} /> Добавить модель</Button>
        </div>
      ) : null}
      {models.isLoading ? <SkeletonRows rows={4} cols={5} /> : null}
      {models.isError ? <ErrorState error={models.error} retry={() => void models.refetch()} /> : null}
      {models.data ? (
        models.data.models.length === 0 ? (
          <div className="table-wrap"><EmptyState title="Моделей нет" desc="Добавьте первую модель и цвет" /></div>
        ) : (
          <DataTable headers={['ID', 'Модель', 'Цвет', 'Имена BT', 'Статус', 'Изменена', '']}>
            {models.data.models.map((m) => (
              <tr key={m.id}>
                <td className="mono">{m.id}</td>
                <td>{m.displayName}</td>
                <td>
                  <span style={{ display: 'inline-block', width: 12, height: 12, borderRadius: 3, background: m.colorHex, border: '1px solid var(--line-2)', marginRight: 6, verticalAlign: 'middle' }} />
                  {m.colorName}
                </td>
                <td className="dim">{m.btNamePatterns.join(', ') || '—'}</td>
                <td><StatusBadge status={m.status === 'HIDDEN' ? 'DISABLED' : 'ACTIVE'} /></td>
                <td className="dim">{formatDateTime(m.updatedAt)}</td>
                <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                  {canWrite ? (
                    <>
                      <Button size="sm" onClick={() => setEditing(m)}>Изменить</Button>
                      <Button size="sm" style={{ marginLeft: 6 }} loading={toggle.isPending && toggle.variables?.id === m.id} onClick={() => toggle.mutate(m)}>
                        {m.status === 'ACTIVE' ? 'Скрыть' : 'Показать'}
                      </Button>
                    </>
                  ) : null}
                </td>
              </tr>
            ))}
          </DataTable>
        )
      ) : null}
      {editing ? (
        <ModelModal
          model={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
          onDone={() => { ok('Модель сохранена'); setEditing(null); refresh() }}
        />
      ) : null}
      <ToastList items={toasts} />
    </div>
  )
}

function ModelModal({ model, onClose, onDone }: { model: EarbudModel | null; onClose: () => void; onDone: () => void }) {
  const [id, setId] = useState(model?.id ?? '')
  const [displayName, setDisplayName] = useState(model?.displayName ?? 'OMNIX Clip')
  const [colorName, setColorName] = useState(model?.colorName ?? '')
  const [colorHex, setColorHex] = useState(model?.colorHex ?? '#111111')
  const [imageUrl, setImageUrl] = useState(model?.imageUrl ?? '')
  const [patterns, setPatterns] = useState((model?.btNamePatterns ?? ['OMNIX']).join(', '))
  const [status, setStatus] = useState<EarbudModelStatus>(model?.status ?? 'ACTIVE')
  const [error, setError] = useState<string | null>(null)
  const mutation = useMutation({
    mutationFn: () => {
      const body: SaveModelBody = {
        displayName: displayName.trim(),
        colorName: colorName.trim(),
        colorHex: colorHex.trim(),
        imageUrl: imageUrl.trim() || undefined,
        btNamePatterns: patterns.split(',').map((p) => p.trim()).filter(Boolean),
        status
      }
      return model ? earbudsApi.updateModel(model.id, body) : earbudsApi.createModel({ ...body, id: id.trim() })
    },
    onSuccess: () => onDone(),
    onError: (e) => setError(isApiError(e) && e.status === 409 ? 'Модель с таким ID уже есть' : errorText(e, 'Не удалось сохранить'))
  })
  const submit = (e: FormEvent) => { e.preventDefault(); setError(null); mutation.mutate() }
  return (
    <Modal title={model ? 'Изменить модель' : 'Новая модель'} onClose={onClose}>
      <form onSubmit={submit}>
        <Field label="ID" hint="латиница, цифры, - и _: например clip-1-black" htmlFor="m-id">
          <Input id="m-id" value={id} onChange={(e) => setId(e.target.value)} disabled={model !== null} required />
        </Field>
        <Field label="Название" htmlFor="m-name"><Input id="m-name" value={displayName} onChange={(e) => setDisplayName(e.target.value)} required /></Field>
        <Field label="Цвет" htmlFor="m-color"><Input id="m-color" value={colorName} onChange={(e) => setColorName(e.target.value)} required /></Field>
        <Field label="Цвет (HEX)" hint="#RRGGBB" htmlFor="m-hex">
          <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            <input type="color" value={/^#[0-9a-fA-F]{6}$/.test(colorHex) ? colorHex : '#000000'} onChange={(e) => setColorHex(e.target.value)} aria-label="Выбор цвета" />
            <Input id="m-hex" value={colorHex} onChange={(e) => setColorHex(e.target.value)} required />
          </div>
        </Field>
        <Field label="Картинка (https://…, необязательно)" htmlFor="m-img"><Input id="m-img" value={imageUrl} onChange={(e) => setImageUrl(e.target.value)} /></Field>
        <Field label="Начало имени BT" hint="через запятую; только подсказка «похоже на OMNIX», не доверие" htmlFor="m-pat">
          <Input id="m-pat" value={patterns} onChange={(e) => setPatterns(e.target.value)} />
        </Field>
        <Field label="Статус">
          <Select value={status} onChange={(e) => setStatus(e.target.value as EarbudModelStatus)}>
            <option value="ACTIVE">Показывать</option>
            <option value="HIDDEN">Скрыта</option>
          </Select>
        </Field>
        {error ? <div className="form-error" role="alert">{error}</div> : null}
        <div className="modal-actions">
          <Button type="button" onClick={onClose}>Отмена</Button>
          <Button type="submit" variant="primary" loading={mutation.isPending}>Сохранить</Button>
        </div>
      </form>
    </Modal>
  )
}
